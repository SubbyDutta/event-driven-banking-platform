# findoc-verify — KYC & Loan Document Verification Pipeline

An event-driven FastAPI service that takes a bundle of applicant documents — Aadhaar, PAN, 3× bank statements, 3× payslips, employment letter, ITR, credit report — and runs them through an async pipeline of nine SQS workers:

```
ocr → classify → extract → aggregate → compliance → cross-doc → fraud → risk → publish
```

Out the other end comes a structured `LoanReport`: per-document extracted fields, compliance check results, cross-document consistency findings, fraud signals, an overall risk score, and a recommendation (`approve` / `reject` / `manual_review`). The final event is published on the `findoc-loan-report-ready` SNS topic for downstream consumers (a banking backend, an underwriting workflow, etc.).

The service is **self-contained** — it ships with its own React + Vite operator UI on `/static`, its own Postgres schema, its own LocalStack-emulated SNS/SQS, and a sample document set. You can run it as a standalone demo without any other service in the picture.

---

## Architecture

```
                 Operator UI (React + Vite, served at :8000/)
                 ──────────────┬──────────────
                               │ multipart upload (9 docs)
                               ▼
                       ┌───────────────┐  ──► MinIO / S3 (raw PDFs)
                       │   FastAPI     │
                       │   :8000       │  ──► Postgres (application + per-doc state)
                       └───────┬───────┘
                               │ publishes  doc-ocr-requested
                               ▼
                            SNS topic
                               │ fanout (RawMessageDelivery=true)
                               ▼
                  SQS queues + DLQs (maxReceiveCount=3)
                               │
   ┌──────────┬──────────┬────┴────┬───────────┬──────────┬─────────┐
   ▼          ▼          ▼         ▼           ▼          ▼         ▼
  ocr     classify   extract   aggregate   compliance  cross-doc  fraud
   │         │          │         │           │           │         │
   └─────────┴──────────┴────► risk ─► result_publisher ──► SNS topic
                                                            findoc-loan-
                                                            report-ready

   Each worker:  asyncio task in run_local.py (dev) or its own container (prod)
                 idempotency: (event_id, worker_name) claim on processed_events
                 long-running stages extend visibility every 15s
                 fail → no ack → SQS redelivers → after 3 → DLQ
```

The pipeline is **purely event-driven** — workers don't poll the DB for work, they subscribe to their queue and react to events. Workers can be scaled independently: OCR is the slowest stage (Document AI latency), so in prod you give it more replicas than the rest.

---

## What each stage actually does

| Stage | What it produces | Provider |
| :--- | :--- | :--- |
| **OCR** | Raw text + page-level coordinates per document | Google Document AI |
| **Classify** | Document type (`AADHAAR`, `PAN`, `BANK_STATEMENT`, …) with a confidence score | Gemini 2.0 Flash + keyword fallback |
| **Extract** | Structured fields (name, DOB, account-no, monthly-income, transactions, …) keyed by doc type | Gemini structured JSON output |
| **Aggregate** | Per-applicant rollups — average monthly inflow, employer continuity, age | pure Python |
| **Compliance** | Rule checks — Aadhaar masked, PAN format, statement period coverage, age ≥ 18, … | rule engine |
| **Cross-doc** | Consistency checks — name match across docs (fuzzy), DOB match, employer match, salary continuity | RapidFuzz + rules |
| **Fraud** | Heuristic signals — duplicate transactions, suspicious round-number salary, mismatched IFSC | rule engine |
| **Risk** | Overall score from aggregated income, DTI, fraud signals, credit-report inputs | weighted scoring |
| **Result publisher** | Emits the final `LoanReport` event with `correlationId = external_id` | SNS |

The output schema is stable (`schemaVersion: 1`) and documented inline so a consuming service can wire to it without reverse-engineering.

---

## Engineering features worth opening

### Async-first, all the way down
FastAPI on uvicorn, async SQLAlchemy on Postgres, aioboto3 for SQS/SNS, asyncio worker tasks running in the same process in dev (`run_local.py`) or in dedicated containers in prod. No thread pools, no blocking calls — the OCR + LLM round-trips don't starve the queue consumers.

### Idempotency per worker
Every stage claims `(event_id, worker_name)` on `processed_events` before doing work. A redelivered SQS message acks without re-running the OCR / re-calling Gemini, so retries don't burn cost or duplicate side-effects.

### Visibility heartbeat for long stages
OCR and Extract can take 30–90 seconds end-to-end. A background asyncio task calls `ChangeMessageVisibility` every 15s while the handler runs — the lease stays ahead of the worker, so SQS never redelivers mid-execution.

### DLQ with `NonRetriableError` short-circuit
Bad PDFs (corrupted, password-protected, > 10 MB) raise `NonRetriableError`. The worker republishes the message **straight to the DLQ** with a `DlqReason` MessageAttribute instead of letting SQS burn 3 redeliveries against the same poison message. Recoverable errors (transient Gemini 5xx, network blip) ride the normal retry path.

### Idempotency on the public endpoint
`POST /api/v1/loan-origination/submit` is idempotent on the caller-supplied `external_id`:
- First call with a new `external_id` → `202 Accepted`, row created, files uploaded, OCR events published. `"idempotentReplay": false`.
- Second call with the same `external_id` → `200 OK`, no new row, no re-upload, no re-enqueue, same `applicationId`. `"idempotentReplay": true`.
- Concurrent submits with the same `external_id` → one wins with `202`, the loser gets `200` with `idempotentReplay: true`. Neither returns `500`.

This matters because the typical caller is an at-least-once SQS consumer in the bank backend, so duplicate submits **will** happen in production.

### Two-mode auth on a single dependency
A single FastAPI dependency `require_caller` handles both UI and service-to-service:
1. `Origin == FRONTEND_ORIGIN` → passthrough (the UI doesn't need a key).
2. Otherwise `X-API-Key` must match an active, SHA-256-hashed key → 401 otherwise.

Bootstrap mode (`ADMIN_BOOTSTRAP_MODE=true`) lets the very first key be minted without auth, then it's flipped off in production.

### Correlation-id end-to-end
A `CorrelationIdMiddleware` reads `X-Correlation-Id` on inbound HTTP, sets it on a Python `ContextVar`, propagates it through SNS `MessageAttributes` on every published event, and re-attaches it on SQS consumer entry. The JSON log format embeds `[correlationId]` in every line — so tracing a request across nine workers is a single grep.

### Operator UI
Vanilla React + Vite + Tailwind, served as static assets from `/`. Five sections:
1. **Submit** — full 9-doc upload form with per-field validation.
2. **Pipeline** — 8-stage horizontal timeline showing completed stages, polls every 3s.
3. **Documents** — collapsible per-doc panel, lazy-loads OCR preview + extracted fields.
4. **Compliance / Cross-doc / Fraud** — grouped status counts with per-rule detail JSON.
5. **Loan report** — recommendation badge, overall score, credit/DTI/fraud panels, income breakdown, decision reasons, raw JSON.

### Cloud-portable storage
boto3 is configured off `AWS_ENDPOINT_URL` / `S3_ENDPOINT_URL`. In dev it talks to LocalStack + MinIO; in prod, unset both vars and it talks to real AWS. **No code changes** — same client, same calls.

---

## Tech stack

| Layer | Tech |
| :--- | :--- |
| **Language** | Python 3.11+ |
| **Framework** | FastAPI, uvicorn (async) |
| **Persistence** | PostgreSQL via async SQLAlchemy 2.0 + asyncpg, Alembic migrations |
| **Messaging** | AWS SNS + SQS (boto3), LocalStack 4 in dev |
| **Object storage** | AWS S3 / MinIO |
| **OCR** | Google Document AI |
| **LLM** | Gemini 2.0 Flash (structured JSON output) |
| **Auth** | SHA-256 hashed `X-API-Key`, origin-based passthrough for the bundled UI |
| **Resilience** | Tenacity retries on external calls, asyncio visibility heartbeat, DLQ + `NonRetriableError` short-circuit |
| **Frontend** | React + Vite + Tailwind |
| **Observability** | Structured JSON logs with correlationId, admin observability endpoints (queue depth, DLQ messages) |

---

## Quickstart (local)

You need Python 3.11+, Docker Desktop, and a `gcloud auth application-default login` run for Document AI access. A Gemini API key is recommended (without it, classification falls back to keyword-only).

```bash
# 1. Copy env template, paste your GEMINI_API_KEY
cp .env.example .env

# 2. Install deps on host
pip install -e .

# 3. Start LocalStack + Postgres + MinIO
docker compose up -d

# 4. Start the API + 9 workers (runs Alembic migrations on first boot)
python run_local.py
```

App is on `http://localhost:8000`. Swagger UI on `/docs`. Operator UI on `/`.

### Mint an API key for service-to-service calls

```bash
python -m scripts.generate_api_key --label my-service
# prints the raw key once — store it
```

### Docker (containerised)

```bash
docker build -t findoc-verify:latest .
docker run -p 8000:8000 --env-file .env findoc-verify:latest
```

### Smoke test the pipeline

```bash
# Submit the bundled fixture
curl -X POST http://localhost:8000/api/v1/loan-origination/submit \
  -H "X-API-Key: $KEY" \
  -F "external_id=test-001" \
  -F "aadhaar=@tests/fixtures/aadhaar.pdf" \
  -F "pan=@tests/fixtures/pan.pdf" \
  -F "bank_statements=@tests/fixtures/bank1.pdf" \
  ...

# Watch the pipeline
curl http://localhost:8000/api/v1/loan-origination/{id} | jq .

# Final report
curl http://localhost:8000/api/v1/loan-origination/{id}/report | jq .
```

---

## API surface

| Method | Path | Purpose |
| :--- | :--- | :--- |
| `POST` | `/api/v1/loan-origination/submit` | Accept full doc bundle, kick off pipeline (idempotent on `external_id`) |
| `GET` | `/api/v1/loan-origination/{id}` | Status + per-doc progress + check results |
| `GET` | `/api/v1/loan-origination/{id}/report` | Final `LoanReport` JSON |
| `GET` | `/api/v1/loan-origination/{id}/documents/{docId}/details` | OCR text preview + classification + extracted fields |
| `GET` | `/api/v1/loan-origination/{id}/documents/{docId}/download` | Presigned S3 URL (5 min TTL) |
| `POST` | `/api/v1/admin/apikeys` | Mint a key (returns raw value once) |
| `GET` | `/api/v1/admin/apikeys` | List keys (no raw values) |
| `DELETE` | `/api/v1/admin/apikeys/{id}` | Revoke |
| `GET` | `/api/v1/admin/observability/queues` | SQS queue depth + DLQ counts |
| `GET` | `/api/v1/health` | Health probe |

### The final event (for downstream consumers)

```json
{
  "eventId": "uuid",
  "schemaVersion": 1,
  "eventType": "application.loan_report_ready",
  "occurredAt": "2026-04-24T10:15:00Z",
  "payload": {
    "applicationId": "uuid",
    "correlationId": "<your external_id>",
    "status": "approved | rejected | needs_review",
    "recommendation": "approve | reject | manual_review",
    "overallScore": 22.4,
    "report": { "...full LoanReport JSON..." }
  }
}
```

---

## Repository layout

```
src/
├── main.py                FastAPI app, lifespan, middleware
├── auth.py                require_caller dependency (origin / API-key)
├── config.py              env-bound Pydantic settings
├── api/
│   ├── applications.py    submit / status / report / doc-detail endpoints
│   ├── admin.py           API key management
│   ├── admin_observability.py   queue + DLQ inspection
│   └── health.py
├── workers/               9 SQS consumers, one file each
│   ├── ocr_worker.py
│   ├── classify_worker.py
│   ├── extract_worker.py
│   ├── aggregate_worker.py
│   ├── compliance_worker.py
│   ├── crossdoc_worker.py
│   ├── fraud_worker.py
│   ├── risk_worker.py
│   └── result_publisher.py
├── pipeline/              pure-logic stage implementations (no I/O, easy to test)
│   ├── classifier.py
│   ├── extractor.py
│   ├── compliance.py
│   ├── cross_doc.py
│   ├── fraud.py
│   ├── risk.py
│   └── required_docs.py
├── providers/             external integrations
│   ├── ocr/               Document AI client
│   └── llm/               Gemini client (structured output)
├── messaging/             SNS publisher, SQS consumer base, idempotency guard, heartbeat
├── storage/               S3 / MinIO upload + presigned URLs
├── policy/                rule definitions (compliance + cross-doc + fraud)
├── models/                async SQLAlchemy models
└── db/                    session factory, Alembic helpers

webui/                     React + Vite operator UI (served at /)
static/                    built UI assets
alembic/                   migrations
tests/                     pytest — unit tests for the pure-logic pipeline stages
scripts/
├── generate_api_key.py    mint a key
└── localstack-init.sh     SNS topics + SQS queues + subscriptions
```

---

## What this service deliberately does NOT do

These are scope boundaries, not unknowns:

- No multi-tenancy, no Keycloak, no JWT — `X-API-Key` is the service-to-service auth.
- No DB polling for work — purely SQS-driven. A worker that has nothing to do consumes nothing.
- No Textract / Tesseract / EasyOCR — OCR is Google Document AI only.
- No LiteLLM / OpenAI / Anthropic — LLM is Gemini 2.0 Flash only.
- No React Router / Vue / framework — vanilla HTML + Tailwind for the UI keeps it deployable as static files.
- No spend cap on Gemini / Document AI — a bulk submission could burn the budget before manual intervention. Per-API-key cost caps are out of scope for this iteration.

---

## Running tests

```bash
pip install -e ".[dev]"
pytest tests/ -v
```

The pure-logic pipeline stages (`classifier`, `extractor`, `compliance`, `cross_doc`, `fraud`, `risk`) are tested without any infra — no Postgres, no LocalStack, no Document AI. The worker layer is integration-tested with testcontainers.

---

## Author

**Subham Dutta** — backend & distributed systems.

- subhamdutta4289@gmail.com
- github.com/SubbyDutta
- linkedin.com/in/subham-dutta-60b7652b9
- Kolkata, India · Open to Remote / Pan-India

This service is one of three projects I'm shipping as separate deploys — the others are SubbyBank (Spring Boot 3 banking backend that consumes this service's events) and the React frontend. Happy to walk through any of the engineering decisions above.
