# End-to-end Demo — 5 minutes

Assumes `docker compose up -d --build` is healthy, both dev frontends are
running, and `FINDOC_API_KEY` is set in `.env` and picked up by subby-bank.

Verified end-to-end on 2026-04-28: signup → KYC_APPROVED (~15s) → bank
account → loan applied with 9 docs → DOCS_VERIFIED (~80s) → ML risk-scored
band B (P(default)=0.068) → APPROVED with EMI in ~93s total. Headless
driver: see the script in this file's commit history at `/tmp/e2e_full4.sh`,
or trigger via the smartbank UI.

**Demo precondition (between runs):** the KYC pipeline rejects re-uses of
the same Aadhaar/PAN by another user (correct anti-fraud behaviour, but
trips up demo replays with the shared fixture identity "Subham Dutta").
Reset between runs:

```bash
docker exec subby-postgres psql -U subby -d subbybank -c \
  "UPDATE users SET aadhaar_number_encrypted=NULL, pan_number_encrypted=NULL, \
                    kyc_status='NONE', account_active=false WHERE role='USER'; \
   DELETE FROM loan_application;"
```

Endpoints in use:

* **smartbank** (user UI) — <http://localhost:3000>
* **findoc-verify webui** (admin/pipeline UI) — <http://localhost:5173>
* **MailHog** (captured mail) — <http://localhost:8025>
* **fraud-python** (XGBoost transaction-fraud scorer) — <http://localhost:8001/health>

## 1. User signs up and completes KYC

1. Open <http://localhost:3000> → click **Sign up**.
2. Provide a username, email, mobile, password → submit.
3. The dashboard shows a "Complete KYC" banner → click through.
4. On the KYC page, upload fixtures:
   * `infra/fixtures/aadhaar.pdf`
   * `infra/fixtures/pan.pdf`
5. Submit. The status page polls the pipeline:

   ```
   SUBMITTED → DOCS_UNDER_REVIEW → KYC_APPROVED
   ```

   Typical end-to-end time: 30–60 s on a warm stack.

## 2. User applies for a loan

1. With KYC approved, the dashboard exposes **Apply for Loan**.
2. Fill the loan form (suggested demo values):
   * Amount: ₹5,00,000
   * Purpose: `EDUCATION`
   * Tenure: 60 months
3. Upload the 9 required docs from `infra/fixtures/`:

   ```
   bank-statement-2026-01.pdf  bank-statement-2026-02.pdf  bank-statement-2026-03.pdf
   payslip-2026-01.pdf         payslip-2026-02.pdf         payslip-2026-03.pdf
   employment-letter.pdf       itr-ay-2025-26.pdf          credit-report.pdf
   ```

4. Submit. Loan status panel walks through 5 stages:

   ```
   received → document_verification → docs_verified → risk_scoring → APPROVED
   ```

   Typical time: 1–3 min.

5. The approved screen surfaces:
   * EMI (e.g. ₹86,349/month for the above inputs)
   * 6-month repayment schedule preview
   * **Pay now** button (wired to Razorpay test mode)

## 3. Verify downstream side effects

| Where                     | What to check                                                       |
|---------------------------|---------------------------------------------------------------------|
| <http://localhost:8025>   | 2–3 emails: KYC approved, loan approved, (optional) welcome email.  |
| findoc-verify webui       | `/` → both applications listed with classification + timeline tabs. |
| findoc-verify webui       | Click a row → Overview / Documents / Compliance / Fraud tabs.       |
| smartbank admin           | Log out, log in as `admin`/`admin` → `/admin/loans` row for the user. |

## 4. Admin override (1 min)

Show that an admin can override an automated loan decision after the fact:

```bash
ADMIN=$(curl -s -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .token)

curl -X POST "http://localhost:8080/api/admin/loans/$LOAN_ID/override" \
  -H "Authorization: Bearer $ADMIN" -H 'Content-Type: application/json' \
  -d '{"decision":"REJECTED","reason":"Manual review found doc anomaly","notifyFindoc":false}'
```

Re-fetch `GET /api/loans/$LOAN_ID/status` — `lifecycleStatus` flips to
REJECTED, `decisionReason` shows the admin's note. The override bypasses the
"already terminal" idempotency guard intentionally (`source` starting with
`admin:`); a redelivered automated `LoanDecisionMade` is still skipped.

For approve overrides supply `interestRate` in the body; the same
LoanFinalizationService path runs, including EMI computation and the bank
disbursement.

## 5. Resilience demo (optional, 2 min)

Show SQS redelivery under container loss:

```bash
# 1. Stop findoc-verify mid-flight
docker compose stop findoc-verify

# 2. In smartbank, submit a new loan application. Status stalls at
#    DOCS_UNDER_REVIEW — outbound events sit in subby-loan-submitted, and
#    findoc's OCR queue fills without being read.

# 3. Bring it back
docker compose start findoc-verify

# Within ~30 s the messages are re-delivered from SQS visibility-timeout
# expiry; the pipeline resumes and converges to APPROVED.
```

If the outage was long enough to exhaust `maxReceiveCount` (default 3), the
message lands in a DLQ. Replay via the Java admin endpoint:

```bash
curl -X POST -H "X-API-Key: $FINDOC_API_KEY" \
    http://localhost:8080/api/admin/dlq/subby-loan-submitted-dlq/replay-all
```

## 6. Common first-run issues

| Symptom                                               | Most likely cause / fix                                               |
|-------------------------------------------------------|-----------------------------------------------------------------------|
| `findoc-verify` unhealthy, logs 401 / `invalid_grant` | ADC expired. `gcloud auth application-default login` on the host, then `docker compose restart findoc-verify`. The volume mount picks up refreshed creds. |
| `findoc-verify` logs `PermissionDenied` on DocAI      | `roles/documentai.apiUser` not granted on the project, or the quota project isn't set (`gcloud auth application-default set-quota-project ...`). |
| `findoc-verify` logs `Processor not found`            | `GOOGLE_DOCAI_PROCESSOR_ID` wrong, or the processor is disabled in GCP Console. |
| Browser blocks requests with CORS error               | Ensure `http://localhost:3000` and `http://localhost:5173` are in `CorsConfig.java` and findoc-verify's `main.py` origins list. |
| Loan stuck at `DOCS_UNDER_REVIEW` forever             | `docker compose logs findoc-verify --tail=200 \| grep -iE "ocr\|docai"` — most likely ADC. |
| Loan stuck at `DOCS_VERIFIED` (no ML response)        | `docker compose logs subby-python-loan --tail=200`. Check `subby-risk-requests` queue depth. |
| Email missing from MailHog                            | `docker compose logs subby-bank --tail=200 \| grep -iE "mail\|smtp"`. Verify `spring.mail.host=mailhog`. |
| DLQ filling                                           | Usually an event schema / idempotency mismatch. Fix the consumer, then replay via `/api/admin/dlq/{queue}/replay-all`. |
| Flyway out-of-order                                   | `docker compose exec postgres psql -U postgres -d subbybank -c "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;"` — should show V1–V4 all `success=true`. |
