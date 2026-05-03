#!/bin/bash
# Scenario F: reverse — approved-then-re-rejected → admin /override APPROVE wins.
#
#   1. Sign up a fresh user, KYC + bank, submit a loan with the approved-segment
#      fixtures. Wait until lifecycleStatus = PENDING_ADMIN_DECISION (ML
#      approved, awaiting maker-checker).
#   2. Snapshot doc_reeval_* (should all be NULL at this point).
#   3. Trigger findoc-verify admin override → recommendation=reject (i.e. an
#      operator decided the docs don't actually pass). This emits a
#      FindocLoanReportReady with overridden=true.
#   4. Assert lifecycleStatus stays PENDING_ADMIN_DECISION (NOT silently
#      flipped) and doc_reeval_result=REJECT in DB.
#   5. Admin /override with decision=APPROVE + interestRate=15 wins:
#      lifecycleStatus → APPROVED.
#
# Idempotent: per-user rows are truncated at the start.

set -euo pipefail
cd "$(dirname "$0")"

FIXTURES_DIR="${FIXTURES_DIR:-./fixtures/segments/1_subham_approved}"
JAVA="${JAVA_BASE:-http://localhost:8080}"
FINDOC="${FINDOC_BASE:-http://localhost:8000}"
FINDOC_API_KEY="${FINDOC_API_KEY:?FINDOC_API_KEY required}"
ADMIN_USER="${ADMIN_USER:-admin}"
ADMIN_PASS="${ADMIN_PASS:-Admin@12345}"
USER="reverse_$(date +%s)_$RANDOM"
EMAIL="${USER}@example.com"
PHONE="9$(printf '%09d' $((RANDOM * RANDOM % 1000000000)))"
PASS="Smoke@12345"

psql_java() { docker compose -f ../docker-compose.yml exec -T postgres psql -U subby -d subbybank -At "$@"; }

echo "==> 0. Truncate prior smoke state for username=$USER"
psql_java -c "DELETE FROM loan_application WHERE username='$USER';" >/dev/null
psql_java -c "DELETE FROM users WHERE username='$USER';" >/dev/null

echo "==> 1. Signup, KYC, bank, loan submit"
curl -fsS -X POST "$JAVA/api/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"mobile\":\"$PHONE\",\"password\":\"$PASS\",\"firstname\":\"Re\",\"lastname\":\"Verse\",\"dob\":\"1995-05-15\"}" >/dev/null
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
  local target="$1"; local timeout="${2:-180}"
  for ((i=0; i<timeout; i++)); do
    s=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$JAVA/api/loans/$LOAN_APP_ID/status" \
        | python -c "import json,sys; print(json.load(sys.stdin).get('lifecycleStatus',''))")
    [ "$s" = "$target" ] && return 0
    sleep 1
  done
  echo "FAIL: lifecycleStatus did not reach $target (last=$s)"; return 1
}

echo "==> 2. Wait for PENDING_ADMIN_DECISION (ML approve)"
poll_status PENDING_ADMIN_DECISION 240

FINDOC_APP_ID=$(curl -fsS -H "X-API-Key: $FINDOC_API_KEY" \
  "$FINDOC/api/v1/admin/applications?external_id=$LOAN_APP_ID&page_size=1" \
  | python -c "import json,sys; d=json.load(sys.stdin); print((d.get('items') or [{}])[0].get('id',''))")

echo "==> 3. findoc-verify admin override → reject (notify=true so the new event fires)"
curl -fsS -X POST "$FINDOC/api/v1/admin/applications/$FINDOC_APP_ID/override" \
  -H "X-API-Key: $FINDOC_API_KEY" -H 'Content-Type: application/json' \
  -d '{"decision":"reject","reason":"smoke: late-discovered fraud","notify":true}' >/dev/null

echo "==> 4. Wait for doc_reeval_result=REJECT to land"
for ((i=0; i<60; i++)); do
  RR=$(psql_java -c "SELECT COALESCE(doc_reeval_result,'') FROM loan_application WHERE external_id='$LOAN_APP_ID';" | tr -d '[:space:]')
  [ "$RR" = "REJECT" ] && break
  sleep 2
done
[ "$RR" = "REJECT" ] || { echo "FAIL: doc_reeval_result != REJECT (got=$RR)"; exit 1; }

echo "==> 5. Assert lifecycle is STILL PENDING_ADMIN_DECISION"
LC=$(psql_java -c "SELECT lifecycle_status FROM loan_application WHERE external_id='$LOAN_APP_ID';" | tr -d '[:space:]')
[ "$LC" = "PENDING_ADMIN_DECISION" ] || {
  echo "FAIL: lifecycle silently changed to $LC after re-eval — invariant broken"
  exit 1
}

echo "==> 6. Admin login + /override APPROVE @ rate=15"
ADMIN_TOKEN=$(curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or '')")
[ -n "$ADMIN_TOKEN" ] || { echo "FAIL: admin login failed"; exit 1; }

curl -fsS -X POST "$JAVA/api/admin/loans/$LOAN_APP_ID/override" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","reason":"smoke: override doc-reeval reject","interestRate":15}' >/dev/null

echo "==> 7. Wait for PENDING_USER_ACCEPTANCE (admin parked the offer)"
poll_status PENDING_USER_ACCEPTANCE 60

echo "==> 8. User accepts the offer → APPROVED + disbursement"
curl -fsS -X POST "$JAVA/api/loans/$LOAN_APP_ID/accept" \
  -H "Authorization: Bearer $TOKEN" >/dev/null
poll_status APPROVED 60

echo "PASS: smoke-reverse-reject-then-override"
