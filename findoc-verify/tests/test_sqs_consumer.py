"""Direct tests for the four-state idempotency claim machine + visibility heartbeat.

Each test stubs SessionLocal and boto3.client so the SqsConsumer base class can be
exercised without LocalStack or a live database. The state matrix asserted here
is the contract Java's at-least-once SQS delivery relies on:

  NEW           — first delivery of an event, proceed to handle
  RETRY         — prior FAILED row, attempts < MAX_RETRIES, reclaim and proceed
  SKIP_OK       — row is SUCCEEDED (or FAILED+exhausted), ACK without handling
  SKIP_INFLIGHT — row is PENDING (another replica owns it), leave for redelivery
"""
from __future__ import annotations

import asyncio
import uuid
from contextlib import asynccontextmanager
from unittest.mock import AsyncMock, MagicMock, patch

import pytest


@pytest.fixture
def session_factory():
    """Returns (factory, session) — factory mimics SessionLocal() context manager."""
    session = MagicMock()
    session.execute = AsyncMock()
    session.commit = AsyncMock()

    @asynccontextmanager
    async def _factory():
        yield session

    factory = MagicMock(side_effect=lambda: _factory())
    return factory, session


@pytest.fixture
def consumer(session_factory):
    """A minimal subclass of SqsConsumer with boto3 + queue-url resolution stubbed."""
    factory, _ = session_factory
    with patch("boto3.client") as mock_boto:
        sqs = MagicMock()
        sqs.get_queue_url.return_value = {"QueueUrl": "http://localstack/q"}
        mock_boto.return_value = sqs

        from src.messaging.sqs_consumer import SqsConsumer

        class _TestConsumer(SqsConsumer):
            queue_name = "test-q"
            worker_name = "test-worker"

            async def handle(self, event):
                return None

        with patch("src.messaging.sqs_consumer.SessionLocal", factory):
            yield _TestConsumer()


@pytest.mark.asyncio
async def test_claim_returns_NEW_first_time(consumer, session_factory):
    _, session = session_factory
    insert_result = MagicMock()
    insert_result.rowcount = 1
    session.execute.return_value = insert_result

    state = await consumer._claim(uuid.uuid4())

    assert state == "NEW"


@pytest.mark.asyncio
async def test_claim_returns_SKIP_OK_after_success(consumer, session_factory):
    _, session = session_factory
    insert_result = MagicMock()
    insert_result.rowcount = 0
    select_result = MagicMock()
    row = MagicMock()
    row.state = "SUCCEEDED"
    row.attempts = 1
    select_result.scalar_one.return_value = row
    session.execute.side_effect = [insert_result, select_result]

    state = await consumer._claim(uuid.uuid4())

    assert state == "SKIP_OK"


@pytest.mark.asyncio
async def test_claim_returns_RETRY_for_failed_under_max(consumer, session_factory):
    _, session = session_factory
    insert_result = MagicMock()
    insert_result.rowcount = 0
    select_result = MagicMock()
    row = MagicMock()
    row.state = "FAILED"
    row.attempts = 1
    select_result.scalar_one.return_value = row
    update_result = MagicMock()
    session.execute.side_effect = [insert_result, select_result, update_result]

    state = await consumer._claim(uuid.uuid4())

    assert state == "RETRY"
    # Three executes: INSERT (insert), SELECT (refetch), UPDATE (state→PENDING + attempts++)
    assert session.execute.await_count == 3


@pytest.mark.asyncio
async def test_claim_returns_SKIP_INFLIGHT_for_pending(consumer, session_factory):
    _, session = session_factory
    insert_result = MagicMock()
    insert_result.rowcount = 0
    select_result = MagicMock()
    row = MagicMock()
    row.state = "PENDING"
    row.attempts = 1
    select_result.scalar_one.return_value = row
    session.execute.side_effect = [insert_result, select_result]

    state = await consumer._claim(uuid.uuid4())

    assert state == "SKIP_INFLIGHT"


@pytest.mark.asyncio
async def test_heartbeat_extends_visibility(consumer):
    consumer._sqs.change_message_visibility = MagicMock()
    sleep_calls = {"n": 0}

    async def fake_sleep(_secs):
        sleep_calls["n"] += 1
        if sleep_calls["n"] >= 2:
            raise asyncio.CancelledError

    with patch("src.messaging.sqs_consumer.asyncio.sleep", side_effect=fake_sleep):
        task = asyncio.create_task(consumer._heartbeat_visibility("rcpt-abc"))
        try:
            await task
        except asyncio.CancelledError:
            pass

    consumer._sqs.change_message_visibility.assert_called_once()
    kwargs = consumer._sqs.change_message_visibility.call_args.kwargs
    assert kwargs["VisibilityTimeout"] == 60
    assert kwargs["ReceiptHandle"] == "rcpt-abc"
