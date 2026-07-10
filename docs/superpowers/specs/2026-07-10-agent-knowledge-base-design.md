# Agent Knowledge Base for walmal / walmal-store / walmal-admin — Design

**Date:** 2026-07-10
**Status:** Approved
**Audience:** AI agents (Claude Code sessions) first. Human-developer docs are a later
phase built on top of this once the update pipeline is stable.

## Problem

Project knowledge for the three walmal repos lives in scattered places: one
machine-local agent memory file, long design specs, and two of three repos'
CLAUDE.md files (walmal-admin has none). New agent sessions rediscover the same
facts, and nothing forces documentation to track code changes. The main
objective is a **self-maintaining** knowledge base: whenever a feature is
added, updated, or deleted, the relevant KB file is updated in the same change.

## Decisions (made during brainstorming)

1. **Audience:** AI agents first; human docs deferred to phase 2.
2. **Layout:** hybrid — per-repo KB files committed with the code they
   describe, plus ONE cross-repo `SYSTEM.md`.
3. **Enforcement:** instruction + review-time check (no git hooks, no CI).
4. **`SYSTEM.md` location:** `walmal/docs/kb/SYSTEM.md` (backend is the hub);
   the two frontend repos point to it.
5. **Granularity:** lean topic files (3–5 per repo), each small and focused.

## Layout

```
walmal/                          (Spring Boot modular monolith — hub)
  CLAUDE.md                      MODIFY: add KB index + maintenance rule
  docs/kb/SYSTEM.md              NEW: the only cross-repo document
  docs/kb/architecture.md        NEW
  docs/kb/conventions.md         NEW
  docs/kb/testing.md             NEW
  docs/kb/gotchas.md             NEW

walmal-store/                    (Next.js App Router storefront)
  AGENTS.md                      MODIFY: add KB index + maintenance rule + SYSTEM.md pointer
                                 (CLAUDE.md already contains only `@AGENTS.md` and loads it;
                                 leave CLAUDE.md unchanged — the rule goes in AGENTS.md only)
  docs/kb/architecture.md        NEW
  docs/kb/conventions.md         NEW
  docs/kb/testing.md             NEW
  docs/kb/gotchas.md             NEW

walmal-admin/                    (Vite/React/Refine admin SPA — used with Claude Code
                                 only, so CLAUDE.md is the correct filename; no AGENTS.md)
  CLAUDE.md                      NEW: KB index + maintenance rule + SYSTEM.md pointer
  docs/kb/architecture.md        NEW
  docs/kb/conventions.md         NEW
  docs/kb/gotchas.md             NEW
  (no testing.md — the repo has no tests; the file is created when tests are)
```

## Content rules

- **Terse and factual.** Bullet points, exact file paths, exact commands. No
  prose padding. Target ≤100 lines per file (soft cap — split or prune when
  exceeded).
- **One home per fact.** Cross-repo facts (contracts between apps) live ONLY
  in `SYSTEM.md`. Repo-internal facts live ONLY in that repo's `docs/kb/`.
  When a deep document already exists (DR_PLAN.md, ADRs, security checklist,
  superpowers specs), the KB links to it instead of duplicating it.
- **KB describes the present.** No changelogs, no history, no "recently
  changed" — git history covers that. Dates appear only where a fact is
  time-sensitive (e.g., a pinned workaround for a specific tool version).
- **Machine-local facts stay out.** Anything about this specific development
  machine (Cygwin quirks, WSL layout, local paths outside the repo) goes in
  `gotchas.md` clearly marked as environment notes, or stays in agent memory
  if it is user-specific rather than project-specific.

## Per-file content outline

### walmal/docs/kb/SYSTEM.md (cross-repo, canonical)
- Repo map: three repos, their roles, absolute workspace paths, default ports
  (backend 8080, store 3000/3001-test, admin Vite default 5173 — not pinned in
  `vite.config.ts`, note this in SYSTEM.md so agents verify before relying on it).
- Infrastructure services: postgres 5432, redis 6379, rabbitmq 5672, minio
  9000, mailhog 1025 (docker compose in walmal).
- Auth contract: JWT (base64url), 15-min access tokens, single-use rotating
  refresh tokens, roles (CUSTOMER, ADMIN, STAFF, WAREHOUSE_*, POS_OPERATOR),
  which client uses which storage strategy (store: httpOnly `walmal-rt`
  cookie via proxy routes; admin: localStorage).
- Error-body contract: Spring emits RFC 9457 ProblemDetail (`detail`);
  store proxy routes emit `{ code, message }`; store payment-intent emits
  `{ error }`; clients' parsing precedence.
- Event contract: transactional outbox → RabbitMQ, routing-key scheme
  `{module}.{event}`, at-most-once relay, FAILED-row recovery →
  link to `docs/DR_PLAN.md`.
- Environment matrix: which env vars each app needs (names + purpose only,
  never values), test profile behavior (100k rate limits, CORS :3001).
- Test credentials: pointer to V12 migration seed accounts (not the passwords
  themselves — the migration file is the source of truth).

### walmal/docs/kb/*
- `architecture.md`: 10 Maven modules one-liner each; module-communication
  rules (service interfaces, events via outbox); key paths (migrations dir,
  OutboxRelay, RateLimitFilter); Flyway one-liner map V1–V{n} (count against
  `walmal-app/src/main/resources/db/migration/` at write time — do not trust
  this spec's number).
- `conventions.md`: cross-module access rules (distilled from CLAUDE.md, not
  duplicated — link), ProblemDetail error responses, naming, ADR index.
- `testing.md`: unit vs `@Tag("integration")`; exact Maven commands incl.
  `-am` requirement and Testcontainers `-Dapi.version=1.44` workaround;
  profiles (`application-test.yml` markers); JAR rebuild requirement after
  config changes.
- `gotchas.md`: stale-JAR pattern, Docker/WSL notes, k6 lessons (or link to
  tests/performance), Cygwin shell caveats (marked as environment notes).

### walmal-store/docs/kb/*
- `architecture.md`: app-route map (pages + API routes incl. auth proxies,
  payment-intent, minio proxy), Zustand stores, middleware, Stripe
  CardElement flow, rate limiter. The mock /api/v1 routes are documented in
  one line flagged "inactive/legacy — not used by tests or the real app";
  deleting them is routine cleanup that updates this file.
- `conventions.md`: how `src/lib/api/client.ts` parses error bodies (parsing
  precedence only — the shape definitions themselves live in SYSTEM.md, link
  to it), component/store patterns, Next.js version warning (read
  node_modules/next/dist/docs — already in AGENTS.md, linked).
- `testing.md`: vitest layout; Playwright two-webServer setup, port-3001
  rule, `.env.test.local` role, global-setup drift checks, Stripe iframe
  test technique, test credentials pointer.
- `gotchas.md`: base64url JWT decode, presence-cookie race, silent-refresh
  429 downgrade, stale `.next/types` tsc errors.

### walmal-admin/docs/kb/*
- `architecture.md`: Refine app structure (App.tsx resources, providers,
  pages), axios client with refresh queue, access-control roles.
- `conventions.md`: custom data provider mappings (Spring Pageable 0-indexed,
  `ApiResponse<T>` unwrapping, filter param renames like `name`→`q`),
  Refine hook patterns, `generate:types` workflow.
- `gotchas.md`: tokens in localStorage (known risk, contrast with store),
  resource-id vs URL mapping (`auth/users` vs `/users`), empty generated
  `types/api.ts`, missing `.env.development` (VITE_API_BASE_URL).

## Maintenance rule (the pipeline)

Each repo's agent-instructions file (walmal `CLAUDE.md`, walmal-store
`AGENTS.md`, walmal-admin `CLAUDE.md`) gets this block, adapted per repo:

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

The review check integrates with the existing workflow (superpowers review
loops) by convention — reviewer prompts include the question. No hooks or CI:
enforcement lives where judgment lives.

## Error handling / failure modes

- **Drift** (KB contradicts code): whichever agent finds it fixes the KB in
  its current change — the KB is code-adjacent, so the fix is one commit.
- **Bloat**: the ≤100-line soft cap plus "link, don't duplicate" keeps files
  loadable; reviewers flag files that outgrow their topic.
- **Stale SYSTEM.md** (cross-repo change committed in a frontend repo can't
  atomically update walmal): the rule requires the SYSTEM.md commit in the
  same work session; acceptable eventual consistency across repos.

## Testing / verification

Docs-only feature — verification is review-based:

1. Each KB file is fact-checked against the code it describes at write time
   (paths exist, commands run, ports/limits match config).
2. Spot-check commands: run the documented test commands in each repo
   (`mvn test` variants, `npx vitest run`, `npm run lint` in admin) to prove
   the documented invocations are correct.
3. Review loop: spec review (this doc), then per-task review during
   implementation, verifying terseness, no duplication, and accuracy.
4. Pipeline dry-run: after landing, the NEXT real feature change in any repo
   must demonstrate the rule (KB updated in the same commit). This is the
   stability signal before starting phase 2 (human docs).

## Out of scope (YAGNI)

- Human-developer documentation (phase 2, after the pipeline proves stable)
- Auto-generation from OpenAPI or code
- Git hooks / CI enforcement of the maintenance rule
- Per-module deep-dive documents or decision logs (ADRs already exist)
- walmal-admin `testing.md` (no tests exist yet)
- Migrating/deleting the machine-local agent memory (stays as user-specific
  memory; project facts get promoted into the KB)
