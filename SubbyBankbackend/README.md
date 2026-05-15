# SubbyBank — Event-Driven Digital Banking Backend

A Spring Boot 3 / Java 21 banking service. Accounts, KYC, peer-to-peer transfers, loan lifecycle, admin override, Razorpay top-up — wired through a **transactional outbox + SNS/SQS + DLQs + idempotency layer** so it stays consistent when a replica dies mid-publish, SQS redelivers, or an operator replays a queue.

This is one half of a two-service demo. The other half ([findoc-verify](https://github.com/SubbyDutta)) handles document OCR + compliance and publishes a `findoc-loan-report-ready` event that this service consumes. SubbyBank also runs **standalone in DEGRADED mode** — fraud-scoring and document verification become no-ops, but signup / KYC / transfers / loan flows still work end-to-end.

---

## What this service owns

| Concern | What it does |
| :--- | :--- |
| **Users & auth** | Signup, login, password reset, JWT access + refresh tokens, audit-logged sensitive actions |
| **Bank accounts** | Account creation, balance, per-user transaction history |
| **Transfers** | Atomic peer-to-peer transfers under `@Transactional(timeout = 10)` with an `IdempotencyKey` per request and a fail-closed fraud check on the hot-path |
| **KYC** | Submission, status tracking, async result consumption from findoc-verify, admin override |
| **Loans** | Apply → docs-verify → ML risk-score → finalize → disburse from a shared `BankPool`; admin override with full disbursement reversal |
| **Payments** | Razorpay order creation, signature verification, withdraw |
| **Admin** | KYC review queue, loan decision override, threshold tuning, DLQ inspection |
| **Chatbot** | Gemini-backed intent router (direct-data / RAG / generative) for in-app help |

---

## Architecture

```
                ┌────────────────────────────────┐
   React / curl │      SubbyBankbackend          │
   ──────────►  │      Spring Boot 3 :8080       │
   JWT + REST   │   (system of record)           │
                └───────┬──────────┬─────────────┘
                        │          │
                        │          └── sync, fail-closed ──► FraudPython (optional)
                        │
              writes inside business tx
                        │
                        ▼
                ┌──────────────────┐      poll under
                │  outbox_events   │  ◄── SELECT … FOR UPDATE
                │  + lease_id      │      SKIP LOCKED
                │  + lease_expires │
                └────────┬─────────┘
                         │ OutboxRelay (2 replicas safe)
                         ▼
                       SNS topics
                         │ fanout
                         ▼
                 SQS queues + DLQs (maxReceiveCount=3)
                         │
   ┌─────────────────────┼────────────────────────────────────┐
   ▼                     ▼                                    ▼
 14 internal       findoc-verify                        SubbyPythonLoan
 SQS consumers     (KYC + loan docs)                    (XGBoost worker)
 (email, KYC,      publishes                            publishes
 loan finalize,    findoc-*-report-ready                subby-risk-result
 audit log)        back to SubbyBank                    back to SubbyBank

                  Storage:  Postgres 16  ·  Redis 7  ·  S3 / MinIO
```

The Java side never does cross-service HTTP fan-out except for two synchronous calls — the doc-bundle upload to findoc-verify and the per-transaction fraud check to FraudPython. Everything else is async via outbox → SNS → SQS.

---

## Engineering features worth opening

These are the pieces I'd ask a reviewer to actually read.

### Transactional outbox with leased multi-replica relay
Every domain event is written to `outbox_events` **inside the same DB transaction** as the business write. `OutboxRelay` polls under `SELECT … FOR UPDATE SKIP LOCKED`, publishes to SNS, and stamps `published_at`. Each row carries a `lease_id` UUID + 30s expiry — two relay replicas run safely, and a crash mid-publish releases its rows once the lease elapses. A unique constraint on `(aggregate_id, event_type, schema_version)` blocks any application-layer race.
→ [`messaging/OutboxRelay.java`](src/main/java/backend/backend/messaging/OutboxRelay.java), [`messaging/OutboxEvent.java`](src/main/java/backend/backend/messaging/OutboxEvent.java)

### Four-state idempotency claim
Every SQS consumer claims an `(event_id, consumer_name)` pair through `processed_events` with one of four outcomes:
- `NEW` — first sighting, run the handler.
- `RETRY` — prior `FAILED` row under `MAX_RETRIES`, run again.
- `SKIP_OK` — already `SUCCEEDED` or exhausted, ack and drop.
- `SKIP_INFLIGHT` — `PENDING`, another replica is mid-handle.

At-least-once SQS delivery is filtered into **exactly-once business effects** with replay safety on transient failures.
→ [`messaging/IdempotencyGuard.java`](src/main/java/backend/backend/messaging/IdempotencyGuard.java)

### SQS visibility heartbeat
While a long-running consumer holds a message, a scheduled task calls `ChangeMessageVisibility` every 15s to extend the lease to 60s — long handlers no longer get redelivered mid-execution.

### DLQ + non-retriable short-circuit
Every primary queue has a paired DLQ; redrive fires after `maxReceiveCount=3`. Handlers that throw `NonRetriableException` republish straight to the DLQ with a `DlqReason` MessageAttribute instead of burning the redelivery budget.

### Admin loan override with reversal
`POST /api/admin/loans/{loanAppId}/override` flips a finalized loan's decision. On `APPROVED → REJECTED` for a previously disbursed loan, `reverseDisbursement()` debits the user's bank account, returns funds to the bank pool, writes a reversal `Transaction` row, and clears `hasLoan` / `loanamount`. The override audit row is keyed by `(loanApplicationId, overriddenBy, newDecision)` — replay returns the prior row with `idempotent: true` and never re-runs `finalize()`. `REQUIRES_NEW` keeps the inner finalize step atomic with side-effects.
→ [`service/loan/AdminLoanOverrideService.java`](src/main/java/backend/backend/service/loan/AdminLoanOverrideService.java)

### Fail-closed fraud check
Transfers call `FraudPython` synchronously. If the service is unreachable, the client enters DEGRADED mode — low-value transfers are allowed, high-value (configurable threshold) are rejected with `FraudServiceUnavailableException`. The service does not silently pass.
→ [`service/fraud/FraudClient.java`](src/main/java/backend/backend/service/fraud/FraudClient.java)

### Correlation-id pipeline
A single `correlationId` flows: HTTP header → `CorrelationIdFilter` → SLF4J MDC → outbox row → SNS `MessageAttribute` → SQS consumer → MDC again → outbound WebClient re-injects it. To trace a request: `grep "<corrId>" subby-bank.log`.

### PII encryption at rest
Aadhaar and PAN are encrypted via a JPA `AttributeConverter` (`PiiConverter` + `CryptoUtils`) keyed off `SECRET_KEY` — never stored in plaintext, never logged.

### Observability
Spring Boot Actuator + Micrometer + Prometheus. Custom `outbox.*` and `sqs.*` metrics expose relay lag, DLQ depth, and per-consumer claim distribution.

---

## Tech stack

| Layer | Tech |
| :--- | :--- |
| **Language** | Java 21 (preview features enabled) |
| **Framework** | Spring Boot 3.5, Spring Security, Spring Data JPA, Hibernate, Spring WebFlux (for the findoc client) |
| **Persistence** | PostgreSQL 16 (prod), H2 (test). Flyway migrations. |
| **Cache** | Redis 7 (Spring Cache) + Caffeine in-process |
| **Messaging** | AWS SNS + SQS via `spring-cloud-aws-starter-*`, AWS SDK v2 |
| **Auth** | JJWT access + refresh tokens, Bcrypt, `X-API-Key` (SHA-256 hashed) for service-to-service |
| **Resilience** | Resilience4j circuit breaker on findoc client, Bucket4j rate limiting, transactional outbox, leased relay, idempotency keys, DLQs, visibility heartbeat |
| **Payments** | Razorpay SDK (order creation, signature verification, webhook handler) |
| **AI** | Google Gemini for the in-app chatbot (intent routing + generative answers) |
| **Observability** | Spring Boot Actuator, Micrometer, Prometheus, structured logs with correlationId |
| **Build / Run** | Maven, Docker multi-stage, docker-compose for the full local stack |

---

## Quickstart (local)

You need JDK 21, Docker Desktop, and Maven 3.8+.

```bash
# 1. Bring up the infra (Postgres, Redis, LocalStack, MailHog)
docker compose up -d

# 2. Configure env (see application-dev.yml for defaults)
export SPRING_PROFILES_ACTIVE=dev
export GEMINI_API_KEY=...          # optional, chatbot only
export RAZORPAY_KEY=...            # optional, payments only
export RAZORPAY_SECRET=...

# 3. Run
./mvnw spring-boot:run
```

App is on `http://localhost:8080`. Health probe:

```bash
curl http://localhost:8080/actuator/health/readiness
curl http://localhost:8080/actuator/prometheus | grep -E 'outbox|sqs'
```

### Run in DEGRADED mode (no findoc / no fraud)

The service runs with KYC / loan auto-rejection turned off and `FraudClient` in DEGRADED mode if those services are absent — useful for a standalone demo.

```bash
export FRAUD_URL=                  # empty → DEGRADED mode
export FINDOC_URL=                 # empty → KYC stays in MANUAL_REVIEW
./mvnw spring-boot:run
```

### Docker

```bash
docker build -t subbybank:latest .
docker run -p 8080:8080 --env-file .env subbybank:latest
```

---

## API surface (selected)

| Method | Path | Purpose |
| :--- | :--- | :--- |
| `POST` | `/api/auth/register` | Signup |
| `POST` | `/api/auth/login` | Issue JWT access + refresh |
| `GET` | `/api/account/profile` | Current user profile |
| `POST` | `/api/kyc/submit` | Upload KYC docs (forwarded to findoc-verify) |
| `GET` | `/api/kyc/status` | Current KYC state |
| `POST` | `/api/transfers/send` | Peer-to-peer transfer (idempotent, fraud-scored) |
| `GET` | `/api/transfers/history` | Statement |
| `POST` | `/api/payments/create-order` | Razorpay top-up order |
| `POST` | `/api/payments/webhook` | Razorpay webhook (signature verified) |
| `POST` | `/api/loans/apply` | Loan application with 9-doc bundle |
| `GET` | `/api/loans/{id}/status` | Loan lifecycle status |
| `POST` | `/api/chatbot/query` | Gemini-backed in-app assistant |
| `POST` | `/api/admin/loans/{id}/override` | Flip a finalized decision with reversal |
| `GET` | `/api/admin/dlq/{queue}` | Inspect DLQ depth + messages |

JWT goes in the `Authorization: Bearer <token>` header. Admin routes require the `ROLE_ADMIN` authority.

---

## Repository layout

```
src/main/java/backend/backend/
├── BackendApplication.java
├── controller/        REST API (17 controllers — auth, account, transfer, loan, admin, …)
├── service/           business logic
│   ├── loan/          loan finalization, override, KYC identity guard, feature extraction
│   ├── fraud/         FraudPython client (sync, fail-closed) + result cache
│   └── findoc/        findoc-verify client (multipart upload, API-key auth, retry)
├── messaging/
│   ├── OutboxEvent / OutboxRelay / OutboxEventPublisher    transactional outbox + relay
│   ├── IdempotencyGuard / ProcessedEvent                   4-state claim
│   ├── BaseSqsHandler                                      visibility heartbeat + ack logic
│   ├── EventPublisher / SnsEnvelopeParser                  wire format + correlation-id
│   └── consumer/                                            14 SQS consumers (loans, KYC, email, …)
├── model/             JPA entities incl. outbox_events, processed_events, audit_log
├── security/          JWT filter, RBAC, rate limiter, audit-logging filter
├── chatbot/           IntentDetector + Direct / RAG / Generative handlers
├── events/            DomainEvent base + concrete event types (@EventType annotated)
└── configuration/     property classes, AWS SDK config, CORS, cache, crypto
```

---

## Known limitations (deliberate)

- **Rate limit is single-replica** — Bucket4j is in-process; horizontal scale needs a Redis-backed shared limiter.
- **Saga gap on override-finalize** — there is one transactional window where an audit row can be lost. No money is moved twice (`wasApproved` retry guard prevents double-reversal); a full saga is over-engineering for the failure rate.
- **No model versioning** beyond the `modelVersion` field on events; rollback is "redeploy the prior container."
- **FraudPython optional** — when absent, the client runs DEGRADED. This is a deployment-cost tradeoff, not a security oversight.

---

## Author

**Subham Dutta** — backend & distributed systems.

- subhamdutta4289@gmail.com
- github.com/SubbyDutta
- linkedin.com/in/subham-dutta-60b7652b9
- Kolkata, India · Open to Remote / Pan-India

This service is one of three projects I'm shipping as separate deploys to show different angles of the same problem domain — the others are findoc-verify (document pipeline) and the React frontend. Happy to walk through any of the engineering decisions above.
