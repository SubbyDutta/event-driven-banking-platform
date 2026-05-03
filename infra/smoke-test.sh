#!/bin/bash
# Smoke test: prove the outbox → SNS → SQS round-trip works against the
# docker-compose stack. Requires `docker compose up` to be running.
#
# Steps:
#   1. Subscribe a throwaway queue to subby-kyc-events so we can observe publishes.
#   2. INSERT a row into outbox_events inside postgres-java.
#   3. Wait up to 10s for OutboxRelay to pick it up (poll interval ~= 1s).
#   4. Receive the message from the observer queue and verify the payload.
#   5. Confirm outbox_events.published_at was populated.
#   6. Run DLQ admin smoke: push a junk message to a DLQ, list, discard.
set -euo pipefail

# Prefer python3; fall back to python (Windows installs often only expose `python`).
if command -v python3 >/dev/null 2>&1; then
  PY=python3
elif command -v python >/dev/null 2>&1; then
  PY=python
else
  echo "FAIL: neither python3 nor python is installed on PATH"
  exit 1
fi

awslocal() {
  AWS_ACCESS_KEY_ID=${AWS_ACCESS_KEY_ID:-test} \
  AWS_SECRET_ACCESS_KEY=${AWS_SECRET_ACCESS_KEY:-test} \
  aws --endpoint-url=http://localhost:4566 --region ap-south-1 --no-cli-pager "$@"
}
psql_java() {
  docker compose exec -T postgres psql -U subby -d subbybank -At "$@"
}

echo "==> Preflight"
awslocal sns list-topics >/dev/null
psql_java -c 'SELECT 1' >/dev/null
echo "    LocalStack + postgres (subbybank DB) reachable."

echo "==> 1. Create observer queue and subscribe to subby-kyc-events"
OBS_QUEUE="smoke-observer-$(date +%s)"
OBS_URL=$(awslocal sqs create-queue --queue-name "$OBS_QUEUE" --query QueueUrl --output text)
OBS_ARN=$(awslocal sqs get-queue-attributes --queue-url "$OBS_URL" \
    --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)
TOPIC_ARN=$(awslocal sns create-topic --name subby-kyc-events --query TopicArn --output text)
awslocal sns subscribe --topic-arn "$TOPIC_ARN" --protocol sqs \
    --notification-endpoint "$OBS_ARN" \
    --attributes RawMessageDelivery=true >/dev/null
# Write attrs file in the current dir so both git-bash and the Windows AWS CLI resolve it.
TMP_ATTR="./.smoke-attr-$$.json"
trap 'rm -f "$TMP_ATTR"' EXIT
cat > "$TMP_ATTR" <<EOF
{
  "Policy": "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":\"*\"},\"Action\":\"sqs:SendMessage\",\"Resource\":\"$OBS_ARN\",\"Condition\":{\"ArnEquals\":{\"aws:SourceArn\":\"$TOPIC_ARN\"}}}]}"
}
EOF
awslocal sqs set-queue-attributes --queue-url "$OBS_URL" --attributes "file://$TMP_ATTR"
echo "    Observer queue ready: $OBS_QUEUE"

echo "==> 2. Insert a fake KycSubmitted row into outbox_events"
EVENT_ID=$("$PY" -c 'import uuid;print(uuid.uuid4())')
PAYLOAD=$(printf '{"eventId":"%s","eventType":"KycSubmitted","schemaVersion":1,"occurredAt":"2026-04-24T00:00:00Z","aggregateType":"kyc_application","aggregateId":"smoke-kyc-1","correlationId":"smoke-cid","data":{"user":"smoke-user"}}' "$EVENT_ID")
psql_java -c "INSERT INTO outbox_events (event_id, aggregate_type, aggregate_id, event_type, topic_name, correlation_id, schema_version, payload)
VALUES ('$EVENT_ID', 'kyc_application', 'smoke-kyc-1', 'KycSubmitted', 'subby-kyc-events', 'smoke-cid', 1, '$PAYLOAD'::jsonb);"
echo "    Inserted eventId=$EVENT_ID"

echo "==> 3. Wait for OutboxRelay to publish (up to 10s)"
received=""
for i in $(seq 1 10); do
  msg=$(awslocal sqs receive-message --queue-url "$OBS_URL" --max-number-of-messages 1 --wait-time-seconds 1 --output json 2>/dev/null || true)
  if echo "$msg" | grep -q '"Body"'; then
    received="$msg"
    break
  fi
done

if [ -z "$received" ]; then
  echo "FAIL: observer queue received no message within 10s"
  awslocal sqs delete-queue --queue-url "$OBS_URL" >/dev/null || true
  exit 1
fi

body=$(printf '%s' "$received" | "$PY" -c 'import json,sys;print(json.load(sys.stdin)["Messages"][0]["Body"])')
echo "$body" | "$PY" -c 'import json,sys; d=json.load(sys.stdin); assert d["eventId"]=="'"$EVENT_ID"'", d; print("    OK: received matching eventId")'

echo "==> 4. Verify outbox_events.published_at is set"
published=$(psql_java -c "SELECT published_at FROM outbox_events WHERE event_id='$EVENT_ID';")
if [ -z "$published" ] || [ "$published" = "null" ]; then
  echo "FAIL: published_at still NULL"
  exit 1
fi
echo "    OK: published_at=$published"

echo "==> 5. DLQ admin smoke"
DLQ_URL=$(awslocal sqs get-queue-url --queue-name subby-kyc-submitted-dlq --query QueueUrl --output text)
awslocal sqs send-message --queue-url "$DLQ_URL" \
    --message-body '{"smoke":"junk","eventId":"00000000-0000-0000-0000-deadbeefcafe"}' >/dev/null
echo "    Seeded junk message in subby-kyc-submitted-dlq"
count=$(awslocal sqs get-queue-attributes --queue-url "$DLQ_URL" \
    --attribute-names ApproximateNumberOfMessages \
    --query 'Attributes.ApproximateNumberOfMessages' --output text)
echo "    DLQ depth=$count"
# Drain it so the next smoke run starts clean.
awslocal sqs purge-queue --queue-url "$DLQ_URL" >/dev/null || true

echo "==> Cleanup"
awslocal sqs delete-queue --queue-url "$OBS_URL" >/dev/null
psql_java -c "DELETE FROM outbox_events WHERE event_id='$EVENT_ID';" >/dev/null

echo ""
echo "SUCCESS: outbox → SNS → SQS round-trip verified."
