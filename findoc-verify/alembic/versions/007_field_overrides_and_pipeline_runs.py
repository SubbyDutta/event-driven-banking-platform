"""extracted_field_overrides + pipeline_runs + applications.current_run_id

Backs the admin replay capability. Overrides are admin edits to extracted
field values; they accumulate as applied_to_run_id=NULL and are stamped to a
new pipeline_runs row at replay time. pipeline_runs preserves a per-replay
forensic snapshot of the recommendation + report so prior runs aren't lost.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "007_field_overrides_and_pipeline_runs"
down_revision = "006_policy_thresholds"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "pipeline_runs",
        sa.Column("id", sa.BigInteger, primary_key=True, autoincrement=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False),
        sa.Column("run_number", sa.Integer, nullable=False),
        sa.Column("triggered_by", sa.String(128), nullable=True),
        sa.Column("reason", sa.Text, nullable=True),
        sa.Column("started_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("completed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("recommendation", sa.String(32), nullable=True),
        sa.Column("report_json", postgresql.JSONB, nullable=True),
        sa.UniqueConstraint("application_id", "run_number", name="uq_pipeline_runs_app_run"),
    )
    op.create_index("idx_pipeline_runs_app", "pipeline_runs", ["application_id", "run_number"])

    op.create_table(
        "extracted_field_overrides",
        sa.Column("id", sa.BigInteger, primary_key=True, autoincrement=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False),
        sa.Column("document_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("documents.id", ondelete="SET NULL"), nullable=True),
        sa.Column("field_name", sa.String(128), nullable=False),
        sa.Column("original_value", sa.Text, nullable=True),
        sa.Column("new_value", sa.Text, nullable=False),
        sa.Column("reason", sa.Text, nullable=False),
        sa.Column("edited_by", sa.String(128), nullable=True),
        sa.Column("edited_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("applied_to_run_id", sa.BigInteger,
                  sa.ForeignKey("pipeline_runs.id", ondelete="SET NULL"), nullable=True),
    )
    op.create_index(
        "idx_field_overrides_app_pending",
        "extracted_field_overrides",
        ["application_id", "applied_to_run_id"],
    )

    op.add_column(
        "applications",
        sa.Column("current_run_id", sa.BigInteger,
                  sa.ForeignKey("pipeline_runs.id", ondelete="SET NULL"), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("applications", "current_run_id")
    op.drop_index("idx_field_overrides_app_pending", table_name="extracted_field_overrides")
    op.drop_table("extracted_field_overrides")
    op.drop_index("idx_pipeline_runs_app", table_name="pipeline_runs")
    op.drop_table("pipeline_runs")
