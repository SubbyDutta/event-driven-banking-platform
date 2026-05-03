-- ============================================================================
-- V9: Persist the ML layer's raw recommendation on the loan row.
--
--   The maker-checker workflow holds ML-approved loans at PENDING_ADMIN_DECISION
--   instead of auto-finalizing. The admin UI needs both layer outcomes (findoc
--   + ML) visible at decision time, so we capture the ML signal explicitly
--   rather than re-deriving it from risk_band + decision_reason text.
-- ============================================================================

ALTER TABLE loan_application
    ADD COLUMN IF NOT EXISTS ml_recommendation VARCHAR(32);
