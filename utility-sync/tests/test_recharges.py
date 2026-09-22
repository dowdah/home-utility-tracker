from __future__ import annotations

import json
import sqlite3
import uuid
from datetime import UTC, datetime, timedelta
from pathlib import Path

import pytest
from alembic.config import Config

from alembic import command
from utility_sync.auth import issue_token
from utility_sync.cli import _migrate
from utility_sync.monitor import read_status, run_monitor
from utility_sync.schemas import SyncRequest
from utility_sync.service import ServiceError


def uid():
    return str(uuid.uuid4())


def mutation(service, entity_type="recharge", **overrides):
    payload = dict(
        meter_id=service.meta()["meters"][0]["id"],
        amount_decimal="60",
        unit_price_decimal="0.6",
        quantity_decimal="100",
        credited_at="2026-09-22T00:00:00Z",
    )
    if entity_type == "reading":
        payload = dict(
            meter_id=payload["meter_id"], value_decimal="150", recorded_at=payload["credited_at"]
        )
    return (
        dict(
            operation_id=uid(),
            entity_type=entity_type,
            entity_id=uid(),
            kind="upsert",
            base_revision=0,
            payload=payload,
        )
        | overrides
    )


def sync(service, mutations, **overrides):
    request = (
        dict(
            client_protocol_version=2,
            backend_instance_id=service.meta()["backend_instance_id"],
            device_id=uid(),
            cursor_revision=0,
            mutations=mutations,
        )
        | overrides
    )
    return service.sync(SyncRequest(**request))


def test_recharge_ledger_rounding_export_delete_backup(service):
    item = mutation(service)
    item["payload"].update(
        amount_decimal="1", unit_price_decimal="3", quantity_decimal="0.333333333333"
    )
    result = sync(service, [item])["results"][0]
    assert result["entity"]["quantity_decimal"] == "0.333333333333"
    assert "amount_decimal" in service.generate_csv("recharges").read_text()
    assert "price_decimal" in service.generate_csv("tariffs").read_text()
    delete = {
        **item,
        "operation_id": uid(),
        "kind": "tombstone",
        "payload": {},
        "base_revision": result["revision"],
    }
    assert sync(service, [delete])["results"][0]["entity"]["deleted"]
    assert service.backup()["restored_counts"]["recharges"] == 1


def test_conflict_replay_and_batch_abort(service):
    original = mutation(service)
    sync(service, [original])
    conflict = original | {"operation_id": uid()}
    fresh = mutation(service)
    result = sync(service, [conflict, fresh])
    assert [r.get("reason") for r in result["results"]] == [None, "batch_aborted"]
    repeated = sync(service, [conflict])["results"][0]
    assert repeated["status"] == "conflict" and repeated["replayed"]
    assert sync(service, [fresh])["results"][0]["status"] == "accepted"
    assert sync(service, [fresh])["results"][0]["status"] == "duplicate"
    changed = fresh | {"payload": fresh["payload"] | {"amount_decimal": "120"}}
    with pytest.raises(ServiceError, match="different content"):
        sync(service, [changed])


def test_atomic_group_cannot_split_or_partially_commit(service):
    group_id = uid()
    items = [
        mutation(service, group_id=group_id, group_size=2),
        mutation(service, "reading", group_id=group_id, group_size=2),
    ]
    with pytest.raises(ServiceError, match="in full"):
        sync(service, items[:1])
    results = sync(service, items)["results"]
    assert [r["status"] for r in results] == ["accepted", "accepted"]
    assert [r["status"] for r in sync(service, items)["results"]] == ["duplicate", "duplicate"]
    next_group = uid()
    conflicting = [item | {"operation_id": uid(), "group_id": next_group} for item in items]
    conflicting[1]["base_revision"] = results[1]["revision"]
    results = sync(service, conflicting)["results"]
    assert [r["status"] for r in results] == ["conflict", "conflict"]
    assert results[1]["reason"] == "group_conflict"
    assert service.meta()["current_revision"] == 5
    assert all(r["status"] == "conflict" for r in sync(service, conflicting)["results"])
    with pytest.raises(ServiceError, match="membership"):
        sync(service, [items[0], items[1] | {"operation_id": uid()}])


def test_protocol_guard_health_privacy_status_auth(client, service):
    with pytest.raises(ServiceError) as error:
        sync(service, [], client_protocol_version=1)
    assert error.value.status_code == 426
    assert set(client.get("/healthz").json()) == {"status", "database", "writes_enabled"}
    assert client.get("/api/v1/status").status_code == 401
    header = {"Authorization": "Bearer " + issue_token(service.database)}
    assert "backup_overdue" in client.get("/api/v1/status", headers=header).json()["alerts"]
    assert client.get("/api/v1/meta", headers=header).json()["min_sync_protocol_version"] == 2


@pytest.mark.parametrize(
    "field,value",
    [
        ("amount_decimal", "0"),
        ("unit_price_decimal", "0"),
        ("quantity_decimal", "99"),
        ("amount_decimal", "1e1000000"),
    ],
)
def test_invalid_values_do_not_write(service, field, value):
    item = mutation(service)
    item["payload"][field] = value
    with pytest.raises(ServiceError):
        sync(service, [item])
    assert service.meta()["current_revision"] == 3


def test_retention_keeps_distinct_days_and_months(service):
    now = datetime(2026, 9, 22, tzinfo=UTC)
    days = [
        Path(f"utility-{(now - timedelta(days=i)):%Y%m%d}T120000000000Z.sqlite3") for i in range(20)
    ]
    duplicates = [Path(f"utility-20260922T1300{i:02d}000000Z.sqlite3") for i in range(20)]
    monthly = [Path(f"utility-2026{m:02d}01T120000000000Z.sqlite3") for m in range(1, 9)]
    all_paths = sorted(days + duplicates + monthly, reverse=True)
    kept = set(all_paths) - set(service._retention_removals(all_paths))
    assert len({p.name[8:16] for p in kept if p.name[8:14] == "202609"}) == 14
    assert all(p in kept for p in monthly)
    assert len([p for p in kept if p.name[8:16] == "20260922"]) == 1


def test_monitor_freshness_checksum_units_and_stale_state(service, monkeypatch):
    from utility_sync import monitor

    service.backup()
    monkeypatch.setattr(
        monitor, "_unit_property", lambda unit, prop: "success" if prop == "Result" else "active"
    )
    assert run_monitor(service)["alerts"] == []
    assert read_status(service)["alerts"] == []
    latest = service._backup_paths()[0]
    latest.with_suffix(".sqlite3.sha256").write_text("bad checksum")
    assert "backup_checksum_failed" in read_status(service)["alerts"]
    path = service.settings.data_dir / "monitor.json"
    state = json.loads(path.read_text())
    state["checked_at"] = "2020-01-01T00:00:00Z"
    path.write_text(json.dumps(state))
    assert "monitor_stale" in read_status(service)["alerts"]


def test_upgrade_preserves_legacy_database(tmp_path):
    from utility_sync.config import Settings
    from utility_sync.service import SyncService

    cfg = Settings(
        tmp_path / "legacy.sqlite3",
        tmp_path,
        tmp_path / "exports",
        tmp_path / "backups",
        0,
        0,
        128 * 1024**2,
    )
    alembic = Config("alembic.ini")
    alembic.set_main_option("sqlalchemy.url", f"sqlite:///{cfg.database}")
    command.upgrade(alembic, "0001_initial")
    with sqlite3.connect(cfg.database) as connection:
        before = connection.execute("SELECT * FROM metadata ORDER BY key").fetchall()
        meters = connection.execute("SELECT * FROM meters ORDER BY id").fetchall()
        connection.execute(
            "INSERT INTO operations VALUES (?, ?, ?)",
            (uid(), '{"status":"conflict"}', "2026-09-22"),
        )
    _migrate(cfg)
    with sqlite3.connect(cfg.database) as connection:
        assert connection.execute("SELECT * FROM metadata ORDER BY key").fetchall() == before
        assert connection.execute("SELECT * FROM meters ORDER BY id").fetchall() == meters
        assert connection.execute("SELECT COUNT(*) FROM operations").fetchone()[0] == 1
    assert SyncService(cfg).meta()["schema_version"] == "0002_recharges"
