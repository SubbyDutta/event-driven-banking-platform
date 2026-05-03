import os
import sys
from pathlib import Path
from unittest.mock import MagicMock

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

if "prometheus_client" not in sys.modules:
    _pc = MagicMock()
    _pc.Counter = lambda *a, **kw: MagicMock(labels=lambda *_a, **_kw: MagicMock(inc=lambda *a, **k: None))
    _pc.Histogram = lambda *a, **kw: MagicMock(labels=lambda *_a, **_kw: MagicMock(observe=lambda *a, **k: None, time=lambda: MagicMock(__enter__=lambda s: None, __exit__=lambda s, *a: None)))
    _pc.Gauge = lambda *a, **kw: MagicMock(labels=lambda *_a, **_kw: MagicMock(set=lambda *a, **k: None))
    sys.modules["prometheus_client"] = _pc

os.environ.setdefault("AWS_ENDPOINT_URL", "http://localhost:4566")
os.environ.setdefault("AWS_REGION", "ap-south-1")
os.environ.setdefault("AWS_ACCESS_KEY_ID", "test")
os.environ.setdefault("AWS_SECRET_ACCESS_KEY", "test")
os.environ.setdefault(
    "DATABASE_URL", "postgresql+asyncpg://subbyloan:subbyloan@localhost:5432/subby_loan"
)
os.environ.setdefault("MODEL_PATH", "/tmp/loan_model.pkl")
os.environ.setdefault("SCALER_PATH", "/tmp/scaler.pkl")
