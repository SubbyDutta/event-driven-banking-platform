from __future__ import annotations

import uuid

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from starlette.middleware.base import BaseHTTPMiddleware

from src.api import admin as admin_router
from src.api import admin_observability as admin_obs_router
from src.api import applications as apps_router
from src.api import health as health_router
from src.config import get_settings
from src.logging_config import configure_logging, correlation_id_var, get_logger

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

def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(
        title="findoc-verify",
        version="0.2.0",
        description="KYC + loan-origination document verification service",
    )
    env_origins = [o.strip() for o in (settings.cors_origins or "").split(",") if o.strip()]
    allowed_origins = list({settings.frontend_origin, *env_origins})
    app.add_middleware(
        CORSMiddleware,
        allow_origins=allowed_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["X-Correlation-Id"],
    )
    app.add_middleware(CorrelationIdMiddleware)

    app.include_router(health_router.router)
    app.include_router(admin_router.router)
    app.include_router(admin_obs_router.router)
    app.include_router(apps_router.router)
    app.include_router(apps_router.loan_router)
    return app

app = create_app()
