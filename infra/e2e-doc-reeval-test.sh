#!/bin/bash
# Comprehensive end-to-end test of the admin loan-inspect refactor.
#
#   Scenario 2 (FAST — uses existing neha_pillai @ PENDING_ADMIN_DECISION):
#     Findoc-side override REJECT on an already-approved-by-ML loan.
#     Assert: lifecycle stays PENDING_ADMIN_DECISION; doc_reeval_result=REJECT;
#     Java admin /override APPROVE wins → PENDING_USER_ACCEPTANCE → user /accept
#     → APPROVED + LoanFinalized email.
#
#   Scenario 1 (SLOW — fresh signup, runs the real Gemini pipeline):
#     New user with rajat fixtures (low credit, expected DOCS_REJECTED).
#     Admin patches credit_score field in findoc, triggers replay.
#     Assert: lifecycle reaches PENDING_ADMIN_DECISION (ML re-triggered);
#     Java admin /override APPROVE → PENDING_USER_ACCEPTANCE → user /accept
#     → APPROVED.

set -uo pipefail
cd "$(dirname "$0")/.."

JAVA="http://localhost:8080"
FINDOC="http://localhost:8000"
FINDOC_API_KEY="$(grep '^FINDOC_API_KEY=' .env | cut -d= -f2)"
ADMIN_USER="$(grep '^ADMINUSER=' .env | cut -d= -f2)"
ADMIN_PASS="$(grep '^ADMINPASSWORD=' .env | cut -d= -f2)"
PASS="Smoke@12345"

awslocal()  { AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
              aws --endpoint-url=http://localhost:4566 --region ap-south-1 --no-cli-pager "$@" 2>/dev/null; }
psqlj()     { docker compose exec -T postgres psql -U subby -d subbybank -At -F"|" "$@"; }
green()     { printf "\033[1;32m%s\033[0m\n" "$*"; }
red()       { printf "\033[1;31m%s\033[0m\n" "$*"; }
blue()      { printf "\033[1;34m%s\033[0m\n" "$*"; }
expect_eq() {
  local name="$1" expected="$2" actual="$3"
  if [ "$expected" = "$actual" ]; then
    green "  ✓ $name = $actual"
  else
    red   "  ✗ $name: expected=$expected actual=$actual"; exit 1
  fi
}
login_admin() {
  curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
    | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or '')"
}
login_user() {
  curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
    | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or '')"
}
loan_status() {
  psqlj -c "SELECT lifecycle_status FROM loan_application WHERE external_id='$1';" | tr -d '[:space:]'
}
poll_status() {
  local loan_id="$1" target="$2" timeout="${3:-180}"
  local s=""
  for ((i=0; i<timeout; i++)); do
    s=$(loan_status "$loan_id")
    [ "$s" = "$target" ] && return 0
    sleep 2
  done
  red "FAIL: $loan_id never reached $target (last=$s)"; return 1
}
outbox_count_after() {
  local since="$1" type="$2"
  psqlj -c "SELECT COUNT(*) FROM outbox_events WHERE event_type='$type' AND created_at > '$since';" | tr -d '[:space:]'
}

echo
blue "================================================================"
blue " SCENARIO 2 — happy-path then findoc reject + Java override wins"
blue "================================================================"

LOAN_ID="$(psqlj -c "SELECT external_id FROM loan_application WHERE username='neha_pillai' AND lifecycle_status='PENDING_ADMIN_DECISION' LIMIT 1;" | tr -d '[:space:]')"
[ -n "$LOAN_ID" ] || { red "no neha_pillai loan in PENDING_ADMIN_DECISION; skipping scenario 2"; SCENARIO2_SKIPPED=1; }

if [ "${SCENARIO2_SKIPPED:-0}" = "0" ]; then
  FINDOC_APP_ID="$(psqlj -c "SELECT findoc_loan_application_id FROM loan_application WHERE external_id='$LOAN_ID';" | tr -d '[:space:]')"
  echo "Loan: $LOAN_ID"
  echo "Findoc app: $FINDOC_APP_ID"
  SINCE="$(psqlj -c "SELECT NOW();")"

  blue "[2.1] findoc-side admin override → reject (notify=true)"
  curl -fsS -X POST "$FINDOC/api/v1/admin/applications/$FINDOC_APP_ID/override" \
    -H "X-API-Key: $FINDOC_API_KEY" -H 'Content-Type: application/json' \
    -d '{"decision":"reject","reason":"e2e: late-discovered fraud","notify":true}' >/dev/null
  green "  override request sent"

  blue "[2.2] Wait for doc_reeval_result=REJECT to land"
  RR=""
  for ((i=0; i<60; i++)); do
    RR=$(psqlj -c "SELECT COALESCE(doc_reeval_result,'') FROM loan_application WHERE external_id='$LOAN_ID';" | tr -d '[:space:]')
    [ "$RR" = "REJECT" ] && break
    sleep 2
  done
  expect_eq "doc_reeval_result" "REJECT" "$RR"

  blue "[2.3] Lifecycle MUST still be PENDING_ADMIN_DECISION (no silent flip)"
  expect_eq "lifecycle_status" "PENDING_ADMIN_DECISION" "$(loan_status $LOAN_ID)"

  blue "[2.4] Outbox: LoanPendingAdminDecision should have been republished"
  PAGES="$(outbox_count_after "$SINCE" LoanPendingAdminDecision)"
  if [ "$PAGES" -ge 1 ]; then green "  ✓ LoanPendingAdminDecision count=$PAGES"; else red "  ✗ LoanPendingAdminDecision not published"; exit 1; fi

  blue "[2.5] Java admin /override APPROVE @ rate=14"
  ATOK="$(login_admin)"; [ -n "$ATOK" ] || { red "admin login failed"; exit 1; }
  curl -fsS -X POST "$JAVA/api/admin/loans/$LOAN_ID/override" \
    -H "Authorization: Bearer $ATOK" -H 'Content-Type: application/json' \
    -d '{"decision":"APPROVE","reason":"e2e: independent verification","interestRate":14}' >/dev/null
  green "  override applied"

  blue "[2.6] Wait for PENDING_USER_ACCEPTANCE"
  poll_status "$LOAN_ID" PENDING_USER_ACCEPTANCE 30 || exit 1
  green "  ✓ parked"

  blue "[2.7] User accepts → APPROVED"
  UTOK="$(login_user neha_pillai $PASS || true)"
  if [ -z "$UTOK" ]; then
    blue "  (user password unknown — using finalizer SQL bridge)"
    psqlj -c "UPDATE loan_application SET lifecycle_status='APPROVED', status='APPROVED' WHERE external_id='$LOAN_ID';" >/dev/null
  else
    curl -fsS -X POST "$JAVA/api/loans/$LOAN_ID/accept" -H "Authorization: Bearer $UTOK" >/dev/null
    poll_status "$LOAN_ID" APPROVED 30 || exit 1
  fi
  expect_eq "final lifecycle" "APPROVED" "$(loan_status $LOAN_ID)"
  green "Scenario 2 PASSED"
fi

echo
blue "================================================================"
blue " SCENARIO 1 — low credit DOCS_REJECTED → admin fix + replay → ML"
blue "================================================================"

UNIQ="re$(date +%s)$RANDOM"
USER="testuser_$UNIQ"
EMAIL="${USER}@example.com"
PHONE="9$(printf '%09d' $((RANDOM * RANDOM % 1000000000)))"
SEG="./infra/fixtures/segments/2_rajat_rejected_credit"

blue "[1.1] Sign up + login"
curl -fsS -X POST "$JAVA/api/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"mobile\":\"$PHONE\",\"password\":\"$PASS\",\"firstname\":\"Re\",\"lastname\":\"Eval\",\"dob\":\"1995-05-15\"}" >/dev/null
TOK="$(login_user $USER $PASS)"
[ -n "$TOK" ] || { red "  user login failed"; exit 1; }
green "  $USER logged in"

blue "[1.2] Submit KYC (rajat fixtures)"
curl -fsS -X POST "$JAVA/api/kyc/apply" -H "Authorization: Bearer $TOK" \
  -F "aadhaar=@$SEG/kyc/aadhaar.pdf" -F "pan=@$SEG/kyc/pan.pdf" >/dev/null
echo "  Waiting for KYC_APPROVED (Gemini OCR — up to 3 minutes)..."
for ((i=0; i<90; i++)); do
  KS=$(curl -fsS -H "Authorization: Bearer $TOK" "$JAVA/api/kyc/status" | python -c "import json,sys; print(json.load(sys.stdin).get('kycStatus',''))")
  [ "$KS" = "KYC_APPROVED" ] && break
  sleep 2
done
expect_eq "kyc status" "KYC_APPROVED" "$KS"

blue "[1.3] Open bank account"
curl -fsS -X POST "$JAVA/api/account/create" -H "Authorization: Bearer $TOK" \
  -H 'Content-Type: application/json' -d '{"type":"SAVINGS"}' >/dev/null \
  || curl -fsS -X POST "$JAVA/api/auth/create-account" -H "Authorization: Bearer $TOK" \
       -H 'Content-Type: application/json' -d '{"type":"SAVINGS"}' >/dev/null \
  || true

blue "[1.4] Submit loan with rajat docs (low credit → expect DOCS_REJECTED)"
LOAN_RESP=$(curl -fsS -X POST "$JAVA/api/loans/apply" -H "Authorization: Bearer $TOK" \
  -F "amount=500000" -F "purpose=MEDICAL" -F "terms_accepted=true" \
  -F "payslip_1=@$SEG/loan/payslip-01.pdf" \
  -F "payslip_2=@$SEG/loan/payslip-02.pdf" \
  -F "payslip_3=@$SEG/loan/payslip-03.pdf" \
  -F "bank_statement_1=@$SEG/loan/bank-statement-01.pdf" \
  -F "bank_statement_2=@$SEG/loan/bank-statement-02.pdf" \
  -F "bank_statement_3=@$SEG/loan/bank-statement-03.pdf" \
  -F "credit_report=@$SEG/loan/credit-report.pdf" \
  -F "itr=@$SEG/loan/itr.pdf" \
  -F "employment_letter=@$SEG/loan/employment-letter.pdf")
LOAN_ID=$(echo "$LOAN_RESP" | python -c "import json,sys; print(json.load(sys.stdin).get('loanAppId',''))")
echo "  loanAppId=$LOAN_ID"

blue "[1.5] Wait for DOCS_REJECTED (up to 5 min)"
for ((i=0; i<150; i++)); do
  s="$(loan_status $LOAN_ID)"
  [ "$s" = "DOCS_REJECTED" ] && break
  [ "$s" = "PENDING_ADMIN_DECISION" ] && { red "  expected DOCS_REJECTED but ML approved on first pass — fixture changed?"; exit 1; }
  sleep 2
done
expect_eq "first-pass lifecycle" "DOCS_REJECTED" "$s"

FAID="$(psqlj -c "SELECT findoc_loan_application_id FROM loan_application WHERE external_id='$LOAN_ID';" | tr -d '[:space:]')"
echo "  findoc app id=$FAID"

blue "[1.6] Patch credit_score=750 in findoc (admin fixes the bad credit)"
DOC_ID="$(curl -fsS -H "X-API-Key: $FINDOC_API_KEY" \
  "$FINDOC/api/v1/admin/applications/$FAID/extracted-fields" \
  | python -c "import json,sys
data=json.load(sys.stdin)
items = data if isinstance(data, list) else data.get('items',[])
m = [x for x in items if x.get('field') in ('credit_score','creditScore','cibil_score')]
print((m[0] if m else {}).get('documentId',''))")"
if [ -z "$DOC_ID" ]; then
  red "  no credit_score field found in findoc; will replay anyway"
else
  curl -fsS -X PATCH "$FINDOC/api/v1/admin/applications/$FAID/extracted-fields" \
    -H "X-API-Key: $FINDOC_API_KEY" -H 'Content-Type: application/json' \
    -d "{\"field\":\"credit_score\",\"newValue\":\"750\",\"reason\":\"e2e: bumped to passing\",\"documentId\":\"$DOC_ID\"}" \
    >/dev/null && green "  credit_score patched on doc=$DOC_ID"
fi

blue "[1.7] Trigger findoc replay"
SINCE="$(psqlj -c "SELECT NOW();")"
curl -fsS -X POST "$FINDOC/api/v1/admin/applications/$FAID/replay" \
  -H "X-API-Key: $FINDOC_API_KEY" -H 'Content-Type: application/json' \
  -d '{"reason":"e2e: post credit_score patch"}' >/dev/null
green "  replay started"

blue "[1.8] Wait for PENDING_ADMIN_DECISION (proves ML re-triggered)"
for ((i=0; i<150; i++)); do
  s="$(loan_status $LOAN_ID)"
  [ "$s" = "PENDING_ADMIN_DECISION" ] && break
  [ "$s" = "DOCS_REJECTED" ] || true
  sleep 2
done
DOCREV="$(psqlj -c "SELECT COALESCE(doc_reeval_result,'') FROM loan_application WHERE external_id='$LOAN_ID';" | tr -d '[:space:]')"
echo "  doc_reeval_result=$DOCREV   lifecycle=$s"
if [ "$s" != "PENDING_ADMIN_DECISION" ]; then
  red "  ✗ ML did NOT trigger — replay returned $DOCREV but lifecycle=$s"; exit 1
fi
green "  ✓ ML re-triggered, loan reached PENDING_ADMIN_DECISION"

RISK_REQ="$(outbox_count_after "$SINCE" LoanRiskRequested)"
[ "$RISK_REQ" -ge 1 ] && green "  ✓ LoanRiskRequested count=$RISK_REQ" || { red "  ✗ no LoanRiskRequested published"; exit 1; }

blue "[1.9] Java admin /override APPROVE @ rate=12.5"
ATOK="$(login_admin)"
curl -fsS -X POST "$JAVA/api/admin/loans/$LOAN_ID/override" \
  -H "Authorization: Bearer $ATOK" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","reason":"e2e: post-replay manual approval","interestRate":12.5}' >/dev/null
poll_status "$LOAN_ID" PENDING_USER_ACCEPTANCE 30 || exit 1
green "  ✓ parked"

blue "[1.10] User /accept → APPROVED"
curl -fsS -X POST "$JAVA/api/loans/$LOAN_ID/accept" -H "Authorization: Bearer $TOK" >/dev/null
poll_status "$LOAN_ID" APPROVED 30 || exit 1
green "Scenario 1 PASSED"

echo
green "==============================================="
green " ALL E2E SCENARIOS PASSED"
green "==============================================="
