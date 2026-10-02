from __future__ import annotations

import json
import smtplib
import ssl
from datetime import UTC, datetime, timedelta

import pytest

from utility_sync import monitor, notifications
from utility_sync.cli import main
from utility_sync.notifications import NotificationError, SMTPSettings, process_notifications


def config_file(tmp_path, **overrides):
    values = (
        dict(
            SMTP_ENABLED="true",
            SMTP_HOST="smtp.invalid",
            SMTP_PORT="587",
            SMTP_SECURITY="starttls",
            SMTP_USERNAME="fixture-user",
            SMTP_PASSWORD="fixture-private-password",
            SMTP_FROM="fixture@example.test",
            SMTP_TO="maintenance@example.test",
        )
        | overrides
    )
    path = tmp_path / "notifications.env"
    path.write_text("\n".join(f"{key}={value}" for key, value in values.items()))
    path.chmod(0o600)
    return path


def test_fault_dedup_reminder_recovery_and_new_incident_survive_restarts(tmp_path):
    config = config_file(tmp_path)
    now = datetime(2026, 10, 2, tzinfo=UTC)
    sent = []

    def sender(config, events, at):
        sent.append(events.copy())

    state = tmp_path / "state"
    process_notifications(state, ["disk_writes_disabled"], config, now=now, sender=sender)
    process_notifications(
        state, ["disk_writes_disabled"], config, now=now + timedelta(minutes=5), sender=sender
    )
    assert sent == [{"disk_writes_disabled": "fault"}]
    process_notifications(
        state, ["disk_writes_disabled"], config, now=now + timedelta(hours=6), sender=sender
    )
    assert sent[-1] == {"disk_writes_disabled": "reminder"}
    process_notifications(state, [], config, now=now + timedelta(hours=6, minutes=5), sender=sender)
    assert len(sent) == 2
    process_notifications(
        state,
        ["disk_writes_disabled"],
        config,
        now=now + timedelta(hours=6, minutes=10),
        sender=sender,
    )
    assert len(sent) == 2
    process_notifications(
        state, [], config, now=now + timedelta(hours=6, minutes=15), sender=sender
    )
    process_notifications(
        state, [], config, now=now + timedelta(hours=6, minutes=20), sender=sender
    )
    assert sent[-1] == {"disk_writes_disabled": "recovery"}
    process_notifications(
        state, ["disk_writes_disabled"], config, now=now + timedelta(hours=7), sender=sender
    )
    assert sent[-1] == {"disk_writes_disabled": "fault"}
    assert "fixture-private-password" not in (state / "notifications.json").read_text()


def test_failures_back_off_and_only_acknowledged_delivery_is_marked(tmp_path):
    config = config_file(tmp_path)
    now = datetime(2026, 10, 2, tzinfo=UTC)
    state = tmp_path / "state"
    calls = []

    def fail(config, events, at):
        calls.append(at)
        raise smtplib.SMTPAuthenticationError(535, b"fixture-private-password provider detail")

    for delay in (5, 10, 20, 40, 60, 60):
        status = process_notifications(state, ["backup_overdue"], config, now=now, sender=fail)
        assert status["last_error_code"] == "smtp_authentication_failed"
        assert status["last_success_at"] is None
        persisted = json.loads((state / "notifications.json").read_text())
        assert persisted["incidents"]["backup_overdue"]["notified_at"] is None
        assert datetime.fromisoformat(persisted["next_attempt_at"]) == now + timedelta(
            minutes=delay
        )
        process_notifications(
            state, ["backup_overdue"], config, now=now + timedelta(minutes=1), sender=fail
        )
        now += timedelta(minutes=delay)
    assert len(calls) == 6
    delivered = []
    status = process_notifications(
        state,
        ["backup_overdue"],
        config,
        now=now,
        sender=lambda config, events, at: delivered.append(events),
    )
    assert delivered == [{"backup_overdue": "fault"}]
    assert status["last_error_code"] is None
    assert status["pending_event_count"] == 0
    assert "fixture-private-password" not in (state / "notifications.json").read_text()


def test_unsent_fault_that_recovers_does_not_send_obsolete_mail(tmp_path):
    config = config_file(tmp_path)
    now = datetime(2026, 10, 2, tzinfo=UTC)
    state = tmp_path / "state"

    def fail(*args):
        raise TimeoutError("private server text")

    process_notifications(state, ["service_unavailable"], config, now=now, sender=fail)
    sent = []
    process_notifications(
        state, [], config, now=now + timedelta(minutes=5), sender=lambda *args: sent.append(args)
    )
    process_notifications(
        state, [], config, now=now + timedelta(minutes=10), sender=lambda *args: sent.append(args)
    )
    assert sent == []
    assert notifications.public_status(state)["pending_event_count"] == 0


@pytest.mark.parametrize("security,port", [("starttls", "587"), ("ssl", "465")])
def test_smtp_uses_verified_tls_and_keeps_credentials_out_of_message(
    tmp_path, monkeypatch, security, port
):
    config = SMTPSettings.load(config_file(tmp_path, SMTP_SECURITY=security, SMTP_PORT=port))
    calls = []

    class SMTP:
        def __init__(self, host, port, timeout, context=None):
            assert timeout == 10
            if context is not None:
                assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname
            calls.append("connect")

        def ehlo(self):
            calls.append("ehlo")

        def starttls(self, context):
            assert context.verify_mode == ssl.CERT_REQUIRED and context.check_hostname
            calls.append("starttls")

        def login(self, username, password):
            assert username == "fixture-user" and password == "fixture-private-password"
            calls.append("login")

        def send_message(self, message):
            assert "fixture-private-password" not in str(message)
            assert "验收测试" in message["Subject"]
            calls.append("send")
            return {}

        def quit(self):
            raise TimeoutError("QUIT failed after accepted mail")

        def close(self):
            calls.append("close")

    monkeypatch.setattr(smtplib, "SMTP", SMTP)
    monkeypatch.setattr(smtplib, "SMTP_SSL", SMTP)
    notifications.send_email(
        config, {"synthetic_acceptance": "fault"}, datetime.now(UTC), test=True
    )
    assert calls[-2:] == ["send", "close"]
    assert ("starttls" in calls) == (security == "starttls")


@pytest.mark.parametrize(
    "overrides",
    [
        {"SMTP_SECURITY": "plain"},
        {"SMTP_TO": "not-an-address"},
        {"SMTP_PASSWORD": ""},
        {"SMTP_PORT": "invalid"},
    ],
)
def test_invalid_configuration_is_sanitized(tmp_path, overrides):
    with pytest.raises(NotificationError) as error:
        SMTPSettings.load(config_file(tmp_path, **overrides))
    assert str(error.value) == "configuration_invalid"


def test_private_permissions_and_disabled_default(tmp_path):
    assert not SMTPSettings.load(tmp_path / "missing").enabled
    path = config_file(tmp_path)
    path.chmod(0o644)
    with pytest.raises(NotificationError, match="configuration_permissions"):
        SMTPSettings.load(path)


def test_monitor_still_notifies_when_database_is_missing_and_main_service_is_down(
    service, tmp_path, monkeypatch
):
    config = config_file(tmp_path)
    service.settings.database.unlink()
    monkeypatch.setattr(
        monitor, "_unit_property", lambda unit, prop: "failed" if prop == "Result" else "inactive"
    )
    sent = []
    monkeypatch.setattr(notifications, "send_email", lambda config, events, at: sent.append(events))
    status = monitor.run_monitor(service, notifications_file=config)
    assert "database_unavailable" in status["alerts"]
    assert "service_unavailable" in status["alerts"]
    assert status["notifications"]["last_success_at"]
    assert sent and not service.settings.database.exists()


def test_cli_configuration_check_sends_nothing_and_test_mail_is_explicit(
    tmp_path, monkeypatch, capsys
):
    config = config_file(tmp_path)
    sent = []
    monkeypatch.setattr(notifications, "send_email", lambda *args, **kwargs: sent.append(kwargs))
    main(["notify-check", "--config", str(config)])
    assert sent == [] and "no mail sent" in capsys.readouterr().out
    main(["notify-test", "--config", str(config)])
    assert sent == [{"test": True}]
    assert "confirm mailbox receipt" in capsys.readouterr().out


def test_corrupt_durable_state_recovers_without_crashing_monitor(tmp_path):
    config = config_file(tmp_path)
    state = tmp_path / "state"
    state.mkdir()
    (state / "notifications.json").write_text('{"version":1,"incidents":{"fault":"broken"}}')
    sent = []
    status = process_notifications(
        state,
        ["database_unavailable"],
        config,
        sender=lambda config, events, at: sent.append(events),
    )
    assert sent == [{"database_unavailable": "fault"}]
    assert status["last_error_code"] is None


@pytest.mark.parametrize(
    "security,accept_auth", [("starttls", True), ("ssl", True), ("starttls", False)]
)
def test_real_loopback_smtp_tls_delivery_and_auth_rejection(
    tmp_path, monkeypatch, security, accept_auth
):
    import socketserver
    import subprocess
    import threading
    from dataclasses import replace
    from email import policy
    from email.parser import BytesParser

    certificate = tmp_path / "certificate.pem"
    key = tmp_path / "key.pem"
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-days",
            "1",
            "-subj",
            "/CN=localhost",
            "-addext",
            "subjectAltName=DNS:localhost",
            "-keyout",
            str(key),
            "-out",
            str(certificate),
        ],
        check=True,
        capture_output=True,
    )
    server_tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server_tls.load_cert_chain(certificate, key)
    client_tls = ssl.create_default_context(cafile=certificate)
    monkeypatch.setattr(notifications.ssl, "create_default_context", lambda: client_tls)
    messages = []

    class Handler(socketserver.StreamRequestHandler):
        def setup(self):
            if security == "ssl":
                self.request = server_tls.wrap_socket(self.request, server_side=True)
            super().setup()

        def answer(self, text):
            self.wfile.write(text.encode() + b"\r\n")
            self.wfile.flush()

        def handle(self):
            self.answer("220 localhost fixture")
            while True:
                command = self.rfile.readline().strip()
                if not command:
                    break
                verb = command.split(b" ", 1)[0].upper()
                if verb == b"EHLO":
                    self.answer("250-localhost\r\n250-STARTTLS\r\n250 AUTH PLAIN")
                elif verb == b"STARTTLS":
                    self.answer("220 Ready for TLS")
                    self.connection = server_tls.wrap_socket(self.connection, server_side=True)
                    self.rfile = self.connection.makefile("rb")
                    self.wfile = self.connection.makefile("wb")
                elif verb == b"AUTH":
                    self.answer(
                        "235 Authenticated" if accept_auth else "535 Rejected synthetic credentials"
                    )
                elif verb in (b"MAIL", b"RCPT"):
                    self.answer("250 Accepted")
                elif verb == b"DATA":
                    self.answer("354 End with dot")
                    lines = []
                    while True:
                        line = self.rfile.readline()
                        if line == b".\r\n" or not line:
                            break
                        lines.append(line)
                    messages.append(b"".join(lines))
                    self.answer("250 Queued")
                elif verb == b"QUIT":
                    self.answer("221 Goodbye")
                    break
                else:
                    self.answer("500 Unsupported fixture command")

    class Server(socketserver.ThreadingTCPServer):
        allow_reuse_address = True
        daemon_threads = True

    with Server(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        config = replace(
            SMTPSettings.load(config_file(tmp_path)),
            host="localhost",
            port=server.server_address[1],
            security=security,
        )
        try:
            if accept_auth:
                notifications.send_email(
                    config, {"database_unavailable": "fault"}, datetime.now(UTC)
                )
                assert len(messages) == 1
                message = BytesParser(policy=policy.default).parsebytes(messages[0])
                assert "database_unavailable" in message.get_body().get_content()
                assert config.password not in str(message)
            else:
                with pytest.raises(NotificationError, match="smtp_authentication_failed"):
                    notifications.send_email(
                        config, {"database_unavailable": "fault"}, datetime.now(UTC)
                    )
                assert messages == []
        finally:
            server.shutdown()
            thread.join(timeout=3)
