-- ============================================================================
-- V10: drop the (aggregate_id, event_type, schema_version) outbox constraint.
-- ============================================================================
--
-- V6 added uq_outbox_aggregate_event_schema as a "belt-and-braces" guard
-- against duplicate emissions of the same logical event. In practice the
-- constraint was too strict: it blocks legitimate re-emission during admin
-- replay flows, e.g. publishing a fresh LoanRiskRequested after corrected
-- extracted-fields trigger a second pipeline run, or staging a new
-- LoanFinalized for a loan whose maker-checker hold previously occupied the
-- (aggregate_id, "LoanFinalized", 1) slot. The application has no clean way
-- to distinguish "redelivered duplicate" from "intentional replay" at the
-- outbox layer.
--
-- Idempotency is preserved by:
--   1. outbox_events.event_id (UNIQUE) — every publish() generates a fresh
--      UUID, and a redelivered logical event reuses the same event_id, so the
--      DB rejects the second insert.
--   2. processed_events table on the consumer side (IdempotencyGuard) —
--      consumers dedupe by event_id, so even if the same event lands twice
--      via SNS at-least-once delivery, side-effects fire once.
--
-- Removing the constraint shifts dedup responsibility to the event_id layer
-- (correct), and unblocks the replay path without introducing per-flow event
-- types like LoanRiskReRequested.

ALTER TABLE outbox_events
    DROP CONSTRAINT IF EXISTS uq_outbox_aggregate_event_schema;
