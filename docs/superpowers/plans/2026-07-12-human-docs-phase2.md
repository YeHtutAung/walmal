# Human-Facing Documentation (KB Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship portfolio-grade READMEs for walmal, walmal-store, and walmal-admin — hub-and-spoke, with a Mermaid architecture diagram in the hub and screenshots in the two frontend repos.

**Architecture:** The hub README (walmal) carries the system story: diagram, engineering highlights, module table, verified quickstart. The frontend READMEs are ~100-line product pages with screenshots, linking back to the hub. Screenshots are captured by temporary Playwright spec files that piggyback on each repo's existing E2E infrastructure (auto-started backend + correct env), then deleted before commit.

**Tech Stack:** Markdown + GitHub-flavored Mermaid; Playwright for screenshot capture; mermaid-cli for diagram validation.

**Spec:** `walmal/docs/superpowers/specs/2026-07-12-human-docs-phase2-design.md`

**Repos:** walmal = `C:/YHA/006_Claude_Workspace/walmal` (main), walmal-store = `C:/YHA/006_Claude_Workspace/walmal-store` (main), walmal-admin = `C:/YHA/006_Claude_Workspace/walmal-admin` (master). One commit per repo. Tasks 2 and 3 both boot the backend on :8080 — run them SEQUENTIALLY, never in parallel, and kill leftover java/node processes between tasks.

**Tone guidance for all READMEs:** written for an engineer skimming for 2–5 minutes. Confident, specific, zero filler ("This project demonstrates…" is banned). Every number and claim must be sourced from a `docs/kb/` file or verified by running a command — the KB copy is canonical. No volatile facts beyond the marquee numbers the spec allows (test counts, checklist score).

---

### Task 1: Hub README (walmal) + maintenance-rule sentence

**Files:**
- Create: `README.md` (repo root)
- Modify: `CLAUDE.md` (~line 229, the `**Maintenance rule:**` paragraph)

- [ ] **Step 1: Gather and verify facts (read, don't trust memory)**

Read these sources; every README claim traces to one of them:
- `docs/kb/architecture.md` — module list + responsibilities, admin-facing endpoints
- `docs/kb/SYSTEM.md` — ports, auth/error/event contracts, env-var names
- `docs/kb/testing.md` — test commands, counts
- `docs/kb/gotchas.md` — anything that contradicts a claim you were about to make
- `docs/adr/` — list the 6 ADR files to link
- `walmal-store/tests/security/FRONTEND_CHECKLIST.md` line ~287 — "45/45 items PASS"
- Marquee numbers: store 96 Playwright tests across chromium/firefox/webkit (32 unique × 3 — say "across", never "× 3"); admin 21 E2E + 65 unit; k6 6 suites passing.
- Engineering-highlight source material: transactional outbox (V15 `outbox_events`, `OutboxRelay` @Scheduled 1s, FOR UPDATE SKIP LOCKED, 60-attempt cap → FAILED, broker-outage recovery verified live); guest email notifications (V14, MailHog-verified); JWT + rotating single-use refresh tokens, storefront refresh token in httpOnly cookie; per-route rate limiting on the storefront API routes; Stripe test-mode payments. Verify each against the KB/ADRs before claiming.

- [ ] **Step 2: Draft `README.md`**

Structure (~180 lines):
1. `# walmal` + one-liner: modular-monolith e-commerce backend (Spring Boot 3, Java 21 — check `pom.xml` for the real Java version) powering a customer storefront and an ops admin.
2. Repo links table: [walmal-store](https://github.com/YeHtutAung/walmal-store) (Next.js storefront), [walmal-admin](https://github.com/YeHtutAung/walmal-admin) (Refine ops admin), this repo (backend hub).
3. `## Architecture` — Mermaid diagram (see Step 3).
4. `## Engineering highlights` — 6–8 bullets, 2–3 lines each, per the fact list above.
5. `## Modules` — table: module → one-line responsibility (auth, product, inventory, order, pos, warehouse, notification; mention common/infrastructure/app as plumbing in a footnote line).
6. `## Quickstart` — fenced commands: `docker compose up -d --wait` (list the services), `./mvnw -pl walmal-app -am -DskipTests clean package`, `java -Dspring.profiles.active=test -jar walmal-app/target/walmal-app-0.1.0-SNAPSHOT.jar`, health check `curl http://localhost:8080/actuator/health`, seeded test credentials (customer_test / admin_test with passwords — they are committed V12 test-profile seeds, fine to show, label them test-only). State Swagger/OpenAPI UI URL if one exists (check `application*.yml` / dependencies — do NOT claim it if unverified).
7. `## Testing` — unit (`./mvnw test`), integration note (`@Tag("integration")`, Testcontainers), the two frontend E2E suites (one line each + counts), k6 suite (6 scenarios, thresholds met).
8. `## Documentation` — links: `docs/adr/` (list titles), `docs/kb/` labeled "agent-facing knowledge base (also the canonical source for every number in this README)".

- [ ] **Step 3: Write and validate the Mermaid diagram**

Content: three app nodes (walmal-store :3000, walmal-admin :5173/5174, walmal backend :8080); backend subgraph with the 7 business modules around RabbitMQ (events: order → warehouse fulfillment, inventory release → order cancel, outbox in front of Rabbit); infra nodes Postgres/Redis/RabbitMQ/MinIO/MailHog; Stripe as external. Keep it readable — ~20 nodes max, `graph TB` or `graph LR`, no styling beyond a couple of `classDef`s.

Validate before committing: extract the block to `<scratchpad>/walmal-arch.mmd` and run
`npx -y @mermaid-js/mermaid-cli -i <scratchpad>/walmal-arch.mmd -o <scratchpad>/walmal-arch.svg`
Expected: exit 0 and an SVG produced. (mmdc needs headless Chromium; it downloads on first run. If mmdc is unworkable in this environment, fall back to pasting the block into a `mermaid.live` render via a quick browser check — do not skip validation.)

- [ ] **Step 4: Verify every quickstart command by running it**

Ports 8080 free first. Run the docker compose command, the JAR (background), the health curl (expect `{"status":"UP"...}`), and a login curl with the seeded customer (expect 200 + accessToken in body). Kill the JAR afterwards. Any command that doesn't work exactly as written in the README must be fixed in the README, not annotated.

- [ ] **Step 5: Add the maintenance-rule sentence to `CLAUDE.md`**

In the `**Maintenance rule:**` paragraph (~line 229), append:

```
If a change alters a fact claimed in `README.md`, update the README in the
same commit (README numbers mirror `docs/kb/` — the KB copy is canonical).
```

- [ ] **Step 6: Commit (walmal)**

```bash
git add README.md CLAUDE.md
git commit -m "docs(readme): flagship system README with architecture diagram

KB phase 2 (portfolio audience): Mermaid architecture diagram,
engineering highlights, module table, verified quickstart, doc links.
Maintenance rule extended to cover README-claimed facts.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Store screenshots + README (walmal-store)

**Files:**
- Create: `docs/images/home.png`, `docs/images/product.png`, `docs/images/checkout.png`
- Create (TEMPORARY — delete before commit): `tests/e2e/zz-screenshots.spec.ts`
- Rewrite: `README.md` (replace create-next-app boilerplate entirely)
- Modify: `AGENTS.md` (maintenance-rule paragraph)

- [ ] **Step 1: Write the temporary capture spec**

`tests/e2e/zz-screenshots.spec.ts` — piggybacks on the repo's Playwright config (auto-boots backend + Next.js on :3001 with `.env.test.local`, so Stripe renders with real test keys):

```typescript
import { test } from "@playwright/test";
import fs from "fs";

test.use({ viewport: { width: 1440, height: 900 } });

test("capture portfolio screenshots", async ({ page }) => {
  fs.mkdirSync("docs/images", { recursive: true });

  await page.goto("/");
  await page.waitForLoadState("networkidle");
  await page.screenshot({ path: "docs/images/home.png" });

  await page.goto("/products");
  await page.waitForLoadState("networkidle");
  // First product card → detail page (seeded Galaxy S24 Ultra variants).
  await page.locator("a[href^='/products/']").first().click();
  await page.waitForLoadState("networkidle");
  await page.screenshot({ path: "docs/images/product.png" });

  // Add to cart → checkout so the Stripe CardElement is visible.
  await page.getByRole("button", { name: /add to cart/i }).click();
  await page.goto("/checkout");
  await page.waitForSelector("iframe[name^='__privateStripeFrame']");
  await page.waitForLoadState("networkidle");
  await page.screenshot({ path: "docs/images/checkout.png" });
});
```

Adjust selectors to the real DOM if they don't match (read the page components first: `src/app/(shop)/page.tsx`, `products/[slug]/page.tsx`, `(checkout)/checkout/page.tsx`). Known corrections from plan review (verified against the existing specs):
- Product cards use `[data-testid="product-card"]` / `[data-testid="product-card-link"]`, not bare `a[href^='/products/']`.
- On the product detail page, "Add to cart" is disabled until a variant is selected (see TC-E2E-005 in the existing suite). Alternatively reuse the `seedCart` helper from `tests/e2e/helpers` — the checkout specs' proven path to a filled cart.
- `/checkout` shows a "Continue as guest" choice first, then requires email + address fields (`#line1/#city/#postalCode/#country`) before the Stripe iframe mounts — follow the existing checkout E2E spec, it is the authoritative recipe.
- Don't `waitForLoadState("networkidle")` after the Stripe iframe appears (Stripe keeps background requests alive) — wait for the card-number field's visibility as the existing specs do.

- [ ] **Step 2: Run the capture (ports 8080/3001 free first)**

Run: `npx playwright test tests/e2e/zz-screenshots.spec.ts --project=chromium`
Expected: 1 passed; three PNGs exist under `docs/images/`, each ~1440×900. Open/Read each PNG to confirm it shows what it should (home hero, product detail, checkout with card field) — not a blank/loading state.

- [ ] **Step 3: Delete the temporary spec**

`rm tests/e2e/zz-screenshots.spec.ts` — it must NOT be committed (it would become a 33rd test and break the documented 96 count).

- [ ] **Step 4: Rewrite `README.md`**

Structure (~100 lines): title + one-liner; screenshots (home near top, product + checkout inline with feature prose, relative paths `docs/images/*.png`); `## Features` (guest + registered checkout, Stripe CardElement test-mode payments, cart persistence + guest-cart merge on login, silent token refresh with httpOnly refresh cookie, server-side `/account` route guarding, rate-limited API proxy routes); `## Stack` (Next.js App Router, TypeScript, Zustand, Tailwind + shadcn/ui, Stripe); `## Running locally` (needs the walmal backend — link; `npm install`, env file with names only — list the `NEXT_PUBLIC_API_URL` and Stripe key names from `.env.test.local`/KB, never values; `npm run dev`); `## Tests` (96 Playwright tests across chromium/firefox/webkit against the real backend, `npx playwright test`, note the suite auto-boots backend + a fresh :3001 server); `## System documentation` link to the walmal hub README.
Source facts from `docs/kb/` in this repo; do not contradict it.

- [ ] **Step 5: Add the maintenance-rule sentence to `AGENTS.md`**

Same sentence as Task 1 Step 5, appended to the maintenance-rule paragraph.

- [ ] **Step 6: Verify + commit (walmal-store)**

`git status -s` must show ONLY: `README.md`, `AGENTS.md`, `docs/images/` (3 PNGs). No temp spec. Then:

```bash
git add README.md AGENTS.md docs/images
git commit -m "docs(readme): portfolio README with screenshots

KB phase 2: replaces create-next-app boilerplate. Screenshots captured
at 1440x900 against the seeded test backend. Maintenance rule extended
to cover README-claimed facts.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Kill any leftover java/node processes from the capture run (ports 8080/3001) before Task 3.

---

### Task 3: Admin screenshots + README (walmal-admin)

**Files:**
- Create: `docs/images/dashboard.png`, `docs/images/products.png`, `docs/images/terminals.png`
- Create (TEMPORARY — delete before commit): `tests/e2e/zz-screenshots.spec.ts`
- Rewrite: `README.md` (replace Vite boilerplate entirely)
- Modify: `CLAUDE.md` (maintenance-rule paragraph)

- [ ] **Step 1: Write the temporary capture spec**

Same pattern as Task 2 — the admin Playwright config auto-boots backend + Vite :5174 and the `chromium` project is pre-authenticated via storage state:

```typescript
import { test } from "@playwright/test";
import fs from "fs";

test.use({ viewport: { width: 1440, height: 900 } });

test("capture portfolio screenshots", async ({ page }) => {
  fs.mkdirSync("docs/images", { recursive: true });

  await page.goto("/");
  await page.waitForLoadState("networkidle");
  await page.screenshot({ path: "docs/images/dashboard.png" });

  await page.goto("/products");
  await page.waitForLoadState("networkidle");
  await page.screenshot({ path: "docs/images/products.png" });

  await page.goto("/pos/terminals");
  await page.waitForLoadState("networkidle");
  await page.screenshot({ path: "docs/images/terminals.png" });
});
```

- [ ] **Step 2: Run the capture (ports 8080/5174 free first)**

Run: `npx playwright test tests/e2e/zz-screenshots.spec.ts` (setup project runs first and authenticates). Expected: 2 passed (setup + capture); three PNGs showing the Daylight-reskinned dashboard (charts populated from seeded data), products table, terminals list. Read each PNG to confirm no blank/loading captures.

- [ ] **Step 3: Delete the temporary spec** (must not become a 22nd test).

- [ ] **Step 4: Rewrite `README.md`**

Structure (~100 lines): title + one-liner (ops admin for the walmal backend); dashboard screenshot near top; `## Features` (role-based access — ADMIN wildcard, per-role grants incl. POS_OPERATOR/WAREHOUSE_*; product/category/user/POS-terminal management; dashboards: orders & revenue 30-day chart, per-category stock health, needs-attention panel; global search across orders/products/users; notifications); products + terminals screenshots inline; `## Stack` (React 18, Refine, Vite, TypeScript, Tailwind + shadcn/ui, Recharts); `## Running locally` (needs walmal backend — link; `npm install`; `VITE_API_BASE_URL=http://localhost:8080` name-only env note — explicitly NO `/api/v1` suffix, the data provider appends it; `npm run dev`); `## Tests` (65 unit / vitest + 21 Playwright E2E against the real backend; commands `npm run test:unit`, `npm run test:e2e`); `## System documentation` link to hub.
Source facts from this repo's `docs/kb/`.

- [ ] **Step 5: Add the maintenance-rule sentence to `CLAUDE.md`** (same sentence).

- [ ] **Step 6: Verify + commit (walmal-admin)**

`git status -s` shows ONLY README.md, CLAUDE.md, docs/images (3 PNGs). Then:

```bash
git add README.md CLAUDE.md docs/images
git commit -m "docs(readme): portfolio README with Daylight screenshots

KB phase 2: replaces Vite template boilerplate. Screenshots captured
at 1440x900 against the seeded test backend (pre-authed storage state).
Maintenance rule extended to cover README-claimed facts.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Final verification sweep + push

- [ ] **Step 1:** All three repos: `git status -s` clean; no leftover `zz-screenshots.spec.ts` anywhere (`Glob **/zz-screenshots*`).
- [ ] **Step 2:** No stray processes on 8080/3001/5174.
- [ ] **Step 3:** Push all three repos.
- [ ] **Step 4:** After push, open each repo's GitHub page and confirm: README renders, Mermaid diagram renders, images render. (Browser check or `WebFetch` the github.com repo URLs.) Fix-forward any rendering issue in a small follow-up commit.
- [ ] **Step 5:** Report: commits, what each README claims (marquee numbers), and confirmation the rendered pages look right.
