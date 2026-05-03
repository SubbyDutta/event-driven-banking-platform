from __future__ import annotations

import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, Response
from fastapi.exceptions import RequestValidationError
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from starlette.middleware.base import BaseHTTPMiddleware

from src.api.predict import router as predict_router
from src.api.predict import validation_handler
from src.config import get_settings
from src.logging_config import configure_logging, correlation_id_var, get_logger
from src.model.predictor import FraudPredictor, warm

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
    predictor = FraudPredictor.load()
    warm(predictor)
    app.state.predictor = predictor
    logger.info("fraud-python started", extra={"env": settings.app_env})
    try:
        yield
    finally:
        logger.info("fraud-python shutting down")

def create_app() -> FastAPI:
    app = FastAPI(
        title="FraudPython",
        version="0.2.0",
        description="Fraud detection scoring service (sync /predict on the transfer hot path).",
        lifespan=lifespan,
    )
    app.add_middleware(CorrelationIdMiddleware)
    app.add_exception_handler(RequestValidationError, validation_handler)
    app.include_router(predict_router)

    @app.api_route("/health", methods=["GET", "HEAD"])
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/metrics", include_in_schema=False)
    def metrics() -> Response:
        return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)

    return app

app = create_app()
