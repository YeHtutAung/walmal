# Database Migration Runbook

This document is the step-by-step procedure for applying Flyway database migrations to production.
Follow it exactly — skipping steps has caused incidents.

## Overview

Walmal uses Flyway for versioned schema migrations. Migration files live in:
```
walmal-app/src/main/resources/db/migration/V*.sql
```

Migrations run automatically on application startup (via `spring.flyway.enabled=true`).
This runbook covers **manual pre-deployment migration** — running migrations before deploying
the new application version, which is required for zero-downtime upgrades.

---

## Pre-Migration Checklist

Before touching production, confirm:

- [ ] Migration has been applied to staging and behaved correctly
- [ ] A backup taken since the last data change exists (see step 2)
- [ ] Application is in maintenance mode or traffic is low
- [ ] You have the production datasource credentials available
- [ ] A rollback SQL script exists if the migration is destructive (see `db/migration/undo/`)
- [ ] Another engineer is available as a second pair of eyes

---

## Step 1: Preview pending migrations (dry run)

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://prod-host:5432/walmal
export SPRING_DATASOURCE_USERNAME=walmal
export SPRING_DATASOURCE_PASSWORD=secret

./mvnw flyway:info -pl walmal-app \
  -Dflyway.url="${SPRING_DATASOURCE_URL}" \
  -Dflyway.user="${SPRING_DATASOURCE_USERNAME}" \
  -Dflyway.password="${SPRING_DATASOURCE_PASSWORD}" \
  --batch-mode --no-transfer-progress
```

Review the output:
- `Pending` migrations will be applied
- `Success` migrations have already been applied
- `Failed` migrations require repair (see below)

If there are no `Pending` migrations, the schema is already up to date. Stop here.

---

## Step 2: Take a backup

**Never skip this step in production.**

```bash
export DB_HOST=prod-host
export DB_USER=walmal
export PGPASSWORD=secret
export S3_BUCKET=s3://my-backups/walmal   # optional

./scripts/backup-db.sh
```

Verify the backup file was created and is non-zero:
```bash
ls -lh /var/backups/walmal/ | tail -5
```

Note the backup filename — you will need it if you must restore.

---

## Step 3: Enable maintenance mode (if applicable)

If your infrastructure supports it, put the application in maintenance mode to prevent
new writes during the migration window:

```bash
# Option A: Stop the application container
docker compose -f docker-compose.prod.yml stop app

# Option B: Route traffic to a maintenance page at the Nginx level
# Edit nginx/nginx.conf to return 503, then:
# docker compose -f docker-compose.prod.yml exec nginx nginx -s reload
```

For additive-only migrations (adding columns, new tables), stopping the app is not required.
For destructive migrations (dropping columns, changing column types), always stop the app first.

---

## Step 4: Apply migrations

Use the production migration script, which includes confirmation prompts:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://prod-host:5432/walmal \
SPRING_DATASOURCE_USERNAME=walmal \
SPRING_DATASOURCE_PASSWORD=secret \
./scripts/migrate-prod.sh
```

You must type `migrate production` to confirm.

Alternatively, to skip the interactive prompt (CI/CD only):
```bash
SKIP_CONFIRM=true SKIP_BACKUP=true ./scripts/migrate-prod.sh
```

Watch for errors in the output. A successful migration ends with:
```
[...] ✓ Production migration complete
```

---

## Step 5: Post-migration verification

The migration script runs `flyway:info` automatically. Confirm that:
- All previously `Pending` migrations now show `Success`
- No migrations show `Failed`

Run a manual spot-check with key queries:

```bash
# Connect to the production database
PGPASSWORD=secret psql -h prod-host -U walmal -d walmal

-- Verify migration history
SELECT version, description, installed_on, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 10;

-- Basic row counts (adjust table names as needed)
SELECT 'products' AS tbl, count(*) FROM products
UNION ALL SELECT 'orders', count(*) FROM orders
UNION ALL SELECT 'users', count(*) FROM users;
```

---

## Step 6: Deploy and re-enable traffic

```bash
# Restart the application with the new image
export WALMAL_IMAGE=ghcr.io/<org>/walmal/walmal-app:sha-<commit-sha>
docker compose -f docker-compose.prod.yml up -d app

# Verify health
curl -sf https://api.walmal.com/actuator/health | python3 -m json.tool

# Run smoke tests
./scripts/smoke-test.sh https://api.walmal.com
```

If everything passes, re-enable traffic (reverse step 3 if you stopped the app).

---

## Rollback

### If migration failed mid-way

1. The `flyway_schema_history` table will have a `Failed` entry. Do **not** try to re-run.

2. Run Flyway repair to clear the failed entry:
   ```bash
   ./mvnw flyway:repair -pl walmal-app \
     -Dflyway.url="${SPRING_DATASOURCE_URL}" \
     -Dflyway.user="${SPRING_DATASOURCE_USERNAME}" \
     -Dflyway.password="${SPRING_DATASOURCE_PASSWORD}" \
     --batch-mode --no-transfer-progress
   ```

3. If the migration is not idempotent and partially modified the schema, restore from the pre-migration backup:
   ```bash
   PGPASSWORD=secret \
   DB_HOST=prod-host \
   DB_USER=walmal \
   DB_NAME=walmal \
   ./scripts/restore-db.sh /var/backups/walmal/walmal_YYYYMMDD_HHMMSS.sql.gz
   ```

4. After restore, run `flyway:repair` again and verify `flyway:info` shows the pending migration.

### If migration succeeded but the release is broken

1. Roll back the application image (no schema change needed for additive migrations):
   ```bash
   export WALMAL_IMAGE=ghcr.io/<org>/walmal/walmal-app:sha-<previous-sha>
   docker compose -f docker-compose.prod.yml up -d app
   ```

2. If the migration was destructive (dropped a column the old code needs), you must restore from backup:
   ```bash
   docker compose -f docker-compose.prod.yml stop app
   PGPASSWORD=secret ./scripts/restore-db.sh /var/backups/walmal/walmal_YYYYMMDD.sql.gz
   docker compose -f docker-compose.prod.yml up -d app
   ```

### Undo migrations (optional)

For complex reversible migrations, create an undo script in `db/migration/undo/`:

```
db/migration/undo/
  U14__drop_legacy_column.sql   # Undoes V14
```

Apply manually:
```bash
PGPASSWORD=secret psql -h prod-host -U walmal -d walmal \
  -f walmal-app/src/main/resources/db/migration/undo/U14__drop_legacy_column.sql
```

Then run `flyway:repair` to reset the migration history to before V14.

---

## Staging Migration Procedure

Staging follows the same process but uses the simplified script:

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://staging-host:5432/walmal \
SPRING_DATASOURCE_USERNAME=walmal \
SPRING_DATASOURCE_PASSWORD=secret \
./scripts/migrate-staging.sh
```

Always run staging migrations at least 24 hours before production to allow time to detect issues.

---

## Troubleshooting

### `ERROR: Validate failed: Migration checksum mismatch`

A committed migration file was modified after it was applied. Never modify a committed migration.

Fix: either revert the file change, or if it was an intentional fix, run `flyway:repair`:
```bash
./mvnw flyway:repair -pl walmal-app -Dflyway.url=... -Dflyway.user=... -Dflyway.password=...
```

### `FlywayException: Found more than one migration with version X`

Duplicate version numbers in `db/migration/`. Rename one of the files.

### `ERROR: permission denied for table flyway_schema_history`

The database user doesn't have write access to the Flyway history table. Grant it:
```sql
GRANT ALL ON flyway_schema_history TO walmal;
```
