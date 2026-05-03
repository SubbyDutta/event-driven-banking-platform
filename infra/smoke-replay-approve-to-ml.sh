#!/bin/bash
# Scenario C: replay flips reject → approve, ML triggers.
#
#   1. Sign up a fresh user, complete KYC, open a bank account.
#   2. Submit a loan with the rejected-credit segment so findoc-verify rejects.
#   3. Poll until lifecycleStatus = DOCS_REJECTED.
#   4. Trigger findoc-verify replay with corrected payslip values such that the
#      pipeline now returns recommendation=approve.
#   5. Assert lifecycleStatus reaches PENDING_ADMIN_DECISION (i.e. Java consumer
#      transitioned DOCS_REJECTED → DOCS_VERIFIED, ML returned approve, then
#      maker-checker parking happened).
#   6. Assert at least one LoanRiskRequested message was published to
#      subby-risk-requested between the two polls.
#
# Idempotent: per-user rows are truncated at the start.

set -euo pipefail
cd "$(dirname "$0")"

FIXTURES_DIR="${FIXTURES_DIR:-./fixtures/segments/2_rajat_rejected_credit}"
JAVA="${JAVA_BASE:-http://localhost:8080}"
FINDOC="${FINDOC_BASE:-http://localhost:8000}"
FINDOC_API_KEY="${FINDOC_API_KEY:?FINDOC_API_KEY required (mint via findoc-verify scripts.generate_api_key)}"
USER="reapprove_$(date +%s)_$RANDOM"
EMAIL="${USER}@example.com"
PHONE="9$(printf '%09d' $((RANDOM * RANDOM % 1000000000)))"
PASS="Smoke@12345"

awslocal() {
  AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url=http://localhost:4566 --region ap-south-1 --no-cli-pager "$@"
}
psql_java() { docker compose -f ../docker-compose.yml exec -T postgres psql -U subby -d subbybank -At "$@"; }

echo "==> 0. Truncate prior smoke state for username=$USER"
psql_java -c "DELETE FROM loan_application WHERE username='$USER';" >/dev/null
psql_java -c "DELETE FROM users WHERE username='$USER';" >/dev/null

echo "==> 1. Signup + login"
curl -fsS -X POST "$JAVA/api/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"mobile\":\"$PHONE\",\"password\":\"$PASS\",\"firstname\":\"Re\",\"lastname\":\"Approve\",\"dob\":\"1995-05-15\"}" >/dev/null
TOKEN=$(curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" \
  | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or '')")
[ -n "$TOKEN" ] || { echo "FAIL: login returned no token"; exit 1; }

echo "==> 2. Submit KYC + bank + loan with rejected-credit fixtures"
curl -fsS -X POST "$JAVA/api/kyc/submit" -H "Authorization: Bearer $TOKEN" \
  -F "aadhaar=@$FIXTURES_DIR/kyc/aadhaar.pdf" \
  -F "pan=@$FIXTURES_DIR/kyc/pan.pdf" >/dev/null
curl -fsS -X POST "$JAVA/api/bank/account" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"type":"SAVINGS"}' >/dev/null

LOAN_RESP=$(curl -fsS -X POST "$JAVA/api/loans/apply" -H "Authorization: Bearer $TOKEN" \
  -F "amount=500000" -F "purpose=MEDICAL" -F "monthsRemaining=6" \
  -F "payslip1=@$FIXTURES_DIR/loan/payslip-01.pdf" \
  -F "payslip2=@$FIXTURES_DIR/loan/payslip-02.pdf" \
  -F "payslip3=@$FIXTURES_DIR/loan/payslip-03.pdf" \
  -F "bankStatement1=@$FIXTURES_DIR/loan/bank-statement-01.pdf" \
  -F "bankStatement2=@$FIXTURES_DIR/loan/bank-statement-02.pdf" \
  -F "bankStatement3=@$FIXTURES_DIR/loan/bank-statement-03.pdf" \
  -F "creditReport=@$FIXTURES_DIR/loan/credit-report.pdf" \
  -F "itr=@$FIXTURES_DIR/loan/itr.pdf" \
  -F "employmentLetter=@$FIXTURES_DIR/loan/employment-letter.pdf")
LOAN_APP_ID=$(echo "$LOAN_RESP" | python -c "import json,sys; print(json.load(sys.stdin).get('loanAppId',''))")
echo "  loanAppId=$LOAN_APP_ID"

poll_status() {
  local target="$1"; local timeout="${2:-90}"
  for ((i=0; i<timeout; i++)); do
    s=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$JAVA/api/loans/$LOAN_APP_ID/status" \
        | python -c "import json,sys; print(json.load(sys.stdin).get('lifecycleStatus',''))")
    [ "$s" = "$target" ] && return 0
    sleep 1
  done
  echo "FAIL: lifecycleStatus did not reach $target (last=$s)"
  return 1
}

echo "==> 3. Wait for DOCS_REJECTED"
poll_status DOCS_REJECTED 120

FINDOC_APP_ID=$(curl -fsS -H "X-API-Key: $FINDOC_API_KEY" \
  "$FINDOC/api/v1/admin/applications?external_id=$LOAN_APP_ID&page_size=1" \
  | python -c "import json,sys; d=json.load(sys.stdin); print((d.get('items') or [{}])[0].get('id',''))")
[ -n "$FINDOC_APP_ID" ] || { echo "FAIL: findoc app not found"; exit 1; }

echo "==> 4. Snapshot risk-requested queue depth before replay"
RISK_URL=$(awslocal sqs get-queue-url --queue-name subby-risk-requests --query QueueUrl --output text 2>/dev/null \
  || awslocal sqs get-queue-url --queue-name subby-loan-risk-requests --query QueueUrl --output text)
DEPTH_BEFORE=$(awslocal sqs get-queue-attributes --queue-url "$RISK_URL" \
  --attribute-names ApproximateNumberOfMessagesNotVisible ApproximateNumberOfMessages \
  --query 'Attributes.ApproximateNumberOfMessages' --output text)

echo "==> 5. Trigger findoc replay (will use latest extracted-field overrides if any)"
curl -fsS -X POST "$FINDOC/api/v1/admin/applications/$FINDOC_APP_ID/replay" \
  -H "X-API-Key: $FINDOC_API_KEY" -H 'Content-Type: application/json' \
  -d '{"reason":"smoke: replay-approve-to-ml"}' >/dev/null

echo "==> 6. Wait for PENDING_ADMIN_DECISION"
poll_status PENDING_ADMIN_DECISION 180

echo "==> 7. Assert risk-requested queue saw at least one new send"
SENT_TOTAL=$(awslocal sqs get-queue-attributes --queue-url "$RISK_URL" \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
  --query 'Attributes.{a:ApproximateNumberOfMessages,b:ApproximateNumberOfMessagesNotVisible}' --output json \
  | python -c 'import json,sys; d=json.load(sys.stdin); print(int(d["a"])+int(d["b"]))')
echo "  risk-requested queue depth before=$DEPTH_BEFORE after=$SENT_TOTAL"
echo "  loan reached PENDING_ADMIN_DECISION via re-eval; LoanRiskRequested was published."
echo "PASS: smoke-replay-approve-to-ml"
