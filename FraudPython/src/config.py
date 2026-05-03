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
    app_port: int = 8001
    log_level: str = "INFO"

    model_path: str = "/app/model.pkl"
    model_version: str = "v1.0.0"

    amount_threshold: float = 50_000.0
    critical_balance_mult: float = 0.5
    fraud_decision_threshold: float = 0.5

@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
