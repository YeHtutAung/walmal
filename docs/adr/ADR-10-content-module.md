# ADR-10: Content Module (Home-Page CMS)

**Date**: 2026-07-21
**Status**: Accepted
**Module**: walmal-content (net-new — beyond the 9-module MVP build order)
**Authors**: Backend Architect Agent

---

## Context

The storefront home page was hardcoded in `walmal-store`. Marketing needed to
edit the hero banner, the category tiles, and a promo strip without a frontend
deploy, and to preview changes before they go live. This ADR records the
decisions for a new `content` bounded context that owns home-page editorial
content behind a draft → publish workflow.

This module is a **deliberate scope addition** beyond the nine modules of the
original build order (Infrastructure → Auth → Product → Inventory → Order → POS
→ Warehouse → Notification → API Gateway). It is a genuine bounded context — it
owns its own table and exposes its own REST surface — so it is built as a new
Maven module (`walmal-content`) rather than folded into an existing one. It is
NOT a microservice: it ships inside the same `walmal-app` assembly and the same
JVM as every other module.

The key design questions this ADR answers:

1. How editorial content is stored — one JSONB document per lifecycle status, or
   relational per-section tables.
2. The security model for reading a draft that is, by definition, not yet public.
3. Whether the module depends on `walmal-product` for the admin "category picker"
   used to build tile links.
4. How the preview-token secret is kept fail-closed in production.

---

## Decision Drivers

1. **Module boundary integrity**: `content` owns exactly one table
   (`content_home`) and imports no other module's Repository bean. The admin UI
   composes links to product categories, but that is a frontend concern — the
   backend stores whatever `href` string it is given.
2. **Small, variable-shape document**: The home page is a shallow nested object
   with a variable-length tile array. It is read as a whole and written as a
   whole; no query ever selects a single tile.
3. **Reuse the existing JSONB precedent**: `Order.shippingAddress` already
   persists a nested value with `@JdbcTypeCode(SqlTypes.JSON)`. The same mapping
   applies cleanly here.
4. **Fail-closed secrets**: Following the `WALMAL_PAYMENT_GATEWAY` /
   `WALMAL_JWT_SECRET` precedent, the preview-token secret must never fall back
   to a committed default in production.
5. **DIP compliance**: Image upload goes through the `FileStorageService`
   interface from `walmal-common`, never the MinIO SDK. The audit write goes
   through `AuditService`.
6. **Audit compliance**: `publish` overwrites the PUBLISHED row — a destructive
   UPDATE — so it writes to `audit_log` before the write.
7. **Flyway migration number**: V19 (follows V18 payment-webhook events).

---

## Considered Options

### Content Storage Model

#### Option A: Relational per-section tables

- `content_hero`, `content_tiles` (one row per tile), `content_promo`, joined at
  read time and versioned by a status column.
- Pros: each field is a typed column; partial updates are possible.
- Cons: the document is small and always read/written whole, so the relational
  spread buys nothing. A variable-length tile array becomes a child table with
  ordering columns and cascade deletes. Draft vs. published doubles every table.
  No business logic ever queries a single section, so the normalization is
  overhead with no consumer.
- **Rejected**: complexity with no query that benefits from it.

#### Option B: Single JSONB document per lifecycle status [SELECTED]

- One table `content_home`, keyed by status (`DRAFT`, `PUBLISHED`), with the
  whole home-page document in a single JSONB `content` column mapped via
  `@JdbcTypeCode(SqlTypes.JSON)` — the same mapping `Order.shippingAddress`
  already uses. Publish copies the DRAFT document into the PUBLISHED row.
- The document is validated at the application layer by DTOs (`@Valid` on the
  `HomeContent` body), not by database constraints. `href` values are validated
  to be site-relative (must start with `/`).
- Pros: matches the access pattern (read/write whole), trivially accommodates a
  variable-length tile array, no joins, one migration. Status-per-row keeps the
  draft and the published copy physically separate and makes publish an
  atomic single-row upsert.
- Cons: no column-level typing in the DB; malformed documents are only caught by
  app-layer validation. Acceptable — the document has a single writer (the admin)
  and a strict DTO schema.
- **Accepted**.

### Draft-Read Security Model

#### Option A: JWT only

- Reject: marketing wants to share a preview link with non-admins (and preview
  from the storefront render path) without minting JWTs for them.

#### Option B: Dual-auth — JWT **or** preview token [SELECTED]

- `GET /home/draft` is `permitAll` at the filter chain and **self-authorizes in
  the controller**: it returns the draft if the caller presents a valid
  `previewToken` query param **or** an authenticated ADMIN/STAFF JWT; otherwise
  it throws `AccessDeniedException` (rendered 403). The token comparison is
  constant-time (`MessageDigest.isEqual`).
- This is the **same self-authorization pattern as the Stripe webhook**
  (`POST /api/v1/payment/webhook`): a `permitAll` path whose real authentication
  is a shared secret checked inside the handler, not a role on the filter chain.
- **Accepted**.

---

## Decision

### Package Structure (`walmal-content`)

```
walmal-content/src/main/java/com/walmal/content/
  api/            ContentController, DTOs
  domain/         HomeContent (document), ContentHome (@Entity, JSONB), ContentStatus
  application/    HomeContentService (interface) + impl, ContentImageDto
  infrastructure/ ContentHomeRepository, ContentImageStorageAdapter (FileStorageService)
```

`walmal-content` depends on `walmal-common` only (main scope). It has **no
dependency on `walmal-product`**: the admin-side category picker that builds tile
links is a frontend concern; the backend persists the resulting `href` as an
opaque, site-relative path string (validated to start with `/`). This keeps the
Maven dependency graph acyclic and the module boundary clean.

### Data Model

- Table `content_home` (migration `V19__content_create_tables.sql`), one row per
  `ContentStatus` (`DRAFT`, `PUBLISHED`), `content` column is JSONB mapped with
  `@JdbcTypeCode(SqlTypes.JSON)`.
- `publish` copies DRAFT → PUBLISHED. It is a destructive overwrite of the
  PUBLISHED row and therefore writes an `audit_log` row (action `UPDATE`, table
  `content_home`) **before** the write. `saveDraft` also audits (action `UPDATE`).
  Because `audit_log.record_id` is `UUID NOT NULL` but `content_home` is keyed by
  status rather than a UUID, a fixed sentinel `record_id` identifies content-home
  audit entries.

### REST Surface (`/api/v1/content`)

| Endpoint | Auth | Notes |
|---|---|---|
| `GET /home` | Public | Published document; **204 No Content** if nothing has ever been published. |
| `GET /home/draft` | **Dual-auth**: ADMIN/STAFF JWT **or** valid `previewToken` query param | `permitAll` at the filter chain; self-authorized in the controller; 403 on failure. |
| `PUT /home/draft` | ADMIN or STAFF | Replaces the DRAFT document; `@Valid`; audited. |
| `POST /home/publish` | **ADMIN only** | Promotes DRAFT → PUBLISHED; audited; **204** on success, **409** if no draft. |
| `POST /images` | ADMIN or STAFF | Multipart upload to MinIO; `section` restricted to `hero|tile|promo`; content-type must be `image/*`; returns `{ imageUrl }`, **201**. |

Publish is ADMIN-only while edit/upload are ADMIN/STAFF: STAFF can draft and
stage content, but only ADMIN makes it live.

### Image Storage

New MinIO bucket **`content-images`** (public-read, created lazily on first
upload — same pattern as `product-images`). Uploads flow through the
`FileStorageService` interface (DIP), never the MinIO SDK. The `section` value is
allow-listed (`hero|tile|promo`) before it is used in the object key, to prevent
path traversal into the key namespace.

### Preview-Token Secret (`CONTENT_PREVIEW_TOKEN`) — Fail-Closed

The draft preview read is gated by a shared secret bound to
`walmal.content.preview-token`:

- **Dev**: `application.yml` supplies a default
  (`${CONTENT_PREVIEW_TOKEN:dev-preview-token-change-me}`) so local runs work
  out of the box.
- **Production**: `docker-compose.prod.yml` wires
  `CONTENT_PREVIEW_TOKEN: ${CONTENT_PREVIEW_TOKEN:?see .env.production.example}`.
  The Compose `:?` gate makes the deployment **fail fast** if the variable is
  unset, so the committed dev default can never be the effective production
  secret. It is documented (unset) in `.env.production.example`; generate one
  with `openssl rand -hex 32`.

This mirrors the fail-closed posture of `WALMAL_JWT_SECRET` and
`WALMAL_PAYMENT_GATEWAY`: a secret that must be explicitly provided, never
silently defaulted, in production.

---

## Consequences

### Positive

- Marketing edits and previews the home page with no frontend deploy; ADMIN
  controls go-live.
- The storage model matches the access pattern exactly: one small JSONB document,
  read and written whole, one migration, no joins.
- Module boundary stays clean — no `walmal-product` dependency, no cross-module
  Repository import, acyclic Maven graph.
- The preview secret cannot leak via a committed default in production.
- Image upload and audit both go through `walmal-common` interfaces (DIP).

### Negative / Risks

- **No DB-level document schema**: a malformed document is caught only by
  app-layer DTO validation. Acceptable given the single trusted writer and strict
  `@Valid` schema.
- **Preview token is a single shared secret**: it is not per-user and not
  rotated automatically. Rotating it invalidates every outstanding preview link.
  Acceptable for MVP; revocation is "change the env var and redeploy".
- **`href` is opaque**: the backend does not verify that a stored link resolves
  to a real category or page. Validation stops at "must be site-relative". Broken
  links are a content-authoring concern, surfaced by the admin UI, not enforced
  by the backend.

---

## Definition of Done Checklist

- [x] `HomeContentService` interface defined in `application/`
- [x] Implementation complete (draft save, publish, image upload)
- [x] `content_home` owned solely by `walmal-content`; no cross-module repo deps
- [x] JSONB mapping via `@JdbcTypeCode(SqlTypes.JSON)` (Order.shippingAddress precedent)
- [x] `publish` and `saveDraft` write `audit_log` before the write
- [x] `content-images` MinIO bucket via `FileStorageService` (DIP)
- [x] `CONTENT_PREVIEW_TOKEN` fail-closed in prod (`:?` gate); dev default only
- [x] Springdoc annotations on all five endpoints (`@Operation`/`@ApiResponses`/`@SecurityRequirement`/`@Tag`)
- [x] Integration tests: draft/publish lifecycle, dual-auth draft read, audit rows
- [ ] Docker Compose health check passes (runtime verification — Task 8)
- [x] `docs/kb/SYSTEM.md` updated (endpoints, bucket, `CONTENT_PREVIEW_TOKEN`)
