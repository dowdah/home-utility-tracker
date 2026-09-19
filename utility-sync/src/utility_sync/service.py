from __future__ import annotations

import csv
import hashlib
import json
import logging
import os
import shutil
import sqlite3
from contextlib import closing
from datetime import UTC, datetime
from decimal import Decimal, InvalidOperation
from pathlib import Path
from tempfile import NamedTemporaryFile
from typing import Any

from .auth import now_utc
from .config import Settings
from .database import Database
from .schemas import Mutation, SyncRequest

logger = logging.getLogger(__name__)


class ServiceError(Exception):
    def __init__(self, status_code: int, detail: str):
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


def _canonical_decimal(value: Any, field: str) -> str:
    if not isinstance(value, str):
        raise ServiceError(422, f"{field} must be a decimal string")
    try:
        number = Decimal(value)
    except InvalidOperation as error:
        raise ServiceError(422, f"{field} must be a decimal string") from error
    if not number.is_finite() or number < 0:
        raise ServiceError(422, f"{field} must be a non-negative finite decimal")
    normalized = number.normalize()
    return format(normalized, "f") if normalized else "0"


def _utc_timestamp(value: Any, field: str) -> str:
    if not isinstance(value, str):
        raise ServiceError(422, f"{field} must be a UTC RFC 3339 string")
    try:
        timestamp = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ServiceError(422, f"{field} must be a UTC RFC 3339 string") from error
    if timestamp.tzinfo is None or timestamp.utcoffset() != UTC.utcoffset(timestamp):
        raise ServiceError(422, f"{field} must be expressed in UTC")
    return timestamp.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _row_dict(row: sqlite3.Row) -> dict[str, Any]:
    result = dict(row)
    for name in ("deleted", "active"):
        if name in result:
            result[name] = bool(result[name])
    return result


class SyncService:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.settings.ensure_directories()
        self.database = Database(settings.database)

    def _free_bytes(self, directory: Path) -> int:
        return shutil.disk_usage(directory).free

    def health(self) -> dict[str, Any]:
        with self.database.read() as connection:
            connection.execute("SELECT 1").fetchone()
        free_bytes = self._free_bytes(self.settings.data_dir)
        backup_free_bytes = self._free_bytes(self.settings.backups_dir)
        return {
            "status": "ok",
            "database": "open",
            "free_bytes": free_bytes,
            "writes_enabled": free_bytes >= self.settings.write_min_free_bytes,
            "backup_free_bytes": backup_free_bytes,
            "backup_budget_bytes": self.settings.backup_max_bytes,
            "published_backup_bytes": self._published_backup_bytes(),
        }

    def meta(self) -> dict[str, Any]:
        with self.database.read() as connection:
            metadata = dict(connection.execute("SELECT key, value FROM metadata").fetchall())
            meters = [
                _row_dict(row)
                for row in connection.execute("SELECT * FROM meters ORDER BY meter_type")
            ]
        return {
            "backend_instance_id": metadata["backend_instance_id"],
            "api_version": "v1",
            "schema_version": "0001_initial",
            "current_revision": int(metadata["global_revision"]),
            "features": {"tombstones": True, "csv_export": True, "currency": "CNY"},
            "meters": meters,
        }

    @staticmethod
    def _entity(
        connection: sqlite3.Connection, entity_type: str, entity_id: str
    ) -> dict[str, Any] | None:
        table = "readings" if entity_type == "reading" else "tariffs"
        row = connection.execute(f"SELECT * FROM {table} WHERE id = ?", (entity_id,)).fetchone()
        return _row_dict(row) if row else None

    @staticmethod
    def _meter_exists(connection: sqlite3.Connection, meter_id: str) -> bool:
        return (
            connection.execute(
                "SELECT 1 FROM meters WHERE id = ? AND deleted = 0", (meter_id,)
            ).fetchone()
            is not None
        )

    def _validate_mutation(
        self, connection: sqlite3.Connection, mutation: Mutation
    ) -> dict[str, Any]:
        if mutation.kind == "tombstone":
            if mutation.payload:
                raise ServiceError(422, "tombstone payload must be empty")
            return {}
        payload = mutation.payload
        meter_id = payload.get("meter_id")
        if not isinstance(meter_id, str) or not self._meter_exists(connection, meter_id):
            raise ServiceError(422, "meter_id must name an active meter")
        if mutation.entity_type == "reading":
            note = payload.get("note")
            if note is not None and (not isinstance(note, str) or len(note) > 1000):
                raise ServiceError(422, "note must be a string no longer than 1000 characters")
            return {
                "meter_id": meter_id,
                "value_decimal": _canonical_decimal(payload.get("value_decimal"), "value_decimal"),
                "recorded_at": _utc_timestamp(payload.get("recorded_at"), "recorded_at"),
                "note": note,
            }
        currency = payload.get("currency", "CNY")
        if currency != "CNY":
            raise ServiceError(422, "currency must be CNY")
        return {
            "meter_id": meter_id,
            "price_decimal": _canonical_decimal(payload.get("price_decimal"), "price_decimal"),
            "currency": "CNY",
            "effective_from": _utc_timestamp(payload.get("effective_from"), "effective_from"),
        }

    @staticmethod
    def _next_revision(connection: sqlite3.Connection) -> int:
        row = connection.execute(
            "SELECT value FROM metadata WHERE key = 'global_revision'"
        ).fetchone()
        revision = int(row["value"]) + 1
        connection.execute(
            "UPDATE metadata SET value = ? WHERE key = 'global_revision'", (str(revision),)
        )
        return revision

    def _apply(
        self,
        connection: sqlite3.Connection,
        mutation: Mutation,
        payload: dict[str, Any],
        device_id: str,
    ) -> dict[str, Any]:
        existing = self._entity(connection, mutation.entity_type, mutation.entity_id)
        now = now_utc()
        revision = self._next_revision(connection)
        table = "readings" if mutation.entity_type == "reading" else "tariffs"
        if mutation.kind == "tombstone":
            assert existing is not None
            connection.execute(
                f"UPDATE {table} SET deleted = 1, updated_at = ?, server_revision = ?, updated_by_device_id = ? WHERE id = ?",
                (now, revision, device_id, mutation.entity_id),
            )
        elif mutation.entity_type == "reading":
            created_at = existing["created_at"] if existing else now
            connection.execute(
                """
                INSERT INTO readings(id, meter_id, value_decimal, recorded_at, note, deleted, created_at, updated_at, server_revision, updated_by_device_id)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET meter_id=excluded.meter_id, value_decimal=excluded.value_decimal,
                  recorded_at=excluded.recorded_at, note=excluded.note, deleted=0, updated_at=excluded.updated_at,
                  server_revision=excluded.server_revision, updated_by_device_id=excluded.updated_by_device_id
                """,
                (
                    mutation.entity_id,
                    payload["meter_id"],
                    payload["value_decimal"],
                    payload["recorded_at"],
                    payload["note"],
                    created_at,
                    now,
                    revision,
                    device_id,
                ),
            )
        else:
            created_at = existing["created_at"] if existing else now
            connection.execute(
                """
                INSERT INTO tariffs(id, meter_id, price_decimal, currency, effective_from, deleted, created_at, updated_at, server_revision, updated_by_device_id)
                VALUES (?, ?, ?, ?, ?, 0, ?, ?, ?, ?)
                ON CONFLICT(id) DO UPDATE SET meter_id=excluded.meter_id, price_decimal=excluded.price_decimal,
                  currency=excluded.currency, effective_from=excluded.effective_from, deleted=0, updated_at=excluded.updated_at,
                  server_revision=excluded.server_revision, updated_by_device_id=excluded.updated_by_device_id
                """,
                (
                    mutation.entity_id,
                    payload["meter_id"],
                    payload["price_decimal"],
                    payload["currency"],
                    payload["effective_from"],
                    created_at,
                    now,
                    revision,
                    device_id,
                ),
            )
        entity = self._entity(connection, mutation.entity_type, mutation.entity_id)
        assert entity is not None
        change_payload = {"entity_type": mutation.entity_type, **entity}
        connection.execute(
            "INSERT INTO changes(revision, entity_type, entity_id, operation_id, payload_json) VALUES (?, ?, ?, ?, ?)",
            (
                revision,
                mutation.entity_type,
                mutation.entity_id,
                mutation.operation_id,
                json.dumps(change_payload, separators=(",", ":")),
            ),
        )
        return {
            "operation_id": mutation.operation_id,
            "status": "accepted",
            "revision": revision,
            "entity": change_payload,
        }

    @staticmethod
    def _save_operation(connection: sqlite3.Connection, result: dict[str, Any]) -> None:
        connection.execute(
            "INSERT INTO operations(operation_id, result_json, created_at) VALUES (?, ?, ?)",
            (result["operation_id"], json.dumps(result, separators=(",", ":")), now_utc()),
        )

    def sync(self, request: SyncRequest) -> dict[str, Any]:
        if (
            self._free_bytes(self.settings.data_dir) < self.settings.write_min_free_bytes
            and request.mutations
        ):
            raise ServiceError(
                507,
                "writes are disabled because available disk space is below the safety threshold",
            )
        with self.database.write() as connection:
            backend_id = connection.execute(
                "SELECT value FROM metadata WHERE key = 'backend_instance_id'"
            ).fetchone()["value"]
            if request.backend_instance_id != backend_id:
                raise ServiceError(409, "backend_instance_id does not match this server")
            results: list[dict[str, Any]] = []
            fresh: list[tuple[Mutation, dict[str, Any]]] = []
            conflicts: list[dict[str, Any]] = []
            operation_ids: set[str] = set()
            for mutation in request.mutations:
                if mutation.operation_id in operation_ids:
                    raise ServiceError(422, "operation_id may appear only once per request")
                operation_ids.add(mutation.operation_id)
                saved = connection.execute(
                    "SELECT result_json FROM operations WHERE operation_id = ?",
                    (mutation.operation_id,),
                ).fetchone()
                if saved:
                    duplicate = json.loads(saved["result_json"])
                    duplicate["status"] = "duplicate"
                    results.append(duplicate)
                    continue
                existing = self._entity(connection, mutation.entity_type, mutation.entity_id)
                current_revision = existing["server_revision"] if existing else 0
                if current_revision != mutation.base_revision:
                    conflicts.append(
                        {
                            "operation_id": mutation.operation_id,
                            "status": "conflict",
                            "entity": {"entity_type": mutation.entity_type, **existing}
                            if existing
                            else None,
                        }
                    )
                    continue
                if mutation.kind == "tombstone" and existing is None:
                    raise ServiceError(422, "cannot tombstone an entity that does not exist")
                fresh.append((mutation, self._validate_mutation(connection, mutation)))
            if conflicts:
                for conflict in conflicts:
                    self._save_operation(connection, conflict)
                results.extend(conflicts)
                results.extend(
                    {
                        "operation_id": mutation.operation_id,
                        "status": "conflict",
                        "reason": "batch_aborted",
                        "entity": None,
                    }
                    for mutation, _ in fresh
                )
            else:
                for mutation, payload in fresh:
                    accepted = self._apply(connection, mutation, payload, request.device_id)
                    self._save_operation(connection, accepted)
                    results.append(accepted)
            high_water = int(
                connection.execute(
                    "SELECT value FROM metadata WHERE key = 'global_revision'"
                ).fetchone()["value"]
            )
            rows = connection.execute(
                "SELECT revision, payload_json FROM changes WHERE revision > ? AND revision <= ? ORDER BY revision LIMIT ?",
                (request.cursor_revision, high_water, request.pull_limit),
            ).fetchall()
            changes = [
                {"revision": row["revision"], "entity": json.loads(row["payload_json"])}
                for row in rows
            ]
            next_cursor = rows[-1]["revision"] if rows else high_water
            return {
                "results": results,
                "changes": changes,
                "next_cursor_revision": next_cursor,
                "has_more": next_cursor < high_water,
                "high_water_revision": high_water,
            }

    def generate_csv(self) -> Path:
        self.settings.exports_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        target = self.settings.exports_dir / "readings.csv"
        with NamedTemporaryFile(
            "w", encoding="utf-8", newline="", dir=self.settings.exports_dir, delete=False
        ) as handle:
            temporary = Path(handle.name)
            writer = csv.writer(handle)
            writer.writerow(
                [
                    "id",
                    "meter_id",
                    "value_decimal",
                    "recorded_at",
                    "note",
                    "deleted",
                    "server_revision",
                ]
            )
            with self.database.read() as connection:
                rows = connection.execute(
                    "SELECT id, meter_id, value_decimal, recorded_at, note, deleted, server_revision FROM readings ORDER BY recorded_at, id"
                ).fetchall()
            writer.writerows([tuple(row) for row in rows])
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
        return target

    @staticmethod
    def _snapshot_counts(connection: sqlite3.Connection) -> dict[str, int]:
        revision = connection.execute(
            "SELECT value FROM metadata WHERE key = 'global_revision'"
        ).fetchone()["value"]
        return {
            "global_revision": int(revision),
            "meters": connection.execute("SELECT COUNT(*) FROM meters").fetchone()[0],
            "readings": connection.execute("SELECT COUNT(*) FROM readings").fetchone()[0],
            "tariffs": connection.execute("SELECT COUNT(*) FROM tariffs").fetchone()[0],
            "changes": connection.execute("SELECT COUNT(*) FROM changes").fetchone()[0],
        }

    def _backup_paths(self) -> list[Path]:
        return sorted(
            (
                item
                for item in self.settings.backups_dir.iterdir()
                if item.is_file() and item.name.startswith("utility-") and item.suffix == ".sqlite3"
            ),
            reverse=True,
        )

    @staticmethod
    def _retention_removals(backups: list[Path]) -> list[Path]:
        keep: set[Path] = set(backups[:14])
        months: set[str] = set()
        for backup in backups:
            month = backup.name[8:14]
            if month not in months and len(months) < 12:
                keep.add(backup)
                months.add(month)
        return [backup for backup in backups if backup not in keep]

    @staticmethod
    def _artifact_size(backup: Path) -> int:
        sidecar = backup.with_suffix(".sqlite3.sha256")
        return backup.stat().st_size + (sidecar.stat().st_size if sidecar.exists() else 0)

    def _published_backup_bytes(self) -> int:
        return sum(self._artifact_size(backup) for backup in self._backup_paths())

    def _preflight_backup(self, target: Path, expected_bytes: int) -> list[Path]:
        backups = [target, *self._backup_paths()]
        removals = self._retention_removals(backups)
        projected = expected_bytes + sum(
            self._artifact_size(backup)
            for backup in backups
            if backup != target and backup not in removals
        )
        if projected > self.settings.backup_max_bytes:
            raise ServiceError(
                507,
                "backup would exceed the configured published-backup budget after retention",
            )
        return [backup for backup in removals if backup != target]

    def backup(self) -> dict[str, Any]:
        if self._free_bytes(self.settings.backups_dir) < self.settings.backup_min_free_bytes:
            raise ServiceError(
                507, "backup is disabled because available disk space is below the safety threshold"
            )
        self.settings.backups_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S%fZ")
        target = self.settings.backups_dir / f"utility-{stamp}.sqlite3"
        temporary = target.with_suffix(".sqlite3.tmp")
        sidecar_temporary = target.with_suffix(".sqlite3.sha256.tmp")
        try:
            with self.database.read() as source:
                source.execute("BEGIN")
                source_counts = self._snapshot_counts(source)
                page_count = source.execute("PRAGMA page_count").fetchone()[0]
                page_size = source.execute("PRAGMA page_size").fetchone()[0]
                planned_removals = self._preflight_backup(target, page_count * page_size + 128)
                with closing(sqlite3.connect(temporary)) as destination:
                    source.backup(destination)
                source.rollback()
            with closing(sqlite3.connect(temporary)) as verification:
                verification.row_factory = sqlite3.Row
                integrity = verification.execute("PRAGMA integrity_check").fetchone()[0]
                restored_counts = self._snapshot_counts(verification)
            if integrity != "ok" or restored_counts != source_counts:
                raise ServiceError(500, "backup integrity or restored-record verification failed")
            digest = hashlib.sha256(temporary.read_bytes()).hexdigest()
            with sidecar_temporary.open("w", encoding="utf-8") as handle:
                handle.write(f"{digest}  {target.name}\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temporary, target)
            os.replace(sidecar_temporary, target.with_suffix(".sqlite3.sha256"))
        except Exception:
            temporary.unlink(missing_ok=True)
            sidecar_temporary.unlink(missing_ok=True)
            raise
        for backup in planned_removals:
            backup.unlink()
            backup.with_suffix(".sqlite3.sha256").unlink(missing_ok=True)
        if planned_removals:
            logger.info(
                "backup_retention_removed paths=%s", [str(path) for path in planned_removals]
            )
        return {
            "path": str(target),
            "sha256": digest,
            "integrity": integrity,
            "restored_counts": restored_counts,
            "removed_backups": [str(path) for path in planned_removals],
        }

    def prune_backups(self, dry_run: bool = True) -> list[str]:
        removals = self._retention_removals(self._backup_paths())
        if not dry_run:
            for backup in removals:
                backup.unlink()
                backup.with_suffix(".sqlite3.sha256").unlink(missing_ok=True)
        return [str(item) for item in removals]
