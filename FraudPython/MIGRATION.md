# FraudPython — production-grade migration

## What changed

Single-file `app.py` was promoted to a `src/` layout matching the
SubbyPythonLoan reference service. The HTTP contract on `/predict` is
unchanged — Java's `TransactionService.checkFraud` still receives the same
`{results: [{fraud_probability, is_fraud, ...}]}` JSON.

```
FraudPython/
├── src/
│   ├── main.py              # FastAPI app, lifespan, middleware, /metrics, /health
│   ├── config.py            # pydantic-settings; tune thresholds via env
│   ├── logging_config.py    # JSON logs + correlation-id contextvar
│   ├── metrics.py           # Prometheus counters + histograms
│   ├── api/predict.py       # /predict route — async, run_in_executor for inference
│   └── model/predictor.py   # FraudPredictor: load(), warm(), predict_batch()
├── legacy/app_legacy.py     # original — kept for diff/regression only
├── Dockerfile               # gunicorn -k uvicorn.workers.UvicornWorker -w 4
└── requirements.txt         # pinned deps incl. gunicorn, prometheus-client, json-logger
```

## Why this change is sync, not event-driven

Loan risk (SubbyPythonLoan) is event-driven because a loan can stay PENDING
for seconds while async scoring happens — the UX is fine. **A transfer
cannot.** Once funds leave the sender account, they're gone; we can't
"approve optimistically and reverse on fraud-detected." So the fraud
hot-path stays synchronous on `/predict`.

What we *did* change is everything else that was making sync hurt under
load:

- **Multi-worker gunicorn**: 4 processes per pod with their own model copy
  in RAM, real parallelism (Python GIL is per-process). Tunable via
  `WEB_CONCURRENCY`.
- **Async route + `run_in_executor`**: inference is offloaded to the default
  thread pool so an in-flight predict doesn't block the asyncio event loop
  on its worker. New requests on the same worker keep flowing.
- **Batch inference**: `predict_batch` calls XGBoost once for N rows
  instead of N times. The Java caller still sends one transaction per
  request; the path is ready for batched callers later (e.g. a backfill
  worker).
- **Model warmup**: first `predict_proba` on a fresh XGBoost booster is
  ~30× slower than subsequent calls. The lifespan does one throwaway
  call at startup so the first real request doesn't pay that.
- **Prometheus `/metrics`**: predictions partitioned by `is_fraud` × risk
  band, latency histogram, validation failure counter, business-rule
  override counter.
- **Correlation IDs**: middleware accepts/echoes `X-Correlation-Id`. Java
  callers can stitch a transfer trace across services via MDC.

## Java-side changes (the actual scaling fix for transfers)

The bigger interview vulnerability was on the *caller* side, not the Python
service. `TransactionService.checkFraud` was calling FraudPython through
an unconfigured `RestTemplate` — **infinite connect/read timeout, no
circuit breaker, no concurrency cap**. A hung FraudPython would have
silently leaked Tomcat threads until the whole bank app stopped responding.

What's now in place:

| Layer            | Where                                                      | Knob (application.yml)                 |
|------------------|------------------------------------------------------------|----------------------------------------|
| HTTP timeouts    | `BackendApplication.fraudRestTemplate` bean (500ms / 1.5s) | hard-coded; bump in code               |
| Bulkhead         | `@Bulkhead(name="fraud")` on `FraudClient.score`           | `resilience4j.bulkhead.instances.fraud`|
| Retry            | `@Retry(name="fraud")` on transient I/O / 5xx              | `resilience4j.retry.instances.fraud`   |
| Circuit breaker  | `@CircuitBreaker(name="fraud")` with `slowCallDuration`    | `resilience4j.circuitbreaker.instances.fraud`|
| Fallback policy  | `FraudClient.fallback`: low-risk → DEGRADED, else UNAVAILABLE| in code                              |
| In-process cache | `FraudResultCache` (Caffeine, 60s TTL, 10k entries)        | in code                                |

`FraudCheckResult.Status` carries `CHECKED / SKIPPED_SYSTEM / DEGRADED /
UNAVAILABLE` so audits can find every transfer that bypassed a real model
decision during an outage.

The same set (timeouts via singleton SDK + circuit breaker + bulkhead +
retry + fallback) is now wrapped around `RazorpayService.createOrder`. The
`RazorpayClient` is also a singleton now — the previous code constructed a
new one per request, which leaked an OkHttp connection pool every call.

## How this scales

| Lever                            | Before                              | After                                 |
|----------------------------------|-------------------------------------|---------------------------------------|
| Concurrent predictions per pod   | 1 (single uvicorn worker, sync)    | 4 (gunicorn) × N async event loop slots|
| Adding capacity                  | Vertical only                      | `kubectl scale --replicas=N`           |
| FraudPython hang → Java effect   | Silent thread-pool drain → app down | Bulkhead caps 50, circuit opens, fallback engages |
| Retry storm / double-submit      | Each hits FraudPython              | Caffeine absorbs in-process for 60s    |
| Fraud outage during outage       | All transfers rejected             | Low-risk allowed (DEGRADED), high-risk rejected |

## Interview talking points

> "Fraud check is on every transfer's hot path, so we kept it synchronous —
> we can't approve a transfer optimistically and reverse it later. What we
> hardened was the failure modes around it. The Java caller now has explicit
> HTTP timeouts (500ms/1.5s) on a dedicated RestTemplate, a Resilience4j
> bulkhead capping concurrent fraud calls at 50 so a slow downstream can't
> drain Tomcat's pool, a circuit breaker that opens on slow-call rate, and
> a fallback that allows low-risk transfers through in a DEGRADED status
> while rejecting high-risk ones during an outage. A 60-second Caffeine
> cache absorbs retried/duplicate transfers before they leave the JVM.
> FraudPython itself runs gunicorn with 4 workers per pod and scales
> horizontally — the SQS/event-driven shape we use for loan risk doesn't
> fit transfers, but it's how we'd handle post-hoc fraud analysis if
> needed (publish TransactionCompleted, async worker re-scores for
> alerting / model feedback)."

The natural follow-up — "what if fraud python is just down?" — has a clean
answer: timeouts trip → retry exhausts → circuit opens → fallback runs.
Low-amount transfers without foreign / high-risk flags continue to work in
DEGRADED mode and are tagged in the audit log; everything else gets
"Fraud detection offline. Transfer aborted to protect funds." That's a
better-than-binary answer to "fail open vs fail closed."

## Config knobs worth knowing

```yaml
# application.yml
resilience4j:
  circuitbreaker.instances.fraud:
    slowCallDurationThreshold: 1500ms     # match RestTemplate readTimeout
    slowCallRateThreshold: 60
    failureRateThreshold: 50
    waitDurationInOpenState: 15s
  bulkhead.instances.fraud:
    maxConcurrentCalls: 50                # caps thread-pool drain
    maxWaitDuration: 100ms
  retry.instances.fraud:
    maxAttempts: 2
    retryExceptions: [ResourceAccessException, HttpServerErrorException]
    ignoreExceptions: [HttpClientErrorException]   # don't retry 4xx
```

```bash
# FraudPython env
WEB_CONCURRENCY=4         # gunicorn workers per pod
LOG_LEVEL=INFO
AMOUNT_THRESHOLD=50000    # business-rule override threshold
FRAUD_DECISION_THRESHOLD=0.5
```

## What was deliberately not done

- **Event-driven /predict**: would require the transfer flow to accept
  PENDING + reconcile via webhook, fundamentally different UX. Out of
  scope.
- **`fraud_check_status` column on transactions table**: would require a
  Flyway migration. The status is in the audit log for now; promote later.
- **Async fraud worker** (post-hoc scoring): mentioned in the interview
  pitch but not built — the SqsConsumer pattern from SubbyPythonLoan is a
  drop-in if the team wants it.
- **Renaming `is_fraud` int field on Transaction**: changing it cascades
  into JPA, DTO, JSON contracts. Left alone.
