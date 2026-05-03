from __future__ import annotations

import asyncio
import json
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any

import boto3
from sqlalchemy import select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from src.config import get_settings
from src.db import ProcessedEvent, SessionLocal
from src.logging_config import correlation_id_var, get_logger
from src.messaging.schemas import NonRetriableError
from src.metrics import sqs_messages_failed_total, sqs_messages_processed_total

MAX_RETRIES = 3

logger = get_logger(__name__)

def _extract_correlation_id(msg: dict[str, Any], event: dict[str, Any]) -> str | None:
    attrs = msg.get("MessageAttributes") or {}
    attr = attrs.get("correlationId")
    if isinstance(attr, dict):
        v = attr.get("StringValue") or attr.get("Value")
        if v:
            return v
    cid = event.get("correlationId")
    return cid if cid else None

class SqsConsumer(ABC):
    """Base SQS consumer with idempotency + graceful shutdown + DLQ escape hatch.

    Subclasses set ``queue_name`` and ``consumer_name`` and implement async ``handle(event)``.
    Semantics:
      - Success  → ACK (DeleteMessage).
      - Duplicate eventId for this consumer → ACK immediately (skip handler).
      - NonRetriableError from handler → ACK + republish to DLQ (poison pill).
      - Any other exception → leave message on queue, let SQS redeliver (and eventually DLQ at maxReceiveCount).
    Idempotency row is rolled back on retriable failure so retries are not skipped.
    """

    queue_name: str = ""
    consumer_name: str = ""

    def __init__(self) -> None:
        assert self.queue_name and self.consumer_name, "subclass must set queue_name and consumer_name"
        s = get_settings()
        kwargs: dict[str, Any] = {
            "region_name": s.aws_region,
            "aws_access_key_id": s.aws_access_key_id,
            "aws_secret_access_key": s.aws_secret_access_key,
        }
        if s.aws_endpoint_url:
            kwargs["endpoint_url"] = s.aws_endpoint_url
        self._sqs = boto3.client("sqs", **kwargs)
        self._queue_url = self._resolve_queue_url()
        self._dlq_url = self._resolve_dlq_url()
        self._poll_wait = s.worker_poll_wait_seconds
        self._batch = s.worker_batch_size
        self._stop = asyncio.Event()

    def _resolve_queue_url(self) -> str:
        import time
        last_err: Exception | None = None
        for _ in range(30):
            try:
                return self._sqs.get_queue_url(QueueName=self.queue_name)["QueueUrl"]
            except Exception as e:
                if "NonExistentQueue" not in repr(e) and "QueueDoesNotExist" not in repr(e):
                    raise
                last_err = e
                logger.info("queue %s not yet present, retrying in 2s", self.queue_name)
                time.sleep(2)
        raise last_err  # type: ignore[misc]

    def _resolve_dlq_url(self) -> str | None:
        try:
            return self._sqs.get_queue_url(QueueName=f"{self.queue_name}-dlq")["QueueUrl"]
        except Exception:
            logger.warning("DLQ %s-dlq not found; NonRetriableError will ACK silently", self.queue_name)
            return None

    @abstractmethod
    async def handle(self, event: dict[str, Any]) -> None: ...

    def stop(self) -> None:
        """Signal the run_forever loop to exit after the current poll.

        The worker runs inside uvicorn's event loop; OS signal handling is
        uvicorn's responsibility (registering our own would mask it and block
        container shutdown). Callers set stop from the FastAPI lifespan hook.
        """
        self._stop.set()

    async def _claim(self, event_id: str) -> str:
        async with SessionLocal() as session:
            stmt = pg_insert(ProcessedEvent).values(
                event_id=event_id,
                consumer_name=self.consumer_name,
                processed_at=datetime.now(timezone.utc),
                state="PENDING",
                attempts=1,
            ).on_conflict_do_nothing(index_elements=["event_id", "consumer_name"])
            res = await session.execute(stmt)
            await session.commit()
            if (res.rowcount or 0) > 0:
                return "NEW"

            row = (await session.execute(
                select(ProcessedEvent).where(
                    (ProcessedEvent.event_id == event_id)
                    & (ProcessedEvent.consumer_name == self.consumer_name)
                )
            )).scalar_one()

            if row.state == "SUCCEEDED":
                return "SKIP_OK"
            if row.state == "PENDING":
                return "SKIP_INFLIGHT"
            if row.state == "FAILED" and row.attempts < MAX_RETRIES:
                await session.execute(
                    update(ProcessedEvent)
                    .where((ProcessedEvent.event_id == event_id)
                           & (ProcessedEvent.consumer_name == self.consumer_name))
                    .values(state="PENDING", attempts=row.attempts + 1)
                )
                await session.commit()
                return "RETRY"
            return "SKIP_OK"

    async def _mark_succeeded(self, event_id: str) -> None:
        async with SessionLocal() as session:
            await session.execute(
                update(ProcessedEvent)
                .where((ProcessedEvent.event_id == event_id)
                       & (ProcessedEvent.consumer_name == self.consumer_name))
                .values(state="SUCCEEDED", last_error=None)
            )
            await session.commit()

    async def _mark_failed(self, event_id: str, error: str) -> None:
        async with SessionLocal() as session:
            await session.execute(
                update(ProcessedEvent)
                .where((ProcessedEvent.event_id == event_id)
                       & (ProcessedEvent.consumer_name == self.consumer_name))
                .values(state="FAILED", last_error=error[:1000])
            )
            await session.commit()

    @staticmethod
    def _parse_envelope(body: str) -> dict[str, Any]:
        outer = json.loads(body)
        if isinstance(outer, dict) and "Message" in outer and "eventType" not in outer:
            try:
                return json.loads(outer["Message"])
            except Exception:
                return outer
        return outer

    def _send_to_dlq(self, body: str, reason: str) -> None:
        if not self._dlq_url:
            return
        try:
            self._sqs.send_message(
                QueueUrl=self._dlq_url,
                MessageBody=body,
                MessageAttributes={
                    "DlqReason": {"DataType": "String", "StringValue": reason[:250]},
                    "DlqSource": {"DataType": "String", "StringValue": self.consumer_name},
                },
            )
        except Exception:
            logger.exception("failed to republish to DLQ")

    async def run_forever(self) -> None:
        logger.info("consumer starting", extra={"consumer": self.consumer_name, "queue": self.queue_name})
        loop = asyncio.get_event_loop()
        while not self._stop.is_set():
            try:
                resp = await loop.run_in_executor(
                    None,
                    lambda: self._sqs.receive_message(
                        QueueUrl=self._queue_url,
                        MaxNumberOfMessages=self._batch,
                        WaitTimeSeconds=self._poll_wait,
                        MessageAttributeNames=["All"],
                    ),
                )
            except Exception as e:
                logger.exception("receive_message failed: %s", e)
                await asyncio.sleep(2)
                continue

            for msg in resp.get("Messages", []):
                if self._stop.is_set():
                    break
                await self._process_one(msg)

        logger.info("consumer stopped", extra={"consumer": self.consumer_name})

    async def _process_one(self, msg: dict[str, Any]) -> None:
        receipt = msg["ReceiptHandle"]
        body = msg["Body"]

        try:
            event = self._parse_envelope(body)
        except Exception:
            logger.exception("bad envelope; routing to DLQ")
            sqs_messages_failed_total.labels(consumer=self.consumer_name, kind="bad_envelope").inc()
            self._send_to_dlq(body, "bad_envelope")
            self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
            return

        event_id = event.get("eventId")
        if not event_id:
            logger.warning("event missing eventId; routing to DLQ")
            sqs_messages_failed_total.labels(consumer=self.consumer_name, kind="missing_event_id").inc()
            self._send_to_dlq(body, "missing_event_id")
            self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
            return

        cid = _extract_correlation_id(msg, event)
        cid_token = correlation_id_var.set(cid) if cid else None
        try:
            claim = await self._claim(event_id)
            if claim == "SKIP_OK":
                logger.info("event already processed; skipping",
                            extra={"consumer": self.consumer_name, "event_id": event_id})
                self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
                return
            if claim == "SKIP_INFLIGHT":
                logger.info("event in flight on another replica; leaving for redelivery",
                            extra={"consumer": self.consumer_name, "event_id": event_id})
                return

            heartbeat = asyncio.create_task(self._heartbeat_visibility(receipt))
            try:
                await self.handle(event)
            except NonRetriableError as e:
                heartbeat.cancel()
                logger.warning(
                    "non-retriable handler failure; routing to DLQ",
                    extra={"consumer": self.consumer_name, "event_id": event_id, "reason": e.reason},
                )
                sqs_messages_failed_total.labels(consumer=self.consumer_name, kind="non_retriable").inc()
                await self._mark_failed(event_id, e.reason)
                self._send_to_dlq(body, e.reason)
                self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
                return
            except Exception as e:
                heartbeat.cancel()
                logger.exception(
                    "handler failed; leaving message for SQS redelivery",
                    extra={"consumer": self.consumer_name, "event_id": event_id},
                )
                sqs_messages_failed_total.labels(consumer=self.consumer_name, kind="retriable").inc()
                await self._mark_failed(event_id, repr(e))
                return

            heartbeat.cancel()
            await self._mark_succeeded(event_id)
            self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
            sqs_messages_processed_total.labels(consumer=self.consumer_name).inc()
            logger.info(
                "event processed",
                extra={"consumer": self.consumer_name, "event_id": event_id},
            )
        finally:
            if cid_token is not None:
                correlation_id_var.reset(cid_token)

    async def _heartbeat_visibility(self, receipt: str) -> None:
        loop = asyncio.get_event_loop()
        while True:
            try:
                await asyncio.sleep(15)
            except asyncio.CancelledError:
                return
            try:
                await loop.run_in_executor(
                    None,
                    lambda: self._sqs.change_message_visibility(
                        QueueUrl=self._queue_url,
                        ReceiptHandle=receipt,
                        VisibilityTimeout=60,
                    ),
                )
                logger.info("extending visibility for receipt %s", receipt[:16])
            except Exception:
                logger.exception("change_message_visibility failed")
                return
