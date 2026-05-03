#!/usr/bin/env bash
set -euo pipefail

echo "==> Truncating subbybank tables"
docker exec subby-postgres psql -U subby -d subbybank -c "
TRUNCATE TABLE
  loan_repayment, loan_decision_overrides, kyc_decision_overrides,
  loan_application, loan_eligibility_request, transaction,
  outbox_events, processed_events, idempotency_keys, bank_account,
  event_audit_log, audit_log, buisness_log, password_reset_token
RESTART IDENTITY CASCADE;
DELETE FROM users WHERE role='USER';
"

echo "==> Truncating findoc tables"
docker exec subby-postgres psql -U findoc -d findoc -c "
TRUNCATE TABLE
  cross_doc_validations, compliance_checks, fraud_results,
  pipeline_events, decision_overrides, documents,
  doc_classifications, extracted_fields, ocr_results,
  verification_reports, applications, processed_events
RESTART IDENTITY CASCADE;
"

echo "==> Truncating subby_loan tables"
docker exec subby-postgres psql -U subbyloan -d subby_loan -c "
TRUNCATE TABLE processed_events RESTART IDENTITY CASCADE;
"

echo "==> Clearing S3 documents bucket"
docker exec subby-localstack awslocal s3 rm s3://subby-documents --recursive 2>/dev/null || true

echo "==> Purging SQS queues (main + DLQ) so prior-run poison doesn't leak"
QUEUES=$(docker exec subby-localstack awslocal sqs list-queues 2>/dev/null \
  | python -c "import sys,json; [print(u) for u in json.load(sys.stdin).get('QueueUrls', [])]")
for q in $QUEUES; do
  docker exec subby-localstack awslocal sqs purge-queue --queue-url "$q" >/dev/null 2>&1 || true
done

echo "==> Clearing mailhog inbox"
curl -s -X DELETE http://localhost:8025/api/v1/messages >/dev/null 2>&1 || true

echo "==> Regenerating segment fixtures"
python infra/generate_segments.py

echo "==> Reset + regen complete. Ready to test."
