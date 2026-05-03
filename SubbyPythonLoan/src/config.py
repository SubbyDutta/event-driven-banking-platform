from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict

class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    app_env: Literal["development", "staging", "production"] = "development"
    app_port: int = 8000
    log_level: str = "INFO"

    database_url: str = (
        "postgresql+asyncpg://subbyloan:subbyloan@postgres:5432/subby_loan"
    )
    alembic_database_url: str = (
        "postgresql+psycopg2://subbyloan:subbyloan@postgres:5432/subby_loan"
    )

    aws_endpoint_url: str | None = "http://localstack:4566"
    aws_region: str = "ap-south-1"
    aws_access_key_id: str = "test"
    aws_secret_access_key: str = "test"

    sqs_queue: str = "subby-risk-requests"
    sns_topic_result: str = "subby-risk-result"

    model_path: str = "/app/loan_model.pkl"
    scaler_path: str = "/app/scaler.pkl"
    model_version: str = "v1.0.0"

    pod_approve_threshold: float = 0.15
    pod_reject_threshold: float = 0.35

    worker_poll_wait_seconds: int = 20
    worker_batch_size: int = 5

    @property
    def is_localstack(self) -> bool:
        return bool(self.aws_endpoint_url)

@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
