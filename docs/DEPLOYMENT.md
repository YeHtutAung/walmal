# Deployment Guide

This document covers deploying the Walmal Spring Boot backend to staging and production.

## Prerequisites

- Docker and Docker Compose v2 installed on the target server
- A PostgreSQL 15 database (managed by `docker-compose.prod.yml` or external)
- SSL certificates for the API domain
- All secrets from `.env.example` filled in and stored as GitHub Actions secrets

---

## Environment Variables

Copy `.env.example` to `.env` on the server and fill in all values:

```bash
cp .env.example .env
# Edit .env — never commit this file
```

Required secrets to add as GitHub Actions secrets (repo → Settings → Secrets and variables → Actions):

| Secret | Used in |
|--------|---------|
| `WALMAL_JWT_SECRET_TEST` | CI test job |
| `STAGING_HOST` | deploy-staging job |
| `STAGING_SSH_KEY` | deploy-staging job |
| `STAGING_USER` | deploy-staging job |
| `STAGING_API_URL` | smoke-test-staging job |
| `PROD_HOST` | deploy-prod job |
| `PROD_SSH_KEY` | deploy-prod job |
| `PROD_USER` | deploy-prod job |
| `PROD_API_URL` | smoke-test-prod job |

---

## Production Deployment (Automated via CI)

On every push to `main`:

1. Tests run (`test` job)
2. Docker image is built and pushed to GHCR (`build-and-push` job)
3. Trivy vulnerability scan runs (`security-scan` job)
4. Automatic deploy to staging (`deploy-staging` job)
5. Smoke tests verify staging (`smoke-test-staging` job)
6. **Manual approval gate** — a reviewer must approve in GitHub UI (`deploy-prod` job environment: `production`)
7. Automatic deploy to production
8. Smoke tests verify production (`smoke-test-prod` job)

To configure the manual approval gate:
- Go to repo → Settings → Environments → `production`
- Enable "Required reviewers" and add the appropriate users/teams

---

## Manual Deployment (Fallback)

Use this procedure if CI is unavailable or you need an emergency deploy.

### 1. Build and push the image

```bash
# On your local machine or build server
cd /path/to/walmal
./mvnw package -DskipTests --batch-mode --no-transfer-progress

docker build -t ghcr.io/<org>/walmal/walmal-app:manual-$(date +%Y%m%d) .
docker push ghcr.io/<org>/walmal/walmal-app:manual-$(date +%Y%m%d)
```

### 2. Deploy to the server

```bash
# SSH to the target server
ssh user@your-server

cd /opt/walmal

# Pull the new image
export WALMAL_IMAGE=ghcr.io/<org>/walmal/walmal-app:manual-YYYYMMDD
docker compose -f docker-compose.prod.yml pull app

# Rolling restart (zero-downtime if you have a load balancer)
docker compose -f docker-compose.prod.yml up -d app

# Verify health
curl -sf http://localhost:8080/actuator/health | python3 -m json.tool
```

### 3. Run smoke tests

```bash
./scripts/smoke-test.sh https://api.walmal.com
```

---

## SSL Certificate Setup

Obtain certificates with Certbot and place them where Nginx expects them:

```bash
# On the server — run once before starting Nginx
certbot certonly --standalone -d api.walmal.com

mkdir -p /opt/walmal/nginx/ssl
cp /etc/letsencrypt/live/api.walmal.com/fullchain.pem /opt/walmal/nginx/ssl/
cp /etc/letsencrypt/live/api.walmal.com/privkey.pem   /opt/walmal/nginx/ssl/
chmod 600 /opt/walmal/nginx/ssl/privkey.pem

# Auto-renew (add to cron or systemd timer)
certbot renew --quiet
# After renewal, copy updated certs and reload Nginx:
# docker compose -f docker-compose.prod.yml exec nginx nginx -s reload
```

---

## Starting the Full Stack

```bash
cd /opt/walmal

# First boot: run database migrations before starting the app
export $(grep -v '^#' .env | xargs)
./scripts/migrate-prod.sh

# Start all services
docker compose -f docker-compose.prod.yml up -d

# Verify all containers are healthy
docker compose -f docker-compose.prod.yml ps
```

---

## Rollback

### Option A — Redeploy previous image tag

```bash
cd /opt/walmal
export WALMAL_IMAGE=ghcr.io/<org>/walmal/walmal-app:sha-<previous-sha>
docker compose -f docker-compose.prod.yml up -d app
./scripts/smoke-test.sh https://api.walmal.com
```

### Option B — Restore from database backup

Only needed if a bad migration was applied. See `docs/DR_PLAN.md` and `docs/MIGRATION_RUNBOOK.md#rollback`.

---

## Secrets Management

Secrets must never be committed to the repository. For production:

- **Short-term**: use a `.env` file on the server (owned by root, permissions `600`)
- **Long-term**: consider HashiCorp Vault or AWS Secrets Manager and inject via `SPRING_CONFIG_IMPORT=vault://...`

Spring Boot relaxed binding maps `SPRING_DATASOURCE_URL` → `spring.datasource.url` automatically, so all secrets can be provided as environment variables without any code changes.

---

## Monitoring

- **Health**: `GET https://api.walmal.com/actuator/health` — accessible publicly, returns `{"status":"UP"}`
- **Metrics**: `/actuator/prometheus` — accessible only from the internal network (blocked by Nginx for public access)
- Connect Prometheus to `http://app:8080/actuator/prometheus` from within the `backend` Docker network
- Recommended alerts: response time p99 > 2s, error rate > 1%, `status != UP`
