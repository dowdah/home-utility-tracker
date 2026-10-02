"""Private SMTP configuration and durable, at-least-once incident notifications.

Neither credentials nor recipient/provider response text enter state, status or logs.
"""

from __future__ import annotations

import contextlib
import fcntl
import json
import os
import re
import smtplib
import socket
import ssl
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from email.message import EmailMessage
from pathlib import Path
from typing import Any


class NotificationError(Exception):
    def __init__(self, code: str):
        super().__init__(code)
        self.code = code


@dataclass(frozen=True)
class SMTPSettings:
    enabled: bool = False
    host: str = ""
    port: int = 587
    security: str = "starttls"
    username: str = field(default="", repr=False)
    password: str = field(default="", repr=False)
    sender: str = field(default="", repr=False)
    recipients: tuple[str, ...] = field(default=(), repr=False)
    timeout: int = 10

    @classmethod
    def load(cls, path: Path | None) -> SMTPSettings:
        if path is None or not path.exists():
            return cls()
        # Local files are 0600; deployed root:utility-sync files are 0640.
        if path.stat().st_mode & 0o027:
            raise NotificationError("configuration_permissions")
        values: dict[str, str] = {}
        try:
            for line in path.read_text().splitlines():
                if not line.strip() or line.lstrip().startswith("#"):
                    continue
                key, value = line.split("=", 1)
                values[key.strip()] = value
            enabled = values.get("SMTP_ENABLED", "false").strip().lower()
            if enabled not in ("true", "false"):
                raise ValueError
            config = cls(
                enabled=enabled == "true",
                host=values.get("SMTP_HOST", "").strip(),
                port=int(values.get("SMTP_PORT", "587")),
                security=values.get("SMTP_SECURITY", "starttls").strip().lower(),
                username=values.get("SMTP_USERNAME", ""),
                password=values.get("SMTP_PASSWORD", ""),
                sender=values.get("SMTP_FROM", "").strip(),
                recipients=tuple(
                    value.strip() for value in values.get("SMTP_TO", "").split(",") if value.strip()
                ),
            )
            if config.enabled:
                config.validate()
            return config
        except (OSError, ValueError):
            raise NotificationError("configuration_invalid") from None

    def validate(self) -> None:
        address = re.compile(r"[^\s<>@,]+@[^\s<>@,]+")
        if (
            not self.host
            or any(c.isspace() for c in self.host)
            or not 1 <= self.port <= 65535
            or self.security not in ("starttls", "ssl")
            or not address.fullmatch(self.sender)
            or not self.recipients
            or any(not address.fullmatch(recipient) for recipient in self.recipients)
            or bool(self.username) != bool(self.password)
        ):
            raise NotificationError("configuration_invalid")


def _error_code(error: Exception) -> str:
    if isinstance(error, NotificationError):
        return error.code
    if isinstance(error, smtplib.SMTPAuthenticationError):
        return "smtp_authentication_failed"
    if isinstance(error, ssl.SSLError):
        return "smtp_tls_failed"
    if isinstance(error, (TimeoutError, socket.timeout)):
        return "smtp_timeout"
    if isinstance(error, smtplib.SMTPRecipientsRefused):
        return "smtp_recipients_rejected"
    if isinstance(error, smtplib.SMTPException):
        return "smtp_protocol_error"
    return "smtp_connection_failed"


def send_email(
    config: SMTPSettings, events: dict[str, str], now: datetime, *, test: bool = False
) -> None:
    config.validate()
    message = EmailMessage()
    message["From"] = config.sender
    message["To"] = ", ".join(config.recipients)
    message["Subject"] = "[Utility Sync] " + ("验收测试" if test else "服务告警 / 恢复")
    lines = ["Utility Sync 本机监控", "检查时间（UTC）: " + now.isoformat(), ""]
    labels = {"fault": "新异常", "reminder": "异常持续", "recovery": "已恢复"}
    lines += [f"{labels[kind]}: {code}" for code, kind in sorted(events.items())]
    lines += [
        "",
        "此邮件不包含账本或访问凭据。",
        "仅覆盖本机监控；整机断电或完全失联时无法即时发送。",
    ]
    message.set_content("\n".join(lines))
    connection = None
    try:
        tls = ssl.create_default_context()
        if config.security == "ssl":
            connection = smtplib.SMTP_SSL(
                config.host, config.port, timeout=config.timeout, context=tls
            )
        else:
            connection = smtplib.SMTP(config.host, config.port, timeout=config.timeout)
            connection.ehlo()
            connection.starttls(context=tls)
            connection.ehlo()
        if config.username:
            connection.login(config.username, config.password)
        if connection.send_message(message):
            raise NotificationError("smtp_recipients_rejected")
    except Exception as error:
        raise NotificationError(_error_code(error)) from None
    finally:
        if connection is not None:
            # A QUIT failure must not turn an acknowledged delivery into a retry.
            with contextlib.suppress(OSError, smtplib.SMTPException):
                connection.quit()
            with contextlib.suppress(OSError, smtplib.SMTPException):
                connection.close()


def _empty_state() -> dict[str, Any]:
    return dict(
        version=1,
        enabled=False,
        configured=False,
        incidents={},
        failure_count=0,
        next_attempt_at=None,
        last_attempt_at=None,
        last_success_at=None,
        last_error_code=None,
    )


def _read_state(path: Path) -> dict[str, Any]:
    if not path.exists():
        return _empty_state()
    try:
        value = json.loads(path.read_text())
        if value.get("version") != 1 or not isinstance(value.get("incidents"), dict):
            raise ValueError
        merged = _empty_state() | {key: value[key] for key in _empty_state() if key in value}
        if not isinstance(merged["failure_count"], int) or not 0 <= merged["failure_count"] <= 5:
            raise ValueError
        for key in ("next_attempt_at", "last_attempt_at", "last_success_at"):
            if merged[key] is not None and datetime.fromisoformat(merged[key]).tzinfo is None:
                raise ValueError
        for code, incident in merged["incidents"].items():
            if not re.fullmatch(r"[a-z][a-z0-9_]{0,79}", code) or not isinstance(incident, dict):
                raise ValueError
            if (
                not isinstance(incident.get("healthy_checks"), int)
                or incident["healthy_checks"] < 0
            ):
                raise ValueError
            if datetime.fromisoformat(incident["since_at"]).tzinfo is None:
                raise ValueError
            if (
                incident.get("notified_at") is not None
                and datetime.fromisoformat(incident["notified_at"]).tzinfo is None
            ):
                raise ValueError
        return merged
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        state = _empty_state()
        state["last_error_code"] = "notification_state_unreadable"
        return state


def _publish(path: Path, state: dict[str, Any]) -> None:
    temporary = path.with_suffix(".tmp")
    with open(temporary, "w", opener=lambda name, flags: os.open(name, flags, 0o600)) as handle:
        json.dump(state, handle)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def public_status(directory: Path) -> dict[str, Any]:
    state = _read_state(directory / "notifications.json")
    pending = sum(
        1
        for item in state["incidents"].values()
        if item.get("notified_at") is None or item.get("healthy_checks", 0) >= 2
    )
    return {
        key: state[key]
        for key in (
            "enabled",
            "configured",
            "last_attempt_at",
            "last_success_at",
            "last_error_code",
        )
    } | {"pending_event_count": pending}


def process_notifications(
    directory: Path,
    alerts: list[str],
    config_path: Path | None,
    *,
    now: datetime | None = None,
    sender=None,
) -> dict[str, Any]:
    now = now or datetime.now(UTC)
    sender = sender or send_email
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    path = directory / "notifications.json"
    with open(
        directory / "notifications.lock",
        "a",
        opener=lambda name, flags: os.open(name, flags, 0o600),
    ) as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        state = _read_state(path)
        try:
            config = SMTPSettings.load(config_path)
        except (OSError, NotificationError) as error:
            state.update(
                enabled=True,
                configured=False,
                last_error_code=error.code
                if isinstance(error, NotificationError)
                else "configuration_unreadable",
            )
            _publish(path, state)
            return public_status(directory)
        state.update(enabled=config.enabled, configured=config.enabled)
        if not config.enabled:
            _publish(path, state)
            return public_status(directory)
        incidents = state["incidents"]
        active = set(alerts)
        for code in active:
            if code not in incidents:
                incidents[code] = dict(since_at=now.isoformat(), notified_at=None, healthy_checks=0)
            incidents[code]["healthy_checks"] = 0
        events: dict[str, str] = {}
        for code, incident in list(incidents.items()):
            notified = incident["notified_at"]
            if code not in active:
                incident["healthy_checks"] += 1
                if incident["healthy_checks"] >= 2:
                    if notified is None:
                        del incidents[code]  # Never send an obsolete fault after it recovered.
                    else:
                        events[code] = "recovery"
            elif notified is None:
                events[code] = "fault"
            elif now - datetime.fromisoformat(notified) >= timedelta(hours=6):
                events[code] = "reminder"
        next_attempt = state["next_attempt_at"]
        if events and (next_attempt is None or now >= datetime.fromisoformat(next_attempt)):
            state["last_attempt_at"] = now.isoformat()
            try:
                sender(config, events, now)
            except Exception as error:
                failures = min(state["failure_count"] + 1, 5)
                state.update(
                    failure_count=failures,
                    last_error_code=_error_code(error),
                    next_attempt_at=(
                        now + timedelta(minutes=(5, 10, 20, 40, 60)[failures - 1])
                    ).isoformat(),
                )
            else:
                state.update(
                    failure_count=0,
                    next_attempt_at=None,
                    last_error_code=None,
                    last_success_at=now.isoformat(),
                )
                for code, kind in events.items():
                    if kind == "recovery":
                        del incidents[code]
                    else:
                        incidents[code]["notified_at"] = now.isoformat()
        elif not events:
            state.update(failure_count=0, next_attempt_at=None)
        _publish(path, state)
        return public_status(directory)
