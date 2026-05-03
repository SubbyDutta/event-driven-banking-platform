from __future__ import annotations

import logging
import re
import sys
from contextvars import ContextVar

from pythonjsonlogger import jsonlogger

from src.config import get_settings

correlation_id_var: ContextVar[str] = ContextVar("correlation_id", default="-")

_PAN_RE = re.compile(r"\b([A-Z])[A-Z]{4}(\d{4})([A-Z])\b")
_AADHAAR_RE = re.compile(r"\b(\d{4})\s?(\d{4})\s?(\d{4})\b")

def _mask_pan(match: re.Match[str]) -> str:
    return f"{match.group(1)}XXXX####{match.group(3)}"

def _mask_aadhaar(match: re.Match[str]) -> str:
    return f"XXXX XXXX {match.group(3)}"

class PiiMaskingFilter(logging.Filter):
    """Redact PAN and Aadhaar anywhere in formatted log output."""

    def filter(self, record: logging.LogRecord) -> bool:
        if isinstance(record.msg, str):
            msg = record.msg
            msg = _PAN_RE.sub(_mask_pan, msg)
            msg = _AADHAAR_RE.sub(_mask_aadhaar, msg)
            record.msg = msg
        if record.args:
            masked_args = []
            for a in record.args if isinstance(record.args, tuple) else (record.args,):
                if isinstance(a, str):
                    a = _PAN_RE.sub(_mask_pan, a)
                    a = _AADHAAR_RE.sub(_mask_aadhaar, a)
                masked_args.append(a)
            record.args = tuple(masked_args)
        return True

class CorrelationIdFilter(logging.Filter):
    def filter(self, record: logging.LogRecord) -> bool:
        record.correlationId = correlation_id_var.get()
        return True

def configure_logging() -> None:
    settings = get_settings()
    root = logging.getLogger()
    if getattr(root, "_findoc_configured", False):
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
    handler.addFilter(PiiMaskingFilter())
    root.addHandler(handler)
    for noisy in ("botocore", "boto3", "urllib3", "s3transfer"):
        logging.getLogger(noisy).setLevel(logging.WARNING)
    root._findoc_configured = True  # type: ignore[attr-defined]

def get_logger(name: str) -> logging.Logger:
    configure_logging()
    return logging.getLogger(name)
