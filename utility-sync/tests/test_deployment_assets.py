from __future__ import annotations

from pathlib import Path


def test_systemd_units_use_preinstalled_opt_environment() -> None:
    ops = Path(__file__).parents[1] / "ops"
    service = (ops / "utility-sync.service").read_text(encoding="utf-8")
    backup = (ops / "utility-sync-backup.service").read_text(encoding="utf-8")
    for unit in (service, backup):
        assert "WorkingDirectory=/opt/utility-sync" in unit
        assert "--no-sync" in unit
        assert "ProtectHome=yes" in unit
        assert "ReadWritePaths=/srv/utility-meter" in unit
    assert "ExecStartPre=/usr/local/bin/uv sync" not in service


def test_reverse_tunnel_is_loopback_only_and_pins_its_host_key() -> None:
    tunnel = (Path(__file__).parents[1] / "ops" / "utility-sync-tunnel.service").read_text(
        encoding="utf-8"
    )
    assert "-R 127.0.0.1:18089:127.0.0.1:8088" in tunnel
    assert "ExitOnForwardFailure=yes" in tunnel
    assert "StrictHostKeyChecking=yes" in tunnel
    assert "UserKnownHostsFile=/etc/utility-sync/known_hosts" in tunnel
    assert "ServerAliveInterval=30" in tunnel
