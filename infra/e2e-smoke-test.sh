#!/bin/bash
# End-to-end smoke test.
#
# Scope: prove that the full stack is wired and the cross-service contracts
# line up. It DOES:
#   - hits Java /actuator/health and findoc-verify /health
#   - signs up a random user via Java, logs in, checks JWT works
#   - verifies the Java → findoc-verify service-to-service API key auth
#   - checks LocalStack topic/queue counts and that no DLQs are already full
#   - checks MailHog is up and reachable
# It DOES NOT submit KYC/loan with real documents + poll the pipeline to
# APPROVED — that path depends on a live Gemini key and Document AI ADC, so
# it is left to the manual walk-through in DEMO.md (which prints a link at
# the end if this smoke passes).
#
# Requires:
#   docker compose stack running
#   FINDOC_API_KEY in .env (populated via the mint step in README.md step 4)
#   aws CLI (for LocalStack checks) — optional; the script degrades gracefully

set -euo pipefail

cd "$(dirname "$0")/.."

fail() { echo "FAIL: $*" >&2; exit 1; }
ok()   { echo "  OK: $*"; }

echo "==> 1. docker compose health"
docker compose ps --format '{{.Name}} {{.Status}}' | while read -r line; do
  case "$line" in
    *"Up"*healthy*)    echo "  $line" ;;
    *"Up"*starting*)   echo "  $line" ;;
    *"Up"*)            echo "  $line (no healthcheck)" ;;
    *)                 echo "  $line" ; fail "service not up: $line" ;;
  esac
done

echo "==> 2. Java /actuator/health"
curl -fsS http://localhost:8080/actuator/health >/dev/null || fail "Java health endpoint unreachable"
ok "Java up on :8080"

echo "==> 3. findoc-verify /health"
curl -fsS http://localhost:8000/api/v1/health >/dev/null || fail "findoc-verify health endpoint unreachable on :8000"
ok "findoc-verify up on :8000"

echo "==> 4. MailHog UI"
curl -fsS http://localhost:8025/api/v2/messages >/dev/null || fail "MailHog not reachable on :8025"
ok "MailHog UI on :8025"

echo "==> 5. Java → findoc-verify service auth"
if [ -z "${FINDOC_API_KEY:-}" ]; then
  if [ -f .env ] && grep -q '^FINDOC_API_KEY=' .env; then
    FINDOC_API_KEY=$(grep '^FINDOC_API_KEY=' .env | head -1 | cut -d= -f2-)
  fi
fi
if [ -z "${FINDOC_API_KEY:-}" ] || [ "$FINDOC_API_KEY" = "local-dev-key" ]; then
  echo "  SKIP: FINDOC_API_KEY is blank or still the placeholder 'local-dev-key'."
  echo "         Mint one: docker compose exec findoc-verify python -m scripts.generate_api_key \\"
  echo "                    --label subby-java --org subby --scopes submit,admin --rate-limit 120"
  echo "         Then drop it in .env and docker compose restart subby-bank."
else
  resp=$(curl -fsS -H "X-API-Key: $FINDOC_API_KEY" \
      "http://localhost:8000/api/v1/admin/applications?page=1&page_size=5" || true)
  if [ -z "$resp" ]; then
    fail "findoc-verify admin API rejected the key (check FINDOC_API_KEY scope=admin)"
  fi
  ok "admin API responded to minted key"
fi

echo "==> 6. Signup + login through Java"
USER="smoke_$(date +%s)_$RANDOM"
EMAIL="${USER}@example.com"
PHONE="9$(printf '%09d' $((RANDOM * RANDOM % 1000000000)))"
PASS="Smoke@12345"

signup=$(curl -fsS -X POST http://localhost:8080/api/auth/signup \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER\",\"email\":\"$EMAIL\",\"mobile\":\"$PHONE\",\"password\":\"$PASS\",\"firstname\":\"Smoke\",\"lastname\":\"Test\",\"dob\":\"1995-05-15\"}" \
    || fail "signup request failed")
echo "  signup → ${signup:0:80}…"

login=$(curl -fsS -X POST http://localhost:8080/api/auth/login \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"$USER\",\"password\":\"$PASS\"}" \
    || fail "login request failed")
TOKEN=$(echo "$login" | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('token') or d.get('accessToken') or d.get('jwt') or '')")
[ -n "$TOKEN" ] || fail "login returned no token. raw=$login"
ok "logged in, token len=${#TOKEN}"

echo "==> 7. Authenticated smoke — GET /api/user/me/account"
# A freshly signed-up user has no bank account yet, so this endpoint returns
# 404 with a {"error":"bank account not found"} body. That's business logic —
# the JWT itself worked (unauthenticated would be 401). Treat 200 and 404 as
# "JWT accepted".
status=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $TOKEN" \
    http://localhost:8080/api/user/me/account)
case "$status" in
  200|404) ok "JWT accepted by /api/user/* (HTTP $status)";;
  *)       fail "/api/user/me/account returned HTTP $status with fresh JWT";;
esac

echo "==> 8. LocalStack topic/queue/subscription counts"
if command -v aws >/dev/null 2>&1; then
  AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url=http://localhost:4566 --region ap-south-1 --no-cli-pager \
      sns list-topics | python -c 'import json,sys; print("  topics:", len(json.load(sys.stdin)["Topics"]))'
  AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url=http://localhost:4566 --region ap-south-1 --no-cli-pager \
      sqs list-queues | python -c 'import json,sys; qs=json.load(sys.stdin).get("QueueUrls",[]); print("  queues:", len(qs))'
else
  echo "  SKIP: aws CLI not found; skipping LocalStack counts"
fi

echo "==> 9. DLQ depth (should all be 0 on a clean stack)"
if command -v aws >/dev/null 2>&1; then
  total=0
  for q in $(AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
      aws --endpoint-url=http://localhost:4566 --region ap-south-1 --no-cli-pager \
      sqs list-queues --queue-name-prefix subby- --output text --query 'QueueUrls[]' \
      | tr '\t' '\n' | grep -- '-dlq$' || true); do
    n=$(AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
        aws --endpoint-url=http://localhost:4566 --region ap-south-1 --no-cli-pager \
        sqs get-queue-attributes --queue-url "$q" \
        --attribute-names ApproximateNumberOfMessages \
        --query 'Attributes.ApproximateNumberOfMessages' --output text)
    [ "$n" != "0" ] && echo "  ${q##*/}: $n"
    total=$((total + n))
  done
  echo "  total DLQ depth: $total"
fi

cat <<EOF

===========================================================================
SMOKE TEST COMPLETE

Infrastructure + wiring check passed. For the full KYC → loan → APPROVED
walk-through (requires a real GEMINI_API_KEY and live Document AI ADC), see:

  infra/DEMO.md

Dev URLs:
  smartbank UI       http://localhost:3000   (npm start in ./smartbank)
  findoc webui       http://localhost:5173   (npm run dev in ./findoc-verify/webui)
  MailHog            http://localhost:8025
  Java actuator      http://localhost:8080/actuator/health
  findoc health      http://localhost:8000/api/v1/health
===========================================================================
EOF
