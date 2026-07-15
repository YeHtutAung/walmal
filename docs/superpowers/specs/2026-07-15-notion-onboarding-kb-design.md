# Notion Onboarding Knowledge Base — Design

**Date:** 2026-07-15
**Status:** Approved (design)
**Scope:** A Notion page tree that onboards a new engineer across the three
walmal repos, from zero to first PR.

## Problem

The three repos (`walmal`, `walmal-store`, `walmal-admin`) have strong
**reference** documentation: per-repo `docs/kb/` (architecture, conventions,
gotchas, testing), `docs/kb/SYSTEM.md` for cross-repo contracts, ADRs, and
READMEs. `SYSTEM.md` is precise down to LIKE-wildcard semantics.

They have no **path**. Reference documentation answers *what is true*.
Onboarding answers *what do I do now*. Nothing in any repo tells a newcomer
where to start, what to read in what order, or how to get from a clean
machine to a merged PR.

A second gap: `walmal/docs/kb/gotchas.md` holds the most valuable setup
knowledge in the project, but half of it (3 of 6 sections — WSL layout, k6
install, Cygwin shell caveats) is tagged *"environment note — this machine
only"* and describes the author's box rather than the project. Those sections
do not transfer to another engineer's machine. The remaining portable traps
are real and carry over (see page 2).

## Constraint that shapes the whole design

`CLAUDE.md` and `SYSTEM.md` establish that `docs/kb/` is the single source of
truth and must be updated in the same commit as any change to a documented
fact. Notion cannot participate in a commit. Any fact copied into Notion will
drift silently, with no mechanism to catch it.

A stale onboarding doc is worse than no onboarding doc: a newcomer who reads
it and writes code against it is actively misled. The precision that makes
`SYSTEM.md` valuable is exactly what makes a copy of it dangerous.

## Approach

**Notion is a front door, not a mirror.** It holds only what does not exist in
the repos — orientation, reading order, machine-agnostic setup, an annotated
first-PR walkthrough — and deep-links to canonical files on GitHub for every
fact.

Approaches considered and rejected:

- **Full mirror** of all ~1,000+ KB lines into Notion. Complete and browsable,
  but duplicates facts the project rules forbid duplicating, and drifts on the
  next commit with nothing to catch it.
- **Hub plus generated snapshot + sync script**, stamping the synced commit
  SHA. Makes drift visible rather than silent, but costs a script, another
  rule to remember, and is still stale between runs. Available as a later
  step if the mirror turns out to be wanted.

The front-door approach was chosen because its content is the part that does
not exist yet — it is pure gain, where the other two spend most of their
effort re-encoding documentation that is already written well. When a
contributor needs an exact contract, sending them to `SYSTEM.md` is *better*
than a Notion copy, because `SYSTEM.md` is correct and a copy might not be.

## The no-facts rule

Stated at the top of the Notion parent page and applied without exception:

> **Notion pages contain no facts.** No ports, no TTLs, no counts, no endpoint
> shapes, no env var names. Anything falsifiable by a commit lives in the repo
> and is linked, never copied.

Test applied to every sentence: **could a commit make this false?** If yes, it
becomes a link.

**Commands vs. values.** Commands that are stable interface (`docker compose
up -d --wait`, the `mvnw` invocation) may appear, because they are how a
reader *uses* the system and they change rarely. The exception also covers the
**entry-point URL** (`/actuator/health`) and the **JDK major version** (21) on
page 2 — both are stable interface in the same sense, and success criterion 1
requires page 2 to stand alone. Values — ports, TTLs, credentials, counts,
response shapes — never appear. This is a deliberate pragmatic exception to
the rule above; it is the one place the design trades a little drift risk for
a page that is actually usable.

Example of the rule in practice: the architecture page says *"inventory owns
the stock-health rollup, and the reason is a Maven cycle you would otherwise
create — [read why](link)"*. It does not say *"returns one entry per category
sorted alphabetically"* — that is `SYSTEM.md`'s job, and a copy starts lying
the moment the sort changes.

## Structure

Six pages under one parent, ordered as a newcomer moves through them:

```
walmal — Engineering Onboarding
├── 1. Orientation
├── 2. Set Up Your Machine
├── 3. Architecture
├── 4. Rules You Must Not Break
├── 5. Your First PR
└── 6. Where the Docs Live
```

### 1. Orientation

What walmal is, the three-repo split and why, the request path from storefront
through backend to Postgres. Includes a Mermaid architecture diagram (Notion
renders Mermaid natively), since shape is what orients a reader.

**The diagram is a de-labelled variant of the one in `walmal/README.md`, not a
copy.** The README's version carries ports (`:3000`, `:5173/5174`, `:8080`),
the relay poll interval, and routing keys as node/edge labels — all values the
no-facts rule bans, and none covered by the commands-vs-values exception. The
Notion variant keeps the topology (which clients call which modules, which
modules publish through the outbox, what backs what) and drops every value
label. Deliberate divergence from the README source: the README may state
values because it lives in the repo and is updated in-commit; Notion may not.
Link to the README's fuller diagram beneath it for readers who want the
labelled version.

### 2. Set Up Your Machine

The genuinely new content. Machine-agnostic path: JDK 21, Docker, `docker
compose up -d --wait`, build the JAR, run with the test profile, verify at
`/actuator/health`.

Followed by a **known sharp edges** section carrying over the *portable* traps
from `gotchas.md` — the ones not tagged "environment note":

- **Stale JAR** — config edits in the working tree are invisible to a running
  JAR that packaged them at build time.
- **`-pl` without `-am`** — compiles against stale `walmal-common` in `~/.m2`,
  producing phantom "symbol not found" / `NoClassDefFoundError` errors in
  otherwise-green tests.
- **Testcontainers + Docker 29.x** — integration tests fail against current
  Docker Engine without the workaround flag documented in `testing.md`.
  Portable and load-bearing: a newcomer on a current Docker install cannot run
  the integration suite without it, which would defeat this page's success
  criterion.

All three bite everyone and all three produce baffling symptoms. The
machine-tagged notes in `gotchas.md` (WSL layout, Cygwin shell caveats, k6
install) stay in the repo, where they belong — they describe the author's box,
not the project.

### 3. Architecture

Reading order, one sentence of "why you care" per stop, linking to each
canonical file: modular monolith and the module table → boundary rules → the
outbox and why it exists → the ADRs.

Includes the two "we already made this mistake" stories from the KB, because
they are precisely what onboarding is for:

- The **inventory/product dependency-cycle correction** — the endpoint was
  specced under `walmal-product` and had to be flipped once the
  one-directional Maven dependency was noticed.
- The **LIKE-escaping double-escape trap** — hand-written JPQL escapes
  manually, derived queries escape themselves, never both. Nearly shipped
  twice.

### 4. Rules You Must Not Break

Framed as "what will get your PR rejected", which serves a newcomer better
than the same list framed as principles. Each rule gets a one-line *why* and
links to `CLAUDE.md` as canonical:

- No cross-module repository injection.
- No direct `RabbitTemplate` / `RedisTemplate` / MinIO SDK in business logic —
  go through the `walmal-common` interfaces.
- Audit log written before destructive DB operations.
- KB updated in the same commit as any documented-fact change.

### 5. Your First PR

Trace-a-feature walkthrough following `GET /api/v1/orders/admin/daily-summary`:

`OrderController` → `OrderAdminService` (interface) → `OrderAdminServiceImpl`
(`getDailySummary` computes query bounds; the pure `buildDailySummary` does
bucketing/zero-fill/summing) → `OrderRepository.findForDailySummary` →
`OrderTimeseriesRow` / `DailyOrderSummaryDto`. Then the tests covering it
(`OrderAdminServiceDailySummaryTest`, `OrderDailySummaryIntegrationTest`,
`OrderControllerTest`) — which also demonstrate *why* the aggregation is a
separate pure method: it is unit-testable without a database. Then the KB
update that must ride along — for this trace, `docs/kb/architecture.md`
(which documents the endpoint and its precedent) and `docs/kb/SYSTEM.md` (which
carries its response contract, since it is admin-facing).

**No migration stop.** This endpoint is a read-only aggregation over tables
created by V5; it added no migration. Schema changes are a real part of many
PRs but not this one — page 5 links to the Flyway map in `architecture.md`
and the migration naming convention rather than inventing a step this trace
does not have.

This endpoint was chosen because the KB already documents its reasoning in
unusual depth (JPQL constructor projection + pure Java-side aggregation,
explicitly framed as a precedent for future rollup endpoints), so the
walkthrough can link rather than explain.

This is where the module package structure stops being abstract.

### 6. Where the Docs Live

The map that makes the no-facts rule survivable: `SYSTEM.md` is canonical for
cross-repo contracts; each repo's `docs/kb/` for its own; ADRs for decisions;
READMEs mirror the KB and the KB wins. Answers "I have a question, where do I
look".

## Linking mechanics

- All three repos are **public** on GitHub under `YeHtutAung`, so deep links
  need no access gate.
- **Default branches differ**: `walmal` and `walmal-store` are `main`;
  `walmal-admin` is `master`. Links must respect this or they 404.
- Link to files at the default branch (not a pinned SHA) so links follow the
  canonical current state — consistent with linking-not-copying.

## Maintenance

Structure changes rarely, so drift is near-zero by construction. The only
thing that can rot is a link, if a file moves.

- Add a short "Notion onboarding" pointer to the **Documentation section of
  `walmal/README.md`** (which already lists `docs/adr/` and `docs/kb/`). The
  README, not `docs/kb/`, because the Notion tree is human-facing onboarding
  and the README is where a human looks first; `docs/kb/` is agent-facing. One
  line, one link, no facts.
- Deliberately **no** `CLAUDE.md` rule. A rule that cannot be enforced
  in-commit is just guilt. The no-facts rule is what removes the need for one.

## Success criteria

- A new engineer can go from a clean machine to a running stack using page 2
  alone, without asking the author a question.
- No page asserts a fact that a commit could falsify (excepting stable-interface
  commands, per the rule above).
- Every fact a reader needs is reachable in one click from the page that
  mentions it.

## Out of scope

- Mirroring `docs/kb/` content into Notion.
- Any sync script or automation.
- Portfolio/showcase framing, roadmap, or task tracking.
- Changes to the repos' existing documentation, other than the one KB pointer.
