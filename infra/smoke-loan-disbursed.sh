#!/bin/bash
# Smoke: Subham loan reaches APPROVED, then BOTH "loan approved" and
# "loan disbursed" emails arrive in MailHog. Order matters — the disbursed
# email must arrive AT or AFTER the approved email.
#
# This script does NOT drive the full origination pipeline; it expects the
# segment-1 fixtures to have produced an APPROVED loan via the standard
# end-to-end flow. To run that yourself:
#   ./smoke-replay-approve-to-ml.sh   # or the segment-1 demo path
#
# Steps performed here:
#   1. Login as Subham, resolve the latest loanAppId.
#   2. If status != APPROVED, FAIL early ("seed approved loan first").
#   3. Poll MailHog up to 15s, exponential backoff.
#   4. Capture timestamps for "loan approved" and "loan disbursed" subjects.
#   5. PASS only if both exist AND disbursed.timestamp >= approved.timestamp.
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA_BASE:-http://localhost:8080}"
MAILHOG="${MAILHOG_BASE:-http://localhost:8025}"

if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi

SUBHAM_USER="${SUBHAM_USERNAME:-subham}"
SUBHAM_PASS="${SUBHAM_PASSWORD:-Smoke@12345}"
SUBHAM_EMAIL="${SUBHAM_EMAIL:-subham@example.com}"

echo "==> 1. Login as $SUBHAM_USER"
TOKEN=$(curl -fsS -X POST "$JAVA/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$SUBHAM_USER\",\"password\":\"$SUBHAM_PASS\"}" \
  | "$PY" -c "import json,sys; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or '')")
[ -n "$TOKEN" ] || { echo "FAIL: login returned no token"; exit 1; }

echo "==> 2. Pull most recent loan and assert lifecycleStatus=APPROVED"
LOANS_JSON=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$JAVA/api/loans/me" || true)
STATUS=$(echo "$LOANS_JSON" | "$PY" -c "
import json, sys
d = json.load(sys.stdin)
items = d.get('items') if isinstance(d, dict) else d
if not items: print('NONE'); exit()
items = sorted(items, key=lambda x: x.get('createdAt') or x.get('id') or 0, reverse=True)
print(items[0].get('lifecycleStatus') or items[0].get('status') or 'NONE')
")
if [ "$STATUS" != "APPROVED" ]; then
  echo "FAIL: latest Subham loan lifecycleStatus=$STATUS (expected APPROVED — seed via segment 1 demo first)"
  exit 1
fi

mailhog_find_ts() {
  local pattern="$1"
  curl -fsS "$MAILHOG/api/v2/messages" \
    | "$PY" -c "
import json, sys, re
d = json.load(sys.stdin)
items = d.get('items') or d
best = None
for m in items:
    headers = m.get('Content', {}).get('Headers', {})
    subj = (headers.get('Subject') or [''])[0]
    to = ','.join(headers.get('To') or [])
    if re.search(r'''$pattern''', subj, re.I) and '$SUBHAM_EMAIL' in to:
        ts = m.get('Created') or ''
        if not best or ts > best:
            best = ts
print(best or '')
"
}

echo "==> 3. Poll MailHog up to 15s for both approved and disbursed emails"
delay=1
elapsed=0
approved_ts=""
disbursed_ts=""
while [ $elapsed -lt 15 ]; do
  [ -z "$approved_ts" ]  && approved_ts=$(mailhog_find_ts 'loan.*approved')
  [ -z "$disbursed_ts" ] && disbursed_ts=$(mailhog_find_ts 'disbursed')
  if [ -n "$approved_ts" ] && [ -n "$disbursed_ts" ]; then break; fi
  sleep $delay
  elapsed=$((elapsed + delay))
  delay=$((delay * 2))
  [ $delay -gt 4 ] && delay=4
done

if [ -z "$approved_ts" ];  then echo "FAIL: 'loan approved' email not seen for $SUBHAM_EMAIL"; exit 1; fi
if [ -z "$disbursed_ts" ]; then echo "FAIL: 'loan disbursed' email not seen for $SUBHAM_EMAIL"; exit 1; fi

echo "==> 4. Assert disbursed timestamp is at or after approved timestamp"
ORDER_OK=$("$PY" -c "
a = '$approved_ts'; d = '$disbursed_ts'
print('OK' if d >= a else 'FAIL')
")
if [ "$ORDER_OK" != "OK" ]; then
  echo "FAIL: disbursed email arrived BEFORE approved email (approved=$approved_ts disbursed=$disbursed_ts)"
  exit 1
fi

echo "PASS: smoke-loan-disbursed (approved=$approved_ts, disbursed=$disbursed_ts)"
exit 0
