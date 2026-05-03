# Shared Infrastructure — LocalStack → AWS Swap

This repo's services (SubbyBankbackend, findoc-verify, SubbyPythonLoan) talk to
SNS, SQS, and S3. In development everything runs against **LocalStack**; in
production the exact same code talks to **real AWS** by switching a profile —
no code changes required.

## 0. First-run checklist

1. **Google Document AI — Application Default Credentials (ADC).** The org policy
   blocks service-account keys, so findoc-verify uses ADC. One-time host setup:

   ```bash
   gcloud auth application-default login
   gcloud auth application-default set-quota-project project-7bb85df2-f684-4eb4-958
   gcloud services enable documentai.googleapis.com --project=project-7bb85df2-f684-4eb4-958
   ```

   Verify the credential file exists — docker-compose mounts it read-only into
   `/root/.config/gcloud` inside the findoc-verify container:

   ```bash
   ls "$USERPROFILE/AppData/Roaming/gcloud/application_default_credentials.json"
   ```

   Do **NOT** set `GOOGLE_APPLICATION_CREDENTIALS` anywhere — the SDK auto-
   discovers ADC from the mounted directory. Setting that env var breaks the flow.

2. **Fill `.env`.** Copy the checked-in template, then set at minimum:

   * `GEMINI_API_KEY=<your real key>` — Gemini powers the LLM extract/reasoning steps.
   * `GOOGLE_PROJECT_ID`, `GOOGLE_DOCAI_LOCATION`, `GOOGLE_DOCAI_PROCESSOR_ID` —
     prefilled with the team's dev processor; override for your own.
   * `FINDOC_API_KEY=<minted>` — see step 4.

3. **`docker compose up -d --build`.** Wait for all services to report healthy
   (`docker compose ps`). MailHog, findoc-verify, and subby-bank pull config from
   `.env`; flipping values and running `docker compose restart <svc>` is enough
   for most changes.

4. **Mint an API key** for the Java → findoc-verify service-to-service call:

   ```bash
   docker compose exec findoc-verify python -m scripts.generate_api_key \
       --label subby-java --org subby --scopes submit,admin --rate-limit 120
   ```

   Copy the raw key into `.env` as `FINDOC_API_KEY=<key>`, then
   `docker compose restart subby-bank` so it picks up the new value.

5. **Run the dev frontends** outside compose:

   ```bash
   cd smartbank        && npm install && npm start      # CRA on :3000, proxies /api → :8080
   cd findoc-verify/webui && npm install && npm run dev # Vite on :5173, proxies /api → :8000
   ```

6. **Default admin user** is seeded on first Java boot from `ADMINUSER`/`ADMINPASSWORD`
   in `.env` (default `admin` / `admin`). Log in via smartbank → admin routes.

7. **MailHog UI** at <http://localhost:8025> captures every outbound Java email
   (KYC approved, loan approved, etc.) for smoke tests without real SMTP.

## 1. Run end-to-end locally

From the monorepo root:

```bash
docker compose up --build
```

What comes up:

| Service          | Port(s)       | Purpose                                              |
|------------------|---------------|------------------------------------------------------|
| localstack       | 4566          | SNS + SQS + S3 emulator                              |
| postgres         | 5433 → 5432   | Shared cluster, hosts `subbybank` + `findoc` DBs     |
| redis            | 6379          | Spring cache for SubbyBankbackend                    |
| subby-bank       | 8080          | Spring Boot app, profile `local`                     |
| findoc-verify    | 8000 → 8000   | FastAPI worker hub (ADC-mounted for Doc AI)          |
| subby-python-loan| 8002 → 8000   | Loan-risk ML service                                 |
| minio            | 9000, 9001    | Alt S3 backend (flip `S3_ENDPOINT_URL` to use it)    |
| mailhog          | 1025, 8025    | SMTP sink + web UI for captured mail                 |

Databases + owners (created by `infra/postgres-init.sql` at first boot):
* `subbybank` owned by role `subby`
* `findoc`    owned by role `findoc`
* superuser `postgres` / `postgres` for admin tasks

On startup LocalStack runs two init scripts in order:

1. `infra/localstack-init.sh` (this directory) — **shared** cross-service
   topics, queues, DLQs, subscriptions, and the `subby-documents` S3 bucket.
2. `findoc-verify/scripts/localstack-init.sh` — findoc-verify's internal
   pipeline topics/queues.

### Verify everything came up

```bash
# Topics (expect the 7 shared + 10 findoc internal = 17)
awslocal sns list-topics

# Queues (11 primary + 11 DLQs = 22 shared, plus findoc-verify's internal)
awslocal sqs list-queues

# Subscriptions with filter policies
awslocal sns list-subscriptions

# S3 buckets
awslocal s3 ls
# expect: subby-documents, findoc-documents

# Java readiness
curl -s http://localhost:8080/actuator/health/readiness

# Java Prometheus metrics (outbox + sqs.* custom metrics)
curl -s http://localhost:8080/actuator/prometheus | grep -E 'outbox|sqs'
```

### Switch to `awslocal` without installing

```bash
alias awslocal='aws --endpoint-url=http://localhost:4566 --region ap-south-1'
```

## 2. Switch to real AWS

No code changes. Set environment variables and flip the Spring profile.

### SubbyBankbackend

```bash
export SPRING_PROFILES_ACTIVE=aws
export AWS_REGION=ap-south-1
# Do NOT set AWS_ENDPOINT_URL — real AWS endpoints are resolved by region.
# Credentials come from the default AWS SDK chain:
#   - IAM role on EC2/ECS (preferred in prod)
#   - AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY (for dev machines)
#   - ~/.aws/credentials (for developer workstations)
export DATABASE_URL=jdbc:postgresql://<rds-host>:5432/subbybank
export POSTGRES_USER=...
export POSTGRES_PASSWORD=...
export REDIS_HOST=<elasticache-host>
export SUBBY_S3_BUCKET=subby-documents-prod
# ... remaining app secrets per SubbyBankbackend/src/main/resources/application-aws.yml
```

Confirm the SDK resolved real endpoints on startup — the log line from
`AwsConfig` will show no endpoint override.

### findoc-verify & SubbyPythonLoan

Same pattern: unset `AWS_ENDPOINT_URL`, set real AWS creds via IAM or env.

## 3. Provisioning AWS (one-time, per region)

The shared init script is the declarative truth. The `aws` CLI commands below
mirror it exactly — run them against real AWS (IAM policies and tags omitted
for brevity; fold into your IaC stack).

### SNS topics

```bash
for t in \
    findoc-loan-report-ready \
    findoc-kyc-report-ready \
    subby-kyc-events \
    subby-loan-events \
    subby-notifications \
    subby-risk-requested \
    subby-risk-result
do
  aws sns create-topic --name "$t"
done
```

### SQS queues + DLQs

For each primary queue (11 of them), create a matching `-dlq`, then set the
primary's `RedrivePolicy`:

```bash
for q in \
    subby-kyc-submitted subby-kyc-findoc-results subby-kyc-decision \
    subby-loan-submitted subby-loan-findoc-results subby-loan-risk-results subby-loan-decision \
    subby-risk-requests \
    subby-email-notify subby-sms-notify subby-audit-log
do
  aws sqs create-queue --queue-name "${q}-dlq"
  dlq_arn=$(aws sqs get-queue-attributes \
      --queue-url $(aws sqs get-queue-url --queue-name "${q}-dlq" --output text --query QueueUrl) \
      --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
  aws sqs create-queue --queue-name "$q" \
      --attributes "{
        \"VisibilityTimeout\": \"300\",
        \"MessageRetentionPeriod\": \"1209600\",
        \"ReceiveMessageWaitTimeSeconds\": \"20\",
        \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
      }"
done
```

### Topic → queue subscriptions

Follow the exact bindings in `infra/localstack-init.sh` (`BINDINGS` array).
Each `aws sns subscribe` call should set `RawMessageDelivery=true` and the
same `FilterPolicy` used locally.

### S3 bucket

```bash
aws s3api create-bucket --bucket subby-documents-prod \
    --region ap-south-1 \
    --create-bucket-configuration LocationConstraint=ap-south-1
aws s3api put-bucket-encryption --bucket subby-documents-prod --server-side-encryption-configuration \
    '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"aws:kms"}}]}'
aws s3api put-public-access-block --bucket subby-documents-prod \
    --public-access-block-configuration 'BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true'
```

### IAM policy snippets

SubbyBankbackend needs:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": ["sns:Publish", "sns:CreateTopic", "sns:GetTopicAttributes"],
      "Resource": "arn:aws:sns:ap-south-1:<acct>:subby-*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:ChangeMessageVisibility",
        "sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:SendMessage", "sqs:ListQueues"
      ],
      "Resource": "arn:aws:sqs:ap-south-1:<acct>:subby-*"
    },
    {
      "Effect": "Allow",
      "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::subby-documents-prod/*"
    }
  ]
}
```

findoc-verify needs read access to `subby-documents-prod/*` plus publish on
`findoc-*-report-ready`. SubbyPythonLoan needs consume on
`subby-risk-requests` and publish on `subby-risk-result`.

## 4. Topic naming convention

* `subby-*-events` — multi-type domain event streams (filter by `eventType` attribute)
* `subby-*-requested` / `subby-*-result` — request / reply pairs between services
* `subby-notifications` — fan-out: one publish, three consumers (email/sms/audit), filtered by `channel`
* `findoc-*-report-ready` — terminal per-use-case reports produced by findoc-verify

Queue names track their source: `subby-<flow>-<signal>`. DLQs append `-dlq`.

## 5. Monitoring

CloudWatch alarms to add in prod:

* `ApproximateNumberOfMessagesVisible > 0` on every `*-dlq` → page on-call
* `outbox.events.dead_letter` counter > 0 (Prometheus → Grafana) → page on-call
* `outbox.events.failed` rate spike → warn
* SNS `NumberOfNotificationsFailed` per topic → warn

Java exposes all custom metrics at `/actuator/prometheus`. Scrape interval 15s is
sufficient for the outbox polling cadence.

## 6. Correlation and tracing

Every HTTP request gets an `X-Correlation-Id` header (minted if missing, echoed
back). Messaging consumers read the `correlationId` field on the event envelope
and push it into SLF4J MDC. Use it to stitch a user action across service
boundaries in Loki / CloudWatch Logs Insights.
