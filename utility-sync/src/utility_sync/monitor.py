"""Authenticated operational status and independent local SMTP incident monitor."""

from __future__ import annotations

import fcntl
import hashlib
import json
import logging
import os
import sqlite3
import subprocess
from datetime import UTC, datetime
from pathlib import Path
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from .service import SyncService

logger = logging.getLogger(__name__)


def storage_status(service: SyncService) -> dict[str, Any]:
    now = datetime.now(UTC)
    free = service._free_bytes(service.settings.data_dir)
    backup_free = service._free_bytes(service.settings.backups_dir)
    alerts: list[str] = []
    if free < service.settings.write_min_free_bytes:
        alerts.append("disk_writes_disabled")
    if backup_free < service.settings.backup_min_free_bytes:
        alerts.append("backup_low_space")
    backups = service._backup_paths()
    latest = backups[0] if backups else None
    last_success = None
    if latest:
        try:
            expected = latest.with_suffix(".sqlite3.sha256").read_text().split()[0]
            if hashlib.sha256(latest.read_bytes()).hexdigest() != expected:
                alerts.append("backup_checksum_failed")
            else:
                last_success = datetime.strptime(latest.name[8:-8], "%Y%m%dT%H%M%S%fZ").replace(
                    tzinfo=UTC
                )
        except (OSError, ValueError, IndexError):
            alerts.append("backup_unverifiable")
    if last_success is None or (now - last_success).total_seconds() > 36 * 3600:
        alerts.append("backup_overdue")
    size = service._published_backup_bytes()
    if size > service.settings.backup_max_bytes:
        alerts.append("backup_budget_exceeded")
    return dict(
        checked_at=now.isoformat().replace("+00:00", "Z"),
        free_bytes=free,
        writes_enabled=free >= service.settings.write_min_free_bytes,
        backup_free_bytes=backup_free,
        backup_budget_bytes=service.settings.backup_max_bytes,
        published_backup_bytes=size,
        last_backup_at=last_success.isoformat() if last_success else None,
        alerts=alerts,
    )


def _unit_property(unit: str, prop: str) -> str:
    try:
        result = subprocess.run(
            ["systemctl", "show", unit, f"--property={prop}", "--value"],
            capture_output=True,
            text=True,
            timeout=5,
            check=False,
        )
        return result.stdout.strip() if result.returncode == 0 else "unknown"
    except (OSError, subprocess.TimeoutExpired):
        return "unknown"


def run_monitor(service: SyncService, *, notifications_file: Path | None = None) -> dict[str, Any]:
    directory = service.settings.monitor_state_dir
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    with open(
        directory / "monitor.lock", "a", opener=lambda name, flags: os.open(name, flags, 0o600)
    ) as lock:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        return _run_monitor_locked(service, notifications_file=notifications_file)


def _run_monitor_locked(service: SyncService, *, notifications_file: Path | None) -> dict[str, Any]:
    try:
        status = storage_status(service)
    except (OSError, ValueError):
        status = dict(
            checked_at=datetime.now(UTC).isoformat(), alerts=["monitor_storage_unavailable"]
        )
    try:
        with sqlite3.connect(
            f"file:{service.settings.database}?mode=ro", uri=True, timeout=5
        ) as connection:
            connection.execute("PRAGMA query_only=ON")
            row = connection.execute(
                "SELECT value FROM metadata WHERE key='backend_instance_id'"
            ).fetchone()
            if row is None:
                status["alerts"].append("database_unavailable")
    except (OSError, sqlite3.Error):
        status["alerts"].append("database_unavailable")
    units = {
        "service": _unit_property("utility-sync.service", "ActiveState"),
        "tunnel": _unit_property("utility-sync-tunnel.service", "ActiveState"),
        "backup_result": _unit_property("utility-sync-backup.service", "Result"),
    }
    for key in ("service", "tunnel"):
        if units[key] != "active":
            status["alerts"].append(f"{key}_unavailable")
    if units["backup_result"] != "success":
        status["alerts"].append("backup_job_failed")
    status["units"] = units
    from .notifications import process_notifications

    directory = service.settings.monitor_state_dir
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    try:
        status["notifications"] = process_notifications(
            directory, status["alerts"], notifications_file
        )
    except (OSError, ValueError, TypeError):
        status["alerts"].append("notification_state_unavailable")
        status["notifications"] = dict(
            enabled=True, configured=False, last_error_code="notification_state_unavailable"
        )
    notification_error = status["notifications"].get("last_error_code")
    if status["notifications"].get("enabled") and notification_error:
        status["alerts"].append("notification_delivery_failed")
    target = directory / "monitor.json"
    temporary = target.with_suffix(".tmp")
    with temporary.open("w") as handle:
        json.dump(status, handle)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, target)
    log = logger.warning if status["alerts"] else logger.info
    log("utility_monitor alerts=%s", ",".join(status["alerts"]) or "none")
    return status


def read_status(service: SyncService) -> dict[str, Any]:
    from .notifications import public_status

    status = storage_status(service)
    status["notifications"] = public_status(service.settings.monitor_state_dir)
    try:
        path = service.settings.monitor_state_dir / "monitor.json"
        if not path.exists():
            path = (
                service.settings.data_dir / "monitor.json"
            )  # Read legacy status until first new run.
        monitor = json.loads(path.read_text())
        age = (
            datetime.now(UTC) - datetime.fromisoformat(monitor["checked_at"].replace("Z", "+00:00"))
        ).total_seconds()
        status["units"] = monitor["units"]
        status["monitor_checked_at"] = monitor["checked_at"]
        if age > 600 or age < 0:
            status["alerts"].append("monitor_stale")
        for alert in monitor["alerts"]:
            if alert in (
                "service_unavailable",
                "tunnel_unavailable",
                "backup_job_failed",
                "database_unavailable",
                "monitor_storage_unavailable",
                "notification_state_unavailable",
                "notification_delivery_failed",
            ):
                status["alerts"].append(alert)
    except (OSError, ValueError, KeyError, TypeError):
        status["alerts"].append("monitor_unavailable")
    return status
