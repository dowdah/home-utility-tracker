"""Create the revisioned utility-sync store and seed its three meters."""

from __future__ import annotations

import json
import uuid

import sqlalchemy as sa

from alembic import op

revision = "0001_initial"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "metadata",
        sa.Column("key", sa.Text(), primary_key=True),
        sa.Column("value", sa.Text(), nullable=False),
    )
    op.create_table(
        "meters",
        sa.Column("id", sa.Text(), primary_key=True),
        sa.Column("meter_type", sa.Text(), nullable=False),
        sa.Column("generation", sa.Integer(), nullable=False),
        sa.Column("unit", sa.Text(), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False),
        sa.Column("created_revision", sa.Integer(), nullable=False),
        sa.Column("deleted", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.UniqueConstraint("meter_type", "generation", name="uq_meters_type_generation"),
    )
    common = [
        sa.Column("id", sa.Text(), primary_key=True),
        sa.Column("meter_id", sa.Text(), sa.ForeignKey("meters.id"), nullable=False),
        sa.Column("deleted", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.Text(), nullable=False),
        sa.Column("server_revision", sa.Integer(), nullable=False, unique=True),
        sa.Column("updated_by_device_id", sa.Text(), nullable=False),
    ]
    op.create_table(
        "readings",
        *common,
        sa.Column("value_decimal", sa.Text(), nullable=False),
        sa.Column("recorded_at", sa.Text(), nullable=False),
        sa.Column("note", sa.Text(), nullable=True),
    )
    op.create_table(
        "tariffs",
        *common,
        sa.Column("price_decimal", sa.Text(), nullable=False),
        sa.Column("currency", sa.Text(), nullable=False),
        sa.Column("effective_from", sa.Text(), nullable=False),
    )
    op.create_table(
        "changes",
        sa.Column("revision", sa.Integer(), primary_key=True),
        sa.Column("entity_type", sa.Text(), nullable=False),
        sa.Column("entity_id", sa.Text(), nullable=False),
        sa.Column("operation_id", sa.Text(), nullable=True),
        sa.Column("payload_json", sa.Text(), nullable=False),
    )
    op.create_index("ix_changes_revision", "changes", ["revision"])
    op.create_table(
        "operations",
        sa.Column("operation_id", sa.Text(), primary_key=True),
        sa.Column("result_json", sa.Text(), nullable=False),
        sa.Column("created_at", sa.Text(), nullable=False),
    )
    op.create_table(
        "tokens",
        sa.Column("id", sa.Text(), primary_key=True),
        sa.Column("hash", sa.Text(), nullable=False),
        sa.Column("scopes", sa.Text(), nullable=False),
        sa.Column("revoked", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.Text(), nullable=False),
        sa.Column("revoked_at", sa.Text(), nullable=True),
    )
    op.create_index("ix_readings_meter_recorded", "readings", ["meter_id", "recorded_at"])
    op.create_index("ix_tariffs_meter_effective", "tariffs", ["meter_id", "effective_from"])

    connection = op.get_bind()
    instance_id = str(uuid.uuid4())
    connection.execute(
        sa.text("INSERT INTO metadata(key, value) VALUES ('backend_instance_id', :value)"),
        {"value": instance_id},
    )
    connection.execute(sa.text("INSERT INTO metadata(key, value) VALUES ('global_revision', '0')"))
    for revision_number, meter_type, unit in (
        (1, "ELECTRICITY", "kWh"),
        (2, "COLD_WATER", "t"),
        (3, "HOT_WATER", "t"),
    ):
        meter_id = str(uuid.uuid4())
        payload = {
            "id": meter_id,
            "meter_type": meter_type,
            "generation": 1,
            "unit": unit,
            "active": True,
            "created_revision": revision_number,
            "deleted": False,
        }
        connection.execute(
            sa.text("INSERT INTO meters VALUES (:id, :meter_type, 1, :unit, 1, :revision, 0)"),
            {"id": meter_id, "meter_type": meter_type, "unit": unit, "revision": revision_number},
        )
        connection.execute(
            sa.text("INSERT INTO changes VALUES (:revision, 'meter', :id, NULL, :payload)"),
            {
                "revision": revision_number,
                "id": meter_id,
                "payload": json.dumps(payload, separators=(",", ":")),
            },
        )
    connection.execute(sa.text("UPDATE metadata SET value = '3' WHERE key = 'global_revision'"))


def downgrade() -> None:
    op.drop_table("tokens")
    op.drop_table("operations")
    op.drop_index("ix_changes_revision", table_name="changes")
    op.drop_table("changes")
    op.drop_index("ix_tariffs_meter_effective", table_name="tariffs")
    op.drop_table("tariffs")
    op.drop_index("ix_readings_meter_recorded", table_name="readings")
    op.drop_table("readings")
    op.drop_table("meters")
    op.drop_table("metadata")
