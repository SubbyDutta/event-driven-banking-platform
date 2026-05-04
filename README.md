# Subby — Production-Grade Digital Banking Platform

**Java 21 · Spring Boot 3 · Python 3.12 · FastAPI · React 18 · PostgreSQL · Redis · AWS (SNS / SQS / S3 / EC2 / RDS / IAM) · Docker · XGBoost · Microservices · Event-Driven · CI/CD · Distributed Systems · Backend Engineer · Full-Stack · ML Engineering · Fintech**

> An event-driven polyglot microservices banking platform — account opening, KYC, payments, internal transfers, loan origination, ML risk scoring, and live transaction-fraud detection. The whole thing is wired through a transactional outbox plus SNS/SQS plus DLQs plus an idempotency layer, so it keeps working when a replica dies mid-publish, when SQS redelivers the same message, or when an operator replays a queue.
>
> It runs on AWS today — EC2, RDS, SNS, SQS, S3, with GitHub Actions doing the deploys. The same code runs on a laptop with one `docker compose up`. Moving between the two is a profile flip; not a single line of business code changes.

<p align="left">
  <img alt="Java" src="https://img.shields.io/badge/Java-21-007396?logo=openjdk&logoColor=white" />
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F?logo=springboot&logoColor=white" />
  <img alt="Python" src="https://img.shields.io/badge/Python-3.12-3776AB?logo=python&logoColor=white" />
  <img alt="FastAPI" src="https://img.shields.io/badge/FastAPI-async-009688?logo=fastapi&logoColor=white" />
  <img alt="React" src="https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black" />
  <img alt="Postgres" src="https://img.shields.io/badge/PostgreSQL-16-336791?logo=postgresql&logoColor=white" />
  <img alt="Redis" src="https://img.shields.io/badge/Redis-7-DC382D?logo=redis&logoColor=white" />
  <img alt="AWS" src="https://img.shields.io/badge/AWS-EC2%20%2F%20RDS%20%2F%20SNS%20%2F%20SQS%20%2F%20S3%20%2F%20IAM-FF9900?logo=amazonaws&logoColor=white" />
  <img alt="Docker" src="https://img.shields.io/badge/Docker-Compose-2496ED?logo=docker&logoColor=white" />
  <img alt="GitHub Actions" src="https://img.shields.io/badge/GitHub%20Actions-CI%2FCD-2088FF?logo=githubactions&logoColor=white" />
  <img alt="XGBoost" src="https://img.shields.io/badge/XGBoost-ML-EE4C2C" />
  <img alt="Document AI" src="https://img.shields.io/badge/Google%20Document%20AI-OCR-4285F4?logo=googlecloud&logoColor=white" />
  <img alt="Gemini" src="https://img.shields.io/badge/Gemini%202.0%20Flash-LLM-8E75B2?logo=google&logoColor=white" />
</p>

---

## Architecture — system context (C4 Level 1)

```mermaid
flowchart LR
    classDef user fill:#FFD43B,stroke:#7a6814,color:#000,stroke-width:1px
    classDef sys fill:#1f6feb,stroke:#0b3a82,color:#fff,stroke-width:1px
    classDef ext fill:#6e7681,stroke:#2b313a,color:#fff,stroke-width:1px

    CUST(["Customer"]):::user
    OPS(["Bank Ops / Admin"]):::user

    SUBBY["<b>Subby Platform</b><br/>banking + KYC + loans<br/>+ fraud + ML risk"]:::sys

    DOCAI["Google Document AI<br/>(OCR)"]:::ext
    GEM["Gemini 2.0 Flash<br/>(LLM extraction)"]:::ext
    RZP["Razorpay<br/>(payments)"]:::ext
    SMTP["Gmail SMTP<br/>(MailHog in dev)"]:::ext

    CUST -- "signup, KYC,<br/>transfer, apply for loan" --> SUBBY
    OPS -- "review pipelines,<br/>override decisions" --> SUBBY

    SUBBY -- "OCR PDFs" --> DOCAI
    SUBBY -- "extract structured fields" --> GEM
    SUBBY -- "top-up / withdraw" --> RZP
    SUBBY -- "transactional email" --> SMTP
```

---

## Architecture — runtime container view (C4 Level 2)

This reflects the **actual** services, queues, and dependencies as wired in [docker-compose.yml](docker-compose.yml), [infra/localstack-init.sh](infra/localstack-init.sh), and [findoc-verify/scripts/localstack-init.sh](findoc-verify/scripts/localstack-init.sh).

```mermaid
flowchart TB
    classDef java fill:#6DB33F,stroke:#2d5a16,color:#fff,stroke-width:1px
    classDef py fill:#3776AB,stroke:#1d3f63,color:#fff,stroke-width:1px
    classDef ml fill:#EE4C2C,stroke:#7a2814,color:#fff,stroke-width:1px
    classDef infra fill:#7B42BC,stroke:#3d1f64,color:#fff,stroke-width:1px
    classDef ui fill:#61DAFB,stroke:#1f6c81,color:#000,stroke-width:1px
    classDef ext fill:#6e7681,stroke:#2b313a,color:#fff,stroke-width:1px

    subgraph Frontends
      UI["smartbank<br/>React 18 CRA :3000"]:::ui
      OPSUI["findoc webui<br/>React + Vite :5173"]:::ui
    end

    subgraph Backend
      JAVA["<b>SubbyBankbackend</b><br/>Spring Boot 3 / Java 21<br/>:8080<br/>system of record"]:::java
      FINDOC_API["<b>findoc-verify</b><br/>FastAPI :8000<br/>API + ingest"]:::py
      FINDOC_W["<b>findoc-verify-workers</b><br/>9 async SQS consumers<br/>OCR → classify → extract →<br/>aggregate → compliance →<br/>cross-doc → fraud → risk → publish"]:::py
      RISK["<b>SubbyPythonLoan</b><br/>FastAPI :8002<br/>XGBoost loan-risk worker"]:::ml
      FRAUD["<b>FraudPython</b><br/>FastAPI :8001<br/>XGBoost transaction-fraud<br/>(sync, fail-closed)"]:::ml
    end

    subgraph "Messaging spine"
      OUTBOX[("outbox_events<br/>+ leased relay<br/>(SELECT FOR UPDATE<br/>SKIP LOCKED)")]:::infra
      SNS["<b>SNS</b><br/>16 topics"]:::infra
      SQS["<b>SQS</b><br/>27 queues + 27 DLQs<br/>maxReceiveCount=3"]:::infra
    end

    subgraph "Data"
      PG[("PostgreSQL 16<br/>subbybank · findoc · subby_loan")]:::infra
      REDIS[("Redis 7<br/>cache + rate-limit")]:::infra
      S3[("S3 / MinIO<br/>document store")]:::infra
    end

    subgraph "External"
      DOCAI["Google Document AI"]:::ext
      GEM["Gemini 2.0 Flash"]:::ext
      RZP["Razorpay"]:::ext
      SMTP["SMTP / MailHog"]:::ext
    end

    UI -- "REST + JWT" --> JAVA
    OPSUI -- "REST + X-API-Key" --> FINDOC_API

    JAVA -- "sync, fail-closed" --> FRAUD
    JAVA -- "doc bundle (multipart)" --> FINDOC_API
    JAVA --> PG
    JAVA --> REDIS
    JAVA -- "writes inside business tx" --> OUTBOX
    JAVA -- "Razorpay top-up/withdraw" --> RZP
    JAVA -- "outbound email" --> SMTP

    OUTBOX -- "lease + publish" --> SNS
    SNS -- "fan-out" --> SQS

    SQS -- "subby-risk-requests" --> RISK
    SQS -- "subby-loan-* / subby-kyc-* /<br/>subby-*-email / subby-audit-log" --> JAVA
    SQS -- "findoc internal stages" --> FINDOC_W

    FINDOC_API --> S3
    FINDOC_W --> S3
    FINDOC_W --> DOCAI
    FINDOC_W --> GEM
    FINDOC_W -- "publishes findoc-*-report-ready" --> SNS

    RISK -- "publishes subby-risk-result" --> SNS
```

**Counts are real**, not aspirational:
- **16 SNS topics** = 7 cross-service ([`infra/localstack-init.sh:46-57`](infra/localstack-init.sh#L46-L57)) + 9 findoc-internal stages ([`findoc-verify/scripts/localstack-init.sh:11-23`](findoc-verify/scripts/localstack-init.sh#L11-L23)).
- **27 primary SQS queues + 27 DLQs** = 18 cross-service bindings + 9 findoc-internal worker queues. Every primary queue has a paired DLQ with `maxReceiveCount=3` ([`infra/localstack-init.sh:128-135`](infra/localstack-init.sh#L128-L135)).
- **14 Java SQS consumer classes** in [`SubbyBankbackend/src/main/java/backend/backend/messaging/consumer/`](SubbyBankbackend/src/main/java/backend/backend/messaging/consumer/).
- **9 findoc workers** in [`findoc-verify/src/workers/`](findoc-verify/src/workers/).

---

## Architecture — loan origination event flow

This is one full origination from "user clicks Apply" to "funds in account". Every arrow is a real handler in this codebase.

```mermaid
sequenceDiagram
    autonumber
    participant U as smartbank UI
    participant J as SubbyBankbackend (Java)
    participant DB as Postgres + outbox
    participant S as SNS / SQS
    participant F as findoc-verify (Python)
    participant R as SubbyPythonLoan (XGBoost)
    participant FR as FraudPython
    participant SM as SMTP

    U->>J: POST /api/loans/apply (9-doc bundle)
    J->>F: POST /ingest/loan (multipart)
    F-->>J: 202 Accepted (applicationId)
    J->>DB: tx { LoanApplication + outbox row }
    DB-->>J: commit
    J-->>U: 202 Accepted

    Note over DB,S: Outbox relay leases unpublished rows<br/>(FOR UPDATE SKIP LOCKED) and publishes to SNS

    DB->>S: LoanApplicationSubmitted (subby-loan-events)
    S->>F: subby-loan-submitted SQS

    Note over F: 9-stage async pipeline<br/>each stage is an SQS consumer<br/>with idempotency + visibility heartbeat

    F->>F: ocr → classify → extract → aggregate
    F->>F: compliance → cross-doc → fraud → risk
    F->>S: findoc-loan-report-ready
    S->>J: subby-loan-findoc-results SQS

    J->>S: LoanRiskRequested (subby-risk-requested)
    S->>R: subby-risk-requests SQS
    R->>R: XGBoost predict (PoD, risk band)
    R->>S: LoanRiskResult (subby-risk-result)
    S->>J: subby-loan-risk-results SQS

    J->>DB: tx { decision, EMI, disbursement,<br/>BankPool debit, Transaction row }
    DB->>S: LoanFinalized + LoanDisbursed
    S->>J: subby-loan-disbursed-email SQS
    J->>SM: send approval + disbursement email

    Note over U,J: Subsequent peer-to-peer transfer
    U->>J: POST /api/transfers
    J->>FR: POST /predict (sync, fail-closed)
    FR-->>J: fraud_score
    J->>DB: tx { Transaction + outbox row }
```

---

## Architecture — the messaging spine (the part that matters)

```mermaid
flowchart LR
    classDef ok fill:#1f6feb,stroke:#0b3a82,color:#fff
    classDef warn fill:#EE4C2C,stroke:#7a2814,color:#fff
    classDef store fill:#7B42BC,stroke:#3d1f64,color:#fff

    BIZ["business write<br/>(loan, transfer, KYC)"]:::ok
    OB[("outbox_events<br/>aggregateId · eventType ·<br/>schemaVersion · correlationId ·<br/>lease_id · lease_expires_at")]:::store

    R1["OutboxRelay #1"]:::ok
    R2["OutboxRelay #2"]:::ok

    SNS["SNS topic<br/>(RawMessageDelivery=true,<br/>FilterPolicy on eventType)"]:::ok
    Q[("SQS queue")]:::store
    DLQ[("DLQ<br/>maxReceiveCount=3")]:::warn

    H["Consumer<br/>+ IdempotencyGuard<br/>(NEW / RETRY / SKIP_OK / SKIP_INFLIGHT)"]:::ok
    HB["Visibility Heartbeat<br/>extends lease every 15s"]:::ok
    PE[("processed_events<br/>(event_id, consumer_name)")]:::store

    BIZ -- "INSERT inside same DB tx" --> OB
    OB -- "SELECT ... FOR UPDATE SKIP LOCKED<br/>+ lease_id UUID + 30s expiry" --> R1
    OB --> R2
    R1 -- "Publish + stamp published_at" --> SNS
    R2 -- "Publish + stamp published_at" --> SNS

    SNS --> Q
    Q -- "ReceiveMessage<br/>(VisibilityTimeout=300s)" --> H
    H -- "claim row" --> PE
    H <-. "ChangeMessageVisibility +60s" .-> HB
    Q -- "after 3 receives" --> DLQ
    H -- "NonRetriableError →<br/>republish with DlqReason" --> DLQ
```

**Why this design** — the engineering deep-dive lives in [`ARCHITECTURE.md`](ARCHITECTURE.md).

---

## Architecture — AWS deployment topology (as shipped)

```mermaid
flowchart TB
    classDef aws fill:#FF9900,stroke:#7a4900,color:#000,stroke-width:1px
    classDef sg fill:#c33,stroke:#5c1212,color:#fff
    classDef gh fill:#24292e,stroke:#0d1117,color:#fff
    classDef ext fill:#6e7681,stroke:#2b313a,color:#fff

    DEV(["Developer push to main"]):::ext
    GH["GitHub Actions<br/>ci.yml + deploy.yml"]:::gh

    subgraph "AWS ap-south-1"
      EIP["Elastic IP"]:::aws
      subgraph "EC2 t3.small (2 GB + 4 GB swap)"
        D1["subby-bank :8080"]:::aws
        D2["findoc-verify :8000"]:::aws
        D3["findoc-verify-workers"]:::aws
        D4["subby-python-loan :8002"]:::aws
        D5["redis :6379 (cache only)"]:::aws
      end
      ROLE["IAM role: subby-ec2-role<br/>sns:Publish · sqs:* ·<br/>s3:* on bucket"]:::aws

      RDS[("RDS Postgres db.t3.micro<br/>subbybank · findoc · subby_loan<br/>private VPC")]:::aws
      SNS["SNS<br/>16 topics"]:::aws
      SQS["SQS<br/>27 queues + 27 DLQs"]:::aws
      S3[("S3 bucket<br/>subby-prod-documents-9680<br/>public-access blocked")]:::aws

      SG1["sg: subby-ec2-sg<br/>inbound 22, 80, 443, 8080"]:::sg
      SG2["sg: subby-rds-sg<br/>inbound 5432<br/>from subby-ec2-sg only"]:::sg
    end

    GMAIL["Gmail SMTP"]:::ext
    DOCAI["Google Document AI"]:::ext
    GEM["Gemini 2.0 Flash"]:::ext

    DEV --> GH
    GH -- "build → SCP → docker compose up" --> EIP
    EIP --> SG1
    SG1 --> D1
    D1 --- D2 --- D3 --- D4 --- D5

    ROLE -. attached .- D1
    ROLE -. attached .- D2
    ROLE -. attached .- D4

    D1 --> SG2
    SG2 --> RDS
    D2 --> RDS
    D4 --> RDS

    D1 --> SNS
    D2 --> SNS
    D4 --> SNS
    SNS --> SQS
    SQS --> D1
    SQS --> D3
    SQS --> D4

    D2 --> S3
    D3 --> S3
    D3 --> DOCAI
    D3 --> GEM
    D1 --> GMAIL
```

**FraudPython is intentionally omitted on AWS** — t3.small can't fit it alongside SubbyPythonLoan; Spring Boot's `FraudClient` enters DEGRADED mode (low-value transfers allowed, high-value rejected). Full topology + cost notes in [`DEPLOYMENT.md`](DEPLOYMENT.md).

---

## The four backend services + two frontends

| Service | Stack | Responsibility |
| :--- | :--- | :--- |
| **`SubbyBankbackend`** | Spring Boot 3 / Java 21 / Postgres / Redis | System of record. Users, KYC state, bank accounts, transfers, loan lifecycle, admin overrides, JWT auth, Razorpay, transactional outbox + relay. |
| **`findoc-verify`** | FastAPI / Python 3.12 / async SQLAlchemy | KYC + loan-origination document pipeline. 9 async stages over SQS: OCR → classify → extract → aggregate → compliance → cross-doc → fraud → risk → publish. |
| **`SubbyPythonLoan`** | FastAPI / Python 3.12 / XGBoost | Async loan-risk worker. Consumes `LoanRiskRequested`, runs the model, emits `LoanRiskResult` with PoD + risk band. |
| **`FraudPython`** | FastAPI / Python 3.12 / XGBoost | Synchronous per-transaction fraud scorer. Called fail-closed on the transfer hot-path. |
| **`smartbank`** | React 18 (CRA) + Tailwind | Customer + admin web UI. Signup, KYC upload, dashboard, transfers, loan apply, statement, admin override. |
| **`findoc-verify/webui`** | React + Vite | Pipeline operator UI — per-stage timeline, OCR previews, compliance / cross-doc / fraud detail, final LoanReport. |

---

## Engineering features that matter

These are the pieces I'd want a reviewer to actually open. Every claim below points at the real file.

### 1. Transactional outbox with leased multi-replica relay

Every domain event is written to `outbox_events` **inside the same DB transaction** as the originating business write. A relay polls under `SELECT ... FOR UPDATE SKIP LOCKED`, publishes to SNS, and stamps `published_at`. Each row is leased with a `lease_id` UUID + 30s expiry, so two relay replicas run safely and a crash mid-publish releases its rows automatically. A unique constraint on `(aggregate_id, event_type, schema_version)` closes any application-layer race.
→ [`OutboxRelay.java`](SubbyBankbackend/src/main/java/backend/backend/messaging/OutboxRelay.java), [`OutboxEvent.java`](SubbyBankbackend/src/main/java/backend/backend/messaging/OutboxEvent.java)

### 2. Four-state idempotency claim

Every consumer claims an `(event_id, consumer_name)` pair through `processed_events`:

- `NEW` — first sighting, run the handler.
- `RETRY` — prior `FAILED` row under `MAX_RETRIES`, run again.
- `SKIP_OK` — already `SUCCEEDED` or exhausted, ack and drop.
- `SKIP_INFLIGHT` — `PENDING`, another replica is mid-handle, leave for redelivery.

At-least-once SQS delivery is filtered into **exactly-once business effects** with replay safety on transient failures.
→ [`IdempotencyGuard.java`](SubbyBankbackend/src/main/java/backend/backend/messaging/IdempotencyGuard.java)

### 3. SQS visibility heartbeat

While a handler runs, an `asyncio` task (Python) or in-tx scheduled callback (Java) calls `ChangeMessageVisibility` every 15s to extend the lease. Long-running document pipelines (Document AI + Gemini) no longer get redelivered mid-execution.

### 4. DLQ + replay

Per-queue DLQs catch poison messages after `maxReceiveCount=3`. The findoc consumer additionally republishes `NonRetriableError` failures **directly** to its DLQ with a `DlqReason` MessageAttribute so they don't burn the redelivery budget.

### 5. Schema versioning on the wire

Every event extends `DomainEvent` (Java) or builds an envelope (Python) carrying `schemaVersion`. The version travels in **both** the JSON envelope and as an SNS `MessageAttribute`, so consumers can branch without parsing the body. Java's `DomainEvent.eventType()` is resolved from a `@EventType` annotation — never inferred from the class name (which would silently break under refactors).

### 6. Correlation-id pipeline

A single `correlationId` flows through:

```
HTTP request header → CorrelationIdFilter / Middleware
  → SLF4J MDC / Python ContextVar
  → outbox row → SNS MessageAttribute + JSON envelope
  → SQS consumer extracts it back to MDC
  → outbound WebClient / boto3 publisher re-injects it
```

To trace a request end-to-end: `grep -h "<corrId>" subby-bank.log findoc-verify.log subby-python-loan.log`

### 7. Admin loan override with reversal

`POST /api/admin/loans/{loanAppId}/override` flips a finalized loan's decision. On `APPROVED → REJECTED` for a previously disbursed loan, `reverseDisbursement()` debits the user's account, returns funds to the bank pool, writes a reversal `Transaction` row, and clears `hasLoan` / `loanamount`. The audit row is keyed by `(loanApplicationId, overriddenBy, newDecision)` — replay returns the prior row with `idempotent: true`. `REQUIRES_NEW` keeps the inner finalize step atomic with side-effects; the `wasApproved` retry guard prevents double-reversal.

### 8. ML feature bridge — pragmatic engineering

The loan-risk event contract carries a rich feature set (`monthly_income`, `dti_ratio`, `fraud_score`, `employment_type`, ...) but the production model was trained on 5 columns. Rather than ship a model that doesn't match the contract, [`SubbyPythonLoan/README.md`](SubbyPythonLoan/README.md) documents the runtime feature bridge and the `prob_eligible` → `probability_of_default` inversion — and is honest about retraining as future work.

### 9. Production-shaped local infra

`docker compose up -d --build` brings up a stack **structurally identical** to production:

- LocalStack 4.0.3 (SNS + SQS + S3) with init scripts that match AWS line-for-line.
- MinIO as alternative S3 backend, swappable via `S3_ENDPOINT_URL`.
- Postgres 16 with three databases and per-DB roles.
- Redis 7 for Spring cache + Bucket4j rate-limit token buckets.
- MailHog SMTP sink with web UI.
- Document AI via mounted ADC (no service-account JSON — complies with org policies blocking long-lived keys).

To go to real AWS: unset `AWS_ENDPOINT_URL`, set `SPRING_PROFILES_ACTIVE=aws`, attach IAM instance role. **No code changes.**

---

## Tech stack (for keyword scanners)

| Layer | Tech |
| :--- | :--- |
| **Languages** | Java 21, Python 3.12, TypeScript / JavaScript ES2022, SQL |
| **Backend frameworks** | Spring Boot 3.5, Spring Security, Spring Data JPA, Hibernate, FastAPI, async SQLAlchemy, Pydantic |
| **Frontend** | React 18, CRA, Vite, Tailwind CSS, Axios |
| **Persistence** | PostgreSQL 16, Redis 7, Caffeine in-process cache |
| **Migrations** | Flyway (Java), Alembic (Python) |
| **Messaging** | AWS SNS + SQS, RawMessageDelivery, FilterPolicy, FIFO-ready, DLQs (`maxReceiveCount=3`), LocalStack 4.0 in dev |
| **Object storage** | AWS S3, MinIO |
| **ML / AI** | XGBoost (loan + fraud), scikit-learn, Pandas, NumPy, Google Document AI (OCR), Gemini 2.0 Flash (extraction) |
| **Auth** | JWT access + refresh tokens, Bcrypt, `X-API-Key` (SHA-256 hashed) for service-to-service |
| **Resilience** | Bucket4j rate limiting, transactional outbox, leased relay, idempotency keys, DLQs, visibility heartbeat, correlation IDs |
| **Security** | PII encryption at rest (Aadhaar / PAN), SHA-256 hashed API keys, KMS-encrypted S3 in prod, S3 public-access block, IAM instance role |
| **Payments** | Razorpay SDK (top-up, withdraw, signature verification) |
| **Observability** | Spring Boot Actuator + Micrometer + Prometheus, custom `outbox.*` and `sqs.*` metrics, JSON structured logs with `correlationId`, MailHog |
| **Cloud** | AWS EC2, RDS, SNS, SQS, S3, IAM, Elastic IP, Security Groups, ap-south-1 |
| **Build / CI/CD** | Maven, pip + pyproject.toml, npm, Docker multi-stage, GitHub Actions (`ci.yml`, `deploy.yml`) |
| **Infra-as-code (dev)** | docker-compose.yml + LocalStack init scripts + postgres-init.sql |
| **Testing** | JUnit 5, Mockito, pytest, integration smoke tests, e2e probes |

---

## Quickstart

```bash
cp .env.example .env          # GEMINI_API_KEY + Doc AI processor IDs
docker compose up -d --build  # ~90s for healthchecks
```

| Service | Port | Purpose |
| :--- | :--- | :--- |
| `subby-bank` | `8080` | Spring Boot API |
| `findoc-verify` | `8000` | FastAPI + 9 SQS workers |
| `subby-python-loan` | `8002` | XGBoost loan-risk worker |
| `fraud-python` | `8001` | XGBoost transaction-fraud scorer |
| `postgres` | `5433` | shared cluster |
| `redis` | `6379` | Spring cache |
| `localstack` | `4566` | SNS / SQS / S3 emulator |
| `minio` | `9000`, `9001` | alt S3 + console |
| `mailhog` | `1025`, `8025` | SMTP sink + UI |

```bash
cd smartbank          && npm install && npm start      # CRA :3000
cd findoc-verify/webui && npm install && npm run dev   # Vite :5173

curl -s http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8080/actuator/prometheus | grep -E 'outbox|sqs'
```

---

## Live demo path (93 seconds, blank slate → disbursed loan)

1. **Sign up** at `http://localhost:3000`.
2. **Submit KYC** with `infra/fixtures/aadhaar.pdf` + `pan.pdf`. `SUBMITTED → DOCS_UNDER_REVIEW → KYC_APPROVED` in ~15s.
3. **Apply for a loan** with the 9-document bundle (3 statements, 3 payslips, employment letter, ITR, credit report).
4. **Pipeline runs**: OCR → classify → extract → aggregate → compliance → cross-doc → fraud → risk. `DOCS_VERIFIED` in ~80s.
5. **ML risk scoring** publishes `LoanRiskResult` with band + decision.
6. **Loan APPROVED**, EMI computed, funds disbursed from `bank_pool` → user account.
7. (Optional) **Admin override** at `/admin` reverses the decision; `reverseDisbursement` debits the user, returns to pool, writes a reversal Transaction.

```bash
./infra/e2e-smoke-test.sh
./infra/smoke-loan-disbursed.sh
./infra/smoke-reverse-reject-then-override.sh
./infra/smoke-replay-approve-to-ml.sh
```

Walkthrough: [`infra/DEMO.md`](infra/DEMO.md). Architecture deep-dive: [`ARCHITECTURE.md`](ARCHITECTURE.md). AWS deployment: [`DEPLOYMENT.md`](DEPLOYMENT.md). Full local setup (prereqs, env files, troubleshooting): [`LOCAL_SETUP.md`](LOCAL_SETUP.md).

---

## Repository layout

```
.
├── ARCHITECTURE.md            ← engineering deep-dive (read this)
├── DEPLOYMENT.md              ← real AWS deployment, CI/CD, costs
├── docker-compose.yml         ← whole stack, one command
├── docker-compose.aws.yml     ← AWS profile compose
│
├── SubbyBankbackend/          ← Spring Boot 3 / Java 21 — system of record
│   └── src/main/java/backend/backend/
│       ├── controller/        REST API
│       ├── service/           loans, transfers, KYC, override
│       ├── messaging/         outbox, relay, SNS publisher, 14 SQS consumers
│       ├── model/             JPA entities incl. outbox_events
│       ├── security/          JWT, filter chain, RBAC
│       ├── chatbot/           Gemini-powered helper
│       └── configuration/     properties classes, AWS SDK setup
│
├── findoc-verify/             ← FastAPI + 9 async SQS workers
│   ├── src/                   API, workers, models, OCR, LLM, pipeline stages
│   ├── alembic/               schema migrations
│   ├── webui/                 Vite + Tailwind operator UI
│   └── scripts/               api-key minter, localstack-init
│
├── SubbyPythonLoan/           ← XGBoost loan-risk worker
├── FraudPython/               ← XGBoost transaction-fraud scorer
├── smartbank/                 ← React 18 customer + admin UI
└── infra/                     ← LocalStack init, AWS bootstrap, smoke tests
    ├── localstack-init.sh     ← shared topics, queues, DLQs, subscriptions
    ├── aws-bootstrap.sh       ← provisions real AWS topology
    ├── postgres-init.sql      ← creates DBs + roles
    └── *.sh                   ← e2e + smoke tests
```

---

## Known limitations & deliberate tradeoffs

The repo is honest about what it doesn't do:

- **Single-replica rate limiting.** Bucket4j is in-process; horizontal scale needs a Redis-backed shared limiter.
- **No spend cap on Gemini / Document AI.** A bulk submission could burn the budget before manual intervention.
- **Model versioning** is a `modelVersion` field; rollback is "redeploy the prior container."
- **Saga for the override-finalize window.** [`ARCHITECTURE.md` § 5](ARCHITECTURE.md) describes one transactional window where an audit row can be lost (no money is moved twice; `wasApproved` guard prevents double-reversal).
- **OCR vendor lock to Document AI.** No Textract / Tesseract fallback.
- **LLM lock to Gemini 2.0 Flash.** No LiteLLM / OpenAI / Anthropic.
- **FraudPython not on AWS** — t3.small RAM budget; Spring Boot's `FraudClient` runs in DEGRADED mode without it.
- **Frontends not yet on AWS** — backend reachable via Elastic IP; S3 + CloudFront pending.

These are intentional scope boundaries documented at the time of writing, not unknowns.

---

## Author

**Rajdeep Mandal** — backend, distributed systems, applied ML.

- Email: **rajdeep.mandal@reverside.co**
- This repo is a portfolio piece. For a 5-minute walkthrough → [`infra/DEMO.md`](infra/DEMO.md). For the engineering reasoning → [`ARCHITECTURE.md`](ARCHITECTURE.md). For the real AWS deploy → [`DEPLOYMENT.md`](DEPLOYMENT.md).

> If you're hiring for backend, full-stack, distributed systems, or platform roles — I'd love to talk.
