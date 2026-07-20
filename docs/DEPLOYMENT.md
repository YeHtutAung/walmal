# Deployment Guide — Walmal Production (single VPS)

The whole system (Spring backend, storefront, admin, data services, TLS,
monitoring) runs on one VPS from `docker-compose.prod.yml` behind Caddy.
Stripe runs in **TEST mode** by design — this is a public demo store; the
storefront shows a banner telling visitors to use card `4242 4242 4242 4242`.

Two tracks below: **Track 1** is everything only a human can do (accounts,
domain, secrets) — roughly an afternoon, once. **Track 2** is what runs
automatically on every push after that.

> Deploying to **AWS Lightsail (Singapore)**? Follow the concrete,
> provider-specific walkthrough in
> [`DEPLOYMENT-aws-lightsail.md`](DEPLOYMENT-aws-lightsail.md) — it fills in
> the account/instance/DNS/budget-alert steps for AWS. This file remains the
> canonical reference for the *why* and for CI (Track 2).

---

## Track 1 — One-time provisioning (you)

### 1. Buy a domain

Any registrar (~$10/yr). Everywhere below, `WALMAL_DOMAIN` means the bare
domain (e.g. `example.com`).

### 2. Create the VPS

- Ubuntu 24.04 LTS, 4–8 GB RAM (Hetzner CPX21/CX32 class or equivalent),
  ≥40 GB disk.
- Add YOUR SSH public key at creation; password auth off.
- Generate a **separate deploy keypair** for CI:
  `ssh-keygen -t ed25519 -f walmal-deploy -C walmal-ci` — the public half
  goes in the VPS `~/.ssh/authorized_keys`, the private half becomes the
  `DEPLOY_SSH_KEY` GitHub secret (step 6).

### 3. Point DNS (5 A-records + apex → the VPS IP)

| Record | Serves |
|---|---|
| `shop.WALMAL_DOMAIN` | storefront (apex also redirects here) |
| `admin.WALMAL_DOMAIN` | ops admin |
| `api.WALMAL_DOMAIN` | Spring API |
| `status.WALMAL_DOMAIN` | Uptime Kuma status page |
| `mail.WALMAL_DOMAIN` | MailHog UI (basic-auth) |
| `WALMAL_DOMAIN` (apex) | redirect → shop. |

Wait for propagation before first boot — Caddy needs resolvable names to
obtain Let's Encrypt certificates (no manual SSL setup: Caddy handles
issuance and renewal).

### 4. Harden + prepare the VPS

```bash
# as root on the VPS
apt update && apt -y upgrade
apt -y install docker.io docker-compose-v2 ufw fail2ban unattended-upgrades
ufw allow OpenSSH && ufw allow 80/tcp && ufw allow 443/tcp && ufw --force enable
systemctl enable --now fail2ban
dpkg-reconfigure -plow unattended-upgrades   # accept

mkdir -p /opt/walmal && cd /opt/walmal
git clone https://github.com/YeHtutAung/walmal.git .
```

### 5. Server environment file

```bash
cp .env.production.example .env
# fill EVERY value — the compose file fails fast on missing required vars
```

Notes while filling it in:
- `MAILHOG_BASIC_AUTH`: generate the bcrypt hash on the VPS with
  `docker run --rm caddy:2 caddy hash-password --plaintext 'your-password'`.
- `STRIPE_SECRET_KEY`: the **test-mode** `sk_test_…` key.
- `STRIPE_WEBHOOK_SECRET`: created in step 7 — come back and fill it in.
- `ACME_EMAIL`: a real mailbox; Let's Encrypt sends expiry warnings there.

### 6. GitHub secrets + variables

Secrets (Settings → Secrets and variables → Actions, in each repo that
deploys — walmal, walmal-store, walmal-admin):

| Secret | Value |
|---|---|
| `DEPLOY_HOST` | VPS IP or hostname |
| `DEPLOY_USER` | the SSH user (e.g. a dedicated `deploy` user) |
| `DEPLOY_SSH_KEY` | the private half of the deploy keypair (step 2) |
| `WALMAL_JWT_SECRET_TEST` | walmal only — CI test job (pre-existing) |

The old `STAGING_*` / `PROD_*` secret names are retired — remove them.

Variables:

| Variable | Repo(s) | Value |
|---|---|---|
| `DEPLOY_ENABLED` | all three | `true` (the master switch) |
| `WALMAL_DOMAIN` | walmal | the bare domain (smoke-job URLs) |
| `NEXT_PUBLIC_API_URL` | walmal-store | `https://api.WALMAL_DOMAIN/api/v1` |
| `NEXT_PUBLIC_STRIPE_PUBLISHABLE_KEY` | walmal-store | the `pk_test_…` key |
| `VITE_API_BASE_URL` | walmal-admin | `https://api.WALMAL_DOMAIN` |

### 7. Stripe test-dashboard webhook

Stripe Dashboard (TEST mode) → Developers → Webhooks → Add endpoint:
- URL: `https://api.WALMAL_DOMAIN/api/v1/payment/webhook`
- Events: `payment_intent.succeeded`, `payment_intent.payment_failed`
- Copy the signing secret (`whsec_…`) into the server `.env` as
  `STRIPE_WEBHOOK_SECRET`.

### 8. First boot + seed

```bash
cd /opt/walmal
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps        # wait for healthy
./scripts/seed-product-images.sh https://api.WALMAL_DOMAIN/api/v1
```

Flyway migrations (V1–V18, including the demo catalog) run automatically on
the backend's first boot; the seeder uploads the 15 product images
(idempotent — safe to re-run any time the products come back imageless).

### 9. Monitoring (Uptime Kuma)

Open `https://status.WALMAL_DOMAIN`, create the admin account, then add
monitors: `https://api.WALMAL_DOMAIN/actuator/health` (keyword `UP`),
`https://shop.WALMAL_DOMAIN`, `https://admin.WALMAL_DOMAIN`. Configure a
notification channel (email/Telegram) in Settings → Notifications and attach
it to every monitor.

### 10. Backups + the restore drill (MUST run once)

```bash
crontab -e
# nightly at 03:00
0 3 * * * cd /opt/walmal && ./deploy/backup.sh >> /var/log/walmal-backup.log 2>&1
```

Run the script once by hand, then **prove the restore path** (a backup you
have never restored is not a backup): the exact `pg_restore` and
volume-restore commands are in the comment block at the top of
`deploy/backup.sh` — restore into a scratch database, confirm row counts,
drop the scratch.

Off-site copies are documented, not provisioned: rclone
`/opt/walmal/backups` to any S3/B2 bucket on the same cron cadence.

---

## Track 2 — Automated on every push (CI)

With `DEPLOY_ENABLED=true` and the secrets set:

- **walmal** (push to main): test suite → Docker image → GHCR → security
  scan → SSH deploy (`compose pull app && up -d app`) → smoke job (public
  health endpoint + shop 200). Gated by the `production` environment —
  optional required-reviewer approval configurable in repo settings.
- **walmal-store / walmal-admin** (push to default branch): lint + unit +
  build → image build with the baked `NEXT_PUBLIC_*`/`VITE_*` variables →
  GHCR → SSH deploy of just that service.

With `DEPLOY_ENABLED` unset, all deploy jobs skip and CI stays green — the
repos are fully buildable with zero infrastructure.

## Rollback

Images are tagged `sha-<commit>` in GHCR. To roll a service back, pin the
tag for that service and re-up:

```bash
cd /opt/walmal
WALMAL_IMAGE_TAG=sha-<good-commit> docker compose -f docker-compose.prod.yml up -d app
```

Database rollbacks are restores (see backup.sh) — Flyway migrations are
forward-only.

## Operational notes

- **Logs**: `docker compose -f docker-compose.prod.yml logs -f <svc>`;
  rotation configured (json-file 50m×5).
- **MailHog** (`mail.WALMAL_DOMAIN`, basic-auth): every email the shop
  "sends" lands here — order confirmations, shipping notices. Demo feature;
  nothing leaves the box.
- **Webhook reconciliation**: `payment_webhook_events` rows with status
  `UNMATCHED` mean Stripe reported an intent no order claims — investigate.
- The `api.` vhost caps request bodies at 20MB (Caddy) above Spring's own
  11MB multipart limit; adjust both together if upload sizes grow.
- **Local full-stack rehearsal** (no VPS): hosts-file entries for the five
  names → 127.0.0.1, `WALMAL_DOMAIN=walmal.local`, and the Caddyfile's
  commented `local_certs` block — the same procedure the repo's final
  pre-deploy verification used.
