from __future__ import annotations

import logging
import sys
from contextvars import ContextVar

from pythonjsonlogger import jsonlogger

from src.config import get_settings

correlation_id_var: ContextVar[str] = ContextVar("correlation_id", default="-")

class CorrelationIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.correlationId = correlation_id_var.get()
        return True

def configure_logging() -> None:
    settings = get_settings()
    root = logging.getLogger()
    if getattr(root, "_fraudpython_configured", False):
        return
    root.setLevel(settings.log_level.upper())
    for h in list(root.handlers):
        root.removeHandler(h)

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        jsonlogger.JsonFormatter(
            "%(asctime)s %(levelname)s %(name)s [%(correlationId)s] %(message)s",
            rename_fields={"asctime": "ts", "levelname": "level", "name": "logger"},
        )
    )
    handler.addFilter(CorrelationIdFilter())
    root.addHandler(handler)
    for noisy in ("botocore", "boto3", "urllib3", "s3transfer"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    root._fraudpython_configured = True  # type: ignore[attr-defined]

def get_logger(name: str) -> logging.Logger:
    configure_logging()
    return logging.getLogger(name)
