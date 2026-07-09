# Disaster Recovery Plan

## Overview

| Metric | Target |
|--------|--------|
| RTO (Recovery Time Objective) | < 1 hour for database restore; < 15 min for application restart |
| RPO (Recovery Point Objective) | < 24 hours (daily backups); < 1 hour with WAL archiving enabled |

---

## Backup Strategy

### What is backed up

| Data | Frequency | Retention | Location |
|------|-----------|-----------|----------|
| PostgreSQL full dump (`.sql.gz`) | Daily at 02:00 UTC | 30 days local, 90 days S3 | `/var/backups/walmal/`, `S3_BUCKET` |
| Docker volumes | Not backed up by default | — | Consider `docker volume` snapshots or VM snapshots |
| MinIO objects | S3 bucket replication or MinIO Mirror | Continuous | External S3 or replica |

### Backup cron setup

```bash
# /etc/cron.d/walmal-backup
0 2 * * * root DB_HOST=localhost PGPASSWORD=secret S3_BUCKET=s3://my-backups/walmal \
  /opt/walmal/scripts/backup-db.sh >> /var/log/walmal-backup.log 2>&1
```

### Verify backups weekly

```bash
# List recent backups
ls -lh /var/backups/walmal/

# Test restore on a throwaway database
DB_NAME=walmal_restore_test \
PGPASSWORD=secret \
SKIP_CONFIRM=true \
./scripts/restore-db.sh /var/backups/walmal/walmal_YYYYMMDD_020000.sql.gz
```

---

## Failure Scenarios and Recovery Steps

### Scenario 1: Application crash / container restart loop

**Symptoms**: `docker compose ps` shows `Restarting`, `/actuator/health` times out.

**Steps**:

1. Check logs:
   ```bash
   docker compose -f docker-compose.prod.yml logs --tail=100 app
   ```

2. If OOM: increase `memory` limit in `docker-compose.prod.yml` → `deploy.resources.limits.memory`

3. If configuration error: verify `.env` file; confirm required secrets are set:
   ```bash
   docker compose -f docker-compose.prod.yml config | grep -i "walmal_jwt"
   ```

4. Restart the application container:
   ```bash
   docker compose -f docker-compose.prod.yml up -d --force-recreate app
   ```

5. Verify:
   ```bash
   curl -sf http://localhost:8080/actuator/health
   ./scripts/smoke-test.sh https://api.walmal.com
   ```

---

### Scenario 2: Database corruption or accidental data loss

**Symptoms**: SQL errors in logs, missing rows, failed health check.

**RTO**: ~30 minutes from detection to restored service.

**Steps**:

1. Stop the application to prevent further writes:
   ```bash
   docker compose -f docker-compose.prod.yml stop app
   ```

2. Identify the last known-good backup:
   ```bash
   ls -lht /var/backups/walmal/ | head -10
   ```

3. Restore the database (see `docs/MIGRATION_RUNBOOK.md` for pre-restore Flyway repair):
   ```bash
   PGPASSWORD=secret \
   DB_HOST=localhost \
   DB_USER=walmal \
   DB_NAME=walmal \
   ./scripts/restore-db.sh /var/backups/walmal/walmal_YYYYMMDD_HHMMSS.sql.gz
   ```

4. If migrations were applied after the backup, re-run missing migrations:
   ```bash
   ./scripts/migrate-prod.sh
   ```

5. Restart the application:
   ```bash
   docker compose -f docker-compose.prod.yml up -d app
   ```

6. Run smoke tests:
   ```bash
   ./scripts/smoke-test.sh https://api.walmal.com
   ```

---

### Scenario 3: Bad deployment / broken release

**Symptoms**: Smoke tests fail immediately after a new deployment.

**Steps**:

1. Roll back to the previous Docker image:
   ```bash
   cd /opt/walmal
   # Find the previous SHA from GitHub Actions run history or GHCR tags
   export WALMAL_IMAGE=ghcr.io/<org>/walmal/walmal-app:sha-<previous-sha>
   docker compose -f docker-compose.prod.yml up -d app
   ```

2. Verify rollback:
   ```bash
   ./scripts/smoke-test.sh https://api.walmal.com
   ```

3. If a bad migration was applied, follow the rollback procedure in `docs/MIGRATION_RUNBOOK.md#rollback`.

---

### Scenario 4: Full server failure

**RTO**: ~45 minutes to provision new server and restore.

**Steps**:

1. Provision a new server with Docker and Docker Compose.

2. Install the walmal stack:
   ```bash
   git clone https://github.com/<org>/walmal.git /opt/walmal
   cd /opt/walmal
   cp /path/to/backup/.env .env   # restore .env from secure secret store
   mkdir -p nginx/ssl
   # copy SSL certs
   ```

3. Restore the latest database backup from S3:
   ```bash
   aws s3 cp s3://my-backups/walmal/walmal_LATEST.sql.gz /var/backups/walmal/
   PGPASSWORD=secret SKIP_CONFIRM=true \
     ./scripts/restore-db.sh /var/backups/walmal/walmal_LATEST.sql.gz
   ```

4. Pull the latest image and start services:
   ```bash
   docker compose -f docker-compose.prod.yml pull
   docker compose -f docker-compose.prod.yml up -d
   ```

5. Update DNS to point to the new server IP.

6. Verify:
   ```bash
   ./scripts/smoke-test.sh https://api.walmal.com
   ```

---

### Scenario 5: Redis failure

**Symptoms**: Token refresh errors, session loss.

**Impact**: Users are logged out; JWT access tokens remain valid until expiry (configurable, default 15 min).

**Steps**:

1. Restart Redis:
   ```bash
   docker compose -f docker-compose.prod.yml restart redis
   ```

2. Redis data will be empty after restart if persistence is not configured. Token refresh tokens stored in Redis will be lost — users must log in again.

3. To prevent data loss, enable Redis persistence in `docker-compose.prod.yml`:
   ```yaml
   command: redis-server --requirepass ${SPRING_DATA_REDIS_PASSWORD} --save 60 1000 --appendonly yes
   ```

---

### Scenario 6: RabbitMQ failure

**Symptoms**: Order confirmation emails not sent; asynchronous events queued.

**Impact**: Non-blocking — the API continues to function. Messages are queued in RabbitMQ durable queues and will be delivered when RabbitMQ recovers.

**Steps**:

1. Restart RabbitMQ:
   ```bash
   docker compose -f docker-compose.prod.yml restart rabbitmq
   ```

2. Spring Boot will automatically reconnect and process queued messages.

3. **Recover parked outbox events.** Domain events are staged in the
   `outbox_events` table (transactional outbox) and relayed to RabbitMQ every
   second. During a broker outage rows stay `PENDING` and retry automatically;
   after 60 failed attempts (~1 minute of continuous outage per row) a row is
   parked as `status = 'FAILED'` and skipped by the relay. After RabbitMQ is
   back, check for and re-queue parked events:

   ```sql
   -- Inspect parked events
   SELECT id, exchange, routing_key, attempts, last_error, created_at
   FROM outbox_events WHERE status = 'FAILED' ORDER BY created_at;

   -- Re-queue them (relay picks them up within ~1s)
   UPDATE outbox_events SET status = 'PENDING', attempts = 0
   WHERE status = 'FAILED';
   ```

   Note: rows created after a row parked as FAILED may have already been
   delivered out of order; consumers are idempotent and state-guarded, so
   re-queuing is safe.

---

## Contact and Escalation

| Role | Responsibility |
|------|---------------|
| On-call engineer | First responder; follows steps above |
| Lead developer | Escalated for data loss or >1hr outage |
| Database administrator | Database corruption or unrecoverable restore |

---

## Post-Incident Review

After every incident lasting more than 15 minutes:

1. Document timeline in a post-mortem
2. Identify root cause
3. Add or improve monitoring/alerting to catch it earlier next time
4. Update this DR plan if a recovery step was unclear or missing
