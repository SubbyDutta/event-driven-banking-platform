-- ============================================================================
-- V2: KYC lifecycle on users + admin override audit table.
--
-- Postgres dialect. The spec (prompt 4) uses MySQL ENUM / VARBINARY / JSON; the
-- equivalents here:
--   ENUM           -> VARCHAR (enum mapped via @Enumerated(EnumType.STRING))
--   VARBINARY(255) -> VARCHAR (PiiConverter produces Base64 ciphertext strings)
--   JSON           -> JSONB   (report blobs benefit from indexing / containment ops)
--
-- All columns are added via ADD COLUMN IF NOT EXISTS so this is idempotent on
-- databases where Hibernate already auto-created partial columns in earlier runs.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- users: KYC lifecycle
-- ---------------------------------------------------------------------------
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS kyc_status VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ADD COLUMN IF NOT EXISTS kyc_submitted_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS kyc_decided_at TIMESTAMPTZ NULL,
    ADD COLUMN IF NOT EXISTS findoc_kyc_application_id VARCHAR(64) NULL,
    ADD COLUMN IF NOT EXISTS kyc_report_json JSONB NULL,
    ADD COLUMN IF NOT EXISTS kyc_decision_reason TEXT NULL,
    -- PiiConverter (AES-GCM) produces Base64 strings; generous sizing for short
    -- PII values ciphertext (12-byte IV + tag + ~16-byte plaintext, Base64-encoded ≈ 60 chars).
    ADD COLUMN IF NOT EXISTS aadhaar_number_encrypted VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS pan_number_encrypted VARCHAR(512) NULL,
    ADD COLUMN IF NOT EXISTS account_active BOOLEAN NOT NULL DEFAULT FALSE;

-- Enforce the enum values at the DB layer too. Dropped-and-recreated (IF NOT
-- EXISTS doesn't exist for CHECK constraints) so reruns stay idempotent.
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_kyc_status_chk;
ALTER TABLE users
    ADD CONSTRAINT users_kyc_status_chk
    CHECK (kyc_status IN ('NONE','KYC_SUBMITTED','KYC_DOCS_UNDER_REVIEW',
                          'KYC_APPROVED','KYC_REJECTED','KYC_MANUAL_REVIEW'));

CREATE INDEX IF NOT EXISTS idx_users_kyc_status ON users (kyc_status);
CREATE INDEX IF NOT EXISTS idx_users_findoc_kyc_app ON users (findoc_kyc_application_id);

-- ---------------------------------------------------------------------------
-- kyc_decision_overrides: append-only audit of admin decisions
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kyc_decision_overrides (
    id                BIGSERIAL     PRIMARY KEY,
    user_id           BIGINT        NOT NULL,
    original_decision VARCHAR(32),
    new_decision      VARCHAR(32),
    reason            TEXT,
    overridden_by     VARCHAR(128),
    notify_findoc     BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_kyc_override_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_kyc_override_user ON kyc_decision_overrides (user_id, created_at);
