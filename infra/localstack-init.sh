#!/bin/bash
# Shared infra bootstrap for LocalStack.
#
# Provisions cross-service SNS topics, SQS queues (+ DLQs), redrive policies,
# topic -> queue subscriptions (with filter policies), and S3 buckets.
#
# Runs first (filename prefix 00-); findoc-verify's internal init runs after (prefix 10-).
# Idempotent: reruns on the same LocalStack produce no changes.
set -euo pipefail

REGION="${DEFAULT_REGION:-ap-south-1}"
awslocal="awslocal --region $REGION"

mkdir -p /tmp/subby-init

# -----------------------------------------------------------------------------
# 1. S3 buckets
# -----------------------------------------------------------------------------
echo "[subby-init] S3 buckets"
for bucket in subby-documents findoc-documents; do
  $awslocal s3api create-bucket --bucket "$bucket" \
    --create-bucket-configuration LocationConstraint="$REGION" >/dev/null 2>&1 || true
done

# Allow any principal in LocalStack to GetObject on subby-documents (permissive for dev;
# prod swap uses an IAM policy scoped to findoc-verify's role — see infra/README.md).
cat > /tmp/subby-init/subby-documents-policy.json <<'EOF'
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "AllowFindocVerifyRead",
    "Effect": "Allow",
    "Principal": {"AWS": "*"},
    "Action": ["s3:GetObject"],
    "Resource": "arn:aws:s3:::subby-documents/*"
  }]
}
EOF
$awslocal s3api put-bucket-policy --bucket subby-documents \
  --policy file:///tmp/subby-init/subby-documents-policy.json >/dev/null 2>&1 || true

# -----------------------------------------------------------------------------
# 2. SNS topics (cross-service). findoc-verify's intra-service topics are
#    created by findoc-verify/scripts/localstack-init.sh which runs next.
# -----------------------------------------------------------------------------
TOPICS=(
  # findoc-verify -> Java: terminal per-use-case reports
  findoc-loan-report-ready
  findoc-kyc-report-ready
  # Java-owned
  subby-kyc-events
  subby-loan-events
  subby-notifications
  subby-risk-requested
  # SubbyPythonLoan-owned
  subby-risk-result
)

echo "[subby-init] SNS topics"
declare -A TOPIC_ARN
for t in "${TOPICS[@]}"; do
  arn=$($awslocal sns create-topic --name "$t" --query 'TopicArn' --output text)
  TOPIC_ARN[$t]="$arn"
done

# -----------------------------------------------------------------------------
# 3. SQS queues + DLQs. Each entry:
#     queue:topic:filter_policy_json_or_NONE
#    Every primary queue gets a paired -dlq with maxReceiveCount=3.
# -----------------------------------------------------------------------------
BINDINGS=(
  # Java KYC
  'subby-kyc-submitted:subby-kyc-events:{"eventType":["KycSubmitted"]}'
  'subby-kyc-findoc-results:findoc-kyc-report-ready:NONE'
  'subby-kyc-decision:subby-kyc-events:{"eventType":["KycDecisionMade"]}'

  # Java Loan (V4 — event class is LoanApplicationSubmitted, not LoanSubmitted)
  'subby-loan-submitted:subby-loan-events:{"eventType":["LoanApplicationSubmitted"]}'
  'subby-loan-findoc-results:findoc-loan-report-ready:NONE'
  'subby-loan-risk-results:subby-risk-result:NONE'
  'subby-loan-decision:subby-loan-events:{"eventType":["LoanDecisionMade"]}'

  # SubbyPythonLoan
  'subby-risk-requests:subby-risk-requested:NONE'

  # Fan-out notifications. OutboxRelay only sets the standard envelope
  # attributes (eventType, eventId, schemaVersion, correlationId,
  # aggregateType) — NOT a "channel" attribute — so the filters here must
  # match on eventType. LoanFinalized drives the three loan emails; add
  # further terminal events to the whitelist as new flows come online.
  'subby-email-notify:subby-notifications:{"eventType":["LoanFinalized"]}'
  'subby-sms-notify:subby-notifications:{"eventType":["LoanFinalized"]}'
  'subby-audit-log:subby-notifications:NONE'

  # Per-eventType sibling queues. Each new consumer (PasswordResetEmailConsumer,
  # AdminLoanPendingConsumer, AdminKycReviewConsumer) drains its own queue so the
  # existing LoanEmailNotificationConsumer (typed to LoanFinalized) is not asked
  # to deserialize an event class it doesn't know.
  'subby-password-reset-email:subby-notifications:{"eventType":["PasswordResetRequested"]}'
  'subby-admin-loan-pending:subby-notifications:{"eventType":["LoanPendingAdminDecision"]}'
  'subby-admin-kyc-review:subby-notifications:{"eventType":["AdminKycReviewNeeded"]}'
  'subby-welcome-email:subby-notifications:{"eventType":["UserSignedUp"]}'
  'subby-transaction-email:subby-notifications:{"eventType":["TransactionPosted"]}'
  'subby-loan-disbursed-email:subby-notifications:{"eventType":["LoanDisbursed"]}'
  'subby-password-changed-email:subby-notifications:{"eventType":["PasswordChanged"]}'
)

echo "[subby-init] SQS queues + subscriptions"
for entry in "${BINDINGS[@]}"; do
  IFS=':' read -r queue topic filter <<< "$entry"
  dlq="${queue}-dlq"

  # Create DLQ (plain, no redrive)
  dlq_url=$($awslocal sqs create-queue --queue-name "$dlq" \
    --query 'QueueUrl' --output text 2>/dev/null \
    || $awslocal sqs get-queue-url --queue-name "$dlq" --query 'QueueUrl' --output text)
  dlq_arn=$($awslocal sqs get-queue-attributes --queue-url "$dlq_url" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

  # Primary queue attributes:
  #   VisibilityTimeout 300s      — room for findoc callbacks / slow consumers
  #   MessageRetentionPeriod max  — 14 days
  #   ReceiveMessageWaitTimeSeconds 5 — long-poll, MUST stay < Java SDK
  #     ApiCallAttemptTimeout (10s) so Spring Cloud AWS pollers don't fire
  #     a timeout before the long-poll naturally returns. LocalStack 4.x
  #     enforces the wait time strictly; 3.8 did not.
  #   RedrivePolicy               — move to DLQ after 3 receives
  cat > /tmp/subby-init/attrs-$queue.json <<EOF
{
  "VisibilityTimeout": "300",
  "MessageRetentionPeriod": "1209600",
  "ReceiveMessageWaitTimeSeconds": "5",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$dlq_arn\",\"maxReceiveCount\":\"3\"}"
}
EOF

  q_url=$($awslocal sqs create-queue --queue-name "$queue" \
    --attributes file:///tmp/subby-init/attrs-$queue.json \
    --query 'QueueUrl' --output text 2>/dev/null \
    || $awslocal sqs get-queue-url --queue-name "$queue" --query 'QueueUrl' --output text)
  q_arn=$($awslocal sqs get-queue-attributes --queue-url "$q_url" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

  topic_arn="${TOPIC_ARN[$topic]}"

  # Subscribe (RawMessageDelivery=true — filter policies still work on message attributes)
  sub_attrs='{"RawMessageDelivery":"true"}'
  if [ "$filter" != "NONE" ]; then
    # Escape the filter JSON for embedding in subscribe-attributes
    escaped=$(printf '%s' "$filter" | python3 -c 'import json,sys; print(json.dumps(sys.stdin.read()))')
    sub_attrs="{\"RawMessageDelivery\":\"true\",\"FilterPolicy\":$escaped}"
  fi

  cat > /tmp/subby-init/sub-$queue.json <<EOF
$sub_attrs
EOF

  $awslocal sns subscribe --topic-arn "$topic_arn" --protocol sqs \
    --notification-endpoint "$q_arn" \
    --attributes file:///tmp/subby-init/sub-$queue.json >/dev/null

  # Allow SNS topic to deliver to this queue
  cat > /tmp/subby-init/policy-$queue.json <<EOF
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$q_arn\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$topic_arn\"}}}]}"
}
EOF
  $awslocal sqs set-queue-attributes --queue-url "$q_url" \
    --attributes file:///tmp/subby-init/policy-$queue.json

  echo "[subby-init]   queue=$queue  <-  topic=$topic  filter=$filter"
done

echo "[subby-init] Shared infra ready:"
echo "[subby-init]   topics=${#TOPICS[@]}  bindings=${#BINDINGS[@]}"
echo "[subby-init] Done."
