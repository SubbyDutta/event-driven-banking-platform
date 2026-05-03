from __future__ import annotations

import asyncio
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from starlette.middleware.base import BaseHTTPMiddleware

from src.api.debug import router as debug_router
from src.config import get_settings
from src.logging_config import configure_logging, correlation_id_var, get_logger
from src.messaging.sns_publisher import SnsPublisher
from src.model.predictor import RiskPredictor, warm
from src.worker.risk_worker import RiskWorker

configure_logging()
logger = get_logger(__name__)

class CorrelationIdMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        cid = request.headers.get("X-Correlation-Id") or str(uuid.uuid4())
        token = correlation_id_var.set(cid)
        try:
            response = await call_next(request)
            response.headers["X-Correlation-Id"] = cid
            return response
        finally:
            correlation_id_var.reset(token)

@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()

    predictor = RiskPredictor.load()
    warm(predictor)
    app.state.predictor = predictor

    publisher = SnsPublisher()
    app.state.publisher = publisher

    worker = RiskWorker(predictor=predictor, publisher=publisher)
    app.state.worker = worker
    app.state.worker_task = asyncio.create_task(worker.run_forever(), name="risk_worker")
    logger.info("subby-python-loan started", extra={"env": settings.app_env})

    try:
        yield
    finally:
        logger.info("subby-python-loan shutting down")
        worker.stop()
        app.state.worker_task.cancel()
        await asyncio.gather(app.state.worker_task, return_exceptions=True)

def create_app() -> FastAPI:
    app = FastAPI(
        title="SubbyPythonLoan",
        version="0.2.0",
        description="Event-driven loan risk scoring (SQS consumer + sync debug endpoint).",
        lifespan=lifespan,
    )
    app.add_middleware(CorrelationIdMiddleware)
    app.include_router(debug_router)

    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app

app = create_app()
