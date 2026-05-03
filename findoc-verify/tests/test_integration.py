"""End-to-end smoke test scaffolding.

This test is a LocalStack-backed integration check — it's marked skip-by-default
so `pytest` without docker doesn't break. To run it:

    docker compose up -d postgres localstack
    alembic upgrade head
    pytest -m integration -s tests/test_integration.py
"""
import os

import pytest

pytestmark = pytest.mark.skipif(
    os.getenv("RUN_INTEGRATION_TESTS") != "1",
    reason="set RUN_INTEGRATION_TESTS=1 with LocalStack + Postgres running",
)


def test_smoke_topics_exist():
    import boto3
    s = boto3.client(
        "sns",
        endpoint_url=os.getenv("AWS_ENDPOINT_URL", "http://localhost:4566"),
        region_name=os.getenv("AWS_REGION", "ap-south-1"),
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )
    names = {t["TopicArn"].rsplit(":", 1)[-1] for t in s.list_topics().get("Topics", [])}
    required = {"findoc-doc-ocr-requested", "findoc-loan-report-ready"}
    assert required.issubset(names), f"missing topics: {required - names}"


def test_bucket_exists():
    import boto3
    s = boto3.client(
        "s3",
        endpoint_url=os.getenv("S3_ENDPOINT_URL", "http://localhost:4566"),
        region_name=os.getenv("S3_REGION", "ap-south-1"),
        aws_access_key_id="test",
        aws_secret_access_key="test",
    )
    buckets = {b["Name"] for b in s.list_buckets().get("Buckets", [])}
    assert os.getenv("S3_BUCKET", "findoc-documents") in buckets
