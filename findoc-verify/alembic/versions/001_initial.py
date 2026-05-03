"""initial schema — unified KYC + Loan"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects import postgresql

revision = "001_initial"
down_revision = None
branch_labels = None
depends_on = None

USE_CASE = ("kyc", "loan")
APPLICATION_STATUS = ("received", "processing", "needs_review", "approved", "rejected")
DOC_TYPE = ("aadhaar", "pan", "bank_statement", "payslip", "employment_letter", "itr", "credit_report")
EXTRACTION_METHOD = ("regex", "llm", "hybrid")
CHECK_STATUS = ("pass", "fail", "warning")
FRAUD_SEVERITY = ("low", "med", "high")
RECOMMENDATION = ("approve", "reject", "manual_review", "verified")


def upgrade() -> None:
    bind = op.get_bind()
    for name, values in (
        ("use_case", USE_CASE),
        ("application_status", APPLICATION_STATUS),
        ("doc_type", DOC_TYPE),
        ("extraction_method", EXTRACTION_METHOD),
        ("check_status", CHECK_STATUS),
        ("fraud_severity", FRAUD_SEVERITY),
        ("recommendation", RECOMMENDATION),
    ):
        postgresql.ENUM(*values, name=name).create(bind, checkfirst=True)

    def e(name: str) -> postgresql.ENUM:
        return postgresql.ENUM(name=name, create_type=False)

    op.create_table(
        "api_keys",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("key_hash", sa.String(128), unique=True, nullable=False),
        sa.Column("label", sa.String(128), nullable=False),
        sa.Column("org_name", sa.String(128), nullable=False, server_default="default"),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("revoked_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("last_used_at", sa.DateTime(timezone=True), nullable=True),
    )

    op.create_table(
        "applications",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("external_id", sa.String(128), nullable=False, unique=True),
        sa.Column("use_case", e("use_case"), nullable=False),
        sa.Column("applicant_name", sa.String(255), nullable=False),
        sa.Column("email", sa.String(255), nullable=False),
        sa.Column("phone", sa.String(32), nullable=False),
        sa.Column("status", e("application_status"), nullable=False, server_default="received"),
        sa.Column("submitted_by_api_key_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("api_keys.id", ondelete="SET NULL"), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_applications_submitted_by", "applications", ["submitted_by_api_key_id"])

    op.create_table(
        "documents",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False),
        sa.Column("doc_type", e("doc_type"), nullable=False),
        sa.Column("file_key", sa.String(512), nullable=False),
        sa.Column("file_hash", sa.String(128), nullable=False),
        sa.Column("original_filename", sa.String(512), nullable=False),
        sa.Column("size_bytes", sa.BigInteger, nullable=False),
        sa.Column("page_count", sa.Integer, nullable=True),
        sa.Column("uploaded_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
        sa.Column("period_month", sa.Date, nullable=True),
    )
    op.create_index("ix_documents_application_id", "documents", ["application_id"])
    op.create_index("ix_documents_file_hash", "documents", ["file_hash"])

    op.create_table(
        "ocr_results",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("document_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("documents.id", ondelete="CASCADE"), nullable=False, unique=True),
        sa.Column("raw_text", sa.Text, nullable=False),
        sa.Column("page_texts", postgresql.JSONB, nullable=False, server_default=sa.text("'[]'::jsonb")),
        sa.Column("provider", sa.String(64), nullable=False),
        sa.Column("latency_ms", sa.Integer, nullable=False),
        sa.Column("avg_confidence", sa.Float, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    op.create_table(
        "doc_classifications",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("document_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("documents.id", ondelete="CASCADE"), nullable=False, unique=True),
        sa.Column("classified_type", sa.String(64), nullable=False),
        sa.Column("confidence", sa.Float, nullable=False),
        sa.Column("reasoning", sa.Text, nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    op.create_table(
        "extracted_fields",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("document_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("documents.id", ondelete="CASCADE"), nullable=False),
        sa.Column("field_name", sa.String(128), nullable=False),
        sa.Column("field_value", sa.Text, nullable=False),
        sa.Column("confidence", sa.Float, nullable=False),
        sa.Column("extraction_method", e("extraction_method"), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_extracted_fields_document_id", "extracted_fields", ["document_id"])

    op.create_table(
        "compliance_checks",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False),
        sa.Column("check_name", sa.String(128), nullable=False),
        sa.Column("status", e("check_status"), nullable=False),
        sa.Column("details", postgresql.JSONB, nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_compliance_checks_application_id", "compliance_checks", ["application_id"])

    op.create_table(
        "cross_doc_validations",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False),
        sa.Column("rule_name", sa.String(128), nullable=False),
        sa.Column("status", e("check_status"), nullable=False),
        sa.Column("doc_ids", postgresql.ARRAY(postgresql.UUID(as_uuid=True)),
                  nullable=False, server_default=sa.text("ARRAY[]::uuid[]")),
        sa.Column("details", postgresql.JSONB, nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_cross_doc_validations_application_id", "cross_doc_validations", ["application_id"])

    op.create_table(
        "fraud_results",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False),
        sa.Column("signal_name", sa.String(128), nullable=False),
        sa.Column("severity", e("fraud_severity"), nullable=False),
        sa.Column("score", sa.Float, nullable=False),
        sa.Column("details", postgresql.JSONB, nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_fraud_results_application_id", "fraud_results", ["application_id"])

    op.create_table(
        "verification_reports",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False, unique=True),
        sa.Column("recommendation", e("recommendation"), nullable=False),
        sa.Column("overall_score", sa.Float, nullable=False),
        sa.Column("income_monthly_inr", sa.Numeric(14, 2), nullable=True),
        sa.Column("income_annual_inr", sa.Numeric(14, 2), nullable=True),
        sa.Column("dti_ratio", sa.Float, nullable=True),
        sa.Column("credit_score", sa.Integer, nullable=True),
        sa.Column("report_json", postgresql.JSONB, nullable=False, server_default=sa.text("'{}'::jsonb")),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )

    op.create_table(
        "decision_overrides",
        sa.Column("id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("application_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("applications.id", ondelete="CASCADE"), nullable=False),
        sa.Column("previous_recommendation", e("recommendation"), nullable=False),
        sa.Column("new_recommendation", e("recommendation"), nullable=False),
        sa.Column("reason", sa.Text, nullable=False),
        sa.Column("actor_api_key_id", postgresql.UUID(as_uuid=True),
                  sa.ForeignKey("api_keys.id", ondelete="SET NULL"), nullable=True),
        sa.Column("actor_org", sa.String(128), nullable=True),
        sa.Column("created_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )
    op.create_index("ix_decision_overrides_application_id", "decision_overrides", ["application_id"])

    op.create_table(
        "processed_events",
        sa.Column("event_id", postgresql.UUID(as_uuid=True), primary_key=True),
        sa.Column("worker_name", sa.String(64), primary_key=True),
        sa.Column("processed_at", sa.DateTime(timezone=True), server_default=sa.func.now()),
    )


def downgrade() -> None:
    for t in [
        "processed_events", "decision_overrides", "verification_reports",
        "fraud_results", "cross_doc_validations", "compliance_checks",
        "extracted_fields", "doc_classifications", "ocr_results", "documents",
        "applications", "api_keys",
    ]:
        op.drop_table(t)
    for name in ("recommendation", "fraud_severity", "check_status",
                 "extraction_method", "doc_type", "application_status", "use_case"):
        op.execute(f"DROP TYPE IF EXISTS {name}")
