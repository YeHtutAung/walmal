# Human-Facing Documentation (KB Phase 2) — Design

**Date:** 2026-07-12
**Status:** Approved by user (pre-implementation)
**Repos touched:** walmal, walmal-store, walmal-admin (one commit each)
**Predecessor:** `2026-07-10-agent-knowledge-base-design.md` — phase 2 was deferred
until the KB same-commit pipeline proved stable. It has: the dry-run passed
(7eee10a) and every feature commit since has carried its KB update.

## Problem

The three public GitHub repos have no useful human-facing documentation:
`walmal` has **no README at all**, `walmal-store` has the stock
create-next-app boilerplate, `walmal-admin` has the stock Vite template. A
visitor learns nothing about the system. All real knowledge lives in
agent-facing `docs/kb/` files and ADRs, which are not written to be read by a
skimming human.

## Decisions (made during brainstorming)

1. **Audience:** portfolio visitors — recruiters/engineers skimming a repo for
   2–5 minutes. Not an onboarding guide, not a runbook.
2. **Visuals:** key screenshots (4–6 across the three repos) plus a Mermaid
   architecture diagram in the hub README. No GIFs.
3. **Structure:** hub-and-spoke. `walmal/README.md` is the flagship system
   README; store and admin get shorter product READMEs that link back to it.
   Every repo landing page is a real page.

## Deliverables

### 1. `walmal/README.md` (new, ~180 lines)

- Title + one-liner: modular-monolith e-commerce backend (Spring Boot 3, Java)
  powering a customer storefront (walmal-store) and an ops admin
  (walmal-admin).
- **Mermaid architecture diagram** (renders natively on GitHub): the three
  apps; the Spring module boundaries (auth, product, inventory, order, pos,
  warehouse, notification) communicating over RabbitMQ; infra (Postgres,
  Redis, RabbitMQ, MinIO, MailHog) and Stripe.
- **Engineering highlights** section, written for a skimming engineer, each a
  2–3 line bullet: transactional outbox (at-least-once publish, FOR UPDATE
  SKIP LOCKED relay, broker-outage recovery drill); guest email notifications;
  JWT auth with rotating single-use refresh tokens (httpOnly cookie on the
  storefront); per-route rate limiting; cross-repo E2E coverage (96 Playwright
  tests × 3 browsers in store + 21 in admin, all against the real backend);
  k6 load suite with passing thresholds; frontend security checklist 45/45.
- Module table (module → responsibility, one line each).
- **Quickstart**: docker compose services + test-profile JAR + seeded test
  credentials; actuator health check. Every command verified by running it.
- Links: ADRs (`docs/adr/`), agent-facing KB (`docs/kb/`, labeled as such),
  and the two sibling repos (GitHub URLs).

### 2. `walmal-store/README.md` (replace boilerplate, ~100 lines)

- What it is + hero screenshots (2–3: home, product page, Stripe checkout).
- Features: guest + registered checkout, Stripe CardElement, cart merge on
  login, silent token refresh, server-side route guarding.
- Stack: Next.js App Router, Zustand, Tailwind/shadcn, Stripe.
- Run instructions (requires the walmal backend; env file shape — names only,
  never values).
- Testing note: 96 Playwright tests across chromium/firefox/webkit against
  the real backend.
- Link to the hub README for architecture.

### 3. `walmal-admin/README.md` (replace boilerplate, ~100 lines)

- What it is + screenshots (2–3: Daylight dashboard, a CRUD page, POS
  terminals or category management).
- Features: role-based access (ADMIN wildcard, per-role grants), product/
  category/user/terminal management, dashboards (orders/revenue, stock
  health), global search.
- Stack: React 18, Refine, Vite, Tailwind/shadcn, Recharts (verify chart lib
  name at write time).
- Run + test instructions (21 E2E / 65 unit).
- Link to the hub README.

### 4. Screenshots

- Captured via a throwaway Playwright script (scratchpad, not committed) at a
  consistent 1440×900 viewport against the real seeded backend.
- Store screenshots use the :3001 test-env setup (real Stripe test keys — the
  :3000 dev server has placeholders that break checkout rendering).
- Committed as PNG under `docs/images/` in each frontend repo (hub README is
  diagram-only unless a system screenshot earns its place). Relative image
  paths so GitHub renders them.
- Port discipline: backend :8080 is shared — store and admin capture sessions
  run sequentially, never in parallel.

### 5. Maintenance-rule extension

READMEs claim durable facts only. Marquee numbers (test counts, security
checklist score) are allowed but each must also exist in a KB file — the KB
copy is canonical. Add one sentence to the maintenance rule in each repo's
CLAUDE.md / AGENTS.md: *if a change alters a fact claimed in README.md, update
the README in the same commit.*

## Out of scope (YAGNI)

- Onboarding/CONTRIBUTING guides, operational runbooks (different audiences —
  future phases if ever needed).
- GitHub Pages / docs site.
- GIF demos.
- Store visual reskin of any kind; screenshots show the apps as they are.
- Auto-generated API reference.

## Verification

1. Every command in every README executed successfully during implementation.
2. Every factual claim cross-checked against `docs/kb/` or code; no claim may
   contradict a KB file.
3. Mermaid diagram syntax validated by rendering (GitHub preview after push,
   or a local mermaid renderer before commit).
4. Screenshots reviewed for accidental sensitive content (none expected —
   seeded test data only).
5. Review loop: spec review (this doc), per-repo review during implementation.

## Commits

One per repo: `docs(readme): ...` including that repo's README, its images,
and its maintenance-rule sentence. Hub commit also carries this spec (already
committed with it).
