from __future__ import annotations

import asyncio
import json
import signal
import uuid
from abc import ABC, abstractmethod
from datetime import datetime, timezone
from typing import Any

import boto3
from sqlalchemy import insert, select, update
from sqlalchemy.dialects.postgresql import insert as pg_insert

from src.config import get_settings
from src.db.models import ProcessedEvent
from src.db.session import SessionLocal
from src.logging_config import correlation_id_var, get_logger

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
    """Base SQS consumer with idempotency and graceful shutdown.

    Subclasses set `queue_name` and `worker_name`, and implement async `handle(event)`.
    Messages are ACKed on success; on exception they are left for SQS to redeliver.
    Duplicate events (same eventId for same worker) are ACKed immediately.
    """

    queue_name: str = ""
    worker_name: str = ""

    def __init__(self) -> None:
        assert self.queue_name and self.worker_name, "subclass must set queue_name and worker_name"
        s = get_settings()
        # Credentials: rely on boto3's default chain (env vars in dev for
        # LocalStack, IAM instance role on EC2). Passing the config defaults
        # explicitly would override the role with placeholder "test" creds.
        kwargs: dict[str, Any] = {"region_name": s.aws_region}
        if s.aws_endpoint_url:
            kwargs["endpoint_url"] = s.aws_endpoint_url
        self._sqs = boto3.client("sqs", **kwargs)
        self._queue_url = self._resolve_queue_url()
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

    @abstractmethod
    async def handle(self, event: dict[str, Any]) -> None:
        ...

    def _install_signal_handlers(self) -> None:
        loop = asyncio.get_event_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            try:
                loop.add_signal_handler(sig, self._stop.set)
            except NotImplementedError:
                signal.signal(sig, lambda *_: self._stop.set())

    async def _claim(self, event_id: uuid.UUID) -> str:
        """Claim the event for processing. Returns one of:
          'NEW'     — first delivery, proceed to handle
          'RETRY'   — prior FAILED row reclaimed, proceed to handle
          'SKIP_OK' — already SUCCEEDED or exhausted retries, ack and move on
          'SKIP_INFLIGHT' — another replica owns it, leave for SQS redelivery
        """
        async with SessionLocal() as session:
            stmt = pg_insert(ProcessedEvent).values(
                event_id=event_id,
                worker_name=self.worker_name,
                processed_at=datetime.now(timezone.utc),
                state="PENDING",
                attempts=1,
            ).on_conflict_do_nothing(index_elements=["event_id", "worker_name"])
            res = await session.execute(stmt)
            await session.commit()
            if (res.rowcount or 0) > 0:
                return "NEW"

            row = (await session.execute(
                select(ProcessedEvent).where(
                    (ProcessedEvent.event_id == event_id)
                    & (ProcessedEvent.worker_name == self.worker_name)
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
                           & (ProcessedEvent.worker_name == self.worker_name))
                    .values(state="PENDING", attempts=row.attempts + 1)
                )
                await session.commit()
                return "RETRY"
            return "SKIP_OK"

    async def _mark_succeeded(self, event_id: uuid.UUID) -> None:
        async with SessionLocal() as session:
            await session.execute(
                update(ProcessedEvent)
                .where((ProcessedEvent.event_id == event_id)
                       & (ProcessedEvent.worker_name == self.worker_name))
                .values(state="SUCCEEDED", last_error=None)
            )
            await session.commit()

    async def _mark_failed(self, event_id: uuid.UUID, error: str) -> None:
        async with SessionLocal() as session:
            await session.execute(
                update(ProcessedEvent)
                .where((ProcessedEvent.event_id == event_id)
                       & (ProcessedEvent.worker_name == self.worker_name))
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

    async def run_forever(self) -> None:
        self._install_signal_handlers()
        logger.info("worker starting", extra={"worker": self.worker_name, "queue": self.queue_name})
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

            messages = resp.get("Messages", [])
            if not messages:
                continue

            for msg in messages:
                await self._process_one(msg)

        logger.info("worker stopped", extra={"worker": self.worker_name})

    async def _process_one(self, msg: dict[str, Any]) -> None:
        receipt = msg["ReceiptHandle"]
        try:
            event = self._parse_envelope(msg["Body"])
        except Exception:
            logger.exception("bad envelope; ACKing to avoid poison loop")
            self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
            return

        event_id_str = event.get("eventId")
        if not event_id_str:
            logger.warning("event missing eventId; ACKing")
            self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
            return

        try:
            event_id = uuid.UUID(event_id_str)
        except ValueError:
            logger.warning("event has non-uuid eventId=%s; ACKing", event_id_str)
            self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
            return

        cid = _extract_correlation_id(msg, event)
        cid_token = correlation_id_var.set(cid) if cid else None
        try:
            claim = await self._claim(event_id)
            if claim == "SKIP_OK":
                logger.info("event already processed; skipping",
                            extra={"worker": self.worker_name, "event_id": str(event_id)})
                self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
                return
            if claim == "SKIP_INFLIGHT":
                logger.info("event in flight on another replica; leaving for redelivery",
                            extra={"worker": self.worker_name, "event_id": str(event_id)})
                return

            heartbeat = asyncio.create_task(self._heartbeat_visibility(receipt))
            try:
                await self.handle(event)
            except Exception as e:
                heartbeat.cancel()
                logger.exception(
                    "handler failed; NOT deleting — SQS will redeliver",
                    extra={"worker": self.worker_name, "event_id": str(event_id)},
                )
                await self._mark_failed(event_id, repr(e))
                return

            heartbeat.cancel()
            await self._mark_succeeded(event_id)
            self._sqs.delete_message(QueueUrl=self._queue_url, ReceiptHandle=receipt)
            logger.info(
                "event processed",
                extra={"worker": self.worker_name, "event_id": str(event_id)},
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
