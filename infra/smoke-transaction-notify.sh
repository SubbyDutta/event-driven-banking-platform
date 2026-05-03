#!/bin/bash
# Smoke: send ₹500 from Subham → Rajat, assert credited + debited emails arrive.
#
# Assumes seg-1 (Subham) and seg-2 (Rajat) fixtures are already provisioned via
# the standard 5-segment demo flow (see infra/DEMO.md). Both must:
#   - exist as users
#   - have KYC_APPROVED + an active bank account
#   - hold enough balance on Subham's account to send ₹500
#
# Steps:
#   1. Login as Subham, look up Rajat's account number.
#   2. POST /api/bank/transfer (₹500).
#   3. Poll MailHog up to 10s for BOTH:
#        - subject contains "credited" → recipient = Rajat's email
#        - subject contains "debited"  → recipient = Subham's email
#   4. PASS only if both arrive within budget.
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA_BASE:-http://localhost:8080}"
MAILHOG="${MAILHOG_BASE:-http://localhost:8025}"

if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi

SUBHAM_USER="${SUBHAM_USERNAME:-subham}"
SUBHAM_PASS="${SUBHAM_PASSWORD:-Smoke@12345}"
SUBHAM_EMAIL="${SUBHAM_EMAIL:-subham@example.com}"
RAJAT_USER="${RAJAT_USERNAME:-rajat}"
RAJAT_EMAIL="${RAJAT_EMAIL:-rajat@example.com}"

echo "==> 1. Login as $SUBHAM_USER"
SUBHAM_TOKEN=$(curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SUBHAM_USER\",\"password\":\"$SUBHAM_PASS\"}" \
  | "$PY" -c "import json,sys; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or '')")
[ -n "$SUBHAM_TOKEN" ] || { echo "FAIL: login as $SUBHAM_USER returned no token (seed segment 1 first)"; exit 1; }

echo "==> 2. Resolve account numbers"
SUBHAM_ACC=$(curl -fsS -H "Authorization: Bearer $SUBHAM_TOKEN" "$JAVA/api/bank/account" \
  | "$PY" -c "import json,sys; d=json.load(sys.stdin); print(d.get('accountNumber',''))")
RAJAT_ACC=$(curl -fsS -H "Authorization: Bearer $SUBHAM_TOKEN" "$JAVA/api/users/by-username/$RAJAT_USER/account-number" \
  2>/dev/null \
  | "$PY" -c "import json,sys; d=json.load(sys.stdin); print(d.get('accountNumber',''))" 2>/dev/null || true)
if [ -z "${RAJAT_ACC:-}" ]; then
  RAJAT_ACC="${RAJAT_ACCOUNT_NUMBER:-}"
fi
[ -n "$SUBHAM_ACC" ] || { echo "FAIL: could not resolve Subham's account"; exit 1; }
[ -n "$RAJAT_ACC" ] || { echo "FAIL: could not resolve Rajat's account (export RAJAT_ACCOUNT_NUMBER)"; exit 1; }

IDEMPOTENCY_KEY="smoke-txn-$(date +%s)-$RANDOM"

echo "==> 3. Transfer ₹500 from $SUBHAM_ACC → $RAJAT_ACC"
curl -fsS -X POST "$JAVA/api/bank/transfer" \
  -H "Authorization: Bearer $SUBHAM_TOKEN" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d "{\"senderAccount\":\"$SUBHAM_ACC\",\"receiverAccount\":\"$RAJAT_ACC\",\"amount\":500,\"password\":\"$SUBHAM_PASS\"}" \
  >/dev/null \
  || { echo "FAIL: transfer request errored"; exit 1; }

mailhog_match() {
  local pattern="$1" recipient="$2"
  curl -fsS "$MAILHOG/api/v2/messages" \
    | "$PY" -c "
import json, sys, re
d = json.load(sys.stdin)
items = d.get('items') or d
for m in items:
    headers = m.get('Content', {}).get('Headers', {})
    subj = (headers.get('Subject') or [''])[0]
    to = ','.join(headers.get('To') or [])
    if re.search(r'''$pattern''', subj, re.I) and '$recipient' in to:
        print('OK'); break
"
}

echo "==> 4. Poll MailHog up to 10s for credited+debited emails"
delay=1
elapsed=0
got_credit=""
got_debit=""
while [ $elapsed -lt 10 ]; do
  [ -z "$got_credit" ] && [ "$(mailhog_match 'credited' "$RAJAT_EMAIL" || true)" = "OK" ] && got_credit=1
  [ -z "$got_debit" ]  && [ "$(mailhog_match 'debited'  "$SUBHAM_EMAIL" || true)" = "OK" ] && got_debit=1
  if [ -n "$got_credit" ] && [ -n "$got_debit" ]; then break; fi
  sleep $delay
  elapsed=$((elapsed + delay))
  delay=$((delay * 2))
  [ $delay -gt 4 ] && delay=4
done

if [ -z "$got_credit" ]; then echo "FAIL: credited email to $RAJAT_EMAIL not seen within 10s"; exit 1; fi
if [ -z "$got_debit" ];  then echo "FAIL: debited email to $SUBHAM_EMAIL not seen within 10s"; exit 1; fi

echo "PASS: smoke-transaction-notify (credited→$RAJAT_EMAIL, debited→$SUBHAM_EMAIL)"
exit 0
