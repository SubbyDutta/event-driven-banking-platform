#!/bin/bash
# Scenario D: replay stays rejected — no LoanRiskRequested published.
#
#   1. Sign up a fresh user and submit a loan with rejected-credit fixtures.
#   2. Wait until DOCS_REJECTED.
#   3. Snapshot risk-requested queue depth (sent total).
#   4. Trigger findoc replay WITHOUT correcting any field (so the replay yields
#      another reject).
#   5. Sleep long enough for the result to land + the consumer to process.
#   6. Assert lifecycleStatus is still DOCS_REJECTED.
#   7. Assert doc_reeval_result=REJECT and doc_reeval_run_number=2 in DB.
#   8. Assert risk-requested queue depth has NOT increased.
#
# Idempotent: per-user rows are truncated at the start.

set -euo pipefail
cd "$(dirname "$0")"

FIXTURES_DIR="${FIXTURES_DIR:-./fixtures/segments/2_rajat_rejected_credit}"
JAVA="${JAVA_BASE:-http://localhost:8080}"
FINDOC="${FINDOC_BASE:-http://localhost:8000}"
FINDOC_API_KEY="${FINDOC_API_KEY:?FINDOC_API_KEY required}"
USER="restill_$(date +%s)_$RANDOM"
EMAIL="${USER}@example.com"
PHONE="9$(printf '%09d' $((RANDOM * RANDOM % 1000000000)))"
PASS="Smoke@12345"

awslocal() {
  AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url=http://localhost:4566 --region ap-south-1 --no-cli-pager "$@"
}
psql_java() { docker compose -f ../docker-compose.yml exec -T postgres psql -U subby -d subbybank -At "$@"; }
queue_total() {
  awslocal sqs get-queue-attributes --queue-url "$1" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query 'Attributes.{a:ApproximateNumberOfMessages,b:ApproximateNumberOfMessagesNotVisible}' --output json \
    | python -c 'import json,sys; d=json.load(sys.stdin); print(int(d["a"])+int(d["b"]))'
}

echo "==> 0. Truncate prior smoke state for username=$USER"
psql_java -c "DELETE FROM loan_application WHERE username='$USER';" >/dev/null
psql_java -c "DELETE FROM users WHERE username='$USER';" >/dev/null

echo "==> 1. Signup + login + KYC + bank + loan submit"
curl -fsS -X POST "$JAVA/api/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"mobile\":\"$PHONE\",\"password\":\"$PASS\",\"firstname\":\"Re\",\"lastname\":\"Reject\",\"dob\":\"1995-05-15\"}" >/dev/null
TOKEN=$(curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" \
  | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or '')")

curl -fsS -X POST "$JAVA/api/kyc/submit" -H "Authorization: Bearer $TOKEN" \
  -F "aadhaar=@$FIXTURES_DIR/kyc/aadhaar.pdf" -F "pan=@$FIXTURES_DIR/kyc/pan.pdf" >/dev/null
curl -fsS -X POST "$JAVA/api/bank/account" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"type":"SAVINGS"}' >/dev/null

LOAN_APP_ID=$(curl -fsS -X POST "$JAVA/api/loans/apply" -H "Authorization: Bearer $TOKEN" \
  -F "amount=500000" -F "purpose=MEDICAL" -F "monthsRemaining=6" \
  -F "payslip1=@$FIXTURES_DIR/loan/payslip-01.pdf" \
  -F "payslip2=@$FIXTURES_DIR/loan/payslip-02.pdf" \
  -F "payslip3=@$FIXTURES_DIR/loan/payslip-03.pdf" \
  -F "bankStatement1=@$FIXTURES_DIR/loan/bank-statement-01.pdf" \
  -F "bankStatement2=@$FIXTURES_DIR/loan/bank-statement-02.pdf" \
  -F "bankStatement3=@$FIXTURES_DIR/loan/bank-statement-03.pdf" \
  -F "creditReport=@$FIXTURES_DIR/loan/credit-report.pdf" \
  -F "itr=@$FIXTURES_DIR/loan/itr.pdf" \
  -F "employmentLetter=@$FIXTURES_DIR/loan/employment-letter.pdf" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('loanAppId',''))")
echo "  loanAppId=$LOAN_APP_ID"

poll_status() {
  local target="$1"; local timeout="${2:-120}"
  for ((i=0; i<timeout; i++)); do
    s=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$JAVA/api/loans/$LOAN_APP_ID/status" \
        | python -c "import json,sys; print(json.load(sys.stdin).get('lifecycleStatus',''))")
    [ "$s" = "$target" ] && return 0
    sleep 1
  done
  echo "FAIL: lifecycleStatus did not reach $target (last=$s)"; return 1
}

echo "==> 2. Wait for DOCS_REJECTED"
poll_status DOCS_REJECTED 120

FINDOC_APP_ID=$(curl -fsS -H "X-API-Key: $FINDOC_API_KEY" \
  "$FINDOC/api/v1/admin/applications?external_id=$LOAN_APP_ID&page_size=1" \
  | python -c "import json,sys; d=json.load(sys.stdin); print((d.get('items') or [{}])[0].get('id',''))")

RISK_URL=$(awslocal sqs get-queue-url --queue-name subby-risk-requests --query QueueUrl --output text 2>/dev/null \
  || awslocal sqs get-queue-url --queue-name subby-loan-risk-requests --query QueueUrl --output text)
DEPTH_BEFORE=$(queue_total "$RISK_URL")
echo "  risk-requested depth before replay=$DEPTH_BEFORE"

echo "==> 3. Replay WITHOUT corrections — pipeline should still reject"
curl -fsS -X POST "$FINDOC/api/v1/admin/applications/$FINDOC_APP_ID/replay" \
  -H "X-API-Key: $FINDOC_API_KEY" -H 'Content-Type: application/json' \
  -d '{"reason":"smoke: replay-stays-rejected"}' >/dev/null

echo "==> 4. Wait for re-eval to land (poll doc_reeval_run_number)"
for ((i=0; i<60; i++)); do
  RUN=$(psql_java -c "SELECT COALESCE(doc_reeval_run_number, 0) FROM loan_application WHERE external_id='$LOAN_APP_ID';" | tr -d '[:space:]')
  [ "$RUN" = "2" ] && break
  sleep 2
done
[ "$RUN" = "2" ] || { echo "FAIL: doc_reeval_run_number != 2 (got=$RUN)"; exit 1; }

echo "==> 5. Assert lifecycle still DOCS_REJECTED, doc_reeval_result=REJECT"
LC=$(psql_java -c "SELECT lifecycle_status FROM loan_application WHERE external_id='$LOAN_APP_ID';" | tr -d '[:space:]')
RR=$(psql_java -c "SELECT doc_reeval_result FROM loan_application WHERE external_id='$LOAN_APP_ID';" | tr -d '[:space:]')
[ "$LC" = "DOCS_REJECTED" ] || { echo "FAIL: lifecycle changed to $LC"; exit 1; }
[ "$RR" = "REJECT" ] || { echo "FAIL: doc_reeval_result=$RR (expected REJECT)"; exit 1; }

echo "==> 6. Assert risk-requested queue depth unchanged"
DEPTH_AFTER=$(queue_total "$RISK_URL")
echo "  risk-requested depth after replay=$DEPTH_AFTER"
[ "$DEPTH_AFTER" = "$DEPTH_BEFORE" ] || {
  echo "FAIL: queue grew from $DEPTH_BEFORE to $DEPTH_AFTER — replay should NOT have triggered ML"
  exit 1
}

echo "PASS: smoke-replay-stays-rejected"
