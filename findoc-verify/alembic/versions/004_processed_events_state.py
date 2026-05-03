"""processed_events: add state/attempts/last_error for retry-safe idempotency"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "004_processed_events_state"
down_revision = "003_applicant_dob"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "processed_events",
        sa.Column("state", sa.String(16), nullable=False, server_default="SUCCEEDED"),
    )
    op.add_column(
        "processed_events",
        sa.Column("attempts", sa.Integer(), nullable=False, server_default="0"),
    )
    op.add_column(
        "processed_events",
        sa.Column("last_error", sa.Text(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("processed_events", "last_error")
    op.drop_column("processed_events", "attempts")
    op.drop_column("processed_events", "state")
