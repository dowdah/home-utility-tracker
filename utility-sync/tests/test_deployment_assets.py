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
    assert "User=root" in tunnel


def test_deployment_fingerprints_cover_recharges_groups_and_operation_contents(service):
    import ast
    import hashlib
    import sqlite3
    import uuid

    from utility_sync.schemas import Mutation, SyncRequest

    tree = ast.parse((Path(__file__).parents[2] / "tools/deploy_backend.py").read_text())
    remote = next(
        ast.literal_eval(node.value)
        for node in tree.body
        if isinstance(node, ast.Assign)
        and any(isinstance(target, ast.Name) and target.id == "REMOTE" for target in node.targets)
    )
    function = next(
        node
        for node in ast.parse(remote).body
        if isinstance(node, ast.FunctionDef) and node.name == "hashes"
    )
    namespace = {"sqlite3": sqlite3, "hashlib": hashlib}
    exec(
        compile(ast.Module(body=[function], type_ignores=[]), "deployment-hashes", "exec"),
        namespace,
    )
    hashes = namespace["hashes"]
    meta = service.meta()
    group = str(uuid.uuid4())
    at = "2026-10-02T00:00:00Z"
    mutations = [
        Mutation(
            operation_id=str(uuid.uuid4()),
            entity_type="reading",
            entity_id=str(uuid.uuid4()),
            kind="upsert",
            base_revision=0,
            group_id=group,
            group_size=2,
            payload=dict(meter_id=meta["meters"][0]["id"], value_decimal="100", recorded_at=at),
        ),
        Mutation(
            operation_id=str(uuid.uuid4()),
            entity_type="recharge",
            entity_id=str(uuid.uuid4()),
            kind="upsert",
            base_revision=0,
            group_id=group,
            group_size=2,
            payload=dict(
                meter_id=meta["meters"][0]["id"],
                amount_decimal="10",
                unit_price_decimal="1",
                quantity_decimal="10",
                credited_at=at,
            ),
        ),
    ]
    service.sync(
        SyncRequest(
            client_protocol_version=2,
            backend_instance_id=meta["backend_instance_id"],
            cursor_revision=0,
            device_id=str(uuid.uuid4()),
            mutations=mutations,
        )
    )
    before = hashes(service.settings.database)
    assert before["recharges"]["count"] == 1 and before["atomic_groups"]["count"] == 1
    assert "request_hash" in before["operations"]["columns"]
    with service.database.write() as connection:
        connection.execute("UPDATE recharges SET note='changed'")
    assert hashes(service.settings.database, before) != before
    with service.database.write() as connection:
        connection.execute("UPDATE recharges SET note=NULL")
    assert hashes(service.settings.database, before) == before
    with service.database.write() as connection:
        connection.execute("UPDATE operations SET request_hash='changed'")
    assert hashes(service.settings.database, before) != before
