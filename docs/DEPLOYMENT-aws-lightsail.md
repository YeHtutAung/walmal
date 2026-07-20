# Deployment Guide — AWS Lightsail (Singapore)

A concrete, provider-specific walkthrough for deploying the whole walmal
stack to a single **AWS Lightsail** instance in **Singapore
(`ap-southeast-1`)**, with DNS at an external registrar.

This is the AWS companion to the provider-agnostic runbook in
[`DEPLOYMENT.md`](DEPLOYMENT.md) — the architecture, compose file
(`docker-compose.prod.yml`), Caddy edge (`deploy/Caddyfile`), and CI
auto-deploy all work identically; only "which box" changes. Read
`DEPLOYMENT.md` for the *why*; this file is the *how* for AWS.

**Scope:** a public **demo** store. Stripe runs in **TEST mode** (pay with
card `4242 4242 4242 4242`); email is captured by MailHog, never delivered.
This is not a real-payments deployment — see `DEPLOYMENT.md` for what would
change for that.

---

## Cost at a glance

| Item | Cost |
|---|---|
| Lightsail 8 GB / 2 vCPU / 160 GB (Singapore) | ~$40/mo |
| Domain (external registrar) | ~$10/yr |
| New-account credits | up to $200 (covers ~5 months) |

Open the account on the **Paid Plan**, not the Free Plan. As a new customer
you still receive the $200 credits, but the Paid Plan does **not** auto-close
when they run out — the Free Plan deletes the account (and your data) after
6 months or when credits are gone. See the credits/plan discussion in
`DEPLOYMENT.md`.

---

## Phase 0 — Open the AWS account

1. Create an AWS account and choose the **Paid Plan** (add a real payment
   method). The $200 new-customer credits still apply.

---

## Phase 0.5 — Cost guardrails (do this BEFORE creating the instance)

Put the spending guardrail in place before anything can spend.

**a. Turn on billing alerts**
Billing and Cost Management → **Billing preferences** → enable *"Receive AWS
Free Tier alerts"* and *"Receive CloudWatch billing alerts"* → add your
email. (CloudWatch billing metrics live only in `us-east-1`, but this toggle
is global.)

**b. Monthly cost budget**
Billing and Cost Management → **Budgets** → **Create budget** → *Customize
(advanced)* → **Cost budget**:
- Period: **Monthly**, recurring
- Amount: **$50** (just above the $40 box, so normal running is quiet)
- Scope: all services
- Alert thresholds:
  - **50 %** ($25) — *Actual*
  - **80 %** ($40) — *Actual*
  - **100 %** — *Forecasted*
- Email recipient: your address → **Create budget**

**c. Zero-credit visibility budget (recommended)**
Credits mask real spend — while credits cover the bill, budget (b) reads ~$0.
Create a **second** $50/month budget, but under **scope → advanced options**
**uncheck "Credits"** so it tracks *gross* spend. Now (b) = "over what credits
absorb"; (c) = "what I'd pay without credits" — so the day credits run out is
not a surprise.

**d. Zero-spend trip-wire (recommended)**
Budgets → **Create budget** → **Use a template** → *Zero spend budget*. Emails
you the instant any charge lands that credits don't cover — the earliest
possible warning that you've crossed into paid usage.

---

## Phase 1 — Create the box (Lightsail console)

1. **Lightsail → Create instance**
   - Region: **Singapore (ap-southeast-1)**
   - Platform: **Linux/Unix** → Blueprint: **OS Only → Ubuntu 24.04 LTS**
   - Plan: **$40/mo — 8 GB RAM / 2 vCPU / 160 GB SSD**
     (the 4 GB plan is too tight — the JVM heap is 75 % of container RAM and
     Postgres needs its share)
   - Name: `walmal-prod` → **Create**

2. **Attach a static IP** — Networking → Create static IP → attach to
   `walmal-prod`. Free while attached. **Record this IP** — every DNS record
   and the `DEPLOY_HOST` secret point at it.

3. **Open firewall ports** — instance → **Networking** tab → add **HTTP 80**
   and **HTTPS 443** (SSH 22 is open by default). Nothing else. Lightsail's
   own firewall handles inbound, so you can skip host `ufw` inbound rules.

4. **Get the SSH key** — Lightsail → Account → SSH keys → download the default
   private key (or upload your own at instance creation).

---

## Phase 2 — DNS at your registrar (BEFORE first boot)

At your registrar's DNS panel, create **6 A-records, all → the static IP**:

| Type | Host | Value | Serves |
|---|---|---|---|
| A | `shop` | `<static IP>` | storefront |
| A | `admin` | `<static IP>` | admin SPA |
| A | `api` | `<static IP>` | Spring backend |
| A | `status` | `<static IP>` | Uptime Kuma |
| A | `mail` | `<static IP>` | MailHog UI |
| A | `@` (apex) | `<static IP>` | redirects → shop |

> **Critical ordering:** Caddy cannot obtain Let's Encrypt certificates until
> these names resolve publicly. Create them and **wait for propagation**
> (`nslookup api.<yourdomain>` returns the IP) *before* `compose up`. Booting
> too early burns Let's Encrypt rate limits.

---

## Phase 3 — Prepare the server (SSH in)

```bash
ssh -i LightsailDefaultKey.pem ubuntu@<static IP>

sudo apt update && sudo apt -y upgrade
sudo apt -y install docker.io docker-compose-v2 fail2ban unattended-upgrades
sudo usermod -aG docker ubuntu          # docker without sudo (re-login to apply)
sudo systemctl enable --now fail2ban docker
sudo dpkg-reconfigure -plow unattended-upgrades   # accept

sudo mkdir -p /opt/walmal && sudo chown ubuntu:ubuntu /opt/walmal
cd /opt/walmal
git clone https://github.com/YeHtutAung/walmal.git .
```

Generate the CI deploy keypair while you're here:

```bash
ssh-keygen -t ed25519 -f ~/walmal-deploy -C walmal-ci
cat ~/walmal-deploy.pub >> ~/.ssh/authorized_keys   # public half authorizes CI
# the PRIVATE half (~/walmal-deploy) becomes the DEPLOY_SSH_KEY GitHub secret
```

---

## Phase 4 — Configure + boot

1. **Fill the env file** — every value; compose fails fast on any missing one:
   ```bash
   cp .env.production.example .env
   nano .env
   ```
   Notable values:
   - `WALMAL_DOMAIN` — the bare domain
   - `STRIPE_SECRET_KEY` — the **test-mode** `sk_test_…` key
   - `STRIPE_WEBHOOK_SECRET` — filled in step 3 below
   - `ACME_EMAIL` — a real mailbox (Let's Encrypt expiry notices)
   - `MAILHOG_BASIC_AUTH` — generate the bcrypt line:
     ```bash
     docker run --rm caddy:2 caddy hash-password --plaintext 'your-mail-password'
     ```

2. **GitHub secrets + variables** (in the walmal, walmal-store, walmal-admin
   repos):
   - Secrets: `DEPLOY_HOST` = static IP, `DEPLOY_USER` = `ubuntu`,
     `DEPLOY_SSH_KEY` = the private deploy key
   - Variable: `DEPLOY_ENABLED` = `true` (arms CI auto-deploy), plus the
     `NEXT_PUBLIC_*` / `VITE_*` build vars listed in `DEPLOYMENT.md` §6

3. **Stripe test-mode webhook** — Stripe Dashboard (TEST mode) → Developers →
   Webhooks → Add endpoint:
   - URL: `https://api.<yourdomain>/api/v1/payment/webhook`
   - Events: `payment_intent.succeeded`, `payment_intent.payment_failed`
   - Copy the signing secret (`whsec_…`) into `.env` as `STRIPE_WEBHOOK_SECRET`

4. **First boot + seed** (only after DNS resolves):
   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env pull
   docker compose -f docker-compose.prod.yml --env-file .env up -d
   docker compose -f docker-compose.prod.yml ps          # wait for healthy
   ./scripts/seed-product-images.sh https://api.<yourdomain>/api/v1
   ```
   Flyway migrations (V1–V18, incl. the demo catalog) run automatically on the
   backend's first boot; the seeder uploads the 15 product images (idempotent).

---

## Phase 5 — Monitoring + backups

1. **Uptime Kuma** — open `https://status.<yourdomain>`, create the admin
   account, add monitors: `https://api.<yourdomain>/actuator/health` (keyword
   `UP`), `https://shop.<yourdomain>`, `https://admin.<yourdomain>`. Wire a
   notification channel (email/Telegram) and attach it to every monitor.

2. **Backups + restore drill (MUST run once)**
   ```bash
   crontab -e
   # nightly at 03:00
   0 3 * * * cd /opt/walmal && ./deploy/backup.sh >> /var/log/walmal-backup.log 2>&1
   ```
   Run `deploy/backup.sh` once by hand, then **prove the restore path** — the
   exact `pg_restore` / volume-restore commands are in the comment block at the
   top of `deploy/backup.sh`. A backup you have never restored is not a backup.

   **Off-site copies (recommended on AWS):** `deploy/backup.sh` writes only to
   the instance's local disk — lose the instance and you lose the backups. Sync
   `/opt/walmal/backups` to an S3 bucket on the same cron (e.g.
   `aws s3 sync /opt/walmal/backups s3://<your-bucket>/walmal-backups`).

---

## After setup — it runs itself

With `DEPLOY_ENABLED=true` and the secrets set, every push to `main` runs the
test suite → builds a Docker image → GHCR → SSH deploy (`compose pull app &&
up -d app`) → smoke check. The storefront and admin repos deploy the same way
on push. See `DEPLOYMENT.md` §"Track 2" for detail.

---

## Do NOT run the "agent-toolkit-for-aws" setup

A third-party `setup.md` circulating for an "Agent Toolkit for AWS" instructs
an agent to pipe a remote install script into a shell, authenticate with a
non-existent `aws login` command, and overwrite `CLAUDE.md` with remotely
downloaded "rules." None of it is needed for this deployment (which runs over
plain SSH), and all three actions are unsafe. If you want local AWS CLI
access, install it from AWS's official package and run `aws configure`; for
agent-driven AWS, use the AWS-maintained MCP servers (`github.com/awslabs/mcp`)
added as an MCP server — never by writing remote rules into `CLAUDE.md`.
