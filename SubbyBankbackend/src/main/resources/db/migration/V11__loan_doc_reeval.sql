-- ============================================================================
-- V11: Persist findoc re-evaluation outcome on the loan row.
--
--   When an admin replays the findoc pipeline (or overrides the findoc-side
--   decision) AFTER the Java side has already moved past DOCS_REJECTED, the
--   re-result must NOT silently flip the lifecycle. Instead the consumer
--   captures the new finding here and re-pages the admin via
--   LoanPendingAdminDecision so the admin can choose to override or not.
--
--   Columns are nullable — first-pass loans never populate them.
-- ============================================================================

ALTER TABLE loan_application
    ADD COLUMN IF NOT EXISTS doc_reeval_result     VARCHAR(32),
    ADD COLUMN IF NOT EXISTS doc_reeval_reason     TEXT,
    ADD COLUMN IF NOT EXISTS doc_reeval_run_number INT,
    ADD COLUMN IF NOT EXISTS doc_reeval_at         TIMESTAMP;
