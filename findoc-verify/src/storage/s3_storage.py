from __future__ import annotations

import uuid
from functools import lru_cache
from typing import Any

import boto3
from botocore.client import Config
from botocore.exceptions import ClientError

from src.config import get_settings
from src.logging_config import get_logger

logger = get_logger(__name__)

class S3Storage:
    """S3 client that works identically against AWS, LocalStack, or MinIO via env."""

    def __init__(self) -> None:
        s = get_settings()
        # Credentials via boto3 default chain (env in dev, IAM role on EC2).
        # See messaging/sqs_consumer.py for why explicit keys would break prod.
        kwargs: dict[str, Any] = {
            "region_name": s.s3_region,
            "config": Config(signature_version="s3v4", s3={"addressing_style": "path"}),
        }
        if s.s3_endpoint_url:
            kwargs["endpoint_url"] = s.s3_endpoint_url
        self._client = boto3.client("s3", **kwargs)
        self._bucket = s.s3_bucket
        self._ensure_bucket()

    def _ensure_bucket(self) -> None:
        try:
            self._client.head_bucket(Bucket=self._bucket)
        except ClientError as e:
            code = e.response.get("Error", {}).get("Code")
            if code in ("404", "NoSuchBucket", "403"):
                try:
                    s = get_settings()
                    self._client.create_bucket(
                        Bucket=self._bucket,
                        CreateBucketConfiguration={"LocationConstraint": s.s3_region},
                    )
                    logger.info("Created bucket %s", self._bucket)
                except ClientError as ce:
                    if ce.response.get("Error", {}).get("Code") not in (
                        "BucketAlreadyOwnedByYou", "BucketAlreadyExists"
                    ):
                        raise
            else:
                raise

    @staticmethod
    def build_key(application_id: uuid.UUID | str, doc_type: str, filename: str) -> str:
        safe_name = filename.replace("/", "_").replace("\\", "_")
        return f"applications/{application_id}/{doc_type}/{uuid.uuid4().hex}_{safe_name}"

    def put_document(
        self,
        application_id: uuid.UUID | str,
        doc_type: str,
        file_bytes: bytes,
        filename: str,
        content_type: str | None = None,
    ) -> str:
        key = self.build_key(application_id, doc_type, filename)
        extra: dict[str, Any] = {}
        if content_type:
            extra["ContentType"] = content_type
        self._client.put_object(Bucket=self._bucket, Key=key, Body=file_bytes, **extra)
        return key

    def get_document(self, key: str) -> bytes:
        resp = self._client.get_object(Bucket=self._bucket, Key=key)
        return resp["Body"].read()

    def get_presigned_url(self, key: str, expires: int = 3600) -> str:
        return self._client.generate_presigned_url(
            "get_object",
            Params={"Bucket": self._bucket, "Key": key},
            ExpiresIn=expires,
        )

@lru_cache(maxsize=1)
def get_storage() -> S3Storage:
    return S3Storage()
