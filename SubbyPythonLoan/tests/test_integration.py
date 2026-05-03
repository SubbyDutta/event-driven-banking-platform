"""LocalStack round-trip integration test.

Run with the monorepo compose up (so LocalStack and Postgres are both reachable
on localhost) and:

    pytest SubbyPythonLoan/tests/test_integration.py -v

Skipped automatically when LocalStack isn't reachable, so a `pytest` in CI
without infra doesn't fail.
"""
from __future__ import annotations

import json
import os
import time
import uuid

import boto3
import pytest
from botocore.exceptions import EndpointConnectionError

LOCALSTACK_URL = os.getenv("INT_LOCALSTACK_URL", "http://localhost:4566")
REGION = os.getenv("INT_AWS_REGION", "ap-south-1")


def _client(service: str):
    return boto3.client(
        service,
        region_name=REGION,
        endpoint_url=LOCALSTACK_URL,
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )


def _localstack_reachable() -> bool:
    try:
        _client("sns").list_topics()
        return True
    except (EndpointConnectionError, Exception):
        return False


pytestmark = pytest.mark.skipif(
    not _localstack_reachable(),
    reason="LocalStack not reachable at {}; bring up the monorepo compose first".format(LOCALSTACK_URL),
)


def _topic_arn(sns, name: str) -> str:
    paginator = sns.get_paginator("list_topics")
    for page in paginator.paginate():
        for t in page.get("Topics", []):
            if t["TopicArn"].rsplit(":", 1)[-1] == name:
                return t["TopicArn"]
    raise RuntimeError(f"topic {name} not found — is infra/localstack-init.sh mounted?")


def _queue_url(sqs, name: str) -> str:
    return sqs["get_queue_url"](QueueName=name)["QueueUrl"] if isinstance(sqs, dict) else sqs.get_queue_url(QueueName=name)["QueueUrl"]


def _drain(sqs, url: str) -> None:
    for _ in range(10):
        resp = sqs.receive_message(QueueUrl=url, MaxNumberOfMessages=10, WaitTimeSeconds=0)
        msgs = resp.get("Messages", [])
        if not msgs:
            return
        for m in msgs:
            sqs.delete_message(QueueUrl=url, ReceiptHandle=m["ReceiptHandle"])


def _await_message(sqs, url: str, timeout_s: float = 10.0) -> dict:
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        resp = sqs.receive_message(
            QueueUrl=url,
            MaxNumberOfMessages=1,
            WaitTimeSeconds=1,
            MessageAttributeNames=["All"],
        )
        msgs = resp.get("Messages", [])
        if msgs:
            return msgs[0]
    raise AssertionError(f"no message on {url} within {timeout_s}s")


@pytest.fixture
def clean_queues():
    sqs = _client("sqs")
    url = sqs.get_queue_url(QueueName="subby-loan-risk-results")["QueueUrl"]
    _drain(sqs, url)
    yield
    _drain(sqs, url)


@pytest.mark.skip(reason="requires LocalStack + running RiskWorker process; run via docker-compose")
def test_round_trip_request_publishes_matching_result(clean_queues):
    sns = _client("sns")
    sqs = _client("sqs")

    topic_arn = _topic_arn(sns, "subby-risk-requested")
    result_q = sqs.get_queue_url(QueueName="subby-loan-risk-results")["QueueUrl"]

    event_id = str(uuid.uuid4())
    correlation_id = f"test-{event_id[:8]}"
    event = {
        "eventId": event_id,
        "eventType": "LoanRiskRequested",
        "schemaVersion": 1,
        "occurredAt": "2026-04-24T10:00:00Z",
        "correlationId": correlation_id,
        "payload": {
            "loanAppId": correlation_id,
            "amountRequested": 500000,
            "tenureMonths": 6,
            "features": {
                "monthly_income": 75000,
                "credit_score": 742,
                "bank_avg_balance": 120000,
                "existing_emi": 8000,
                "dti_ratio": 0.106,
            },
        },
    }

    sns.publish(TopicArn=topic_arn, Message=json.dumps(event))

    msg = _await_message(sqs, result_q, timeout_s=15.0)
    sqs.delete_message(QueueUrl=result_q, ReceiptHandle=msg["ReceiptHandle"])

    envelope = json.loads(msg["Body"])
    assert envelope["eventType"] == "LoanRiskResult"
    assert envelope["correlationId"] == correlation_id
    payload = envelope["payload"]
    assert payload["loanAppId"] == correlation_id
    assert payload["decision"] in ("approve", "manual_review", "reject")
    assert 0.0 <= payload["probability_of_default"] <= 1.0
    assert payload["risk_band"] in ("A", "B", "C", "D", "E")
    assert payload["modelVersion"]


@pytest.mark.skip(reason="requires LocalStack + running RiskWorker process; run via docker-compose")
def test_duplicate_event_id_yields_single_result(clean_queues):
    sns = _client("sns")
    sqs = _client("sqs")

    topic_arn = _topic_arn(sns, "subby-risk-requested")
    result_q = sqs.get_queue_url(QueueName="subby-loan-risk-results")["QueueUrl"]

    event_id = str(uuid.uuid4())
    correlation_id = f"dup-{event_id[:8]}"
    event = {
        "eventId": event_id,
        "eventType": "LoanRiskRequested",
        "schemaVersion": 1,
        "correlationId": correlation_id,
        "payload": {
            "loanAppId": correlation_id,
            "amountRequested": 100000,
            "tenureMonths": 12,
            "features": {"monthly_income": 50000, "credit_score": 720, "bank_avg_balance": 80000},
        },
    }
    body = json.dumps(event)
    sns.publish(TopicArn=topic_arn, Message=body)
    sns.publish(TopicArn=topic_arn, Message=body)

    first = _await_message(sqs, result_q, timeout_s=15.0)
    sqs.delete_message(QueueUrl=result_q, ReceiptHandle=first["ReceiptHandle"])
    first_envelope = json.loads(first["Body"])
    assert first_envelope["correlationId"] == correlation_id

    # Give the worker a generous window in case the second message was somehow
    # still in flight — with idempotency it should be ACK'd without publishing.
    resp = sqs.receive_message(QueueUrl=result_q, MaxNumberOfMessages=1, WaitTimeSeconds=5)
    assert not resp.get("Messages"), "duplicate event emitted a second LoanRiskResult — idempotency broken"


def test_malformed_event_routes_to_dlq():
    """An event missing required fields must land on subby-risk-requests-dlq, not the result topic."""
    sns = _client("sns")
    sqs = _client("sqs")

    topic_arn = _topic_arn(sns, "subby-risk-requested")
    dlq_url = sqs.get_queue_url(QueueName="subby-risk-requests-dlq")["QueueUrl"]
    _drain(sqs, dlq_url)

    event = {
        "eventId": str(uuid.uuid4()),
        "eventType": "LoanRiskRequested",
        "schemaVersion": 1,
        "correlationId": "bad-event",
        "payload": {"loanAppId": "bad-event"},  # missing amountRequested + features
    }
    sns.publish(TopicArn=topic_arn, Message=json.dumps(event))

    msg = _await_message(sqs, dlq_url, timeout_s=15.0)
    sqs.delete_message(QueueUrl=dlq_url, ReceiptHandle=msg["ReceiptHandle"])
    attrs = msg.get("MessageAttributes") or {}
    assert "DlqReason" in attrs
    assert "missing" in attrs["DlqReason"]["StringValue"].lower()
