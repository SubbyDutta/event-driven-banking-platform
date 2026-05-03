-- ============================================================================
-- V4: Event-driven loan origination
--   Extends the existing Hibernate-defaulted `loan_application` table with
--   lifecycle fields, findoc-verify correlation, ML risk outputs, and audit
--   timestamps. Adds a sibling `loan_decision_overrides` table that mirrors
--   the KYC override pattern introduced in V2.
--
--   Nothing is dropped or renamed — the legacy columns (username, amount,
--   due_amount, approved, status, months_remaining, monthly_emi, approved_at,
--   next_due_date) stay intact so LoanRepayController and the existing repay
--   flow keep working unchanged. The new columns are additive; the one-shot
--   UPDATE at the bottom back-fills lifecycle_status for pre-V4 rows.
-- ============================================================================

-- ---------- loan_application: additive columns ----------

ALTER TABLE loan_application
    ADD COLUMN IF NOT EXISTS external_id                 VARCHAR(64),
    ADD COLUMN IF NOT EXISTS user_id                     BIGINT,
    ADD COLUMN IF NOT EXISTS lifecycle_status            VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN IF NOT EXISTS purpose                     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS findoc_loan_application_id  VARCHAR(64),
    ADD COLUMN IF NOT EXISTS loan_report_json            JSONB,
    ADD COLUMN IF NOT EXISTS fraud_score                 NUMERIC(5,3),
    ADD COLUMN IF NOT EXISTS risk_band                   VARCHAR(4),
    ADD COLUMN IF NOT EXISTS risk_probability            NUMERIC(5,3),
    ADD COLUMN IF NOT EXISTS interest_rate               NUMERIC(5,2),
    ADD COLUMN IF NOT EXISTS decision_reason             TEXT,
    ADD COLUMN IF NOT EXISTS submitted_at                TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS decided_at                  TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- Lifecycle enum values (app-enforced, no DB CHECK so we can evolve without a migration):
--   DRAFT, DOCS_UNDER_REVIEW, DOCS_VERIFIED, DOCS_REJECTED,
--   RISK_EVALUATED, APPROVED, REJECTED, MANUAL_REVIEW, FAILED

CREATE UNIQUE INDEX IF NOT EXISTS idx_loan_app_external_id
    ON loan_application (external_id)
    WHERE external_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_loan_app_user_id
    ON loan_application (user_id);

CREATE INDEX IF NOT EXISTS idx_loan_app_lifecycle
    ON loan_application (lifecycle_status);

-- Back-fill lifecycle_status for rows created before V4. Every legacy row has a
-- populated `status` ('PENDING' | 'APPROVED' | 'PAID'); we map those to the
-- closest terminal state. Rows stuck at PENDING move to MANUAL_REVIEW so an
-- admin can decide what to do with them rather than leaving them in DRAFT and
-- tripping "active application" preconditions.
UPDATE loan_application
SET lifecycle_status = CASE status
        WHEN 'APPROVED' THEN 'APPROVED'
        WHEN 'PAID'     THEN 'APPROVED'
        ELSE                 'MANUAL_REVIEW'
    END
WHERE lifecycle_status = 'DRAFT'
  AND status IS NOT NULL;

-- ---------- loan_decision_overrides: admin audit trail ----------

CREATE TABLE IF NOT EXISTS loan_decision_overrides (
    id                    BIGSERIAL      PRIMARY KEY,
    loan_application_id   BIGINT         NOT NULL REFERENCES loan_application(id),
    original_decision     VARCHAR(32),
    new_decision          VARCHAR(32),
    reason                TEXT,
    overridden_by         VARCHAR(128),
    notify_findoc         BOOLEAN        NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_loan_overrides_app
    ON loan_decision_overrides (loan_application_id);
