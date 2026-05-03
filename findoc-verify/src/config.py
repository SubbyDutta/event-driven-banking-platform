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
    frontend_origin: str = "http://localhost:8000"
    cors_origins: str = "http://localhost:3000,http://localhost:5173,http://127.0.0.1:3000,http://127.0.0.1:5173"
    admin_bootstrap_mode: bool = True

    database_url: str = "postgresql+asyncpg://postgres:postgres@postgres:5432/findoc"
    alembic_database_url: str = "postgresql+psycopg2://postgres:postgres@postgres:5432/findoc"

    s3_endpoint_url: str | None = "http://localstack:4566"
    s3_bucket: str = "findoc-documents"
    s3_access_key: str = "test"
    s3_secret_key: str = "test"
    s3_region: str = "ap-south-1"

    aws_endpoint_url: str | None = "http://localstack:4566"
    aws_region: str = "ap-south-1"
    aws_access_key_id: str = "test"
    aws_secret_access_key: str = "test"

    ocr_provider: str = "google_docai"
    google_project_id: str = "project-7bb85df2-f684-4eb4-958"
    google_docai_location: str = "asia-south1"
    google_docai_processor_id: str = "478659c7b8314834"

    llm_provider: str = "gemini"
    gemini_api_key: str = "replace-me"
    gemini_model: str = "gemini-2.0-flash"

    worker_poll_wait_seconds: int = 20
    worker_batch_size: int = 5
    worker_max_retries: int = 3

    @property
    def is_localstack(self) -> bool:
        return bool(self.aws_endpoint_url)

@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
