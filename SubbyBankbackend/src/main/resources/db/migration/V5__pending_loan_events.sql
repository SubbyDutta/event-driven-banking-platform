CREATE TABLE IF NOT EXISTS pending_loan_events (
    id            BIGSERIAL    PRIMARY KEY,
    loan_id       BIGINT       NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    payload_json  TEXT         NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_pending_loan_events_loan
    ON pending_loan_events (loan_id, id);
