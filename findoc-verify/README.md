# findoc-verify

Event-driven KYC + loan-origination document verification service.

Accepts a bundle of 9+ applicant documents (Aadhaar/PAN, 3× bank statements, 3× payslips, employment letter, ITR, credit report), runs them through an async pipeline (OCR → classify → extract → aggregate → compliance → cross-doc → fraud → risk), and emits a final `LoanReport` on the `findoc-loan-report-ready` SNS topic.

## Runtime layout (local dev)

```
┌───────────────────────────────────────────────────────────────┐
│  Host (Windows) — everything below runs on localhost          │
│                                                               │
│  python run_local.py                                          │
│   ├─ uvicorn  (FastAPI on :8000)                              │
│   └─ 9 SQS worker tasks (asyncio)                             │
│                                                               │
│  Docker Desktop                                               │
│   ├─ findoc-localstack  :4566   (SNS + SQS)                   │
│   ├─ findocai-minio     :9000   (S3 storage — reused)         │
│   └─ findocai-postgres  :5432   (DB — reused)                 │
└───────────────────────────────────────────────────────────────┘
```

Postgres and MinIO are taken from the sibling `findoc-ai` project's compose — both are already running on ports `5432` and `9000`, so `findoc-verify` piggybacks on them and only adds LocalStack.

## Prerequisites

1. Python 3.11+ on host
2. Docker Desktop
3. `findocai-postgres` and `findocai-minio` containers running (they're in the `findoc-ai/backend/compose` stack)
4. `gcloud auth application-default login` already done — ADC file at `~/.config/gcloud/application_default_credentials.json` (or on Windows: `C:\Users\<you>\AppData\Roaming\gcloud\application_default_credentials.json`)
5. (Optional but recommended) A Gemini API key from [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey) — without it, classification falls back to keyword-only

## First-time setup

```bash
cd findoc-verify
cp .env.example .env
# open .env and paste your GEMINI_API_KEY if you have one

# Install deps on host
pip install -e .

# Start LocalStack
docker compose up -d

# Start backend + workers (creates DB + runs migrations on first boot)
python run_local.py
```

First boot output:

```
INFO  run_local            created database 'findoc_verify'
INFO  alembic.runtime      Running upgrade  -> 001_initial, initial schema
INFO  run_local            findoc-verify up — API http://localhost:8000 · ...
INFO  src.messaging        worker starting   worker=ocr queue=findoc-ocr
…
INFO  uvicorn              Uvicorn running on http://0.0.0.0:8000
```

Open [http://localhost:8000](http://localhost:8000).

## Day-to-day

```bash
# start — same command every time
python run_local.py

# stop — Ctrl-C in the terminal; LocalStack stays up

# reset everything
docker compose down -v        # flush LocalStack queues + bucket state
# (drop the DB too if you want):
PGPASSWORD=123456 psql -U postgres -h localhost -c "DROP DATABASE findoc_verify;"
```

## Architecture

```
Frontend (static HTML) ─┐
                        │ Origin = FRONTEND_ORIGIN → no API key required
Java backend ───────────┤ Any other origin → X-API-Key required
                        ▼
                 ┌──────────────┐      uploads → MinIO (findoc-verify-docs)
                 │  FastAPI     │────── publishes doc-ocr-requested → SNS
                 └──────────────┘
                        │
      SNS fanout ────▶  SQS queues (one per worker, each + DLQ, maxReceiveCount=3)
                        │
        OCR → Classify → Extract → Aggregate → Compliance
        → CrossDoc → Fraud → Risk → ResultPublisher
                                       │
                                       ▼
                 SNS: findoc-loan-report-ready  (Java-facing contract)
```

Each worker subscribes to one SQS queue, does its work, writes results, publishes the next event, and ACKs. Idempotency is guarded by `processed_events (event_id, worker_name)` — dup events ACK without re-running. Failure → no ACK → SQS redelivers → after 3 failures → DLQ.

## Auth

Single dependency `require_caller`:
1. `Origin == FRONTEND_ORIGIN` (`http://localhost:8000`) → passthrough (no key needed from the UI)
2. Otherwise `X-API-Key` must match an active key (SHA-256 hashed) → 401 otherwise

`ADMIN_BOOTSTRAP_MODE=true` makes `/api/v1/admin/apikeys` callable without auth so the very first key can be minted. Set to `false` in production.

```bash
# mint a key for the Java backend
python -m scripts.generate_api_key --label subby-java
```

## API surface

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/api/v1/loan-origination/submit` | Accept full doc bundle, kick off pipeline |
| `GET`  | `/api/v1/loan-origination/{id}` | Status + per-doc progress + checks |
| `GET`  | `/api/v1/loan-origination/{id}/report` | Final LoanReport JSON |
| `GET`  | `/api/v1/loan-origination/{id}/documents/{docId}/details` | OCR text preview, classification, extracted fields |
| `GET`  | `/api/v1/loan-origination/{id}/documents/{docId}/download` | Presigned S3 URL (5 min TTL) |
| `POST` | `/api/v1/admin/apikeys` | Mint an API key (returns raw once) |
| `GET`  | `/api/v1/admin/apikeys` | List keys (no raw values) |
| `DELETE` | `/api/v1/admin/apikeys/{id}` | Revoke |
| `GET`  | `/api/v1/health` | Health probe |

Swagger UI: [http://localhost:8000/docs](http://localhost:8000/docs).

## Frontend

Single page served at `/` from `static/`. Sections:

1. **Submit** — the full doc bundle; 400 with per-field errors if incomplete.
2. **Pipeline** — 8-stage horizontal timeline showing completed steps (Submit → OCR → Classify → Extract → Compliance → CrossDoc → Fraud → Report). Polls every 3s.
3. **Documents** — each collapsible; expanding lazy-loads OCR preview, classification, and the extracted-fields table.
4. **Compliance / Cross-doc / Fraud** — grouped status counts with collapsible detail JSON per rule.
5. **Loan report** — recommendation badge, overall score, credit/DTI/fraud panels, income breakdown table, decision reasons list, and raw JSON.

## Java integration contract

The Java backend (`SubbyBankbackend`) will:
1. Use a minted API key in the `X-API-Key` header.
2. Call `POST /api/v1/loan-origination/submit` with the doc bundle. Include `external_id` = your internal `loanAppId`; the service echoes it back as `correlationId` on the final event.
3. Subscribe its own queue to the `findoc-loan-report-ready` SNS topic.

### Idempotency

`POST /api/v1/loan-origination/submit` is idempotent on `external_id`:

- First call with a new `external_id` → `202 Accepted`, row created, files uploaded, OCR events published. Response includes `"idempotentReplay": false`.
- Second call with the same `external_id` → `200 OK`, no new row, no re-upload, no re-enqueue. Response includes `"idempotentReplay": true` and the same `applicationId` as the first call.
- Concurrent submits with the same `external_id` (race) → one wins with `202`, the loser gets `200` with `idempotentReplay: true`. Neither returns `500`.
- If `external_id` is missing/empty on both calls, idempotency cannot be applied — every call creates a new row.

Java's SQS consumer is at-least-once, so redeliveries of the same loan-submit message will hit this path. Treat `idempotentReplay: true` as "already accepted, just track the returned `applicationId`".

Final event payload (`schemaVersion: 1`):

```json
{
  "eventId": "uuid",
  "schemaVersion": 1,
  "eventType": "application.loan_report_ready",
  "occurredAt": "2026-04-24T10:15:00Z",
  "payload": {
    "schemaVersion": 1,
    "applicationId": "uuid",
    "correlationId": "<your external_id>",
    "status": "approved | rejected | needs_review",
    "recommendation": "approve | reject | manual_review",
    "overallScore": 22.4,
    "report": { ...full LoanReport JSON... }
  }
}
```

## Switching to real AWS

Unset `AWS_ENDPOINT_URL` and `S3_ENDPOINT_URL`, supply real `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`, point `S3_BUCKET` at the real bucket. The `scripts/localstack-init.sh` script documents the required topic/queue shape — port that to Terraform/CDK for prod.

Zero code changes — boto3 picks up whichever endpoint is configured.

## Running tests

Unit tests (no infra needed):

```bash
pip install -e ".[dev]"
pytest tests/test_compliance.py tests/test_cross_doc.py tests/test_extractor.py -v
```

## Verifying the pipeline by hand

```bash
pip install awscli-local
awslocal --region ap-south-1 sqs list-queues
awslocal --region ap-south-1 sns list-subscriptions
awslocal --region ap-south-1 sqs receive-message --queue-url http://localhost:4566/000000000000/findoc-result
```

## Constraints / things this service deliberately does NOT do

- No multi-tenancy, no Keycloak, no JWT.
- No DB polling for work — purely SQS-driven.
- No Textract / Tesseract. OCR = Google Document AI only.
- No LiteLLM / OpenAI / Anthropic. LLM = Gemini 2.0 Flash only.
- No React/Vue framework — vanilla HTML + Tailwind CDN.
