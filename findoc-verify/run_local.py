"""Single-process local entrypoint.

- Ensures the target Postgres database exists (creates it if missing).
- Runs Alembic migrations to head.
- Starts FastAPI (uvicorn) and all 9 SQS workers on one asyncio event loop.
- Ctrl-C stops everything cleanly.

Usage:
    python run_local.py
"""
from __future__ import annotations

import asyncio
import logging
import os
import re
import subprocess
import sys
from pathlib import Path

from dotenv import load_dotenv

ROOT = Path(__file__).resolve().parent
# Load .env before importing settings-consumers.
load_dotenv(ROOT / ".env")

from src.logging_config import configure_logging, get_logger  # noqa: E402

configure_logging()
log = get_logger("run_local")


_PG_URL_RE = re.compile(
    r"postgresql(?:\+\w+)?://(?P<user>[^:]+):(?P<pw>[^@]+)@(?P<host>[^:/]+)(?::(?P<port>\d+))?/(?P<db>[A-Za-z0-9_]+)"
)


def _parse_pg(url: str) -> dict[str, str]:
    m = _PG_URL_RE.match(url)
    if not m:
        raise RuntimeError(f"could not parse DATABASE_URL: {url}")
    return {k: (v or "") for k, v in m.groupdict().items()}


def ensure_database_exists() -> None:
    url = os.getenv("ALEMBIC_DATABASE_URL") or os.getenv("DATABASE_URL", "")
    parts = _parse_pg(url)
    dbname = parts["db"]
    port = parts["port"] or "5432"

    try:
        import psycopg2
        from psycopg2 import sql
    except ImportError:
        log.error("psycopg2 not installed on host — run: pip install -e .")
        sys.exit(1)

    # Connect to the default 'postgres' database to check/create target.
    try:
        conn = psycopg2.connect(
            dbname="postgres",
            user=parts["user"],
            password=parts["pw"],
            host=parts["host"],
            port=port,
        )
    except Exception as e:
        log.error(
            "cannot connect to Postgres at %s:%s as %s — is it running and the password right?\n  %s",
            parts["host"], port, parts["user"], e,
        )
        sys.exit(1)

    conn.autocommit = True
    with conn.cursor() as cur:
        cur.execute("SELECT 1 FROM pg_database WHERE datname = %s", (dbname,))
        if cur.fetchone():
            log.info("database %r already exists", dbname)
        else:
            cur.execute(sql.SQL("CREATE DATABASE {}").format(sql.Identifier(dbname)))
            log.info("created database %r", dbname)
    conn.close()


def run_migrations() -> None:
    log.info("alembic upgrade head")
    result = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        cwd=str(ROOT),
        check=False,
    )
    if result.returncode != 0:
        log.error("alembic upgrade failed (exit %s)", result.returncode)
        sys.exit(result.returncode)


async def _serve_api(stop_event: asyncio.Event) -> None:
    import uvicorn
    config = uvicorn.Config(
        "src.main:app",
        host="0.0.0.0",
        port=int(os.getenv("APP_PORT", "8000")),
        log_level=os.getenv("LOG_LEVEL", "info").lower(),
        reload=False,
        access_log=False,
    )
    server = uvicorn.Server(config)

    async def _watch_stop() -> None:
        await stop_event.wait()
        server.should_exit = True

    watcher = asyncio.create_task(_watch_stop())
    try:
        await server.serve()
    finally:
        watcher.cancel()


async def _main() -> None:
    from src.workers.run_all import WORKER_CLASSES, _run_with_restart

    stop_event = asyncio.Event()

    def _handle_sig() -> None:
        log.info("shutdown signal received")
        stop_event.set()

    loop = asyncio.get_running_loop()
    for sig_name in ("SIGTERM", "SIGINT"):
        import signal
        sig = getattr(signal, sig_name, None)
        if sig is None:
            continue
        try:
            loop.add_signal_handler(sig, _handle_sig)
        except NotImplementedError:
            # Windows: fall back to default KeyboardInterrupt handling.
            pass

    worker_tasks = [
        asyncio.create_task(_run_with_restart(c), name=c.__name__)
        for c in WORKER_CLASSES
    ]
    api_task = asyncio.create_task(_serve_api(stop_event), name="uvicorn")

    log.info(
        "findoc-verify up — API http://localhost:%s · MinIO console http://localhost:9001 · LocalStack http://localhost:4566",
        os.getenv("APP_PORT", "8000"),
    )
    log.info("press Ctrl-C to stop")

    try:
        await asyncio.gather(api_task, *worker_tasks)
    except asyncio.CancelledError:
        pass


def main() -> None:
    ensure_database_exists()
    run_migrations()
    try:
        asyncio.run(_main())
    except KeyboardInterrupt:
        log.info("shutdown")


if __name__ == "__main__":
    main()
