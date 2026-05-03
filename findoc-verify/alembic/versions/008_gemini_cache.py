"""gemini_cache: Postgres-backed response cache for Gemini classify/extract calls"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "008_gemini_cache"
down_revision = "007_field_overrides_and_pipeline_runs"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "gemini_cache",
        sa.Column("id", sa.BigInteger, primary_key=True, autoincrement=True),
        sa.Column("cache_key", sa.String(128), nullable=False),
        sa.Column("prompt_version", sa.String(32), nullable=False),
        sa.Column("model_name", sa.String(128), nullable=False),
        sa.Column("response_json", postgresql.JSONB, nullable=False),
        sa.Column("prompt_tokens_in", sa.Integer, nullable=False, server_default="0"),
        sa.Column("tokens_out", sa.Integer, nullable=False, server_default="0"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now(), nullable=False),
        sa.Column("last_hit_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("hit_count", sa.Integer, nullable=False, server_default="0"),
        sa.UniqueConstraint("cache_key", name="uq_gemini_cache_key"),
    )
    op.create_index("idx_gemini_cache_key", "gemini_cache", ["cache_key"])


def downgrade() -> None:
    op.drop_index("idx_gemini_cache_key", table_name="gemini_cache")
    op.drop_table("gemini_cache")
