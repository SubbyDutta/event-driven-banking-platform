# Deployment Journal

A chronological account of taking Subby from local docker-compose to a working
AWS deployment with backend services, both frontends, and CI/CD. Includes every
issue we hit, the fix, and the lessons that became the playbook for future
changes.

This is the document to read **before** redeploying or extending the system.

---

## Table of contents

1. [Environment summary as shipped](#environment-summary-as-shipped)
2. [Phase-by-phase log](#phase-by-phase-log)
3. [Issues we hit and how we fixed them](#issues-we-hit-and-how-we-fixed-them)
4. [Future-update playbook](#future-update-playbook)
5. [Common operations cheatsheet](#common-operations-cheatsheet)

---

## Environment summary as shipped

| Layer | Resource | Identifier (sanitize before sharing) |
| :--- | :--- | :--- |
| Region | AWS | `ap-south-1` |
| Compute | EC2 instance | `subby-prod`, `t3.small`, Amazon Linux 2023, 30 GB EBS gp3 |
| Compute | Elastic IP | `35.154.101.168` |
| Compute | EC2 IAM role | `subby-ec2-role` (sns:Publish + sqs:* + s3:* on bucket prefix) |
| Compute | EC2 SG | `subby-ec2-sg` — inbound 22 (My IP), 80, 443, 8080, 8000 (0.0.0.0/0) |
| Database | RDS Postgres 16 | `subby-prod`, `db.t3.micro`, single-AZ, public access No, 20 GB |
| Database | RDS SG | `subby-rds-sg` — inbound 5432 from `subby-ec2-sg` only |
| Database | DBs | `subbybank` (subby/subby), `findoc` (findoc/findoc), `subby_loan` (subbyloan/subbyloan) |
| Messaging | SNS topics | 16 (mirrors `infra/localstack-init.sh`) |
| Messaging | SQS queues | 27 + 27 DLQs, RawMessageDelivery=true, redrive `maxReceiveCount=3` |
| Storage | Documents bucket | `subby-prod-documents-9680` (private, IAM-gated) |
| Storage | smartbank frontend bucket | `subby-frontend-8909` (public read, static website hosting) |
| Storage | findoc webui bucket | `subby-findoc-webui-2607` (public read, static website hosting) |
| Auth | Gmail SMTP | `subhamdutta4289@gmail.com` + 16-char app password |
| Auth | Google Doc AI | ADC user-credential (`gcloud auth application-default login`), mounted at `/app/secrets/google-sa.json` |
| Auth | Bank → findoc-verify | API key labeled `subby-java-backend` (scope: `submit`), value in `.env.aws` as `FINDOC_API_KEY` |
| Auth | Admin webui | API key labeled `demo-admin` (scope: `admin`), accessed by smartbank via `/api/admin/findoc-key` and passed to webui via URL fragment |
| LLM | Gemini model | `gemini-2.0-flash` (15 RPM free-tier quota) |

**Frontends are deployed but the FraudPython service is not.** Spring Boot's
`FraudClient` runs in DEGRADED mode: any non-flagged transfer goes through;
foreign or `is_high_risk=1` transfers are rejected.

---

## Phase-by-phase log

### Phase 1 — Repo prep

- Pushed monorepo to private GitHub repo `event-driven-banking-platform`.
- Confirmed `.gitignore` blocks `.env`, `secrets/`, `*.pem`, `*.key`,
  `gh_deploy_key`, `gh_deploy_key.pub`.
- Added `docker-compose.aws.yml` — strips localstack/minio/mailhog/postgres,
  drops fraud-python, makes `findoc-verify-workers` reuse the
  `findoc-verify` image.
- Added `.env.aws.example` with placeholders for every required variable.
- Added `infra/aws-bootstrap.sh` — port of the two LocalStack init scripts
  to real AWS CLI, idempotent, run once from laptop with admin creds.

### Phase 2 — AWS account setup

- Signed up, region `ap-south-1`.
- Enabled MFA on root, created budget alert at $10/mo with email at
  50/90/100%, enabled free-tier alerts.
- Created IAM user `subham-admin` with `AdministratorAccess`, MFA enabled.
- Created CLI access key under "Command Line Interface (CLI)" use-case.
- Configured AWS CLI on laptop with `aws configure`. Verified with
  `aws sts get-caller-identity`.

### Phase 3 — AWS resources

- **S3 documents bucket** created (`subby-prod-documents-9680`) with public
  access blocked.
- **SNS + SQS** provisioned via `bash infra/aws-bootstrap.sh` from Git Bash.
- **RDS** created via console, db.t3.micro, free-tier template, single-AZ,
  public access No, master user `postgres`, security group `subby-rds-sg`.
  Saved master password.
- **EC2** launched, Amazon Linux 2023, t3.small (originally created tighter,
  later resized — see Phase 6), key pair `subby-key.pem` downloaded and
  permissions locked via `icacls`.
- **Elastic IP** allocated and associated with the EC2.
- **IAM instance role** `subby-ec2-role` with inline policy granting SNS,
  SQS, and S3 on the prefix — attached to the EC2 instance.
- **Open RDS to EC2**: edited `subby-rds-sg` to allow Postgres (5432) source
  = `subby-ec2-sg`.

### Phase 4 — First manual deploy

- SSH'd to EC2, installed `docker`, `git`, `postgresql15`, started Docker
  daemon.
- Installed Docker Compose v2 plugin (`~/.docker/cli-plugins/docker-compose`).
- Installed buildx v0.18.0 manually because Amazon Linux 2023 ships an
  older buildx that compose rejected.
- Added 4 GB swap (`/swap`).
- Initialized RDS schemas via `psql -f infra/postgres-init.sql`.
- Cloned repo with PAT-embedded HTTPS URL.
- `scp`'d `google-sa.json` to `~/subby/secrets/`.
- Created `.env.aws` on the EC2 (`chmod 600`) with all real values.
- Built services sequentially (parallel pip-install OOMs t3.small).
- Brought up the stack with
  `docker compose --env-file .env.aws -f docker-compose.aws.yml up -d`.

### Phase 5 — CI/CD

- Generated `gh_deploy_key` (ed25519) on laptop.
- Public half appended to EC2 `~/.ssh/authorized_keys`.
- Private half + EIP + `ec2-user` added as GitHub repo secrets:
  `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`.
- Added `.github/workflows/ci.yml` — verifies Java + 3 Python services build
  on a clean GHA runner, plus a Docker build sanity check.
- Added `.github/workflows/deploy.yml` — fires on `workflow_run` after CI
  green on `main`, SSHes in, sequential build, smoke test, image prune.

### Phase 6 — Frontend deploy

- Patched `smartbank/src/api.js` to read `process.env.REACT_APP_API_URL` with
  a `/api` fallback for local dev.
- Patched `findoc-verify/webui/src/api.ts` to prepend
  `import.meta.env.VITE_API_URL` to each fetch path.
- Created `smartbank/.env.production` and `findoc-verify/webui/.env.production`
  with the EC2 EIP baked in (gitignored).
- Built locally: `npm run build` for both frontends.
- Created two more S3 buckets:
  - `subby-frontend-8909` — smartbank
  - `subby-findoc-webui-2607` — findoc webui
- Configured each for **static website hosting** with
  `--error-document index.html` (so React Router routes survive a refresh).
- Disabled "Block Public Access" on these two buckets only, applied a
  public-read bucket policy.
- Synced `build/` (CRA) and `dist/` (Vite) to the respective buckets with
  `aws s3 sync ... --delete`.
- Opened EC2 SG ports 8080 and 8000 to `0.0.0.0/0` so browsers can reach
  the APIs from any user's network.

### Phase 7 — CORS plumbing

- Patched `SubbyBankbackend/.../CorsConfig.java` to expose
  `CorsConfigurationSource` (not `CorsFilter`) and read additional origins
  from the `frontend.url` Spring property.
- Patched `SecurityConfig.java` to call `http.cors(Customizer.withDefaults())`
  so the security filter chain wires CORS in **before** the auth check —
  fixes OPTIONS preflights to protected endpoints.
- Updated `.env.aws` on EC2: `FRONTEND_URL` and `FRONTEND_ORIGIN` set to
  the smartbank S3 website URL.
- For findoc-verify: updated `CORS_ORIGINS` env var (comma-separated) to
  include both S3 frontends.

### Phase 8 — Schema fixes for Hibernate auto-DDL

Running on a fresh RDS (Hibernate `update`, Flyway disabled) hit several
columns with `insertable=false`/`updatable=false` JPA annotations expecting
the database to provide a `DEFAULT NOW()`. Hibernate's auto-DDL doesn't
generate that. Fixed across all three databases with a generic loop that
finds every `NOT NULL` `*_at` column lacking a default and adds `DEFAULT NOW()`.

Affected tables: `outbox_events`, `loan_decision_overrides`,
`kyc_decision_overrides`, plus a backfill `UPDATE ... SET <col> = NOW()`
for any pre-existing null rows. See [Issues](#issues-we-hit-and-how-we-fixed-them)
for the loop.

### Phase 9 — Seamless API-key flow

- Added Spring controller `FindocKeyController.java` exposing
  `GET /api/admin/findoc-key` (admin-only, returns `subby.findoc.api-key`).
- Patched `KycUsersView.jsx` and `LoanReviewView.jsx` in smartbank to
  fetch this key on mount and append `#key=<value>` to the webui URL when
  the admin clicks "Inspect".
- Patched `findoc-verify/webui/src/main.tsx` to read `window.location.hash`
  on boot, extract `key=...`, write to localStorage as `findoc.apiKey`, and
  scrub the fragment via `history.replaceState`.

### Phase 10 — Defensive frontend rendering

- Added `safeJoin` and `safeArr` helpers to `Humanize.tsx`.
- Wrapped every `(value ?? []).join(...)` and `.filter(...)` with the helpers
  so partial pipeline data (Gemini quota errors leaving fields null/string)
  doesn't crash render.
- Added `ErrorBoundary` component, wrapped the `ApplicationDetail` route
  in `App.tsx` so any future render-time data-shape surprise shows a friendly
  fallback instead of a blank page.

### Phase 11 — Admin field-edit UI

- Added `FieldNewRow` component to `ApplicationDetail.tsx`.
- "+ Add field" button on each document's Fields tab — both in the empty
  state ("No fields extracted") and next to the search bar.
- Calls the existing `PATCH /api/v1/admin/applications/{id}/extracted-fields`
  endpoint (which upserts), so admins can supply values that the LLM missed
  before clicking Replay.

### Phase 12 — FraudClient DEGRADED policy permissive

- Patched `FraudClient.java` `fallback()`: any transfer not explicitly
  `isForeign=1` or `isHighRisk=1` is allowed under DEGRADED status.
  Original threshold (₹10,000) was theatre because fraud-python isn't
  deployed at all in this stack.

---

## Issues we hit and how we fixed them

A list of every concrete failure during the deploy and the resolution.
Many are footguns in Hibernate-on-fresh-DB, AWS-CLI-on-Windows, and S3
static-site quirks.

### 1. Bash AWS CLI failed with `Unable to load paramfile file:///tmp/...`

**Cause:** running `infra/aws-bootstrap.sh` in Git Bash on Windows. AWS
CLI is a native Windows binary and can't read Git Bash's virtual
`/tmp/...` paths.

**Fix:** added a `file_url()` helper that wraps paths with `cygpath -m`
to convert `/tmp/...` → `C:/Users/.../Temp/...` for the native CLI.

### 2. SQS create-queue silently failed

The script's original `2>/dev/null` swallowed the actual error. Removed
suppression so we could see Issue 1 above.

### 3. Bank backend crashed at boot — Flyway V2 referenced `users` not yet created

**Cause:** Flyway V2 (`V2__kyc.sql`) does `ALTER TABLE users ADD COLUMN`
expecting Hibernate had already auto-created `users`. On a fresh RDS that
precondition didn't hold; Flyway runs *before* Hibernate, so V2 hit
"relation users does not exist".

**Fix:** disabled Flyway in `application-aws.yml` (`flyway.enabled: false`)
and set `ddl-auto: update` so Hibernate creates everything from JPA
entities. Documented as a tradeoff in [`DEPLOYMENT.md`](DEPLOYMENT.md).

### 4. `MAIL_APP_PASSWORD` vs `MAIL_PASSWORD` mismatch

`application-aws.yml` referenced `${MAIL_APP_PASSWORD}` but `.env.aws.example`
used `MAIL_PASSWORD`. Aligned both to `MAIL_PASSWORD`.

### 5. Spring Boot's compose `--env-file` not auto-resolving

Compose interpolates `${VAR}` from the shell + auto-loaded `.env` only.
We named the file `.env.aws`. Without `--env-file` flag the variables
came up blank. **Always pass `--env-file .env.aws`** in any compose
command (already encoded in the deploy workflow and the manual scripts
in `DEPLOYMENT.md`).

### 6. boto3 in Python services rejected by real AWS — `InvalidClientTokenId`

**Cause:** `findoc-verify/src/config.py` and `SubbyPythonLoan/src/config.py`
hardcoded `aws_access_key_id: str = "test"` defaults that were passed
explicitly to `boto3.client(...)`. On real AWS this overrode the IAM
instance role with the placeholder credentials.

**Fix:** stripped the explicit `aws_access_key_id`/`aws_secret_access_key`
kwargs from every boto3 client construction (5 sites across both Python
services). With them gone, boto3's default credential chain finds the
EC2 instance metadata creds.

Also added `AWS_ENDPOINT_URL=` (empty) and `S3_ENDPOINT_URL=` (empty) to
`.env.aws` to override the LocalStack defaults baked into the configs.

### 7. Hibernate `created_at` NOT NULL but no DEFAULT

JPA entities for `outbox_events`, `loan_decision_overrides`, etc. mark
`created_at` as `insertable=false` expecting the DB to fill it. Hibernate's
auto-DDL creates the column NOT NULL but without `DEFAULT NOW()`. Caused
500s on first signup, then later on first override.

**Fix:** generic loop over `information_schema.columns` adding
`DEFAULT NOW()` to every NOT NULL `*_at` column in each database. See
the playbook section below for the script.

### 8. CORS preflight blocked on protected endpoints

`CorsConfig.java` was registering a `CorsFilter` bean. Spring Security's
filter chain rejects unauthenticated OPTIONS requests *before* CORS headers
are added. Browsers see "No 'Access-Control-Allow-Origin' header" and block.

**Fix:** changed `CorsConfig.java` to expose `CorsConfigurationSource`
instead of `CorsFilter`, and called `http.cors(Customizer.withDefaults())`
in `SecurityConfig.java`. CORS now runs as part of the security chain,
preflights pass before auth checks.

### 9. Build OOM during pip-install

Parallel `docker compose build` of multiple Python services (each
installing pandas / xgboost) exceeded 2 GB RAM on t3.small.

**Fix:** sequential builds in the deploy workflow:

```bash
docker compose ... build subby-bank
docker compose ... build findoc-verify
docker compose ... build subby-python-loan
```

Plus dropped FraudPython entirely from `docker-compose.aws.yml`. Each build
runs in isolation and fits under 2 GB.

### 10. Disk full during Maven repackage

EBS volume was originally 8 GB. Maven downloads + Docker build cache filled
it. Build failed with `No space left on device`.

**Fix:** `docker system prune -af --volumes` and `docker builder prune -af`
freed ~3 GB, but the right move was a permanent resize: `aws ec2 modify-volume
--volume-id ... --size 30`, then on EC2 `sudo growpart /dev/nvme0n1 1` and
`sudo xfs_growfs -d /`. Within free tier (30 GB EBS gp3 free for 12 months).

### 11. Webui blank page during partial pipeline data

Loan inspection page tried to render `(d.found_months ?? []).join(", ")` —
when Gemini hit free-tier 5 RPM quota and returned partial data, fields
came back as null/string instead of arrays. `.join` blew up the whole
React tree.

**Fix:** added `safeJoin`/`safeArr` helpers in `Humanize.tsx` and an
`ErrorBoundary` around the route. Page now renders even on partial data.

Also switched `GEMINI_MODEL` from `gemini-2.5-flash` (5 RPM free tier) to
`gemini-2.0-flash` (15 RPM). Verified availability via:

```bash
GEMINI_KEY=$(grep "^GEMINI_API_KEY=" ~/subby/.env.aws | cut -d= -f2)
curl -s "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash?key=$GEMINI_KEY" | head -10
```

### 12. Cross-origin localStorage isolation

Smartbank and findoc webui live on different S3 origins, so localStorage
isn't shared. Admin had to paste the API key into the webui every time.

**Fix:** the seamless flow described in Phase 9 — smartbank fetches the
findoc API key from a Spring admin endpoint (JWT-protected) and appends it
as a URL fragment when opening the webui. Webui's `main.tsx` extracts the
fragment, writes it to its own localStorage, scrubs the URL.

### 13. Gemini returned `MAIL_APP_PASSWORD: not set` ... actually `gemini-1.5-flash` deprecated

Found via:
```bash
curl -s "https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash?key=$GEMINI_KEY"
```
Returns 404 NOT_FOUND. Switched to `gemini-2.0-flash` instead.

### 14. Google service-account key generation blocked by org policy

`iam.disableServiceAccountKeyCreation` org constraint blocked creating a
proper service-account JSON. Workaround: used Application Default
Credentials (`gcloud auth application-default login`) and copied the
generated `application_default_credentials.json` to `secrets/google-sa.json`.
The Google client SDKs auto-detect both formats via `GOOGLE_APPLICATION_CREDENTIALS`.

**Caveat:** Doc AI calls are now billed against the personal Google
account. Audits show that user as the caller, not "the app".

### 15. Bind-mount silently created a directory at `secrets/google-sa.json`

When we first brought up the stack, the file didn't yet exist on the EC2.
Docker's bind-mount auto-creates the source path as a directory. Result:
the container saw `/app/secrets/google-sa.json` as a directory, Google
SDK threw `IsADirectoryError`.

**Fix:** `sudo chown -R ec2-user:ec2-user ~/subby/secrets/`,
`sudo rm -rf ~/subby/secrets/google-sa.json` (the bogus dir),
re-`scp` the actual file, then `docker compose up --force-recreate`.

### 16. Loan inspect 404 because Replay endpoint scoping by `submitted_by_api_key_id`

`_load_app_scoped` returns 403 if the requester's API key didn't submit the
application. Initially confused us — we thought it was a 404. After mapping
the key in localStorage to the row in `api_keys` table by SHA-256 hash,
realized both KYC and loan were submitted by the bank's `subby-java-backend`
key, so logging into the webui with the bank key shows everything.

The seamless flow (Phase 9) now passes the bank key automatically — admin
never sees the prompt and inspect always works for whatever the bank
submitted.

### 17. SQS messages stuck in DLQ after pipeline failures

Quota errors during loan extract burned through `maxReceiveCount=3` on
several queues. Resolved by purging both main and DLQ queues
(`aws sqs purge-queue --queue-url ...`) and re-submitting from clean state.
Documented as a step in the playbook.

### 18. Loan repayment 500 — same NOT NULL `created_at` bug on a different table

Running the same generic ALTER loop that fixed `outbox_events` also caught
`loan_decision_overrides`, etc. (See Issue 7.)

### 19. DEGRADED-mode threshold rejected legitimate transfers

`FraudClient.fallback()` capped low-risk at ₹10,000 amount. Demo transfers
above that got hard-failed even though fraud-python was intentionally
absent. Loosened the policy to "non-flagged transfers go through" since
the ML check isn't deployed at all.

---

## Future-update playbook

Every time you change code in any of the following parts, here's exactly
what to do.

### Updating a backend service (Spring Boot, findoc-verify, SubbyPythonLoan)

```powershell
git add <files>
git commit -m "..."
git push
```

That's it. CI runs (~5–8 min), then deploy.yml auto-fires (~10 min):
SSH → `git pull` → sequential rebuild → restart → smoke test. Watch the
**Actions** tab; deploy goes red and doesn't restart anything if smoke
test fails.

### Updating smartbank (CRA frontend)

CI/CD does **not** auto-deploy frontends — they're manual. After pushing
your code change:

```powershell
cd c:\Users\rever\Desktop\reverside\code\smartbank
Remove-Item -Recurse -Force build -ErrorAction SilentlyContinue
npm run build

cd ..
aws s3 sync smartbank\build\ "s3://subby-frontend-8909/" --delete
```

Then **hard-refresh** the smartbank URL in your browser (Ctrl+Shift+R) or
open in incognito to bust browser cache.

If you change `REACT_APP_API_URL` (e.g., new EIP), update
`smartbank/.env.production` first, **then** rebuild + sync.

### Updating findoc-verify webui (Vite frontend)

```powershell
cd c:\Users\rever\Desktop\reverside\code\findoc-verify\webui
Remove-Item -Recurse -Force dist -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force node_modules\.vite -ErrorAction SilentlyContinue
npm run build

cd ..\..
aws s3 sync findoc-verify\webui\dist\ "s3://subby-findoc-webui-2607/" --delete
```

`Remove-Item` of `node_modules\.vite` forces Vite to regenerate the bundle
hash. Without it Vite may reuse the previous chunk filename and the browser
serves cached bytes.

If you change `VITE_API_URL`, update `findoc-verify/webui/.env.production`
first.

### Verifying a frontend update actually shipped

```powershell
aws s3 ls s3://subby-frontend-8909/static/js/
aws s3 ls s3://subby-findoc-webui-2607/assets/
```

The hashed filename should be different from the last deploy. If it's the
same, your build didn't pick up your changes (often `.env.production` not
re-read or browser cache).

In DevTools Network tab → reload → click the JS file row → confirm the
filename matches what S3 lists. If they differ, browser cache; close all
tabs for that origin and retry incognito.

### Pushing a database schema change

If you add a new JPA entity to Spring Boot or a new SQLAlchemy model
to a Python service, Hibernate `update` and Alembic respectively will
create the new table on next deploy. Watch the new table for the same
NOT NULL-without-DEFAULT bug from Issue 7:

```bash
ssh -i ~/subby-key.pem ec2-user@35.154.101.168
RDS_HOST=subby-prod.cf006s0k2vfw.ap-south-1.rds.amazonaws.com

cat > /tmp/fix-defaults.sql <<'EOF'
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN
    SELECT table_name, column_name FROM information_schema.columns
    WHERE table_schema='public'
      AND (column_name LIKE '%_at' OR column_name LIKE '%_time')
      AND is_nullable='NO' AND column_default IS NULL
  LOOP
    EXECUTE format('ALTER TABLE %I ALTER COLUMN %I SET DEFAULT NOW()', r.table_name, r.column_name);
    EXECUTE format('UPDATE %I SET %I = NOW() WHERE %I IS NULL', r.table_name, r.column_name, r.column_name);
    RAISE NOTICE 'fixed: %.%', r.table_name, r.column_name;
  END LOOP;
END
$$;
EOF

PGPASSWORD=subby psql "host=$RDS_HOST port=5432 dbname=subbybank user=subby" -f /tmp/fix-defaults.sql
PGPASSWORD=findoc psql "host=$RDS_HOST port=5432 dbname=findoc user=findoc" -f /tmp/fix-defaults.sql
PGPASSWORD=subbyloan psql "host=$RDS_HOST port=5432 dbname=subby_loan user=subbyloan" -f /tmp/fix-defaults.sql
```

Run after any deploy that adds tables.

### Adding a new SNS topic / SQS queue

Edit both `infra/localstack-init.sh` (or `findoc-verify/scripts/localstack-init.sh`)
**and** `infra/aws-bootstrap.sh`. Push the dev-side change so local stays
consistent, then re-run the bootstrap on AWS:

```bash
bash infra/aws-bootstrap.sh
```

It's idempotent — existing topics/queues are reused, only new ones get
created.

### Rotating a secret

| Secret | How to rotate |
| :--- | :--- |
| Gmail app password | Generate a new one at `myaccount.google.com/apppasswords`, update `MAIL_PASSWORD` in `.env.aws` on EC2, recreate `subby-bank` |
| Findoc API key (bank → findoc) | `docker exec findoc-verify python -m scripts.generate_api_key --label "subby-java-backend-v2" --org "subby" --scopes submit`, update `FINDOC_API_KEY` in `.env.aws`, recreate `subby-bank`, then revoke the old key via `DELETE /api/v1/admin/apikeys/{id}` |
| RDS master password | RDS Console → Modify → Set new password → Apply immediately. Update any psql one-liners you have saved. |
| JWT secret | Edit `JWT_SECRET` in `.env.aws`, recreate `subby-bank`. Existing tokens become invalid; users must re-login. |

Always recreate the affected container with `--force-recreate --no-deps`
so the new env actually takes effect:

```bash
docker compose --env-file .env.aws -f docker-compose.aws.yml up -d --force-recreate --no-deps subby-bank
```

### Switching the Gemini model

```bash
ssh -i ~/subby-key.pem ec2-user@35.154.101.168
nano ~/subby/.env.aws
# GEMINI_MODEL=gemini-2.0-flash   (or whatever's current)
docker compose --env-file .env.aws -f docker-compose.aws.yml up -d --force-recreate --no-deps findoc-verify-workers
```

To check what's available with your key:
```bash
GEMINI_KEY=$(grep "^GEMINI_API_KEY=" ~/subby/.env.aws | cut -d= -f2)
curl -s "https://generativelanguage.googleapis.com/v1beta/models?key=$GEMINI_KEY" | grep -oE '"name": "models/[^"]+"' | sort -u
```

### Deploying after the EIP changes

The EC2 EIP shouldn't change unless you detach + reattach. But if it does:

1. Update `smartbank/.env.production` and `findoc-verify/webui/.env.production`
   with the new EIP.
2. Rebuild + sync both frontends (above).
3. Update `FRONTEND_URL`, `FRONTEND_ORIGIN` in `.env.aws` on EC2.
4. Recreate `subby-bank` to pick up new CORS origins.

### Stopping vs terminating the EC2 (cost control)

```powershell
aws ec2 stop-instances --instance-ids <id>
```

Stopped instance: no compute charge, only EBS storage (~$3/mo for 30 GB).
EIP charges $0.005/hr while detached from a running instance — to keep
EIP free, either restart the instance or release+reallocate the EIP.

To resume:
```powershell
aws ec2 start-instances --instance-ids <id>
```

Public IP from EIP is preserved across stop/start.

---

## Common operations cheatsheet

```bash
# SSH to EC2
ssh -i ~/subby-key.pem ec2-user@35.154.101.168

# Watch all service logs
docker compose --env-file .env.aws -f docker-compose.aws.yml logs -f

# Restart one service
docker compose --env-file .env.aws -f docker-compose.aws.yml up -d --force-recreate --no-deps <service-name>

# Connect to RDS as admin
RDS_HOST=subby-prod.cf006s0k2vfw.ap-south-1.rds.amazonaws.com
PGPASSWORD=<master-pw> psql "host=$RDS_HOST port=5432 dbname=postgres user=postgres"

# Connect to a service DB
PGPASSWORD=subby     psql "host=$RDS_HOST port=5432 dbname=subbybank   user=subby"
PGPASSWORD=findoc    psql "host=$RDS_HOST port=5432 dbname=findoc     user=findoc"
PGPASSWORD=subbyloan psql "host=$RDS_HOST port=5432 dbname=subby_loan user=subbyloan"

# Free up disk space
docker system prune -af --volumes && docker builder prune -af

# Mint a new findoc API key
docker exec findoc-verify python -m scripts.generate_api_key \
  --label "<label>" --org "<org>" --scopes submit,admin --rate-limit 240

# Run alembic migrations manually
docker exec findoc-verify alembic upgrade head
docker exec subby-python-loan alembic upgrade head

# Purge a stuck SQS queue (60s cooldown between purges per queue)
url=$(aws sqs get-queue-url --queue-name <queue-name> --query QueueUrl --output text)
aws sqs purge-queue --queue-url $url

# Show queue depths across the pipeline
for q in findoc-ocr findoc-classify findoc-extract findoc-aggregate findoc-compliance findoc-crossdoc findoc-fraud findoc-risk findoc-result; do
  url=$(aws sqs get-queue-url --queue-name $q --query QueueUrl --output text)
  attrs=$(aws sqs get-queue-attributes --queue-url $url --attribute-names ApproximateNumberOfMessages,ApproximateNumberOfMessagesNotVisible --query Attributes --output json)
  echo "$q: $attrs"
done

# Reset a user's KYC + loan state (start the demo over)
PGPASSWORD=subby psql "host=$RDS_HOST port=5432 dbname=subbybank user=subby" <<'SQL'
TRUNCATE loan_repayment, loan_decision_overrides, pending_loan_events,
         loan_eligibility_request, loan_application
RESTART IDENTITY CASCADE;
UPDATE users SET has_loan=false, loanamount=0, kyc_status='NONE',
                 findoc_kyc_application_id=NULL, kyc_submitted_at=NULL,
                 kyc_decided_at=NULL, kyc_decision_reason=NULL, kyc_report_json=NULL
  WHERE email='subbydutta@gmail.com';
SQL

PGPASSWORD=findoc psql "host=$RDS_HOST port=5432 dbname=findoc user=findoc" -c "
TRUNCATE applications RESTART IDENTITY CASCADE;
"
```
