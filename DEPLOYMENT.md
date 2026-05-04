# AWS Deployment

This document describes how Subby is deployed to AWS — the actual decisions,
resources, and tradeoffs as shipped, not an aspirational plan.

> **Status:** backend services deployed and CI/CD live. **Frontend is not yet
> deployed** — see [Pending work](#pending-work).

---

## Topology

```
                     ┌──────────────────────────────────────────┐
                     │ EC2 t3.small (Amazon Linux 2023)         │
                     │ Elastic IP attached, 2 GB RAM + 4 GB swap│
                     │                                          │
                     │  docker compose -f docker-compose.aws.yml│
                     │   ├─ subby-bank        :8080  (Java 21)  │
                     │   ├─ findoc-verify     :8000  (FastAPI)  │
                     │   ├─ findoc-verify-workers   (worker pool)│
                     │   ├─ subby-python-loan :8002  (FastAPI)  │
                     │   └─ redis             :6379             │
                     │                                          │
                     │  IAM instance role: subby-ec2-role       │
                     │  (sns:Publish, sqs:*, s3:* on bucket)    │
                     └──────┬───────────────┬───────────────────┘
                            │               │
                    ┌───────▼─────┐  ┌──────▼──────────────┐
                    │ RDS Postgres│  │ AWS managed services│
                    │ db.t3.micro │  │ - SNS (16 topics)   │
                    │ subbybank,  │  │ - SQS (54 queues)   │
                    │ findoc,     │  │ - S3 (1 bucket)     │
                    │ subby_loan  │  │ - Gmail SMTP        │
                    │ private VPC │  │   (subhamdutta...)  │
                    └─────────────┘  └─────────────────────┘
```

**Region:** `ap-south-1` (Mumbai).

---

## What is and isn't deployed

| Component | Status | Notes |
| :--- | :--- | :--- |
| `SubbyBankbackend` (Spring Boot) | ✅ deployed | Profile: `aws`. Hibernate `update` creates schema on first boot; Flyway disabled. |
| `findoc-verify` (FastAPI) | ✅ deployed | Connects to real SNS/SQS/S3 + Google Document AI via mounted credentials. |
| `findoc-verify-workers` | ✅ deployed | Reuses `findoc-verify` image, runs `python -m src.workers.run_all`. |
| `SubbyPythonLoan` (XGBoost worker) | ✅ deployed | Alembic migrations run on boot. |
| Redis | ✅ deployed | In-container; Spring Boot uses it for caching only. |
| Postgres | ✅ deployed | RDS `db.t3.micro`, three DBs: `subbybank`, `findoc`, `subby_loan`. |
| SNS topics + SQS queues | ✅ deployed | 16 topics, 27 queues + 27 DLQs, names identical to LocalStack init. |
| `FraudPython` | ❌ **intentionally omitted** | t3.small can't fit it alongside SubbyPythonLoan. Spring Boot's `FraudClient` enters DEGRADED mode (low-value transfers allowed, high-value rejected). See [Constraints](#constraints-and-decisions). |
| `smartbank` (React UI) | ❌ not yet deployed | Backend reachable via `http://<EIP>:8080`. Frontend deployment to S3 + CloudFront pending. |
| `findoc-verify/webui` | ❌ not yet deployed | Same as above. |
| `findoc-ai` | ❌ excluded by design | Not part of this deploy scope. |

---

## AWS resources

| Resource | Spec | Purpose |
| :--- | :--- | :--- |
| EC2 instance | `t3.small` (2 GB RAM, 2 vCPU), 20 GB gp3, 4 GB swap | All app containers + Redis |
| Elastic IP | attached to EC2 | Stable public address |
| Security group `subby-ec2-sg` | inbound 22, 80, 443, 8080 (scoped) | Public HTTP, dev SSH |
| Security group `subby-rds-sg` | inbound 5432 from `subby-ec2-sg` only | Locks RDS to the EC2 |
| RDS Postgres | `db.t3.micro`, 20 GB gp3, single-AZ, no backups (demo) | Three service databases |
| IAM role `subby-ec2-role` | inline policy: SNS publish, SQS full, S3 on bucket prefix | Instance profile attached to EC2 |
| S3 bucket | `subby-prod-documents-9680`, all public access blocked | Document storage |
| SNS topics | 16 (full set from `infra/localstack-init.sh` + `findoc-verify/scripts/localstack-init.sh`) | Pub/sub |
| SQS queues | 27 primary + 27 DLQs, redrive policy `maxReceiveCount=3` | Workers |

---

## Constraints and decisions

### Why `t3.small` instead of `t3.micro` (free tier)

The combined runtime memory of all backend services is ~1.6 GB. `t3.micro`
(1 GB RAM) cannot run them even with swap; Spring Boot startup alone needs
~350 MB heap. `t3.small` (2 GB) was chosen as the cheapest practical option;
~$15/mo. Free tier is 12 months of `t3.micro` only — not workable for this stack.

### Why `FraudPython` is omitted

With all five services, runtime memory is ~2.0 GB on a 2 GB box — every
boot risks OOM. Dropping `FraudPython` brings it to ~1.2 GB with breathing
room. The Java backend's `FraudClient` already implements a DEGRADED mode for
exactly this case: when fraud-python is unreachable, low-value transfers
proceed (defensible default for a demo) and high-value transfers are
rejected. No code change was needed.

### Why Flyway is disabled in the `aws` profile

The Flyway migrations (`V1__event_infra.sql`, `V2__kyc.sql`, ...) assume
the base `users` table already exists from prior Hibernate auto-DDL runs in
local dev. On a fresh RDS this assumption fails — V2 references `users`
before it has been created. Rather than backfill a `V0__bootstrap.sql`,
the `aws` profile sets `ddl-auto: update` and `flyway.enabled: false`,
letting Hibernate create the schema from JPA entities. If new Flyway-only
tables are added later (not modeled as entities), reintroduce a `V0` and
re-enable Flyway.

### Database password strategy

The per-app role passwords (`subby/subby`, `findoc/findoc`,
`subbyloan/subbyloan`) are weak by design. **RDS is locked to the EC2
security group** — port 5432 is unreachable from the public internet. The
RDS master password (used only for admin tasks) is the strong one. To
harden later: `ALTER USER ... WITH PASSWORD '<strong>'` on RDS, update
all three URLs in `.env.aws`, recreate containers.

### Google Document AI authentication

An organization policy on the Google Cloud account
(`iam.disableServiceAccountKeyCreation`) blocks generating service-account
JSON keys. Path used: **Application Default Credentials (ADC)** — the same
file that local docker-compose mounts. The `gcloud auth application-default
login` artifact at `~/AppData/Roaming/gcloud/application_default_credentials.json`
is copied to `secrets/google-sa.json` and mounted into the findoc-verify
container at `/app/secrets/google-sa.json` via `GOOGLE_APPLICATION_CREDENTIALS`.

The Google client SDK auto-detects user-credential ADC vs service-account
JSON from the same file, so no code change. **Caveat:** ADC ties Doc AI
calls to the personal Google account that ran `gcloud auth login`. If the
token is revoked or the password rotated, the deploy breaks until the file
is regenerated.

---

## CI/CD

Two GitHub Actions workflows in `.github/workflows/`:

### `ci.yml` — runs on every push and PR

Four parallel jobs verify the codebase compiles before any deploy:

- `build-java` — `mvn package` on Spring Boot.
- `build-findoc` — pip install + Python import smoke check.
- `build-loan` — same for SubbyPythonLoan.
- `docker-build-check` — builds all three Dockerfiles in a clean GitHub
  runner. Catches Dockerfile bugs that wouldn't surface on a pre-warmed EC2.

Total: ~5–8 min. Failed CI blocks the deploy workflow from firing.

### `deploy.yml` — runs after CI passes on `main`

SSHes into the EC2 with a deploy keypair (separate from your personal one),
runs:

```bash
git fetch --all && git reset --hard origin/main
docker compose --env-file .env.aws -f docker-compose.aws.yml build subby-bank
docker compose --env-file .env.aws -f docker-compose.aws.yml build findoc-verify
docker compose --env-file .env.aws -f docker-compose.aws.yml build subby-python-loan
docker compose --env-file .env.aws -f docker-compose.aws.yml up -d
sleep 60
# smoke test all four /health endpoints — fail-loud if any is unhealthy
docker image prune -f
```

Builds run **sequentially** (not parallel) — parallel pip installs of
pandas/xgboost OOM the t3.small. A 25-min timeout covers the worst-case
cold-cache build.

Failed deploys leave the EC2 on the previous code (manual rollback documented
[below](#rollback)).

### Required GitHub secrets

| Name | Value |
| :--- | :--- |
| `EC2_HOST` | The Elastic IP |
| `EC2_USER` | `ec2-user` |
| `EC2_SSH_KEY` | Private half of the `gh_deploy_key` ed25519 keypair |

The deploy public key is appended to `~/.ssh/authorized_keys` on the EC2.

---

## Environment configuration

### File layout

| File | Location | Tracked in git? |
| :--- | :--- | :--- |
| `.env.aws.example` | repo root | yes — placeholders only |
| `.env.aws` | EC2 `~/subby/.env.aws`, `chmod 600` | **no** — contains all production secrets |
| `secrets/google-sa.json` | EC2 `~/subby/secrets/`, `chmod 600` | **no** — gcloud ADC file |

### Critical variables in `.env.aws`

```bash
AWS_REGION=ap-south-1
AWS_ENDPOINT_URL=                # MUST be empty — overrides hardcoded LocalStack default
S3_ENDPOINT_URL=                 # same — overrides default

RDS_HOST=subby-prod.<id>.ap-south-1.rds.amazonaws.com

DATABASE_URL=jdbc:postgresql://${RDS_HOST}:5432/subbybank
POSTGRES_USER=subby
POSTGRES_PASSWORD=subby

FINDOC_DATABASE_URL=postgresql+asyncpg://findoc:findoc@${RDS_HOST}:5432/findoc
SUBBY_LOAN_DATABASE_URL=postgresql+asyncpg://subbyloan:subbyloan@${RDS_HOST}:5432/subby_loan

S3_BUCKET=subby-prod-documents-9680

MAIL_HOST=smtp.gmail.com
MAIL_USERNAME=subhamdutta4289@gmail.com
MAIL_PASSWORD=<16-char-app-password>     # generated at myaccount.google.com/apppasswords

JWT_SECRET=<48-char-random>
MYSECRETKEY=<32-hex-chars>
FIXED=<16-hex-chars>
ADMINPASSWORD=<20-char-random>

GEMINI_API_KEY=<from-aistudio.google.com>
GOOGLE_PROJECT_ID=project-7bb85df2-f684-4eb4-958
GOOGLE_DOCAI_LOCATION=<region>
GOOGLE_DOCAI_PROCESSOR_ID=<hex-id>
```

The `${RDS_HOST}` interpolation requires `--env-file .env.aws` on every
`docker compose` invocation; without it, compose interpolates only from
the auto-loaded `.env` file (which doesn't exist) and the values come out
blank.

---

## Manual deploy from scratch

Order is important; each step depends on the previous. Run from your laptop
unless noted otherwise.

```bash
# 1. Provision SNS + SQS
bash infra/aws-bootstrap.sh

# 2. Provision RDS via console (db.t3.micro, single-AZ, public access No,
#    private SG `subby-rds-sg`).

# 3. Provision EC2 (t3.small, default VPC, EIP attached, SG `subby-ec2-sg`).

# 4. Attach IAM role `subby-ec2-role` to the EC2.

# 5. Allow EC2 SG -> RDS SG on 5432.
#    aws ec2 authorize-security-group-ingress --group-id <rds-sg> \
#      --protocol tcp --port 5432 --source-group <ec2-sg>

# 6. SSH to EC2 and provision base tools.
ssh -i subby-key.pem ec2-user@<eip>
sudo dnf install -y docker git postgresql15
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user && exec sg docker

mkdir -p ~/.docker/cli-plugins
curl -SL https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64 \
  -o ~/.docker/cli-plugins/docker-compose
chmod +x ~/.docker/cli-plugins/docker-compose
curl -SL https://github.com/docker/buildx/releases/download/v0.18.0/buildx-v0.18.0.linux-amd64 \
  -o ~/.docker/cli-plugins/docker-buildx
chmod +x ~/.docker/cli-plugins/docker-buildx

# 7. Add 4 GB swap.
sudo dd if=/dev/zero of=/swap bs=1M count=4096
sudo chmod 600 /swap && sudo mkswap /swap && sudo swapon /swap
echo '/swap none swap sw 0 0' | sudo tee -a /etc/fstab

# 8. Initialize the three databases.
psql "host=$RDS_HOST port=5432 dbname=postgres user=postgres" -f infra/postgres-init.sql

# 9. Clone repo + drop in secrets.
git clone https://<PAT>@github.com/<user>/<repo>.git subby
cd subby
mkdir -p secrets
# scp google-sa.json and create .env.aws by hand (chmod 600 both)

# 10. First deploy.
docker compose --env-file .env.aws -f docker-compose.aws.yml build subby-bank
docker compose --env-file .env.aws -f docker-compose.aws.yml build findoc-verify
docker compose --env-file .env.aws -f docker-compose.aws.yml build subby-python-loan
docker compose --env-file .env.aws -f docker-compose.aws.yml up -d
```

Everything after this point is automated by the `deploy.yml` workflow.

---

## Smoke testing

```bash
# On EC2
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8000/api/v1/health
curl -s http://localhost:8002/health

# From laptop (requires SG inbound 8080 from current public IP)
curl http://<EIP>:8080/actuator/health
```

The deploy workflow runs the EC2-local versions automatically and exits
non-zero if any health check fails.

---

## Rollback

GitHub Actions does not auto-rollback. If a deploy lands broken code:

```bash
ssh -i subby-key.pem ec2-user@<eip>
cd ~/subby
git log --oneline -5                # find the last good SHA
git reset --hard <good-sha>
docker compose --env-file .env.aws -f docker-compose.aws.yml up -d --build
```

For an instant rollback workflow (zero downtime, image-tagged releases),
move to ECR + tagged images. Out of scope for this iteration.

---

## Costs

Approximate monthly cost (Mumbai region, demo traffic):

| Item | $/mo |
| :--- | ---: |
| EC2 `t3.small` | ~15 |
| RDS `db.t3.micro` (free tier — 12 months) | 0 |
| Elastic IP (attached) | 0 |
| S3 storage + requests | <1 |
| SNS + SQS demo traffic (well under 1M ops/mo always-free) | 0 |
| Egress (low traffic demo) | ~1 |
| **Total** | **~$17** |

After the 12-month RDS free tier expires, RDS adds ~$15/mo.

---

## Pending work

In rough priority order:

1. **Frontend deploy.** Build `smartbank` to static, push to S3 with website
   hosting, optionally front with Cloudflare for HTTPS. Update `FRONTEND_URL`
   and `FRONTEND_ORIGIN` in `.env.aws`. Same pattern for `findoc-verify/webui`.
2. **HTTPS for the API.** Currently the bank API is HTTP on port 8080.
   Easiest path: Cloudflare proxy in front of an nginx reverse proxy on the
   EC2; ALB + ACM is the more "AWS-native" path but adds ~$18/mo.
3. **Bring `FraudPython` back online.** Either upgrade EC2 to `t3.medium`
   (~$30/mo) or move FraudPython to a Lambda triggered by HTTP from the bank.
4. **Move `.env.aws` into SSM Parameter Store.** Currently a flat file on
   the EC2; SSM is free and gives you audit + rotation primitives.
5. **CloudWatch alarms.** DLQ depth > 0, RDS free storage < 10%, EC2 CPU
   sustained > 80%.
6. **Tighten dev SG rules.** Port 8080 is open to a fixed IP for testing;
   remove once HTTPS is live.

---

## Operational runbook

| Symptom | Fix |
| :--- | :--- |
| Push to `main` doesn't deploy | Check **Actions** tab — CI may be red, blocking deploy. |
| GHA deploy fails with SSH timeout | Your dev IP changed; the deploy key still works, but the EC2 is unreachable from GHA's runners (rare). Check EC2 status and SG rules. |
| Service unhealthy after deploy | `docker compose --env-file .env.aws -f docker-compose.aws.yml logs <service> --tail 100` |
| Spring Boot can't connect to DB | Verify `.env.aws` has the per-app passwords (`subby:subby`, etc.), not the RDS master password. |
| boto3 errors `localstack:4566` | `.env.aws` is missing `AWS_ENDPOINT_URL=` (empty). |
| Email not sending | Confirm Gmail app password (not regular password), 16 chars, no spaces. Check Gmail's "Less secure apps" / 2FA status. |
| RDS connection timeout from laptop | RDS is private by design. Use EC2 as a jump host: `psql "host=$RDS_HOST ..."` from the EC2 shell. |
