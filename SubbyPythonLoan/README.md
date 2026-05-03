# SubbyPythonLoan — async risk scoring worker

Event-driven SQS consumer that runs the loan-risk ML model. Receives
`LoanRiskRequested` events from SubbyBankbackend over SNS, scores them, and
publishes `LoanRiskResult` events back onto a result topic.

## Previous state (before the rewrite)

- **Framework**: FastAPI (sync, single process, Flask-style).
- **Endpoint**: `POST /predictloan` — took `{income, pan, adhar, credit_score,
  requested_amount, balance, avg_transaction}`, returned `{eligible, probability}`.
- **Auxiliary**: `GET /health` returning the literal string `"OK"`.
- **Port**: `8000` in-container, mapped to `8002` on the host by the monorepo
  compose.
- **Model**: XGBoost classifier (`loan_model.pkl`) + StandardScaler
  (`scaler.pkl`), produced by `train.py` on a synthetic dataset. The target
  variable is *eligibility*, **not probability of default**.

## Target architecture

```
Java (SubbyBankbackend)
    │   publishes LoanRiskRequested
    ▼
SNS: subby-risk-requested
    │
    ▼
SQS: subby-risk-requests   (maxReceiveCount=3 → subby-risk-requests-dlq)
    │
    ▼
RiskWorker (this service)
    │  1. parse + validate envelope
    │  2. idempotency check (processed_events)
    │  3. map inbound features → 5-col model input
    │  4. run predictor  → probability_of_default
    │  5. decision + risk band + reason
    │  6. publish LoanRiskResult
    │  7. ACK
    ▼
SNS: subby-risk-result
    │
    ▼
SQS: subby-loan-risk-results   (consumed by Java)
```

## Feature mapping gap (important)

The event contract exposes a rich feature set (monthly_income, dti_ratio,
fraud_score, employment_type, …) but the existing `loan_model.pkl` was trained
on only 5 columns: `income, balance, avg_transaction, credit_score,
requested_amount`. We bridge at runtime:

| Inbound feature     | Model input         | Notes                                           |
| ------------------- | ------------------- | ----------------------------------------------- |
| `monthly_income`    | `income`            | direct                                          |
| `bank_avg_balance`  | `balance`           | falls back to `monthly_income` when absent      |
| (synthesized)       | `avg_transaction`   | `monthly_income * 0.05` — model has no real hook |
| `credit_score`      | `credit_score`      | direct                                          |
| `amountRequested`   | `requested_amount`  | from `payload.amountRequested`, not features    |

The model predicts `prob_eligible`; we invert to
`probability_of_default = clip(1 - prob_eligible, 0, 1)`. Retraining the model
on the full feature schema will remove this bridge — left out of this change
since it requires a real dataset.

## Decision thresholds (env-tunable)

| probability_of_default | decision        | risk band |
| ---------------------- | --------------- | --------- |
| `< 0.05`               | `approve`       | A         |
| `< 0.10`               | `approve`       | B         |
| `< POD_APPROVE (0.15)` | `approve`       | C         |
| `< POD_REJECT  (0.35)` | `manual_review` | D         |
| `>= POD_REJECT`        | `reject`        | E         |

## Event contracts

**Inbound** (Java → this service), on topic `subby-risk-requested`:

```json
{
  "eventId": "uuid",
  "occurredAt": "2026-04-24T10:00:00Z",
  "schemaVersion": 1,
  "eventType": "LoanRiskRequested",
  "correlationId": "loanAppId-abc",
  "payload": {
    "loanAppId": "loanAppId-abc",
    "amountRequested": 500000,
    "tenureMonths": 6,
    "features": {
      "monthly_income": 75000,
      "credit_score": 742,
      "existing_emi": 8000,
      "declared_income_annual": 900000,
      "bank_avg_balance": 120000,
      "employment_type": "salaried",
      "age": 28,
      "dti_ratio": 0.106,
      "fraud_score": 0.12,
      "compliance_warnings_count": 1
    }
  }
}
```

**Outbound** (this service → Java), on topic `subby-risk-result`:

```json
{
  "eventId": "uuid",
  "occurredAt": "2026-04-24T10:00:01Z",
  "schemaVersion": 1,
  "eventType": "LoanRiskResult",
  "correlationId": "loanAppId-abc",
  "payload": {
    "loanAppId": "loanAppId-abc",
    "decision": "approve",
    "probability_of_default": 0.042,
    "risk_band": "A",
    "modelVersion": "v1.0.0",
    "featuresUsed": ["income", "balance", "avg_transaction", "credit_score", "requested_amount"],
    "reason": "low_probability_of_default"
  }
}
```

## Processing guarantees

- **Idempotent**: same `eventId` published twice → exactly one result event.
  Enforced by `processed_events (event_id, consumer_name)` primary key with
  `ON CONFLICT DO NOTHING`.
- **DLQ**:
  - *Poison pills* (bad envelope, missing fields, invalid features) — ACK'd on
    the primary queue and republished to `subby-risk-requests-dlq` with a
    `DlqReason` message attribute. No retries.
  - *Retriable failures* (model load error, DB timeout, …) — left on queue for
    SQS redelivery; the idempotency row is rolled back so the next attempt is
    not skipped. Goes to DLQ at `maxReceiveCount=3`.
- **Graceful shutdown**: `SIGTERM` → in-flight message finishes → worker task
  cancels → container exits clean.

## HTTP surface (debug only)

- `GET  /health` → `{"status": "ok", "model_version": "v1.0.0"}`.
- `POST /api/v1/debug/predict` → runs the predictor synchronously, **does not**
  publish to SNS. For threshold tuning and curl-testing. Example:

  ```bash
  curl -X POST http://localhost:8002/api/v1/debug/predict \
    -H 'content-type: application/json' \
    -d '{"amountRequested": 500000, "features": {"monthly_income": 75000, "credit_score": 742, "bank_avg_balance": 120000}}'
  ```

- `GET  /metrics` → Prometheus exposition: `risk_predictions_total`,
  `risk_prediction_duration_seconds`, `sqs_messages_processed_total`,
  `sqs_messages_failed_total`.

## Local run — monorepo

Bootstrapped by the root `docker-compose.yml`. Topics, queues, DLQ policies,
and subscriptions are provisioned by `infra/localstack-init.sh` (already
includes `subby-risk-requested`, `subby-risk-requests`, `subby-risk-result`,
`subby-loan-risk-results`). The dedicated `subby_loan` Postgres database is
created by `infra/postgres-init.sql`.

```bash
docker compose up subby-python-loan -d
docker compose exec subby-python-loan alembic upgrade head
```

## Local run — standalone

For hacking on risk scoring without booting Java or findoc-verify:

```bash
cd SubbyPythonLoan
docker compose up --build
# exposes: localstack → :4567, predictor → :8002
```

## Smoke test

```bash
# 1. Publish a request
awslocal sns publish \
  --endpoint-url http://localhost:4566 \
  --topic-arn arn:aws:sns:ap-south-1:000000000000:subby-risk-requested \
  --message '{"eventId":"11111111-1111-1111-1111-111111111111","eventType":"LoanRiskRequested","schemaVersion":1,"correlationId":"app-1","occurredAt":"2026-04-24T10:00:00Z","payload":{"loanAppId":"app-1","amountRequested":500000,"tenureMonths":6,"features":{"monthly_income":75000,"credit_score":742,"bank_avg_balance":120000}}}'

# 2. Drain the result queue — should see the matching LoanRiskResult
awslocal sqs receive-message \
  --endpoint-url http://localhost:4566 \
  --queue-url http://localhost:4566/000000000000/subby-loan-risk-results \
  --max-number-of-messages 1 --wait-time-seconds 5
```

## Prod swap

`AWS_ENDPOINT_URL` unset + real AWS creds = real SQS/SNS. No code change.

## Out of scope in this rewrite

- Retraining the model on the richer inbound feature set.
- Any feature engineering beyond the 5 columns the existing model knows.
- Java-side producers/consumers (prompts 4 + 5).

## Reference: layout

```
SubbyPythonLoan/
├── Dockerfile
├── docker-compose.yml           standalone compose (dev-only)
├── pyproject.toml
├── requirements.txt
├── alembic.ini
├── .env.example
├── README.md
├── loan_model.pkl               (kept)
├── scaler.pkl                   (kept)
├── train.py                     (kept)
├── alembic/
│   ├── env.py
│   ├── script.py.mako
│   └── versions/001_init.py
├── scripts/
│   ├── standalone-postgres-init.sql
│   └── standalone-localstack-init.sh
├── src/
│   ├── main.py                  FastAPI app + /health + /metrics + lifespan-managed worker
│   ├── config.py
│   ├── db.py                    ProcessedEvent model + async engine
│   ├── logging_config.py
│   ├── metrics.py               Prometheus counters/histograms
│   ├── messaging/
│   │   ├── topics.py            queue + topic name constants
│   │   ├── schemas.py           Pydantic event models, NonRetriableError
│   │   ├── sqs_consumer.py      base class (idempotency + DLQ + signals)
│   │   └── sns_publisher.py
│   ├── model/
│   │   └── predictor.py         pkl loader + feature bridge + PoD conversion
│   ├── worker/
│   │   └── risk_worker.py       SqsConsumer → SnsPublisher
│   └── api/
│       └── debug.py             /health, /api/v1/debug/predict
└── tests/
    └── test_integration.py      LocalStack round-trip
```
