-- ============================================================================
-- V1: event / messaging infrastructure tables.
-- Postgres dialect. Safe to run on a Hibernate-managed schema (baseline-on-migrate
-- is enabled; existing tables are untouched, only the three below are created).
--
-- Table naming note: the domain model already defines an entity `AuditLog` which
-- Hibernate maps to `audit_log`. To avoid collision, the event-driven audit
-- table used by AuditConsumer is named `event_audit_log`.
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Idempotency guard: one row per (eventId, consumer) that has been processed.
-- Consumers INSERT ... ON CONFLICT DO NOTHING to claim an event exactly once.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS processed_events (
    event_id       VARCHAR(64)  NOT NULL,
    consumer_name  VARCHAR(128) NOT NULL,
    processed_at   TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (event_id, consumer_name)
);

-- ---------------------------------------------------------------------------
-- Transactional outbox: events written inside the same transaction as the
-- business state change; a background relay publishes them to SNS.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS outbox_events (
    id                BIGSERIAL    PRIMARY KEY,
    event_id          CHAR(36)     NOT NULL UNIQUE,
    aggregate_type    VARCHAR(64)  NOT NULL,
    aggregate_id      VARCHAR(64)  NOT NULL,
    event_type        VARCHAR(128) NOT NULL,
    topic_name        VARCHAR(128) NOT NULL,
    correlation_id    VARCHAR(64),
    schema_version    INT          NOT NULL DEFAULT 1,
    payload           JSONB        NOT NULL,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at      TIMESTAMPTZ  NULL,
    attempt_count     INT          NOT NULL DEFAULT 0,
    last_attempt_at   TIMESTAMPTZ  NULL,
    last_error        TEXT         NULL
);

-- Relay polls WHERE published_at IS NULL ORDER BY id — this partial index keeps
-- the hot-path scan on unpublished rows tight as the table grows.
CREATE INDEX IF NOT EXISTS idx_outbox_unpublished
    ON outbox_events (id)
    WHERE published_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_outbox_aggregate
    ON outbox_events (aggregate_type, aggregate_id);

-- ---------------------------------------------------------------------------
-- Event-level audit log (distinct from the HTTP AuditLog entity). Populated by
-- the AuditConsumer subscribed to subby-audit-log.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS event_audit_log (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        CHAR(36),
    event_type      VARCHAR(128),
    aggregate_type  VARCHAR(64),
    aggregate_id    VARCHAR(64),
    actor           VARCHAR(128),
    details         JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_event_audit_aggregate
    ON event_audit_log (aggregate_type, aggregate_id, created_at);
