#!/bin/bash
# Standalone-only bootstrap: subby-risk-requested topic → subby-risk-requests queue
# (+ DLQ), and subby-risk-result topic. The monorepo has a richer init in
# infra/localstack-init.sh; this script mirrors the subset relevant to this service.
set -euo pipefail

REGION="${DEFAULT_REGION:-ap-south-1}"
awslocal="awslocal --region $REGION"

mkdir -p /tmp/slinit

echo "[subby-loan-init] creating SNS topics"
req_arn=$($awslocal sns create-topic --name subby-risk-requested --query 'TopicArn' --output text)
res_arn=$($awslocal sns create-topic --name subby-risk-result    --query 'TopicArn' --output text)

echo "[subby-loan-init] creating DLQ + primary queue"
dlq_url=$($awslocal sqs create-queue --queue-name subby-risk-requests-dlq --query 'QueueUrl' --output text)
dlq_arn=$($awslocal sqs get-queue-attributes --queue-url "$dlq_url" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

cat > /tmp/slinit/attrs.json <<EOF
{
  "VisibilityTimeout": "60",
  "ReceiveMessageWaitTimeSeconds": "20",
  "RedrivePolicy": "{\"deadLetterTargetArn\":\"$dlq_arn\",\"maxReceiveCount\":\"3\"}"
}
EOF

q_url=$($awslocal sqs create-queue --queue-name subby-risk-requests \
  --attributes file:///tmp/slinit/attrs.json --query 'QueueUrl' --output text)
q_arn=$($awslocal sqs get-queue-attributes --queue-url "$q_url" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

echo "[subby-loan-init] subscribing queue to topic"
$awslocal sns subscribe --topic-arn "$req_arn" --protocol sqs \
  --notification-endpoint "$q_arn" \
  --attributes RawMessageDelivery=true >/dev/null

cat > /tmp/slinit/policy.json <<EOF
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$q_arn\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$req_arn\"}}}]}"
}
EOF
$awslocal sqs set-queue-attributes --queue-url "$q_url" --attributes file:///tmp/slinit/policy.json

# Self-subscribed queue so the standalone compose can smoke-test the result topic
# by reading from a local queue without Java being up.
smoke_url=$($awslocal sqs create-queue --queue-name subby-risk-result-smoke --query 'QueueUrl' --output text)
smoke_arn=$($awslocal sqs get-queue-attributes --queue-url "$smoke_url" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
$awslocal sns subscribe --topic-arn "$res_arn" --protocol sqs \
  --notification-endpoint "$smoke_arn" \
  --attributes RawMessageDelivery=true >/dev/null

cat > /tmp/slinit/smoke-policy.json <<EOF
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$smoke_arn\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$res_arn\"}}}]}"
}
EOF
$awslocal sqs set-queue-attributes --queue-url "$smoke_url" --attributes file:///tmp/slinit/smoke-policy.json

echo "[subby-loan-init] done"
