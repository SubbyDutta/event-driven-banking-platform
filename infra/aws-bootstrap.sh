#!/bin/bash
# =============================================================================
# Bootstraps real AWS SNS topics, SQS queues (+ DLQs), filter-policy bindings.
# Mirrors infra/localstack-init.sh + findoc-verify/scripts/localstack-init.sh
# byte-for-byte on names, so the apps need no env changes other than
# unsetting AWS_ENDPOINT_URL.
#
# Run ONCE from your laptop with admin AWS creds (after `aws configure`):
#
#   bash infra/aws-bootstrap.sh
#
# Idempotent — safe to re-run; existing resources are reused.
# =============================================================================
set -euo pipefail

REGION="${AWS_REGION:-ap-south-1}"
ACCT=$(aws sts get-caller-identity --query Account --output text)
AWS_CMD="aws --region $REGION"

TMP="$(mktemp -d)"
trap "rm -rf $TMP" EXIT

# On Git Bash / Windows, the AWS CLI is a native Windows binary and cannot
# resolve Unix-style /tmp/... paths. Translate via cygpath when present.
file_url() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    echo "file://$(cygpath -m "$p")"
  else
    echo "file://$p"
  fi
}

echo "[bootstrap] Account=$ACCT  Region=$REGION"

# -----------------------------------------------------------------------------
# 1. SNS topics — union of cross-service and findoc-verify intra-service.
# -----------------------------------------------------------------------------
TOPICS=(
  # cross-service
  findoc-loan-report-ready
  findoc-kyc-report-ready
  subby-kyc-events
  subby-loan-events
  subby-notifications
  subby-risk-requested
  subby-risk-result
  # findoc-verify intra-service pipeline
  findoc-doc-ocr-requested
  findoc-doc-ocr-completed
  findoc-doc-classified
  findoc-doc-extracted
  findoc-application-aggregated
  findoc-compliance-checked
  findoc-crossdoc-validated
  findoc-fraud-checked
  findoc-risk-scored
)

echo "[bootstrap] Creating ${#TOPICS[@]} SNS topics"
declare -A TOPIC_ARN
for t in "${TOPICS[@]}"; do
  arn=$($AWS_CMD sns create-topic --name "$t" --query 'TopicArn' --output text)
  TOPIC_ARN[$t]="$arn"
  echo "[bootstrap]   topic=$t"
done

# -----------------------------------------------------------------------------
# 2. SQS bindings: queue:topic:filter_policy_or_NONE
#    Order: cross-service first, then findoc-verify pipeline.
# -----------------------------------------------------------------------------
BINDINGS=(
  # Java KYC
  'subby-kyc-submitted:subby-kyc-events:{"eventType":["KycSubmitted"]}'
  'subby-kyc-findoc-results:findoc-kyc-report-ready:NONE'
  'subby-kyc-decision:subby-kyc-events:{"eventType":["KycDecisionMade"]}'

  # Java Loan
  'subby-loan-submitted:subby-loan-events:{"eventType":["LoanApplicationSubmitted"]}'
  'subby-loan-findoc-results:findoc-loan-report-ready:NONE'
  'subby-loan-risk-results:subby-risk-result:NONE'
  'subby-loan-decision:subby-loan-events:{"eventType":["LoanDecisionMade"]}'

  # SubbyPythonLoan
  'subby-risk-requests:subby-risk-requested:NONE'

  # Notifications fan-out
  'subby-email-notify:subby-notifications:{"eventType":["LoanFinalized"]}'
  'subby-sms-notify:subby-notifications:{"eventType":["LoanFinalized"]}'
  'subby-audit-log:subby-notifications:NONE'
  'subby-password-reset-email:subby-notifications:{"eventType":["PasswordResetRequested"]}'
  'subby-admin-loan-pending:subby-notifications:{"eventType":["LoanPendingAdminDecision"]}'
  'subby-admin-kyc-review:subby-notifications:{"eventType":["AdminKycReviewNeeded"]}'
  'subby-welcome-email:subby-notifications:{"eventType":["UserSignedUp"]}'
  'subby-transaction-email:subby-notifications:{"eventType":["TransactionPosted"]}'
  'subby-loan-disbursed-email:subby-notifications:{"eventType":["LoanDisbursed"]}'
  'subby-password-changed-email:subby-notifications:{"eventType":["PasswordChanged"]}'

  # findoc-verify pipeline
  'findoc-ocr:findoc-doc-ocr-requested:NONE'
  'findoc-classify:findoc-doc-ocr-completed:NONE'
  'findoc-extract:findoc-doc-classified:NONE'
  'findoc-aggregate:findoc-doc-extracted:NONE'
  'findoc-compliance:findoc-application-aggregated:NONE'
  'findoc-crossdoc:findoc-compliance-checked:NONE'
  'findoc-fraud:findoc-crossdoc-validated:NONE'
  'findoc-risk:findoc-fraud-checked:NONE'
  'findoc-result:findoc-risk-scored:NONE'
)

echo "[bootstrap] Creating ${#BINDINGS[@]} queues + DLQs + subscriptions"

for entry in "${BINDINGS[@]}"; do
  IFS=':' read -r queue topic filter <<< "$entry"
  dlq="${queue}-dlq"

  # ----- DLQ -----
  echo "[bootstrap] -> creating DLQ: $dlq"
  if ! dlq_url=$($AWS_CMD sqs create-queue --queue-name "$dlq" --query 'QueueUrl' --output text); then
    echo "[bootstrap] create-queue failed for $dlq, trying get-queue-url"
    dlq_url=$($AWS_CMD sqs get-queue-url --queue-name "$dlq" --query 'QueueUrl' --output text)
  fi
  dlq_arn=$($AWS_CMD sqs get-queue-attributes --queue-url "$dlq_url" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

  # ----- primary queue -----
  cat > "$TMP/attrs-$queue.json" <<EOF
{
  "VisibilityTimeout": "300",
  "MessageRetentionPeriod": "1209600",
  "ReceiveMessageWaitTimeSeconds": "5",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$dlq_arn\",\"maxReceiveCount\":\"3\"}"
}
EOF

  echo "[bootstrap] -> creating queue: $queue"
  if ! q_url=$($AWS_CMD sqs create-queue --queue-name "$queue" \
      --attributes "$(file_url "$TMP/attrs-$queue.json")" \
      --query 'QueueUrl' --output text); then
    echo "[bootstrap] create-queue failed for $queue, trying get-queue-url"
    q_url=$($AWS_CMD sqs get-queue-url --queue-name "$queue" --query 'QueueUrl' --output text)
  fi

  # If the queue pre-existed, push the same attributes anyway to keep them in sync.
  $AWS_CMD sqs set-queue-attributes --queue-url "$q_url" \
    --attributes "$(file_url "$TMP/attrs-$queue.json")" >/dev/null

  q_arn=$($AWS_CMD sqs get-queue-attributes --queue-url "$q_url" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

  topic_arn="${TOPIC_ARN[$topic]}"

  # ----- subscription -----
  if [ "$filter" = "NONE" ]; then
    sub_attrs='{"RawMessageDelivery":"true"}'
  else
    escaped=$(printf '%s' "$filter" | python -c 'import json,sys;print(json.dumps(sys.stdin.read()))')
    sub_attrs="{\"RawMessageDelivery\":\"true\",\"FilterPolicy\":$escaped}"
  fi

  echo "$sub_attrs" > "$TMP/sub-$queue.json"

  # Subscribe is idempotent only by (topic, protocol, endpoint). Calling it
  # again on an existing subscription returns the same SubscriptionArn.
  $AWS_CMD sns subscribe --topic-arn "$topic_arn" --protocol sqs \
    --notification-endpoint "$q_arn" \
    --attributes "$(file_url "$TMP/sub-$queue.json")" >/dev/null

  # ----- queue policy (allow SNS to deliver) -----
  cat > "$TMP/policy-$queue.json" <<EOF
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"Service\":\"sns.amazonaws.com\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$q_arn\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$topic_arn\"}}}]}"
}
EOF
  $AWS_CMD sqs set-queue-attributes --queue-url "$q_url" \
    --attributes "$(file_url "$TMP/policy-$queue.json")" >/dev/null

  echo "[bootstrap]   queue=$queue  <-  topic=$topic  filter=$filter"
done

echo "[bootstrap] Done."
echo "[bootstrap] Topics: ${#TOPICS[@]}  Queues: ${#BINDINGS[@]}  (each with -dlq sibling)"
