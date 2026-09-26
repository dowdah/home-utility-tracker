"""Small authenticated status surface and systemd monitor; no external notification service."""

from __future__ import annotations

import hashlib
import json
import logging
import os
import subprocess
from datetime import UTC, datetime
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


def run_monitor(service: SyncService) -> dict[str, Any]:
    status = storage_status(service)
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
    target = service.settings.data_dir / "monitor.json"
    temporary = target.with_suffix(".tmp")
    with temporary.open("w") as handle:
        json.dump(status, handle)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, target)
    logger.warning("utility_monitor alerts=%s", ",".join(status["alerts"]) or "none")
    return status


def read_status(service: SyncService) -> dict[str, Any]:
    status = storage_status(service)
    try:
        monitor = json.loads((service.settings.data_dir / "monitor.json").read_text())
        age = (
            datetime.now(UTC) - datetime.fromisoformat(monitor["checked_at"].replace("Z", "+00:00"))
        ).total_seconds()
        status["units"] = monitor["units"]
        status["monitor_checked_at"] = monitor["checked_at"]
        if age > 600 or age < 0:
            status["alerts"].append("monitor_stale")
        for alert in monitor["alerts"]:
            if alert in ("service_unavailable", "tunnel_unavailable", "backup_job_failed"):
                status["alerts"].append(alert)
    except (OSError, ValueError, KeyError, TypeError):
        status["alerts"].append("monitor_unavailable")
    return status
