-- H2 runs Hibernate ddl-auto=create-drop to build the schema from @Entity
-- mappings. Several entities declare `insertable=false, updatable=false` on
-- created_at/updated_at columns, relying on the Postgres DEFAULT NOW() that
-- Flyway provides in production. H2 has no such default, so inserts fail
-- with "NULL not allowed". This script patches those columns with a DEFAULT
-- CURRENT_TIMESTAMP that H2 understands. Test-only.

ALTER TABLE outbox_events       ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE loan_application    ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE loan_application    ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE kyc_decision_overrides       ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE loan_decision_overrides      ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE pending_loan_events          ALTER COLUMN received_at SET DEFAULT CURRENT_TIMESTAMP;
