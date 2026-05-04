# Running Subby on a laptop

This is the long-form, hand-held version of the Quickstart in [`README.md`](README.md). It assumes you've never seen this repo before.

The whole stack — five backend services, two React frontends, Postgres, Redis, LocalStack (SNS / SQS / S3 emulator), MinIO, and MailHog — comes up with a single `docker compose up`. You only need to install Docker and Node on the host. Everything else runs in containers.

---

## 1. Prerequisites

| Tool | Version | Why |
| :--- | :--- | :--- |
| Docker Desktop | 24+ (with Compose v2) | Runs the entire backend stack |
| Node.js | 18 LTS or 20 LTS | Builds the two React frontends |
| npm | 9+ | Comes with Node |
| Git | any recent | Clone the repo |
| Bash / Git Bash / WSL | — | The smoke-test scripts under `infra/` are bash |

You do **not** need a local JDK, Maven, Python, or pip — those run inside the containers. Only install them if you plan to run a service outside Docker for debugging.

Recommended host resources: **8 GB RAM** free, **10 GB** disk for images and Postgres data. The first build takes 5–10 minutes; subsequent boots are ~90 seconds.

### Optional credentials

These are only needed if you want the loan and KYC pipelines to run end-to-end against real cloud APIs. Without them the stack still boots and you can sign up, transfer, and use the admin UI — only OCR and LLM extraction will be no-ops.

- **Google Document AI** — service-account JSON for OCR.
- **Gemini API key** — used for structured field extraction.
- **Razorpay test keys** — only required to exercise the top-up / withdraw flow against Razorpay's sandbox.

---

## 2. Clone and configure

```bash
git clone <this-repo>
cd code
cp .env.example .env
```

Open `.env` and fill in whichever of these you actually want to use:

| Variable | Default | What to set it to |
| :--- | :--- | :--- |
| `GEMINI_API_KEY` | `replace-me` | Your Gemini key, or leave as-is to skip LLM extraction |
| `GOOGLE_PROJECT_ID` | `replace-me` | GCP project that owns the Document AI processor |
| `GOOGLE_DOCAI_PROCESSOR_ID` | `replace-me` | Processor ID from the Document AI console |
| `JWT_SECRET` | dev value | Any 32+ character string — only matters for prod-like runs |
| `RAZORPAY_KEY_ID` / `RAZORPAY_SECRET` | placeholders | Razorpay test keys, optional |

If you want OCR to work, drop your Google service-account JSON at `secrets/google-sa.json`. The compose file mounts that path into the `findoc-verify` container at `/app/secrets/google-sa.json`.

The defaults for everything else (Postgres credentials, LocalStack endpoints, queue names) are correct for a local run — don't change them unless you know what you're doing.

---

## 3. Boot the backend

```bash
docker compose up -d --build
```

First build: ~5–10 minutes. Subsequent boots: ~90 seconds for healthchecks.

Watch progress in another terminal:

```bash
docker compose ps
docker compose logs -f subby-bank findoc-verify
```

You're good when every service shows `(healthy)` in `docker compose ps`. Common ports:

| Service | URL | Purpose |
| :--- | :--- | :--- |
| `subby-bank` | http://localhost:8080 | Spring Boot REST API |
| `findoc-verify` | http://localhost:8000 | FastAPI doc-pipeline + 9 SQS workers |
| `subby-python-loan` | http://localhost:8002 | XGBoost loan-risk worker |
| `fraud-python` | http://localhost:8001 | XGBoost fraud scorer |
| `postgres` | `localhost:5433` | Three databases (`subbybank`, `findoc`, `subby_loan`) |
| `redis` | `localhost:6379` | Spring cache + rate-limit token bucket |
| `localstack` | http://localhost:4566 | SNS + SQS + S3 emulator |
| `minio` | http://localhost:9001 | Alt S3 console (login `minio` / `minio12345`) |
| `mailhog` | http://localhost:8025 | Catches every outbound email |

Sanity check:

```bash
curl -s http://localhost:8080/actuator/health/readiness
curl -s http://localhost:8000/healthz
curl -s http://localhost:4566/_localstack/health
```

All three should return `200 OK`.

---

## 4. Boot the frontends

The two React UIs run on the host (not in Docker), because hot-reload through a bind-mounted node_modules on Windows is painful. Open two more terminals:

```bash
cd smartbank
npm install
npm start
```

That serves the customer + admin UI at http://localhost:3000.

```bash
cd findoc-verify/webui
npm install
npm run dev
```

That serves the pipeline operator UI at http://localhost:5173.

---

## 5. End-to-end smoke

The `infra/` directory has scripts that walk the system through real flows.

```bash
./infra/e2e-smoke-test.sh                       # signup -> KYC -> approval
./infra/smoke-loan-disbursed.sh                 # loan apply -> ML -> disburse
./infra/smoke-reverse-reject-then-override.sh   # admin override + reversal
./infra/smoke-replay-approve-to-ml.sh           # event replay safety
```

Or do it by hand — the live demo path in [`README.md`](README.md#live-demo-path-93-seconds-blank-slate--disbursed-loan) takes 93 seconds from signup to a disbursed loan.

---

## 6. Useful commands

```bash
docker compose ps                         # what's healthy
docker compose logs -f subby-bank         # tail one service
docker compose restart findoc-verify      # restart one service after code change

docker compose exec postgres psql -U postgres -d subbybank   # poke the DB
docker compose exec redis redis-cli                          # poke the cache

aws --endpoint-url=http://localhost:4566 sqs list-queues     # talk to LocalStack
aws --endpoint-url=http://localhost:4566 sns list-topics

curl -s http://localhost:8080/actuator/prometheus | grep -E 'outbox|sqs'
```

Reset everything to a blank slate:

```bash
docker compose down -v       # nukes the postgres + localstack volumes
docker compose up -d --build
```

---

## 7. Common problems

**`localstack` exits with code 55.**
You're on `localstack:latest`, which is now LocalStack Pro and needs a license token. The compose file pins to `4.0.3` (community); make sure you didn't override the tag.

**`findoc-verify` keeps restarting with "Document AI permission denied".**
Either drop a real service-account JSON at `secrets/google-sa.json`, or set `OCR_PROVIDER=` to something other than `google_docai` in `.env`. Without an OCR provider the pipeline accepts uploads but won't extract fields.

**Frontends say "Network Error" on every call.**
The Spring Boot service takes 30–40 seconds to finish starting after the container reports healthy. Wait, then refresh.

**"port 5433 already in use".**
You have a host Postgres running on the default 5432 plus our remap. Either stop it or change the host port in `docker-compose.yml`.

**Out of memory on the JVM container.**
Docker Desktop default is 2 GB. Bump the VM to 6 GB+ in Docker Desktop → Settings → Resources.

---

## 8. Going to AWS

The same code runs on AWS by flipping a single profile. See [`DEPLOYMENT.md`](DEPLOYMENT.md) for the bootstrap script, IAM roles, and the GitHub Actions pipeline.
