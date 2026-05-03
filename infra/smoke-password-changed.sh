#!/bin/bash
# Smoke: forgot-password → reset-password → password-changed email arrives.
#
#   1. POST /api/auth/forgot-password for Subham.
#   2. Read OTP from password_reset_token table (or fall back to MailHog parse).
#   3. POST /api/auth/reset-password with the OTP and a new password.
#   4. Poll MailHog up to 15s for "password was changed" subject; assert it
#      arrived AT or AFTER the OTP email.
#
# Restores password back to original on PASS so subsequent reruns work.
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA_BASE:-http://localhost:8080}"
MAILHOG="${MAILHOG_BASE:-http://localhost:8025}"

if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi

SUBHAM_USER="${SUBHAM_USERNAME:-subham}"
SUBHAM_EMAIL="${SUBHAM_EMAIL:-subham@example.com}"
SUBHAM_PASS="${SUBHAM_PASSWORD:-Smoke@12345}"
NEW_PASS="${NEW_PASS:-Smoke@Reset1}"

psql_java() { docker compose -f ../docker-compose.yml exec -T postgres psql -U subby -d subbybank -At "$@"; }

echo "==> 1. POST /api/auth/forgot-password for $SUBHAM_EMAIL"
curl -fsS -X POST "$JAVA/api/auth/forgot-password" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$SUBHAM_EMAIL\"}" >/dev/null \
  || { echo "FAIL: forgot-password HTTP error"; exit 1; }

echo "==> 2. Read OTP from password_reset_token table"
delay=1
elapsed=0
OTP=""
while [ $elapsed -lt 6 ]; do
  OTP=$(psql_java -c "SELECT otp FROM password_reset_token WHERE email='$SUBHAM_EMAIL' ORDER BY id DESC LIMIT 1;" 2>/dev/null | tr -d '[:space:]' || true)
  if [ -n "$OTP" ]; then break; fi
  sleep $delay
  elapsed=$((elapsed + delay))
  delay=$((delay * 2))
done

if [ -z "$OTP" ]; then
  echo "  DB lookup empty; falling back to MailHog OTP parse"
  OTP=$(curl -fsS "$MAILHOG/api/v2/messages" \
    | "$PY" -c "
import json, sys, re
d = json.load(sys.stdin)
items = d.get('items') or d
items = sorted(items, key=lambda x: x.get('Created') or '', reverse=True)
for m in items:
    headers = m.get('Content', {}).get('Headers', {})
    subj = (headers.get('Subject') or [''])[0]
    to = ','.join(headers.get('To') or [])
    if 'reset' in subj.lower() and '$SUBHAM_EMAIL' in to:
        body = m.get('Content', {}).get('Body', '')
        match = re.search(r'(\d{6})', body)
        if match:
            print(match.group(1)); break
")
fi
[ -n "$OTP" ] || { echo "FAIL: no OTP retrievable for $SUBHAM_EMAIL"; exit 1; }

OTP_TS=$(curl -fsS "$MAILHOG/api/v2/messages" \
  | "$PY" -c "
import json, sys, re
d = json.load(sys.stdin)
items = d.get('items') or d
best = ''
for m in items:
    headers = m.get('Content', {}).get('Headers', {})
    subj = (headers.get('Subject') or [''])[0]
    to = ','.join(headers.get('To') or [])
    if re.search(r'reset', subj, re.I) and '$SUBHAM_EMAIL' in to:
        ts = m.get('Created') or ''
        if ts > best: best = ts
print(best)
")

echo "==> 3. POST /api/auth/reset-password with OTP=$OTP"
curl -fsS -X POST "$JAVA/api/auth/reset-password" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$SUBHAM_EMAIL\",\"otp\":\"$OTP\",\"newPassword\":\"$NEW_PASS\"}" >/dev/null \
  || { echo "FAIL: reset-password HTTP error"; exit 1; }

mailhog_find_ts() {
  local pattern="$1"
  curl -fsS "$MAILHOG/api/v2/messages" \
    | "$PY" -c "
import json, sys, re
d = json.load(sys.stdin)
items = d.get('items') or d
best = ''
for m in items:
    headers = m.get('Content', {}).get('Headers', {})
    subj = (headers.get('Subject') or [''])[0]
    to = ','.join(headers.get('To') or [])
    if re.search(r'''$pattern''', subj, re.I) and '$SUBHAM_EMAIL' in to:
        ts = m.get('Created') or ''
        if ts > best: best = ts
print(best)
"
}

echo "==> 4. Poll MailHog up to 15s for 'password was changed' email"
delay=1
elapsed=0
CHANGED_TS=""
while [ $elapsed -lt 15 ]; do
  CHANGED_TS=$(mailhog_find_ts 'password was changed')
  if [ -n "$CHANGED_TS" ]; then break; fi
  sleep $delay
  elapsed=$((elapsed + delay))
  delay=$((delay * 2))
  [ $delay -gt 4 ] && delay=4
done

if [ -z "$CHANGED_TS" ]; then echo "FAIL: 'password was changed' email not seen for $SUBHAM_EMAIL"; exit 1; fi
if [ -n "$OTP_TS" ] && [ "$CHANGED_TS" \< "$OTP_TS" ]; then
  echo "FAIL: 'password was changed' arrived BEFORE OTP email (otp=$OTP_TS changed=$CHANGED_TS)"
  exit 1
fi

echo "==> 5. Restore original password"
NEW_OTP_REQ=$(curl -fsS -X POST "$JAVA/api/auth/forgot-password" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$SUBHAM_EMAIL\"}" -o /dev/null -w '%{http_code}' || true)
sleep 1
RESTORE_OTP=$(psql_java -c "SELECT otp FROM password_reset_token WHERE email='$SUBHAM_EMAIL' ORDER BY id DESC LIMIT 1;" 2>/dev/null | tr -d '[:space:]' || true)
if [ -n "$RESTORE_OTP" ]; then
  curl -fsS -X POST "$JAVA/api/auth/reset-password" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$SUBHAM_EMAIL\",\"otp\":\"$RESTORE_OTP\",\"newPassword\":\"$SUBHAM_PASS\"}" >/dev/null \
    && echo "  password restored to original"
fi

echo "PASS: smoke-password-changed (otp=$OTP_TS, changed=$CHANGED_TS)"
exit 0
