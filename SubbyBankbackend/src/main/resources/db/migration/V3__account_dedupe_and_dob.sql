-- ============================================================================
-- V3: KYC-driven account hardening
--   1. Add users.dob (applicant-provided, cross-checked against Aadhaar/PAN
--      during KYC via findoc-verify's applicant_dob_match compliance check).
--   2. UNIQUE on users.aadhaar_number_encrypted and users.pan_number_encrypted
--      — duplicate-document prevention. PiiConverter is deterministic (same
--      plaintext -> same ciphertext), so a plain SQL UNIQUE works as the
--      "is this document already linked to someone else?" guard. Postgres
--      treats multiple NULLs as distinct, so users who haven't completed KYC
--      yet don't collide.
--   3. Drop bank_account.adhar / bank_account.pan. After V2, the same values
--      live (encrypted) on users, and BankAccount is 1:1 with User — keeping
--      them was pure duplication. The KycController already gates account
--      creation; the unique constraints above keep the invariant.
-- ============================================================================

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS dob DATE NULL;

-- UNIQUE constraint names are dropped-then-added so reruns stay idempotent
-- (ALTER TABLE ADD CONSTRAINT IF NOT EXISTS doesn't exist for UNIQUE).
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_aadhaar_unique;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_pan_unique;
ALTER TABLE users
    ADD CONSTRAINT users_aadhaar_unique UNIQUE (aadhaar_number_encrypted);
ALTER TABLE users
    ADD CONSTRAINT users_pan_unique     UNIQUE (pan_number_encrypted);

-- Drop the duplicated columns from bank_account. IF EXISTS so the migration
-- is safe to re-run against a schema that was already trimmed manually.
ALTER TABLE bank_account
    DROP COLUMN IF EXISTS adhar,
    DROP COLUMN IF EXISTS pan;
