from __future__ import annotations

import csv
import uuid
from dataclasses import replace

from fastapi.testclient import TestClient

from utility_sync.api import create_app
from utility_sync.auth import issue_token, revoke_token
from utility_sync.service import ServiceError, SyncService


def token_headers(service: SyncService) -> dict[str, str]:
    return {"Authorization": f"Bearer {issue_token(service.database)}"}


def sync_payload(
    client: TestClient,
    headers: dict[str, str],
    mutations: list[dict],
    cursor: int = 0,
    limit: int = 500,
) -> dict:
    backend_id = client.get("/api/v1/meta", headers=headers).json()["backend_instance_id"]
    response = client.post(
        "/api/v1/sync",
        headers=headers,
        json={
            "client_protocol_version": 2,
            "backend_instance_id": backend_id,
            "cursor_revision": cursor,
            "device_id": str(uuid.uuid4()),
            "mutations": mutations,
            "pull_limit": limit,
        },
    )
    assert response.status_code == 200, response.text
    return response.json()


def reading_mutation(
    meter_id: str, reading_id: str, operation_id: str, base_revision: int = 0
) -> dict:
    return {
        "operation_id": operation_id,
        "entity_type": "reading",
        "entity_id": reading_id,
        "kind": "upsert",
        "base_revision": base_revision,
        "payload": {
            "meter_id": meter_id,
            "value_decimal": "12.3400",
            "recorded_at": "2026-09-19T08:00:00Z",
            "note": "meter photo",
        },
    }


def test_health_auth_meta_and_revocation(client: TestClient, service: SyncService) -> None:
    assert client.get("/healthz").json()["status"] == "ok"
    assert client.get("/api/v1/meta").status_code == 401
    token = issue_token(service.database)
    response = client.get("/api/v1/meta", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    assert len(response.json()["meters"]) == 3
    assert revoke_token(service.database, token.split("_", 2)[1])
    assert (
        client.get("/api/v1/meta", headers={"Authorization": f"Bearer {token}"}).status_code == 401
    )


def test_sync_is_idempotent_conflict_aware_and_atomic(
    client: TestClient, service: SyncService
) -> None:
    headers = token_headers(service)
    meter_id = client.get("/api/v1/meta", headers=headers).json()["meters"][0]["id"]
    reading_id, operation_id = str(uuid.uuid4()), str(uuid.uuid4())
    mutation = reading_mutation(meter_id, reading_id, operation_id)
    first = sync_payload(client, headers, [mutation])
    assert first["results"][0]["status"] == "accepted"
    assert first["results"][0]["entity"]["value_decimal"] == "12.34"
    revision = first["results"][0]["revision"]

    duplicate = sync_payload(client, headers, [mutation])
    assert duplicate["results"][0]["status"] == "duplicate"
    assert duplicate["high_water_revision"] == revision

    conflict = reading_mutation(meter_id, reading_id, str(uuid.uuid4()), base_revision=0)
    conflict["payload"]["value_decimal"] = "18"
    outcome = sync_payload(client, headers, [conflict])
    assert outcome["results"][0]["status"] == "conflict"
    assert outcome["results"][0]["entity"]["value_decimal"] == "12.34"

    new_reading = reading_mutation(meter_id, str(uuid.uuid4()), str(uuid.uuid4()))
    atomic = sync_payload(
        client, headers, [new_reading, reading_mutation(meter_id, reading_id, str(uuid.uuid4()), 0)]
    )
    assert len(atomic["results"]) == 2
    assert any(item.get("reason") == "batch_aborted" for item in atomic["results"])
    all_changes = sync_payload(client, headers, [], cursor=0)["changes"]
    assert new_reading["entity_id"] not in {
        change["entity"]["id"] for change in all_changes if "id" in change["entity"]
    }


def test_tombstone_paging_validation_csv_and_backup(
    client: TestClient, service: SyncService
) -> None:
    headers = token_headers(service)
    meter_id = client.get("/api/v1/meta", headers=headers).json()["meters"][0]["id"]
    reading_id = str(uuid.uuid4())
    first = sync_payload(
        client, headers, [reading_mutation(meter_id, reading_id, str(uuid.uuid4()))]
    )
    revision = first["results"][0]["revision"]
    tombstone = {
        "operation_id": str(uuid.uuid4()),
        "entity_type": "reading",
        "entity_id": reading_id,
        "kind": "tombstone",
        "base_revision": revision,
        "payload": {},
    }
    deleted = sync_payload(client, headers, [tombstone])
    assert deleted["results"][0]["entity"]["deleted"] is True

    bad = reading_mutation(meter_id, str(uuid.uuid4()), str(uuid.uuid4()))
    bad["payload"]["value_decimal"] = 1.2
    backend_id = client.get("/api/v1/meta", headers=headers).json()["backend_instance_id"]
    response = client.post(
        "/api/v1/sync",
        headers=headers,
        json={
            "client_protocol_version": 2,
            "backend_instance_id": backend_id,
            "cursor_revision": 0,
            "device_id": str(uuid.uuid4()),
            "mutations": [bad],
        },
    )
    assert response.status_code == 422

    page_one = sync_payload(client, headers, [], cursor=0, limit=2)
    assert len(page_one["changes"]) == 2
    assert page_one["has_more"] is True
    page_two = sync_payload(
        client, headers, [], cursor=page_one["next_cursor_revision"], limit=1000
    )
    assert page_two["next_cursor_revision"] == page_two["high_water_revision"]

    export = client.get("/api/v1/exports/readings.csv", headers=headers)
    assert export.status_code == 200
    rows = list(csv.DictReader(export.text.splitlines()))
    assert rows[0]["deleted"] == "1"
    backup = service.backup()
    assert backup["integrity"] == "ok"
    assert backup["restored_counts"]["global_revision"] == deleted["high_water_revision"]
    assert service.settings.backups_dir.joinpath(
        "utility-" + backup["path"].split("utility-")[1].split(".sqlite3")[0] + ".sqlite3.sha256"
    ).exists()


def test_tariff_low_space_and_request_id(
    client: TestClient, service: SyncService, settings
) -> None:
    headers = token_headers(service)
    meter_id = client.get("/api/v1/meta", headers=headers).json()["meters"][0]["id"]
    tariff = {
        "operation_id": str(uuid.uuid4()),
        "entity_type": "tariff",
        "entity_id": str(uuid.uuid4()),
        "kind": "upsert",
        "base_revision": 0,
        "payload": {
            "meter_id": meter_id,
            "price_decimal": "0.488300",
            "currency": "CNY",
            "effective_from": "2026-09-01T00:00:00Z",
        },
    }
    result = sync_payload(client, headers, [tariff])
    assert result["results"][0]["entity"]["price_decimal"] == "0.4883"
    request_id = str(uuid.uuid4())
    health = client.get("/healthz", headers={"X-Request-ID": request_id})
    assert health.headers["X-Request-ID"] == request_id

    constrained = replace(settings, write_min_free_bytes=10**30)
    constrained_service = SyncService(constrained)
    constrained_headers = token_headers(constrained_service)
    with TestClient(create_app(constrained)) as constrained_client:
        assert sync_payload(constrained_client, constrained_headers, []).get("changes")
        backend_id = constrained_client.get("/api/v1/meta", headers=constrained_headers).json()[
            "backend_instance_id"
        ]
        response = constrained_client.post(
            "/api/v1/sync",
            headers=constrained_headers,
            json={
                "client_protocol_version": 2,
                "backend_instance_id": backend_id,
                "cursor_revision": 0,
                "device_id": str(uuid.uuid4()),
                "mutations": [reading_mutation(meter_id, str(uuid.uuid4()), str(uuid.uuid4()))],
            },
        )
        assert response.status_code == 507
        assert (
            constrained_client.get(
                "/api/v1/exports/readings.csv", headers=constrained_headers
            ).status_code
            == 200
        )


def test_backup_threshold_and_budget(settings) -> None:
    low_free = SyncService(replace(settings, backup_min_free_bytes=10**30))
    try:
        low_free.backup()
    except ServiceError as error:
        assert error.status_code == 507
    else:
        raise AssertionError("backup unexpectedly ran with an impossible free-space threshold")

    over_budget = SyncService(replace(settings, backup_max_bytes=1))
    try:
        over_budget.backup()
    except ServiceError as error:
        assert error.status_code == 507
    else:
        raise AssertionError("backup unexpectedly exceeded the published-backup budget")
