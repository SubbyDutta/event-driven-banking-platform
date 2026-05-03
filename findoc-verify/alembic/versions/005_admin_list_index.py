"""applications: composite index on (status, created_at) for the admin status-filtered list query"""
from __future__ import annotations

from alembic import op

revision = "005_admin_list_index"
down_revision = "004_processed_events_state"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_index(
        "idx_applications_status_created",
        "applications",
        ["status", "created_at"],
        postgresql_using="btree",
    )


def downgrade() -> None:
    op.drop_index("idx_applications_status_created", table_name="applications")
