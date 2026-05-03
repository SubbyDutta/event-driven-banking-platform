-- ============================================================================
-- V6: outbox relay race-safety — lease columns + duplicate-prevention constraint.
-- ============================================================================

ALTER TABLE outbox_events
    ADD COLUMN IF NOT EXISTS lease_id          UUID         NULL,
    ADD COLUMN IF NOT EXISTS lease_expires_at  TIMESTAMPTZ  NULL;

-- Multi-replica relay safety: a row is claimable only when not yet published AND
-- (no current lease, or the lease has expired). The relay claims rows inside a
-- SELECT FOR UPDATE batch and stamps lease_id + lease_expires_at on each.
CREATE INDEX IF NOT EXISTS idx_outbox_lease_claim
    ON outbox_events (published_at, lease_expires_at)
    WHERE published_at IS NULL;

-- Belt-and-braces: prevent duplicate emissions for the same aggregate +
-- event type at the same schema version. The application layer already
-- guards against this; this constraint closes any race at the DB level.
ALTER TABLE outbox_events
    ADD CONSTRAINT uq_outbox_aggregate_event_schema
        UNIQUE (aggregate_id, event_type, schema_version);
