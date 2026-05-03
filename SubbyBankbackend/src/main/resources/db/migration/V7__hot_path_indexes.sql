-- ============================================================================
-- V7: indexes on hot query paths.
--   * Admin loans-list query filters by lifecycle_status and orders by created_at DESC.
--   * Per-user transaction history filters by user_id and orders by timestamp DESC.
-- Tables `loan_application` and `transaction` are Hibernate-managed (no @Table
-- override on the Transaction entity → Spring naming strategy yields singular
-- `transaction`).
-- ============================================================================

CREATE INDEX IF NOT EXISTS idx_loan_status_created
    ON loan_application (lifecycle_status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_tx_user_time
    ON transaction (user_id, timestamp DESC);
