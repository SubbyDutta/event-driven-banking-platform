from __future__ import annotations

TOPIC_DOC_OCR_REQUESTED = "findoc-doc-ocr-requested"
TOPIC_DOC_OCR_COMPLETED = "findoc-doc-ocr-completed"
TOPIC_DOC_CLASSIFIED = "findoc-doc-classified"
TOPIC_DOC_EXTRACTED = "findoc-doc-extracted"
TOPIC_APPLICATION_AGGREGATED = "findoc-application-aggregated"
TOPIC_COMPLIANCE_CHECKED = "findoc-compliance-checked"
TOPIC_CROSSDOC_VALIDATED = "findoc-crossdoc-validated"
TOPIC_FRAUD_CHECKED = "findoc-fraud-checked"
TOPIC_RISK_SCORED = "findoc-risk-scored"
TOPIC_LOAN_REPORT_READY = "findoc-loan-report-ready"
TOPIC_KYC_REPORT_READY = "findoc-kyc-report-ready"

ALL_TOPICS = [
    TOPIC_DOC_OCR_REQUESTED,
    TOPIC_DOC_OCR_COMPLETED,
    TOPIC_DOC_CLASSIFIED,
    TOPIC_DOC_EXTRACTED,
    TOPIC_APPLICATION_AGGREGATED,
    TOPIC_COMPLIANCE_CHECKED,
    TOPIC_CROSSDOC_VALIDATED,
    TOPIC_FRAUD_CHECKED,
    TOPIC_RISK_SCORED,
    TOPIC_LOAN_REPORT_READY,
    TOPIC_KYC_REPORT_READY,
]

QUEUE_OCR = "findoc-ocr"
QUEUE_CLASSIFY = "findoc-classify"
QUEUE_EXTRACT = "findoc-extract"
QUEUE_AGGREGATE = "findoc-aggregate"
QUEUE_COMPLIANCE = "findoc-compliance"
QUEUE_CROSSDOC = "findoc-crossdoc"
QUEUE_FRAUD = "findoc-fraud"
QUEUE_RISK = "findoc-risk"
QUEUE_RESULT = "findoc-result"

EVT_DOC_OCR_REQUESTED = "doc.ocr.requested"
EVT_DOC_OCR_COMPLETED = "doc.ocr.completed"
EVT_DOC_CLASSIFIED = "doc.classified"
EVT_DOC_EXTRACTED = "doc.extracted"
EVT_APPLICATION_AGGREGATED = "application.aggregated"
EVT_COMPLIANCE_CHECKED = "application.compliance_checked"
EVT_CROSSDOC_VALIDATED = "application.crossdoc_validated"
EVT_FRAUD_CHECKED = "application.fraud_checked"
EVT_RISK_SCORED = "application.risk_scored"
EVT_LOAN_REPORT_READY = "application.loan_report_ready"
EVT_KYC_REPORT_READY = "application.kyc_report_ready"

SCHEMA_VERSION = 1
