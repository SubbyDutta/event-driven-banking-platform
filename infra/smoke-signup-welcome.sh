#!/bin/bash
# Smoke: signup → welcome email lands in MailHog.
#
#   1. POST /api/auth/signup with a fresh user.
#   2. Poll MailHog /api/v2/messages with exponential backoff up to 15s.
#   3. PASS if a message exists with subject containing "Welcome" addressed
#      to the signup email; FAIL otherwise.
#
# Idempotent: each run uses a unique username/email so it never collides.
set -euo pipefail
cd "$(dirname "$0")"

JAVA="${JAVA_BASE:-http://localhost:8080}"
MAILHOG="${MAILHOG_BASE:-http://localhost:8025}"

if command -v python3 >/dev/null 2>&1; then PY=python3; else PY=python; fi

USER="welcome_$(date +%s)_$RANDOM"
EMAIL="${USER}@example.com"
PHONE="9$(printf '%09d' $((RANDOM * RANDOM % 1000000000)))"
PASS="Smoke@12345"

echo "==> 1. Signup as $USER"
curl -fsS -X POST "$JAVA/api/auth/signup" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"mobile\":\"$PHONE\",\"password\":\"$PASS\",\"firstname\":\"Welcome\",\"lastname\":\"Smoke\",\"dob\":\"1995-05-15\"}" >/dev/null \
  || { echo "FAIL: signup HTTP error"; exit 1; }

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

echo "==> 2. Poll MailHog for welcome email (15s budget, exp backoff)"
delay=1
elapsed=0
found=""
while [ $elapsed -lt 15 ]; do
  hit=$(mailhog_match 'welcome' "$EMAIL" || true)
  if [ "$hit" = "OK" ]; then found=1; break; fi
  sleep $delay
  elapsed=$((elapsed + delay))
  delay=$((delay * 2))
  [ $delay -gt 8 ] && delay=8
done

if [ -z "$found" ]; then
  echo "FAIL: welcome email for $EMAIL did not arrive in MailHog within 15s"
  exit 1
fi

echo "PASS: smoke-signup-welcome ($EMAIL)"
exit 0
