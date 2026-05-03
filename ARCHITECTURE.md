# Subby — System Architecture

This document is the script for walking through the system end-to-end. It covers
the four services, the event-driven topology that joins them, the reliability
primitives that keep money safe under partial failure, and the trade-offs that
were taken deliberately.

> For a high-level overview, screenshots, and the live demo path, see
> [`README.md`](README.md). This document is the engineering deep-dive.

## Contents

1. [Stack & services](#1-stack--services)
2. [Event-driven topology](#2-event-driven-topology)
3. [Reliability primitives](#3-reliability-primitives)
   - Transactional outbox with leased relay
   - Four-state idempotency claim
   - Visibility heartbeat
   - DLQ + replay
   - Schema versioning
4. [CorrelationId flow](#4-correlationid-flow)
5. [Admin override path](#5-admin-override-path)
6. [Scaling notes](#6-scaling-notes)
7. [Known limitations](#7-known-limitations)

---

## 1. Stack & services

- **SubbyBankbackend** — Spring Boot 3 / Java 21, Postgres, Redis cache, JWT auth,
  Flyway migrations, AWS SDK v2 (SNS/SQS/S3 via LocalStack in dev). Owns users,
  bank accounts, transactions, loan-application lifecycle, admin overrides.
- **findoc-verify** — FastAPI / Python 3.12, async SQLAlchemy on Postgres,
  Google Document AI + Gemini for OCR/extraction. Performs KYC and
  loan-origination document verification, publishes a verification report.
- **SubbyPythonLoan** — FastAPI / Python 3.12, XGBoost classifier loaded at
  startup. Consumes risk-request events, scores them, emits a band + decision.
- **FraudPython** — FastAPI / Python 3.12, XGBoost classifier. Sync HTTP service
  called from SubbyBankbackend's transfer path to score per-transaction fraud.

## 2. Event-driven topology

SNS topics fan out to per-consumer SQS queues with `RawMessageDelivery=true`.
Each queue has a paired DLQ; redrive policy fires after `maxReceiveCount` retries.

```
                       ┌─────────────────────────────┐
                       │    SubbyBankbackend (Java)  │
                       │  REST API, owns the truth   │
                       └─────────────┬───────────────┘
                  outbox writes      │  outbox row → SNS via OutboxRelay
   ┌───────────────────┬─────────────┼──────────────┬───────────────────┐
   │                   │             │              │                   │
   ▼                   ▼             ▼              ▼                   ▼
subby-kyc-events  subby-loan-   subby-risk-    subby-notifications  subby-audit-log
                  events        requested
   │                   │             │              │                   │
   ▼                   ▼             ▼              ▼                   ▼
┌───────────┐  ┌────────────────┐  ┌──────────────┐  ┌────────────┐  ┌─────────────┐
│ findoc-   │  │ Java loan      │  │ SubbyPython  │  │ Notify     │  │ Audit       │
│ verify    │  │ consumers      │  │ Loan worker  │  │ consumers  │  │ consumer    │
│ KycSubmit │  │ (Decision/etc) │  │ (XGBoost)    │  │ email/sms  │  │             │
└─────┬─────┘  └────────────────┘  └──────┬───────┘  └────────────┘  └─────────────┘
      │                                   │
      ▼ (after pipeline)                  ▼
findoc.{kyc|loan}.report.ready    subby-loan-risk-results
      │                                   │
      └──────────► Java consumers ◄───────┘
                   LoanFindocResultConsumer + LoanRiskResultConsumer
```

The Java side never does cross-service HTTP fan-out except for two synchronous
calls: the inbound document submission to findoc-verify (multipart upload) and
the per-transaction fraud check to FraudPython (fail-closed). Everything else
is async via the outbox + SNS + SQS chain.

## 3. Reliability primitives

**Transactional outbox.** Every published event is written to `outbox_events`
inside the same DB transaction as the originating business write. A
`OutboxRelay` polls unpublished rows under `SELECT ... FOR UPDATE SKIP LOCKED`,
publishes to SNS, and stamps `published_at`. Two replicas of the relay can run
simultaneously: each row is now leased with a `lease_id` UUID + 30s
`lease_expires_at`, so a relay that crashes mid-publish releases its rows
automatically once the lease elapses. A unique constraint on
`(aggregate_id, event_type, schema_version)` closes any application-layer race
that could otherwise emit a duplicate.

**Four-state idempotency claim.** Each consumer claims an `(event_id,
consumer_name)` pair through `processed_events` with one of four outcomes —
NEW, RETRY (FAILED row under `MAX_RETRIES`), SKIP_OK (already SUCCEEDED or
exhausted), SKIP_INFLIGHT (PENDING — another replica is mid-handle).
At-least-once delivery from SQS is therefore filtered into exactly-once
business effects with replay safety on transient failures.

**Visibility heartbeat.** While a handler runs, an asyncio task (Python) or
in-tx scheduled callback (Java) calls `ChangeMessageVisibility` every 15s to
extend the lease to 60s. Long-running document pipelines no longer get redelivered
mid-execution.

**DLQ + replay.** Per-queue DLQs catch poison messages after `maxReceiveCount`.
The findoc-verify consumer additionally republishes `NonRetriableError`
failures directly to its DLQ with a `DlqReason` MessageAttribute so they don't
burn the redelivery budget. Replay is operational only today (manual
redrive); the planned dedicated replay endpoint is deferred.

**Schema versioning.** Every event extends `DomainEvent` (Java) or builds an
envelope (Python) carrying `schemaVersion` (currently `1`). The version travels
in both the JSON envelope and as an SNS MessageAttribute, so consumers can
filter or branch on it without parsing the body. The Java `DomainEvent.eventType()`
is resolved from a `@EventType` annotation on the concrete class — the wire
type string is never inferred from the class name.

## 4. CorrelationId flow

Every request, event, and downstream call carries a `correlationId`:

1. `CorrelationIdFilter` (Java) and `CorrelationIdMiddleware` (Python, both
   services) read the `X-Correlation-Id` header on inbound HTTP, mint a UUID
   if absent, set it on SLF4J MDC / a Python `ContextVar`, and echo it back.
2. `OutboxEventPublisher` reads the MDC value and persists it on each
   `outbox_events` row; the relay copies it into the SNS MessageAttribute
   AND the JSON envelope on publish.
3. SQS consumers extract `correlationId` from the MessageAttribute (preferring
   it over the body field) and put it on MDC / contextvar before invoking the
   handler.
4. `FindocVerifyClient` and the Python publishers re-add `X-Correlation-Id`
   on outbound HTTP/SNS via a WebClient filter / `MessageAttribute` injection.

To trace a request end-to-end:

```bash
# Tail every service's stdout, filter by the id from the inbound response header
grep -h "<corrId>" subby-bank.log findoc-verify.log subby-python-loan.log
```

The Python JSON log format embeds `[%(correlationId)s]` in every line; the
Java MDC is rendered in the configured Logback pattern.

## 5. Admin override path

The admin override endpoint at `POST /api/admin/loans/{loanAppId}/override`
changes a finalized loan's decision (APPROVED ↔ REJECTED ↔ MANUAL_REVIEW) and
runs the same `LoanFinalizationService.finalize()` the auto-pipeline uses. On
APPROVED→REJECTED for a previously disbursed loan, `reverseDisbursement()`
fires: it debits the user's bank account by the loan amount, returns the funds
to the bank pool, writes a reversal Transaction row, and clears
`hasLoan / loanamount` on the user. The override audit lives in
`loan_decision_overrides` and is keyed by
`(loanApplicationId, overriddenBy, newDecision)` — replay returns the prior
row with `idempotent: true` and never re-runs `finalize()`.

> The admin override path uses REQUIRES_NEW for finalize() so the
> auto-pipeline's idempotency claim commits atomically with side-effects.
> This creates a small window where if the outer transaction (override row
> save + outbox publish) fails after the inner commits, the audit row is
> missing. The wasApproved guard on retry prevents double-reversal, so the
> worst-case outcome is a missing audit log entry — no money is moved twice.
> A saga pattern would close this fully but is over-engineering for the
> failure rate.

## 6. Scaling notes

The Java HikariCP pool sits at `maximum-pool-size=30` with 5 minimum-idle and
a 5s connection-timeout — sized for the service's two main hot paths
(transfer and admin loans-list) plus the relay's claim transaction. The
Python services run async SQLAlchemy with `pool_size=20, max_overflow=20,
pool_recycle=1800, pool_pre_ping=True` — large enough for the SQS worker batch
(default 10) plus a small inbound HTTP burst.

Index choices target the two slow queries actually hit in production-shaped
traffic:
- `loan_application(lifecycle_status, created_at DESC)` — admin loans-list
  pages are filtered by status and ordered by recency. Avoids a full sequential
  scan + sort on every page; expected drop from O(n log n) to O(log n + k).
- `transaction(user_id, timestamp DESC)` — per-user statement screen.
- `outbox_events(published_at, lease_expires_at) WHERE published_at IS NULL` —
  partial index keeps the relay's claim under low cost as the table grows;
  unpublished rows are typically a tiny fraction of the total.
- findoc-verify `applications(status, created_at)` — admin status-filtered list.

The idempotency claim itself is horizontally safe: the `INSERT ... ON CONFLICT
DO NOTHING` is atomic at the row level, and the four-state matrix tolerates any
number of concurrent claimants for the same `(event_id, consumer)`.

## 7. Known limitations

- Rate limit is single-replica (in-process token bucket); a Redis-backed
  shared limiter would be needed for horizontal scale.
- Admin override on a disbursed loan does NOT propagate the override to
  findoc-verify automatically — the optional `notify_findoc=true` flag fires
  a best-effort POST but failures are logged, not retried.
- No cost cap on Gemini / Document AI — a malicious bulk submission could
  burn the budget before manual intervention. Per-API-key spend caps are
  out-of-scope for this iteration.
- No model versioning on the Python services beyond the `modelVersion` field
  in events. Rollback is "redeploy the prior container."
