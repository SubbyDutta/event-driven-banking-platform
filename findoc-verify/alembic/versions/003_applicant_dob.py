"""applications.applicant_dob — applicant-provided DOB for cross-doc verification.

Added so KYC can check the DOB the applicant claims at signup matches the DOB
extracted from their Aadhaar and PAN documents. Nullable because older rows
pre-date this column and the loan use-case doesn't strictly need it.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "003_applicant_dob"
down_revision = "002_pipeline_events_and_apikey_scopes"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "applications",
        sa.Column("applicant_dob", sa.Date(), nullable=True),
    )


def downgrade() -> None:
    op.drop_column("applications", "applicant_dob")
