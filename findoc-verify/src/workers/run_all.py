"""Entrypoint that launches every worker in-process, each on its own asyncio task.

Used by the `app-workers` container. A worker crashing is isolated — its task is
restarted. Graceful shutdown on SIGTERM.
"""
from __future__ import annotations

import asyncio

from src.logging_config import get_logger
from src.workers.aggregate_worker import AggregateWorker
from src.workers.classify_worker import ClassifyWorker
from src.workers.compliance_worker import ComplianceWorker
from src.workers.crossdoc_worker import CrossDocWorker
from src.workers.extract_worker import ExtractWorker
from src.workers.fraud_worker import FraudWorker
from src.workers.ocr_worker import OcrWorker
from src.workers.result_publisher import ResultPublisher
from src.workers.risk_worker import RiskWorker

logger = get_logger(__name__)

WORKER_CLASSES = [
    OcrWorker,
    ClassifyWorker,
    ExtractWorker,
    AggregateWorker,
    ComplianceWorker,
    CrossDocWorker,
    FraudWorker,
    RiskWorker,
    ResultPublisher,
]

async def _run_with_restart(cls) -> None:
    while True:
        try:
            w = cls()
            await w.run_forever()
            return
        except Exception:
            logger.exception("worker %s crashed — restarting in 5s", cls.__name__)
            await asyncio.sleep(5)

async def main() -> None:
    logger.info("starting all workers: %s", [c.__name__ for c in WORKER_CLASSES])
    tasks = [asyncio.create_task(_run_with_restart(c), name=c.__name__) for c in WORKER_CLASSES]
    await asyncio.gather(*tasks)

if __name__ == "__main__":
    asyncio.run(main())
