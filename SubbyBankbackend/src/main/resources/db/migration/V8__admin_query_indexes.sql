-- ============================================================================
-- V8: indexes covering remaining admin-view query paths.
--
--   * AdminController.getAllUsers        users ORDER BY id (already PK)
--   * AdminKycController.searchForKycAdmin
--                                          users WHERE kyc_status = ? AND
--                                          (lower(...) LIKE ?) ORDER BY id
--   * AdminLoanController.list             loan_application WHERE
--                                          lifecycle_status = ? ORDER BY id DESC
--                                          (V7 covered status + created_at;
--                                          this adds id desc for stable paging)
--   * LoanApplicationRepository.findByExternalId / findByUserIdOrderByIdDesc
--   * BankAccountRepository.findByUser_Id  → bank_account.user_id
--   * BuisnessLoggingRepository.findAllByOrderByTimestampDesc / findByAction
--   * KycDecisionOverrideRepository.findByUserIdOrderByCreatedAtDesc
--   * LoanDecisionOverrideRepository.findByLoanApplicationIdOrderByIdDesc
-- ============================================================================

-- KYC admin list: filter by kyc_status, paginate by id
CREATE INDEX IF NOT EXISTS idx_users_kyc_status_id
    ON users (kyc_status, id DESC);

-- Case-insensitive search across username/email/firstname/lastname/mobile.
-- Single trigram index per column would be ideal; here we stick with simple
-- expression indexes that match the existing LOWER(...) LIKE pattern.
CREATE INDEX IF NOT EXISTS idx_users_lower_username
    ON users (LOWER(username));
CREATE INDEX IF NOT EXISTS idx_users_lower_email
    ON users (LOWER(email));
CREATE INDEX IF NOT EXISTS idx_users_lower_mobile
    ON users (LOWER(mobile));

-- Loan admin list: stable pagination by id desc inside a status filter.
CREATE INDEX IF NOT EXISTS idx_loan_status_id
    ON loan_application (lifecycle_status, id DESC);

-- Per-user loan history.
CREATE INDEX IF NOT EXISTS idx_loan_user_id
    ON loan_application (user_id, id DESC);

-- BankAccount join from User detail screens.
CREATE INDEX IF NOT EXISTS idx_bank_account_user_id
    ON bank_account (user_id);

-- Business log audit panel: list newest first, optionally filter by action.
CREATE INDEX IF NOT EXISTS idx_buisness_log_timestamp
    ON buisness_log (timestamp DESC);
CREATE INDEX IF NOT EXISTS idx_buisness_log_action_timestamp
    ON buisness_log (action, timestamp DESC);

-- KYC override history (user detail drawer).
CREATE INDEX IF NOT EXISTS idx_kyc_overrides_user_created
    ON kyc_decision_overrides (user_id, created_at DESC);

-- Loan override history (loan detail drawer).
CREATE INDEX IF NOT EXISTS idx_loan_overrides_app_id
    ON loan_decision_overrides (loan_application_id, id DESC);
