from __future__ import annotations

import json
from functools import lru_cache
from typing import Any

import boto3

from src.config import get_settings
from src.logging_config import get_logger

logger = get_logger(__name__)

class SnsPublisher:
    """Thin SNS publisher that publishes *pre-envelope-wrapped* event dicts.

    The worker owns envelope construction (eventId, occurredAt, schemaVersion,
    eventType, correlationId, payload) — unlike findoc-verify's publisher which
    composes the envelope itself — so the contract with Java stays explicit and
    correlationId can be echoed back from the inbound request.
    """

    def __init__(self) -> None:
        s = get_settings()
        kwargs: dict[str, Any] = {
            "region_name": s.aws_region,
            "aws_access_key_id": s.aws_access_key_id,
            "aws_secret_access_key": s.aws_secret_access_key,
        }
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

    def publish(self, topic_name: str, envelope: dict[str, Any]) -> str:
        arn = self._resolve_topic_arn(topic_name)
        publish_kwargs: dict[str, Any] = {
            "TopicArn": arn,
            "Message": json.dumps(envelope, default=str),
        }
        cid = envelope.get("correlationId")
        if cid:
            publish_kwargs["MessageAttributes"] = {
                "correlationId": {"DataType": "String", "StringValue": str(cid)}
            }
        resp = self._client.publish(**publish_kwargs)
        logger.info(
            "published event",
            extra={
                "topic": topic_name,
                "event_type": envelope.get("eventType"),
                "event_id": envelope.get("eventId"),
                "correlation_id": cid,
            },
        )
        return resp["MessageId"]

@lru_cache(maxsize=1)
def get_publisher() -> SnsPublisher:
    return SnsPublisher()
