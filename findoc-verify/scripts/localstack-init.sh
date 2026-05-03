#!/bin/bash
# Bootstraps LocalStack: S3 bucket, SNS topics, SQS queues (+ DLQs), redrive policies,
# topic->queue subscriptions. Idempotent.
set -euo pipefail

REGION="${DEFAULT_REGION:-ap-south-1}"
awslocal="awslocal --region $REGION"

# S3 is served by MinIO — no bucket creation here.

TOPICS=(
  findoc-doc-ocr-requested
  findoc-doc-ocr-completed
  findoc-doc-classified
  findoc-doc-extracted
  findoc-application-aggregated
  findoc-compliance-checked
  findoc-crossdoc-validated
  findoc-fraud-checked
  findoc-risk-scored
  findoc-loan-report-ready
  findoc-kyc-report-ready
)

# worker:queue:topic-it-subscribes-to
BINDINGS=(
  "ocr:findoc-ocr:findoc-doc-ocr-requested"
  "classify:findoc-classify:findoc-doc-ocr-completed"
  "extract:findoc-extract:findoc-doc-classified"
  "aggregate:findoc-aggregate:findoc-doc-extracted"
  "compliance:findoc-compliance:findoc-application-aggregated"
  "crossdoc:findoc-crossdoc:findoc-compliance-checked"
  "fraud:findoc-fraud:findoc-crossdoc-validated"
  "risk:findoc-risk:findoc-fraud-checked"
  "result:findoc-result:findoc-risk-scored"
)

echo "[init] Creating SNS topics"
declare -A TOPIC_ARN
for t in "${TOPICS[@]}"; do
  arn=$($awslocal sns create-topic --name "$t" --query 'TopicArn' --output text)
  TOPIC_ARN[$t]="$arn"
done

mkdir -p /tmp/lsinit

echo "[init] Creating SQS queues and subscriptions"
for entry in "${BINDINGS[@]}"; do
  IFS=':' read -r worker queue topic <<< "$entry"
  dlq="${queue}-dlq"

  dlq_url=$($awslocal sqs create-queue --queue-name "$dlq" --query 'QueueUrl' --output text)
  dlq_arn=$($awslocal sqs get-queue-attributes --queue-url "$dlq_url" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

  cat > /tmp/lsinit/attrs-$queue.json <<EOF
{
  "VisibilityTimeout": "300",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$dlq_arn\",\"maxReceiveCount\":\"3\"}"
}
EOF

  q_url=$($awslocal sqs create-queue --queue-name "$queue" \
    --attributes file:///tmp/lsinit/attrs-$queue.json \
    --query 'QueueUrl' --output text 2>/dev/null \
    || $awslocal sqs get-queue-url --queue-name "$queue" --query 'QueueUrl' --output text)

  q_arn=$($awslocal sqs get-queue-attributes --queue-url "$q_url" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

  topic_arn="${TOPIC_ARN[$topic]}"
  $awslocal sns subscribe --topic-arn "$topic_arn" --protocol sqs \
    --notification-endpoint "$q_arn" \
    --attributes RawMessageDelivery=true >/dev/null

  # Allow SNS to deliver to the queue
  cat > /tmp/lsinit/policy-$queue.json <<EOF
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$q_arn\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$topic_arn\"}}}]}"
}
EOF
  $awslocal sqs set-queue-attributes --queue-url "$q_url" \
    --attributes file:///tmp/lsinit/policy-$queue.json

  echo "[init]   worker=$worker queue=$queue  <-  topic=$topic"
done

echo "[init] Done."
