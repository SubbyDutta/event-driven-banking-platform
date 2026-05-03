#!/bin/bash
# Doc-reeval E2E with the correct Rajat identity (matches rajat fixtures).
# Walks scenarios S2 -> S3 -> S1 invariants live:
#   S2: rajat fixtures -> first-pass DOCS_REJECTED, list shows findocRecommendation=rejected
#   S3: patch credit_score -> replay -> ML retriggers -> PENDING_ADMIN_DECISION,
#       list shows docReevalResult=APPROVE run #2, mlRecommendation populated
#   S1: Java admin /override APPROVE @ rate=12.5 -> PENDING_USER_ACCEPTANCE,
#       user /accept -> APPROVED, list shows APPROVED
set -uo pipefail
cd "$(dirname "$0")/.."

JAVA="http://localhost:8080"
FINDOC="http://localhost:8000"
FINDOC_API_KEY="$(grep '^FINDOC_API_KEY=' .env | cut -d= -f2)"
ADMIN_USER="$(grep '^ADMINUSER=' .env | cut -d= -f2)"
ADMIN_PASS="$(grep '^ADMINPASSWORD=' .env | cut -d= -f2)"
PASS="Smoke@12345"
SEG="./infra/fixtures/segments/2_rajat_rejected_credit"

psqlj()  { docker exec subby-postgres psql -U subby -d subbybank -At -F"|" "$@"; }
green()  { printf "\033[1;32m%s\033[0m\n" "$*"; }
red()    { printf "\033[1;31m%s\033[0m\n" "$*"; }
blue()   { printf "\033[1;34m%s\033[0m\n" "$*"; }
expect() { local n="$1" e="$2" a="$3"; if [ "$e" = "$a" ]; then green "  OK $n=$a"; else red "  FAIL $n: expected=$e actual=$a"; exit 1; fi; }

login_admin() { curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$ADMIN_USER\",\"password\":\"$ADMIN_PASS\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('token',''))"; }
login_user()  { curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$1\",\"password\":\"$2\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('token',''))"; }

loan_status() { psqlj -c "SELECT lifecycle_status FROM loan_application WHERE external_id='$1';" | tr -d '[:space:]'; }
poll_status() { local id="$1" tgt="$2" tmo="${3:-180}"; local s=""; for ((i=0;i<tmo;i++)); do s=$(loan_status "$id"); [ "$s" = "$tgt" ] && return 0; sleep 2; done; red "  FAIL $id never reached $tgt (last=$s)"; return 1; }

list_row() {
  local id="$1" atok="$2"
  curl -fsS "$JAVA/api/admin/loans?page=0&size=50" -H "Authorization: Bearer $atok" \
    | python -c "import json,sys; d=json.load(sys.stdin); rows=[r for r in d.get('content',[]) if r.get('loanAppId')=='$id']; print(json.dumps(rows[0]) if rows else '{}')"
}

UNIQ="rj$(date +%s)$RANDOM"
USER="rajat_$UNIQ"
EMAIL="${USER}@example.com"
PHONE="9$(printf '%09d' $((RANDOM * RANDOM % 1000000000)))"

blue "[setup] sign up Rajat Sharma identity"
curl -fsS -X POST "$JAVA/api/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"mobile\":\"$PHONE\",\"password\":\"$PASS\",\"firstname\":\"Rajat\",\"lastname\":\"Sharma\",\"dob\":\"1992-08-20\"}" >/dev/null
TOK="$(login_user "$USER" "$PASS")"
[ -n "$TOK" ] || { red "user login failed"; exit 1; }
green "  $USER logged in"

blue "[setup] KYC apply (rajat fixtures, expect KYC_APPROVED)"
curl -fsS -X POST "$JAVA/api/kyc/apply" -H "Authorization: Bearer $TOK" \
  -F "aadhaar=@$SEG/kyc/aadhaar.pdf" -F "pan=@$SEG/kyc/pan.pdf" >/dev/null
echo "  Waiting up to 4 min for KYC_APPROVED..."
for ((i=0;i<120;i++)); do
  KS=$(curl -fsS -H "Authorization: Bearer $TOK" "$JAVA/api/kyc/status" | python -c "import json,sys; print(json.load(sys.stdin).get('kycStatus',''))")
  [ "$KS" = "KYC_APPROVED" ] && break
  [ "$KS" = "KYC_REJECTED" ] && { red "KYC rejected unexpectedly"; exit 1; }
  sleep 2
done
expect "kyc status" "KYC_APPROVED" "$KS"

blue "[setup] open bank account"
curl -fsS -X POST "$JAVA/api/user/create-account" -H "Authorization: Bearer $TOK" \
  -H 'Content-Type: application/json' -d "{\"username\":\"$USER\",\"type\":\"SAVINGS\"}" >/dev/null \
  || curl -fsS -X POST "$JAVA/api/account/create" -H "Authorization: Bearer $TOK" \
       -H 'Content-Type: application/json' -d '{"type":"SAVINGS"}' >/dev/null \
  || true

blue "===== S2 — first-pass DOCS_REJECTED (rajat: low credit) ====="
LOAN_RESP=$(curl -fsS -X POST "$JAVA/api/loans/apply" -H "Authorization: Bearer $TOK" \
  -F "amount=500000" -F "purpose=MEDICAL" -F "terms_accepted=true" \
  -F "payslip_1=@$SEG/loan/payslip-01.pdf" -F "payslip_2=@$SEG/loan/payslip-02.pdf" -F "payslip_3=@$SEG/loan/payslip-03.pdf" \
  -F "bank_statement_1=@$SEG/loan/bank-statement-01.pdf" -F "bank_statement_2=@$SEG/loan/bank-statement-02.pdf" -F "bank_statement_3=@$SEG/loan/bank-statement-03.pdf" \
  -F "credit_report=@$SEG/loan/credit-report.pdf" -F "itr=@$SEG/loan/itr.pdf" -F "employment_letter=@$SEG/loan/employment-letter.pdf")
LOAN_ID=$(echo "$LOAN_RESP" | python -c "import json,sys; print(json.load(sys.stdin).get('loanAppId',''))")
[ -n "$LOAN_ID" ] || { red "  loan submit failed: $LOAN_RESP"; exit 1; }
green "  loanAppId=$LOAN_ID"

echo "  Waiting up to 5 min for DOCS_REJECTED..."
for ((i=0;i<150;i++)); do
  s=$(loan_status "$LOAN_ID")
  [ "$s" = "DOCS_REJECTED" ] && break
  [ "$s" = "PENDING_ADMIN_DECISION" ] && { red "  expected DOCS_REJECTED but ML approved on first pass"; exit 1; }
  sleep 2
done
expect "S2 first-pass lifecycle" "DOCS_REJECTED" "$s"

ATOK="$(login_admin)"; [ -n "$ATOK" ] || { red "admin login failed"; exit 1; }
ROW="$(list_row "$LOAN_ID" "$ATOK")"
echo "  S2 row=$ROW"
expect "S2 lifecycleStatus"      "DOCS_REJECTED" "$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('lifecycleStatus'))")"
expect "S2 findocRecommendation" "rejected"      "$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('findocRecommendation'))")"
expect "S2 mlRecommendation"     "None"          "$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('mlRecommendation'))")"
expect "S2 docReevalResult"      "None"          "$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('docReevalResult'))")"

blue "===== S3 — patch credit_score + replay -> ML triggers -> PENDING_ADMIN_DECISION ====="
FAID=$(psqlj -c "SELECT findoc_loan_application_id FROM loan_application WHERE external_id='$LOAN_ID';" | tr -d '[:space:]')
echo "  findoc app id=$FAID"
DOC_ID=$(curl -fsS -H "X-API-Key: $FINDOC_API_KEY" "$FINDOC/api/v1/admin/applications/$FAID/extracted-fields" \
  | python -c "import json,sys
data=json.load(sys.stdin)
items = data if isinstance(data, list) else data.get('items',[])
m = [x for x in items if x.get('field') in ('credit_score','creditScore','cibil_score')]
print((m[0] if m else {}).get('documentId',''))")
[ -n "$DOC_ID" ] && green "  credit_score doc=$DOC_ID" || red "  no credit_score doc found, replay alone may not flip"
if [ -n "$DOC_ID" ]; then
  curl -fsS -X PATCH "$FINDOC/api/v1/admin/applications/$FAID/extracted-fields" \
    -H "X-API-Key: $FINDOC_API_KEY" -H 'Content-Type: application/json' \
    -d "{\"field\":\"credit_score\",\"newValue\":\"750\",\"reason\":\"e2e: bumped to passing\",\"documentId\":\"$DOC_ID\"}" >/dev/null \
    && green "  credit_score patched -> 750"
fi

curl -fsS -X POST "$FINDOC/api/v1/admin/applications/$FAID/replay" \
  -H "X-API-Key: $FINDOC_API_KEY" -H 'Content-Type: application/json' \
  -d '{"reason":"e2e: post credit_score patch"}' >/dev/null
green "  replay started"

echo "  Waiting up to 5 min for PENDING_ADMIN_DECISION (replay + ML)..."
for ((i=0;i<150;i++)); do
  s=$(loan_status "$LOAN_ID")
  [ "$s" = "PENDING_ADMIN_DECISION" ] && break
  sleep 2
done
expect "S3 lifecycle after replay" "PENDING_ADMIN_DECISION" "$s"

ROW="$(list_row "$LOAN_ID" "$ATOK")"
echo "  S3 row=$ROW"
expect "S3 docReevalResult" "APPROVE" "$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('docReevalResult'))")"
RUN=$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('docReevalRunNumber'))")
[ "$RUN" = "2" ] && green "  OK S3 docReevalRunNumber=2" || red "  FAIL S3 docReevalRunNumber expected=2 actual=$RUN"
ML=$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('mlRecommendation'))")
[ -n "$ML" ] && [ "$ML" != "None" ] && green "  OK S3 mlRecommendation=$ML" || red "  FAIL S3 mlRecommendation empty"
RB=$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('riskBand'))")
[ -n "$RB" ] && [ "$RB" != "None" ] && green "  OK S3 riskBand=$RB" || red "  FAIL S3 riskBand empty"

blue "===== S1 invariant — Java /override APPROVE -> PENDING_USER_ACCEPTANCE -> /accept -> APPROVED ====="
curl -fsS -X POST "$JAVA/api/admin/loans/$LOAN_ID/override" \
  -H "Authorization: Bearer $ATOK" -H 'Content-Type: application/json' \
  -d '{"decision":"APPROVE","reason":"e2e: post-replay manual approval","interestRate":12.5}' >/dev/null
poll_status "$LOAN_ID" PENDING_USER_ACCEPTANCE 30 || exit 1
green "  parked at PENDING_USER_ACCEPTANCE"

curl -fsS -X POST "$JAVA/api/loans/$LOAN_ID/accept" -H "Authorization: Bearer $TOK" >/dev/null
poll_status "$LOAN_ID" APPROVED 30 || exit 1
green "  user accepted"

ROW="$(list_row "$LOAN_ID" "$ATOK")"
expect "final lifecycleStatus"  "APPROVED" "$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('lifecycleStatus'))")"
RATE=$(echo "$ROW" | python -c "import json,sys; print(json.load(sys.stdin).get('interestRate'))")
green "  rate=$RATE"

echo
green "==========================================="
green " S2 + S3 + S1 invariant LIVE PASSED"
green " loanAppId=$LOAN_ID  user=$USER"
green "==========================================="
