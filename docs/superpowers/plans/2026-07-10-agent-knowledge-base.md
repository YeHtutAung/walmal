# Agent Knowledge Base Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create the agent-facing knowledge base (per-repo `docs/kb/` + cross-repo `SYSTEM.md`) with the self-maintaining update rule in each repo's agent-instructions file.

**Architecture:** Docs-only feature across three repos. One task per repo, each producing a self-contained commit: walmal (hub — SYSTEM.md + 4 KB files + CLAUDE.md rule), walmal-store (4 KB files + AGENTS.md rule), walmal-admin (new CLAUDE.md + 3 KB files). Every factual claim is verified against code at write time.

**Tech Stack:** Markdown only. Verification uses read-only inspection plus the documented commands themselves.

**Spec:** `walmal/docs/superpowers/specs/2026-07-10-agent-knowledge-base-design.md` — the implementer MUST read it first; content rules there are binding (terse bullets, exact paths, ≤100 lines/file soft cap, one home per fact, present-tense only, link-don't-duplicate).

**Repos (absolute paths):**
- `C:/YHA/006_Claude_Workspace/walmal` — Spring Boot modular monolith (branch `main`)
- `C:/YHA/006_Claude_Workspace/walmal-store` — Next.js storefront (branch `main`)
- `C:/YHA/006_Claude_Workspace/walmal-admin` — Vite/React/Refine admin SPA (branch `master`)

---

## Shared: the maintenance-rule block

Each task inserts this block (verbatim, adjusting only the intro sentence's file references) into the repo's agent-instructions file:

```markdown
## Knowledge base — MUST keep current

Agent-facing project knowledge lives in `docs/kb/` (cross-repo contracts:
`walmal/docs/kb/SYSTEM.md`). Read the relevant file before working in an
unfamiliar area.

**Maintenance rule:** any change that adds, updates, or removes a feature,
endpoint, contract, config, or workflow MUST update the affected
`docs/kb/*.md` file(s) in the same commit. If a cross-repo contract changed
(auth, error bodies, events, ports, env vars), also update
`walmal/docs/kb/SYSTEM.md` in the walmal repo — in the same work session;
cross-repo commit atomicity is not required.

**Review check:** every code review must answer: "Does this change require a
KB update, and was it made?" Refactors and test-only changes that alter no
documented fact need none.
```

In walmal-store and walmal-admin, `docs/kb/` in the first sentence refers to the local repo's KB, and the SYSTEM.md path stays as written (it is in the walmal repo).

---

## Fact-verification protocol (applies to every KB file)

For every bullet you write, verify against the code before committing:
- **Paths**: `ls` / Glob the exact path. A path that 404s may not be written.
- **Commands**: prefer running them (read-only ones); if too heavy (full test suites), verify the script/config they reference exists and flags match documentation (e.g., check `pom.xml`/`package.json`/`playwright.config.ts`).
- **Numbers** (ports, limits, versions, migration count): read them from the config/source file, cite nothing from memory or from this plan without checking.
- **Names** (env vars, cookie names, routing keys, roles): grep the source.

If a fact in this plan's outline turns out wrong, write the KB from the code (code wins) and note the correction in your report.

---

### Task 1: walmal — SYSTEM.md, KB files, CLAUDE.md rule

**Files:**
- Create: `docs/kb/SYSTEM.md`, `docs/kb/architecture.md`, `docs/kb/conventions.md`, `docs/kb/testing.md`, `docs/kb/gotchas.md`
- Modify: `CLAUDE.md` (append KB block)

- [ ] **Step 1: Read the spec** (`docs/superpowers/specs/2026-07-10-agent-knowledge-base-design.md`) — the per-file outlines there are the authoritative table of contents.

- [ ] **Step 2: Write `docs/kb/SYSTEM.md`** covering exactly (verify each):
  - Repo map: 3 repos, roles, workspace paths, ports — backend 8080; store dev 3000 / E2E 3001; admin Vite default 5173 **annotated as "not pinned in vite.config.ts — verify before relying on it"**.
  - Infra services + ports from `docker-compose.yml`: postgres 5432, redis 6379, rabbitmq 5672 (+15672 mgmt if exposed — check), minio 9000 (+console port — check), mailhog 1025 (+8025 UI — check).
  - Auth contract: JWT base64url; access-token TTL (read from config — do not assume 15 min); single-use rotating refresh tokens; roles (grep the backend enum/migrations: CUSTOMER, ADMIN, and admin-SPA roles STAFF/WAREHOUSE_*/POS_OPERATOR — verify exact names); per-client storage: store = httpOnly `walmal-rt` cookie via proxy routes, admin = localStorage.
  - Error-body contract (canonical home): Spring → RFC 9457 ProblemDetail (`detail`); store auth proxies → `{ code, message }`; store payment-intent → `{ error }`; clients parse field errors > message > detail.
  - Event contract: transactional outbox (`outbox_events`) → OutboxRelay → RabbitMQ; routing keys `{module}.{event}`; at-most-once after-commit; FAILED-row recovery → link `docs/DR_PLAN.md` (Scenario 6).
  - Env matrix: names + purpose only (WALMAL_JWT_SECRET, SPRING_DATA_REDIS_PASSWORD, NEXT_PUBLIC_API_URL, STRIPE keys, RATE_LIMIT_*, VITE_API_BASE_URL...) — never values. Test-profile behavior: `application-test.yml` = 100k rate limits, CORS :3001, `info.walmal.profile=test` marker.
  - Test credentials: pointer to the V12 seed migration file path (no passwords in SYSTEM.md).

- [ ] **Step 3: Write `docs/kb/architecture.md`**: one line per Maven module (list from root `pom.xml` `<modules>`); communication rules (service interfaces sync, outbox events async); key paths (migrations dir, `OutboxRelay`, `RateLimitFilter`, `InfrastructureConfiguration`); Flyway map V1–V{n} one-liner each — **count the files in `walmal-app/src/main/resources/db/migration/` first**.

- [ ] **Step 4: Write `docs/kb/conventions.md`**: link to root `CLAUDE.md` architecture rules (do not duplicate them); ProblemDetail responses (`AuthExceptionHandler`, `GlobalExceptionHandler` — verify class names); naming conventions; ADR index (`docs/adr/` — list actual files).

- [ ] **Step 5: Write `docs/kb/testing.md`**: unit vs `@Tag("integration")` (excluded by default); exact commands — `./mvnw test`, `./mvnw test -DexcludedGroups=`, the `-am` requirement for `-pl` runs, Testcontainers workaround `-Dapi.version=1.44` (Docker 29.x vs Testcontainers 1.20.4); profile facts (`application-test.yml`); JAR rebuild rule after config changes (`./mvnw -pl walmal-app -am -DskipTests clean package`).

- [ ] **Step 6: Write `docs/kb/gotchas.md`**: stale-JAR pattern; Docker Desktop/WSL notes relevant to the project (compose health flags vs `pg_isready`); k6 performance-test pointers (`tests/performance/`, `--summary-export`, rate-limit overrides for load tests); Cygwin shell caveats (`!` in curl bodies, `/E`-flag mangling) — mark the machine-specific ones as "environment notes".

- [ ] **Step 7: Append the shared KB block to `CLAUDE.md`** (see Shared section above; local `docs/kb/` reference is this repo's).

- [ ] **Step 8: Verify**: every path cited exists; every number matches config; each file ≤~100 lines; no fact duplicated between SYSTEM.md and the repo files.

- [ ] **Step 9: Commit**

```bash
git add docs/kb/ CLAUDE.md
git commit -m "docs(kb): add agent knowledge base — SYSTEM.md, repo KB, maintenance rule"
```

---

### Task 2: walmal-store — KB files, AGENTS.md rule

**Files:**
- Create: `docs/kb/architecture.md`, `docs/kb/conventions.md`, `docs/kb/testing.md`, `docs/kb/gotchas.md`
- Modify: `AGENTS.md` (append KB block; do NOT touch `CLAUDE.md` — it only contains `@AGENTS.md`)

- [ ] **Step 1: Read the spec** (in the walmal repo) and `walmal/docs/kb/SYSTEM.md` as written by Task 1 — do not restate any fact that lives there.

- [ ] **Step 2: Write `docs/kb/architecture.md`**: page-route map from `src/app/` (route groups `(account)`, `(checkout)`, `(shop)`, login/register/order-confirmation); API routes — auth proxies (login/register/refresh/logout + `walmal-rt` cookie mechanics summary, details in SYSTEM.md), `payment-intent` (Stripe, rate-limited), `minio/[...path]` proxy; ONE line for mock `/api/v1/*` routes flagged "inactive/legacy — not used by tests or the real app; deletion is routine cleanup that updates this file"; Zustand stores (`auth-store`, `cart-store` w/ persist key `walmal-cart`); `src/middleware.ts` presence-cookie guard; Stripe CardElement flow; `src/lib/rate-limit.ts` one-liner with limits + env names.

- [ ] **Step 3: Write `docs/kb/conventions.md`**: `src/lib/api/client.ts` error-parsing precedence (shape definitions live in SYSTEM.md — link); ApiError shape; component/store patterns actually used; pointer to AGENTS.md Next.js-version warning (`node_modules/next/dist/docs/`).

- [ ] **Step 4: Write `docs/kb/testing.md`**: vitest layout (`tests/**/*.test.ts`, jsdom, `@` alias) + run command; Playwright: two webServers (Docker+Spring JAR test profile; Next.js on **3001**, `reuseExistingServer:false` and why), `.env.test.local` role (real test Stripe keys + 100k rate limits; gitignored), `global-setup.ts` drift checks, Stripe iframe fill technique, test-credentials pointer to walmal V12 migration, `npx playwright test` expected 96 passed.

- [ ] **Step 5: Write `docs/kb/gotchas.md`**: base64url JWT decode fix (`decodePayload`); server-set presence cookie (Chromium IPC race); silent-refresh 429 → guest downgrade (recoverable on reload); stale `.next/types` tsc errors after route deletions; reused :3000 dev server has placeholder Stripe keys.

- [ ] **Step 6: Append the shared KB block to `AGENTS.md`.**

- [ ] **Step 7: Verify** per the fact-verification protocol; run `npx vitest run` to confirm the documented unit-test command works (fast).

- [ ] **Step 8: Commit**

```bash
git add docs/kb/ AGENTS.md
git commit -m "docs(kb): add agent knowledge base and maintenance rule"
```

---

### Task 3: walmal-admin — new CLAUDE.md, KB files

**Files:**
- Create: `CLAUDE.md`, `docs/kb/architecture.md`, `docs/kb/conventions.md`, `docs/kb/gotchas.md`
- (No `testing.md` — the repo has no tests. No AGENTS.md — Claude Code only.)

- [ ] **Step 1: Read the spec and `walmal/docs/kb/SYSTEM.md`.** Note: repo branch is `master`, not main.

- [ ] **Step 2: Write `CLAUDE.md`**: 2–3 lines of repo identity (React 19 + Vite + Refine 5 admin SPA for the walmal backend; `VITE_API_BASE_URL`), then the shared KB block, then a pointer to `walmal/docs/kb/SYSTEM.md` for cross-repo contracts.

- [ ] **Step 3: Write `docs/kb/architecture.md`**: `src/App.tsx` (Refine resources + routes), providers (`auth-provider.ts`, `walmal-data-provider.ts`, `access-control-provider.ts` — note `data-provider.ts` is legacy/unused), `src/lib/axios-client.ts` (JWT interceptors + single-flight refresh queue), pages/ areas one-liner each, `components/shared/DataTable.tsx`.

- [ ] **Step 4: Write `docs/kb/conventions.md`**: custom data-provider mappings — Spring Pageable 0-indexed `page`/`size`, `ApiResponse<T>` unwrapping, Spring `Page<T>` handling, filter renames (e.g. `name`→`q`, `channel`→`type` — verify in `walmal-data-provider.ts`); Refine hook patterns (`useTable`, `useCan`); `npm run generate:types` workflow (OpenAPI from `http://localhost:8080/v3/api-docs`); commands (`npm run dev|build|lint|preview`).

- [ ] **Step 5: Write `docs/kb/gotchas.md`**: tokens in localStorage (`accessToken`, `refreshToken`, `role`, `tokenExpiry`) — known risk, contrast with store's httpOnly approach (see SYSTEM.md); resource-id vs URL mapping (`auth/users` resource vs `/users` route); `src/types/api.ts` generated but effectively empty; no `.env.development` — `VITE_API_BASE_URL` must be provided for dev; Playwright dep installed but zero tests.

- [ ] **Step 6: Verify** per protocol; run `npm run lint` to confirm the documented command works.

- [ ] **Step 7: Commit**

```bash
git add CLAUDE.md docs/kb/
git commit -m "docs(kb): add CLAUDE.md and agent knowledge base with maintenance rule"
```

---

### Task 4: Cross-repo consistency check

**Files:** none created — read-only verification, fixes only if issues found.

- [ ] **Step 1:** Re-read all 12 created/modified files across the three repos.
- [ ] **Step 2:** Verify: no fact appears in two places (especially error shapes, ports, auth storage — SYSTEM.md is the only home for cross-repo facts); every cross-file link/path resolves; all three instruction files carry the identical rule block (modulo the intro sentence); each KB file ≤~100 lines.
- [ ] **Step 3:** Fix any violations in the offending repo (amend via new commit in that repo, message `docs(kb): fix cross-repo consistency — <what>`).
- [ ] **Step 4:** Report a summary table: file → line count → verified facts spot-checked.

---

## Verification (whole feature)

1. All four tasks' verification steps passed.
2. `git log` in each repo shows exactly one KB commit (plus fixes if any); working trees clean.
3. Pipeline dry-run happens organically: the next real feature change must update the KB in the same commit — reviewers now ask the review-check question. (Not executable now; noted as the stability signal per the spec.)
