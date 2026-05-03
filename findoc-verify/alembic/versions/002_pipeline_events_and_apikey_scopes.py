"""pipeline_events table + api_keys.scopes/rate_limit columns"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "002_pipeline_events_and_apikey_scopes"
down_revision = "001_initial"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "pipeline_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False),
        sa.Column("step_name", sa.String(64), nullable=False),
        sa.Column("step_status", sa.String(32), nullable=False),
        sa.Column("document_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("duration_ms", sa.Integer, nullable=True),
        sa.Column("details", postgresql.JSONB, nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index(
        "idx_pipeline_events_app",
        "pipeline_events",
        ["application_id", "created_at"],
    )

    op.add_column(
        "api_keys",
        sa.Column("scopes", postgresql.JSONB, nullable=False, server_default=sa.text("'[]'::jsonb")),
    )
    op.add_column(
        "api_keys",
        sa.Column("rate_limit_per_min", sa.Integer, nullable=False, server_default="60"),
    )


def downgrade() -> None:
    op.drop_column("api_keys", "rate_limit_per_min")
    op.drop_column("api_keys", "scopes")
    op.drop_index("idx_pipeline_events_app", table_name="pipeline_events")
    op.drop_table("pipeline_events")
