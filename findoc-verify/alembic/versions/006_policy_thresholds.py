"""policy_thresholds: admin-tunable numeric thresholds with seed defaults"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "006_policy_thresholds"
down_revision = "005_admin_list_index"
branch_labels = None
depends_on = None


_SEED = [
    ("bank_holder_name_match_min", 85.0),
    ("credit_score_min", 650.0),
    ("name_match_threshold", 0.5),
    ("payslip_period_months", 3.0),
    ("dti_max_ratio", 0.55),
    ("income_cv_max", 0.40),
    ("bank_bounce_max", 3.0),
    ("itr_payslip_deviation_max", 0.30),
    ("emi_burden_max", 0.50),
    ("id_min_short_side_px", 600.0),
    ("ocr_confidence_min", 0.80),
    ("recommendation_approve_min_score", 0.85),
    ("recommendation_reject_max_score", 0.45),
]


def upgrade() -> None:
    op.create_table(
        "policy_thresholds",
        sa.Column("key", sa.String(64), primary_key=True),
        sa.Column("value", sa.Float(), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("updated_by", sa.String(128), nullable=True),
    )
    table = sa.table(
        "policy_thresholds",
        sa.column("key", sa.String),
        sa.column("value", sa.Float),
        sa.column("updated_by", sa.String),
    )
    op.bulk_insert(table, [{"key": k, "value": v, "updated_by": "migration"} for k, v in _SEED])


def downgrade() -> None:
    op.drop_table("policy_thresholds")
