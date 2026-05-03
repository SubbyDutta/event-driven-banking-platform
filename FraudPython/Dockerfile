FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --upgrade pip && pip install --no-cache-dir -r requirements.txt

COPY src /app/src
COPY model.pkl /app/model.pkl

EXPOSE 8001

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=6 \
  CMD curl -f http://localhost:8001/health || exit 1

# WEB_CONCURRENCY = number of gunicorn workers (one Python process each — real
# parallelism, since the GIL is per-process). Tune via env at deploy time;
# 4 is a sane default for a 2-core pod. Each worker has its own model copy
# loaded into RAM at startup, so memory scales linearly with concurrency.
ENV WEB_CONCURRENCY=4
CMD ["sh", "-c", "exec gunicorn -k uvicorn.workers.UvicornWorker -w ${WEB_CONCURRENCY} --bind 0.0.0.0:8001 --timeout 30 --graceful-timeout 20 --access-logfile - src.main:app"]
