#!/bin/bash
# Phase A only: signup + KYC + loan submit, wait to DOCS_REJECTED.
# Output: prints LOAN_ID, FAID, USER, UTOK to stdout once docs-rejected; chained
# scenarios S4/S6-S9 run inline against this loan from the parent shell.
#
# We can't reuse rajat identity again because the previous run's user holds the
# Aadhaar/PAN. Use the priya identity from segment 3 with rajat's loan docs:
# wait — KYC doc identity must match signup. Use unique synthetic rajat-2.
set -uo pipefail
cd "$(dirname "$0")/.."

JAVA="http://localhost:8080"
PASS="Smoke@12345"
SEG="./infra/fixtures/segments/2_rajat_rejected_credit"

# Generate a one-off variant Aadhaar/PAN by reusing rajat fixtures BUT bypass
# the dup-id guard by clearing the prior rajat's encrypted IDs.
docker exec subby-postgres psql -U subby -d subbybank -c \
  "UPDATE users SET aadhaar_number_encrypted=NULL, pan_number_encrypted=NULL WHERE username LIKE 'rajat_rj%';" >/dev/null

UNIQ="rj2$(date +%s)$RANDOM"
USER="rajat2_$UNIQ"
EMAIL="${USER}@example.com"
PHONE="9$(printf '%09d' $((RANDOM * RANDOM % 1000000000)))"

curl -fsS -X POST "$JAVA/api/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"mobile\":\"$PHONE\",\"password\":\"$PASS\",\"firstname\":\"Rajat\",\"lastname\":\"Sharma\",\"dob\":\"1992-08-20\"}" >/dev/null
TOK=$(curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" \
  | python -c "import json,sys; print(json.load(sys.stdin).get('token',''))")
[ -n "$TOK" ] || { echo "FAIL login"; exit 1; }
echo "USER=$USER" >&2
echo "UTOK=$TOK" >&2

curl -fsS -X POST "$JAVA/api/kyc/apply" -H "Authorization: Bearer $TOK" \
  -F "aadhaar=@$SEG/kyc/aadhaar.pdf" -F "pan=@$SEG/kyc/pan.pdf" >/dev/null
echo "KYC submitted, waiting..." >&2

for ((i=0;i<150;i++)); do
  KS=$(curl -fsS -H "Authorization: Bearer $TOK" "$JAVA/api/kyc/status" \
       | python -c "import json,sys; print(json.load(sys.stdin).get('kycStatus',''))")
  [ "$KS" = "KYC_APPROVED" ] && break
  [ "$KS" = "KYC_REJECTED" ] && { echo "FAIL kyc rejected"; exit 1; }
  sleep 2
done
[ "$KS" = "KYC_APPROVED" ] || { echo "FAIL kyc timeout state=$KS"; exit 1; }
echo "KYC_APPROVED" >&2

curl -fsS -X POST "$JAVA/api/user/create-account" -H "Authorization: Bearer $TOK" \
  -H 'Content-Type: application/json' -d "{\"username\":\"$USER\",\"type\":\"SAVINGS\"}" >/dev/null \
  || curl -fsS -X POST "$JAVA/api/account/create" -H "Authorization: Bearer $TOK" \
       -H 'Content-Type: application/json' -d '{"type":"SAVINGS"}' >/dev/null \
  || true

LOAN_RESP=$(curl -fsS -X POST "$JAVA/api/loans/apply" -H "Authorization: Bearer $TOK" \
  -F "amount=500000" -F "purpose=MEDICAL" -F "terms_accepted=true" \
  -F "payslip_1=@$SEG/loan/payslip-01.pdf" -F "payslip_2=@$SEG/loan/payslip-02.pdf" -F "payslip_3=@$SEG/loan/payslip-03.pdf" \
  -F "bank_statement_1=@$SEG/loan/bank-statement-01.pdf" -F "bank_statement_2=@$SEG/loan/bank-statement-02.pdf" -F "bank_statement_3=@$SEG/loan/bank-statement-03.pdf" \
  -F "credit_report=@$SEG/loan/credit-report.pdf" -F "itr=@$SEG/loan/itr.pdf" -F "employment_letter=@$SEG/loan/employment-letter.pdf")
LOAN_ID=$(echo "$LOAN_RESP" | python -c "import json,sys; print(json.load(sys.stdin).get('loanAppId',''))")
[ -n "$LOAN_ID" ] || { echo "FAIL loan submit: $LOAN_RESP"; exit 1; }
echo "LOAN_ID=$LOAN_ID" >&2

for ((i=0;i<150;i++)); do
  s=$(docker exec subby-postgres psql -U subby -d subbybank -At -c "SELECT lifecycle_status FROM loan_application WHERE external_id='$LOAN_ID';" | tr -d '[:space:]')
  [ "$s" = "DOCS_REJECTED" ] && break
  [ "$s" = "PENDING_ADMIN_DECISION" ] && { echo "FAIL ML approved on first pass"; exit 1; }
  sleep 2
done
[ "$s" = "DOCS_REJECTED" ] || { echo "FAIL final state=$s"; exit 1; }

FAID=$(docker exec subby-postgres psql -U subby -d subbybank -At -c "SELECT findoc_loan_application_id FROM loan_application WHERE external_id='$LOAN_ID';" | tr -d '[:space:]')
echo "FAID=$FAID" >&2
echo "DOCS_REJECTED reached" >&2

# Final stdout: machine-readable env block for the parent shell to source
echo "USER=$USER"
echo "UTOK=$TOK"
echo "LOAN_ID=$LOAN_ID"
echo "FAID=$FAID"
