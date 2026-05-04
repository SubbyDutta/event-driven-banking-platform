from __future__ import annotations

import json
import uuid
from datetime import datetime, timezone
from functools import lru_cache
from typing import Any

import boto3

from src.config import get_settings
from src.logging_config import correlation_id_var, get_logger
from src.messaging.topics import SCHEMA_VERSION

logger = get_logger(__name__)

class SnsPublisher:
    def __init__(self) -> None:
        s = get_settings()
        # Credentials via boto3 default chain — see sqs_consumer.py for why.
        kwargs: dict[str, Any] = {"region_name": s.aws_region}
        if s.aws_endpoint_url:
            kwargs["endpoint_url"] = s.aws_endpoint_url
        self._client = boto3.client("sns", **kwargs)
        self._arn_cache: dict[str, str] = {}

    def _resolve_topic_arn(self, topic_name: str) -> str:
        if topic_name in self._arn_cache:
            return self._arn_cache[topic_name]
        paginator = self._client.get_paginator("list_topics")
        for page in paginator.paginate():
            for t in page.get("Topics", []):
                arn = t["TopicArn"]
                if arn.rsplit(":", 1)[-1] == topic_name:
                    self._arn_cache[topic_name] = arn
                    return arn
        resp = self._client.create_topic(Name=topic_name)
        arn = resp["TopicArn"]
        self._arn_cache[topic_name] = arn
        return arn

    def publish(
        self,
        topic_name: str,
        event_type: str,
        payload: dict[str, Any],
        deduplication_id: str | None = None,
    ) -> str:
        arn = self._resolve_topic_arn(topic_name)
        cid = correlation_id_var.get()
        if cid == "-":
            cid = ""
        envelope = {
            "eventId": deduplication_id or str(uuid.uuid4()),
            "schemaVersion": SCHEMA_VERSION,
            "eventType": event_type,
            "occurredAt": datetime.now(timezone.utc).isoformat(),
            "payload": payload,
        }
        if cid:
            envelope["correlationId"] = cid
        publish_kwargs: dict[str, Any] = {
            "TopicArn": arn,
            "Message": json.dumps(envelope, default=str),
        }
        if cid:
            publish_kwargs["MessageAttributes"] = {
                "correlationId": {"DataType": "String", "StringValue": cid}
            }
        resp = self._client.publish(**publish_kwargs)
        logger.info(
            "published event",
            extra={"topic": topic_name, "event_type": event_type, "event_id": envelope["eventId"]},
        )
        return resp["MessageId"]

@lru_cache(maxsize=1)
def get_publisher() -> SnsPublisher:
    return SnsPublisher()
