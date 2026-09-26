from __future__ import annotations

import csv
import hashlib
import json
import logging
import os
import re
import shutil
import sqlite3
from contextlib import closing
from datetime import UTC, datetime
from decimal import ROUND_HALF_EVEN, Decimal, InvalidOperation, localcontext
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
    if len(value) > 128 or number.adjusted() > 24 or number.as_tuple().exponent < -24:
        raise ServiceError(422, f"{field} exceeds supported precision")
    return (
        format(number, "f").rstrip("0").rstrip(".")
        if "." in format(number, "f")
        else format(number, "f")
    )


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
        return {
            "status": "ok",
            "database": "open",
            "writes_enabled": free_bytes >= self.settings.write_min_free_bytes,
        }

    def status(self) -> dict[str, Any]:
        from .monitor import read_status

        return read_status(self)

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
            "schema_version": "0002_recharges",
            "sync_protocol_version": 2,
            "min_sync_protocol_version": 2,
            "current_revision": int(metadata["global_revision"]),
            "features": {
                "tombstones": True,
                "csv_export": True,
                "currency": "CNY",
                "recharges": True,
                "atomic_groups": True,
            },
            "meters": meters,
        }

    @staticmethod
    def _entity(
        connection: sqlite3.Connection, entity_type: str, entity_id: str
    ) -> dict[str, Any] | None:
        table = {"reading": "readings", "tariff": "tariffs", "recharge": "recharges"}[entity_type]
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
        if mutation.entity_type == "recharge":
            amount = _canonical_decimal(payload.get("amount_decimal"), "amount_decimal")
            price = _canonical_decimal(payload.get("unit_price_decimal"), "unit_price_decimal")
            if Decimal(amount) <= 0 or Decimal(price) <= 0:
                raise ServiceError(422, "recharge amount and purchase price must be positive")
            with localcontext() as context:
                context.prec = 80
                quantity = (Decimal(amount) / Decimal(price)).quantize(
                    Decimal("0.000000000001"), rounding=ROUND_HALF_EVEN
                )
            if quantity <= 0 or quantity.adjusted() > 24:
                raise ServiceError(422, "credited quantity exceeds supported precision")
            credited = _canonical_decimal(payload.get("quantity_decimal"), "quantity_decimal")
            if Decimal(credited) != quantity:
                raise ServiceError(
                    422,
                    "credited quantity must equal amount / price rounded to 12 places HALF_EVEN",
                )
            note = payload.get("note")
            if note is not None and (not isinstance(note, str) or len(note) > 1000):
                raise ServiceError(422, "note must be a string no longer than 1000 characters")
            if payload.get("currency", "CNY") != "CNY":
                raise ServiceError(422, "currency must be CNY")
            return dict(
                meter_id=meter_id,
                amount_decimal=amount,
                unit_price_decimal=price,
                quantity_decimal=credited,
                currency="CNY",
                credited_at=_utc_timestamp(payload.get("credited_at"), "credited_at"),
                note=note,
            )
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
        table = {"reading": "readings", "tariff": "tariffs", "recharge": "recharges"}[
            mutation.entity_type
        ]
        if mutation.kind == "tombstone":
            assert existing is not None
            connection.execute(
                f"UPDATE {table} SET deleted = 1, updated_at = ?, server_revision = ?, updated_by_device_id = ? WHERE id = ?",
                (now, revision, device_id, mutation.entity_id),
            )
        else:
            # Payload names come only from the validated, closed entity schema above.
            fields = [
                "id",
                *payload,
                "deleted",
                "created_at",
                "updated_at",
                "server_revision",
                "updated_by_device_id",
            ]
            values = [
                mutation.entity_id,
                *payload.values(),
                0,
                existing["created_at"] if existing else now,
                now,
                revision,
                device_id,
            ]
            assignments = ",".join(
                f"{name}=excluded.{name}" for name in fields if name not in ("id", "created_at")
            )
            connection.execute(
                f"INSERT INTO {table} ({','.join(fields)}) VALUES ({','.join('?' for _ in fields)}) "
                f"ON CONFLICT(id) DO UPDATE SET {assignments}",
                values,
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
        if request.client_protocol_version != 2:
            raise ServiceError(426, "client_upgrade_required: sync protocol 2 is required")
        if (
            self._free_bytes(self.settings.data_dir) < self.settings.write_min_free_bytes
            and request.mutations
        ):
            raise ServiceError(
                507,
                "writes are disabled because available disk space is below the safety threshold",
            )
        with self.database.write() as connection:
            metadata = dict(connection.execute("SELECT key, value FROM metadata"))
            if request.backend_instance_id != metadata["backend_instance_id"]:
                raise ServiceError(409, "backend_instance_id does not match this server")
            if request.cursor_revision > int(metadata["global_revision"]):
                raise ServiceError(409, "cursor is ahead of server; recovery is required")
            operations = {item.operation_id: item for item in request.mutations}
            if len(operations) != len(request.mutations):
                raise ServiceError(422, "operation_id may appear only once per request")
            identities = {(item.entity_type, item.entity_id) for item in request.mutations}
            if len(identities) != len(request.mutations):
                raise ServiceError(422, "send only one operation per entity per request")
            groups: dict[str, list[Mutation]] = {}
            for item in request.mutations:
                if (item.group_id is None) != (item.group_size is None):
                    raise ServiceError(422, "group_id and group_size must be supplied together")
                if item.group_id:
                    groups.setdefault(item.group_id, []).append(item)
            for group_id, members in groups.items():
                if any(item.group_size != len(members) for item in members):
                    raise ServiceError(422, "atomic group must be submitted in full")
                manifest = json.dumps(sorted(item.operation_id for item in members))
                previous = connection.execute(
                    "SELECT members_json FROM atomic_groups WHERE id=?", (group_id,)
                ).fetchone()
                if previous and previous[0] != manifest:
                    raise ServiceError(422, "atomic group membership is immutable")
                connection.execute(
                    "INSERT OR IGNORE INTO atomic_groups VALUES (?, ?)", (group_id, manifest)
                )
            results: dict[str, dict[str, Any]] = {}
            fresh: dict[str, tuple[Mutation, dict[str, Any], str]] = {}
            conflicts: set[str] = set()
            for item in request.mutations:
                fingerprint = hashlib.sha256(item.model_dump_json().encode()).hexdigest()
                saved = connection.execute(
                    "SELECT result_json, request_hash FROM operations WHERE operation_id=?",
                    (item.operation_id,),
                ).fetchone()
                if saved:
                    if saved["request_hash"] and saved["request_hash"] != fingerprint:
                        raise ServiceError(
                            422, "operation_id cannot be reused with different content"
                        )
                    result = json.loads(saved["result_json"])
                    result["replayed"] = True
                    if result["status"] == "accepted":
                        result["status"] = "duplicate"
                    else:
                        conflicts.add(item.operation_id)
                    results[item.operation_id] = result
                    continue
                entity = self._entity(connection, item.entity_type, item.entity_id)
                if (entity["server_revision"] if entity else 0) != item.base_revision:
                    conflicts.add(item.operation_id)
                    results[item.operation_id] = dict(
                        operation_id=item.operation_id,
                        status="conflict",
                        entity={"entity_type": item.entity_type, **entity} if entity else None,
                    )
                    fresh[item.operation_id] = (item, {}, fingerprint)
                else:
                    if item.kind == "tombstone" and entity is None:
                        raise ServiceError(422, "cannot tombstone an entity that does not exist")
                    fresh[item.operation_id] = (
                        item,
                        self._validate_mutation(connection, item),
                        fingerprint,
                    )
            # A linked recharge/reading must remain one decision, including conflict recovery.
            for members in groups.values():
                if any(item.operation_id in conflicts for item in members):
                    for item in members:
                        if item.operation_id not in conflicts:
                            entity = self._entity(connection, item.entity_type, item.entity_id)
                            results[item.operation_id] = dict(
                                operation_id=item.operation_id,
                                status="conflict",
                                reason="group_conflict",
                                entity={"entity_type": item.entity_type, **entity}
                                if entity
                                else None,
                            )
                            conflicts.add(item.operation_id)
            for operation_id, (item, payload, fingerprint) in fresh.items():
                if operation_id in conflicts:
                    result = results[operation_id]
                elif conflicts:
                    results[operation_id] = dict(
                        operation_id=operation_id,
                        status="conflict",
                        reason="batch_aborted",
                        entity=None,
                    )
                    continue
                else:
                    result = self._apply(connection, item, payload, request.device_id)
                    results[operation_id] = result
                self._save_operation(connection, result)
                connection.execute(
                    "UPDATE operations SET request_hash=? WHERE operation_id=?",
                    (fingerprint, operation_id),
                )
            high_water = int(
                connection.execute(
                    "SELECT value FROM metadata WHERE key='global_revision'"
                ).fetchone()[0]
            )
            rows = connection.execute(
                "SELECT revision, payload_json FROM changes WHERE revision>? AND revision<=? ORDER BY revision LIMIT ?",
                (request.cursor_revision, high_water, request.pull_limit),
            ).fetchall()
            cursor = rows[-1]["revision"] if rows else high_water
            return dict(
                results=[results[item.operation_id] for item in request.mutations],
                changes=[
                    dict(revision=row["revision"], entity=json.loads(row["payload_json"]))
                    for row in rows
                ],
                next_cursor_revision=cursor,
                has_more=cursor < high_water,
                high_water_revision=high_water,
            )

    def generate_csv(self, kind: str = "readings") -> Path:
        self.settings.exports_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
        columns = {
            "readings": "id,meter_id,value_decimal,recorded_at,note,deleted,server_revision",
            "tariffs": "id,meter_id,price_decimal,currency,effective_from,deleted,server_revision",
            "recharges": "id,meter_id,amount_decimal,unit_price_decimal,quantity_decimal,currency,credited_at,note,deleted,server_revision",
        }
        if kind not in columns:
            raise ServiceError(404, "unknown export")
        target = self.settings.exports_dir / f"{kind}.csv"
        with NamedTemporaryFile(
            "w", encoding="utf-8", newline="", dir=self.settings.exports_dir, delete=False
        ) as handle:
            temporary = Path(handle.name)
            writer = csv.writer(handle)
            writer.writerow(columns[kind].split(","))
            with self.database.read() as connection:
                rows = connection.execute(
                    f"SELECT {columns[kind]} FROM {kind} ORDER BY id"
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
            "recharges": connection.execute("SELECT COUNT(*) FROM recharges").fetchone()[0],
            "operations": connection.execute("SELECT COUNT(*) FROM operations").fetchone()[0],
            "tokens": connection.execute("SELECT COUNT(*) FROM tokens").fetchone()[0],
            "changes": connection.execute("SELECT COUNT(*) FROM changes").fetchone()[0],
        }

    def _backup_paths(self) -> list[Path]:
        return sorted(
            (
                item
                for item in self.settings.backups_dir.iterdir()
                if item.is_file() and re.fullmatch(r"utility-\d{8}T\d{12}Z\.sqlite3", item.name)
            ),
            reverse=True,
        )

    @staticmethod
    def _retention_removals(backups: list[Path]) -> list[Path]:
        keep: set[Path] = set()
        days: set[str] = set()
        months: set[str] = set()
        for backup in sorted(backups, reverse=True):
            day = backup.name[8:16]
            if day not in days and len(days) < 14:
                keep.add(backup)
                days.add(day)
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
