"""Add recharge ledger and immutable synchronization operation metadata."""

import sqlalchemy as sa

from alembic import op

revision = "0002_recharges"
down_revision = "0001_initial"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "recharges",
        sa.Column("id", sa.Text(), primary_key=True),
        sa.Column("meter_id", sa.Text(), sa.ForeignKey("meters.id"), nullable=False),
        sa.Column("amount_decimal", sa.Text(), nullable=False),
        sa.Column("unit_price_decimal", sa.Text(), nullable=False),
        sa.Column("quantity_decimal", sa.Text(), nullable=False),
        sa.Column("currency", sa.Text(), nullable=False),
        sa.Column("credited_at", sa.Text(), nullable=False),
        sa.Column("note", sa.Text()),
        sa.Column("deleted", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.Text(), nullable=False),
        sa.Column("updated_at", sa.Text(), nullable=False),
        sa.Column("server_revision", sa.Integer(), nullable=False, unique=True),
        sa.Column("updated_by_device_id", sa.Text(), nullable=False),
    )
    op.create_index("ix_recharges_meter_credited", "recharges", ["meter_id", "credited_at"])
    op.add_column("operations", sa.Column("request_hash", sa.Text()))
    op.create_table(
        "atomic_groups",
        sa.Column("id", sa.Text(), primary_key=True),
        sa.Column("members_json", sa.Text(), nullable=False),
    )


def downgrade() -> None:
    raise RuntimeError(
        "Use a verified backup only before new writes; otherwise forward-fix the ledger"
    )
