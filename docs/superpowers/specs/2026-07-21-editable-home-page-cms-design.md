# Editable Home Page (Draft → Publish CMS) — Design

**Status:** Approved (design, 2026-07-21). Awaiting written-spec review + implementation plan.
**Repos affected:**
- `walmal` (backend — **new `content` module**: `content_home` table, `HomeContentService`, public + admin REST endpoints, `content-images` MinIO bucket, ADR, integration test)
- `walmal-admin` (frontend — new "Home Page" section: draft editor with Save Draft / Preview / Publish, image uploads, add/remove/reorder category tiles)
- `walmal-store` (storefront — `hero`, `category-tiles`, `promo-banner` become data-driven with static fallback; Next.js Draft Mode preview route)

## Context

The storefront home page (`shop.yehtutaung.xyz`) is assembled from hardcoded
components — `hero.tsx`, `category-tiles.tsx`, `promo-banner.tsx` — whose imagery
is static SVGs baked into the store build under `public/sport/` and whose copy is
literal JSX. Changing any of it today requires a developer edit + a store redeploy.
The New Arrivals and Best Sellers rails are already data-driven (product images
via the admin, served through the store's `/api/minio` proxy); only the three
"editorial" sections are frozen.

This spec makes those three sections fully admin-editable — images **and** text
**and** links — behind a draft/preview/publish workflow, with variable-length,
reorderable category tiles and category-aware link entry.

**Scope note:** the 9-module MVP build order (see `CLAUDE.md`) is complete; this
is a deliberate **net-new `content` module** beyond that list. It is not on the
"do not build" list. The user chose the fullest of three offered tiers
(images + text + links, draft→publish) on 2026-07-21.

## Goals

- Admin can edit the Hero, Category Tiles, and Promo Banner without a developer.
- Edits are staged as a **draft**, viewable in a **preview** of the real store,
  then explicitly **published** to go live.
- Category tiles are a **variable-length, reorderable** list (add / remove / move).
- Links are entered via a **category picker** (from live catalog categories) or a
  **custom path**, stored as a plain path string.
- Before the first publish, the store renders **exactly what it renders today**
  (static SVGs + current copy) — zero-regression fallback.

## Non-goals (deliberate YAGNI cuts)

- No orphaned-image garbage collection. Replacing an image leaves the old MinIO
  object in the `content-images` bucket; storage cost is negligible and cleanup
  is out of scope.
- No version history beyond the single DRAFT/PUBLISHED pair (no "revert to
  version N", no audit of each field change beyond the existing `audit_log`
  entries the publish action writes).
- No generic multi-page CMS. This models the home page specifically; a future
  "about page" would extend the schema, not reuse a generic key-value store.
- No scheduled/timed publishing.
- No rich-text/WYSIWYG. Text fields are plain strings (headline supports a single
  optional line-break marker — see Content model).

## Architectural decision — content storage

**Chosen: Approach A — one JSONB content document per status.**

A single `content_home` table with (at most) two rows, one per `status`
(`DRAFT`, `PUBLISHED`), each holding the whole home-page document as `JSONB`.
Publishing copies the DRAFT document into the PUBLISHED row.

Rejected alternatives:
- **B — relational tables** (`home_hero`, `home_category_tile` w/ ordering,
  `home_promo`, each ×draft/published): many tables, joins, and manual ordering
  bookkeeping for a single page's worth of content. Overkill.
- **C — generic `site_content(key, status, value JSONB)`**: future-proofs for
  other CMS pages we have no requirement for. YAGNI.

Rationale for A: the document is small, read as a whole, nested, and contains a
variable-length array (tiles). JSONB fits exactly; structure is enforced at the
application layer by DTOs (Bean Validation), which is the same validation the
REST layer already applies everywhere else. This does **not** violate the
module-ownership rule — `content_home` is owned solely by the new `content`
module; no other module reads it.

## Content model (the JSONB document)

Canonical shape stored in `content_home.content` and returned by the API:

```jsonc
{
  "hero": {
    "eyebrow": "26/27 Season Drop",
    "headline": "Own\nthe pitch.",          // \n = the single <br/> in today's H1
    "subtext": "The latest match kits, elite boots and training gear …",
    "primaryCta":   { "label": "Shop new arrivals", "href": "/products" },
    "secondaryCta": { "label": "Shop boots",         "href": "/products?category=boots" },
    "imageUrl": "http://minio:9000/content-images/home/hero/<file>.png"
  },
  "categoryTiles": [                          // ordered; array index = display order
    { "label": "Jerseys",  "href": "/products?category=jerseys",  "imageUrl": "…" },
    { "label": "Boots",    "href": "/products?category=boots",    "imageUrl": "…" }
    // add / remove / reorder in admin
  ],
  "promo": {
    "eyebrow": "Limited release",
    "heading": "The Velocity\nElite Pack",
    "text": "Featherweight speed boots engineered for the counter-attack. …",
    "cta": { "label": "Shop the pack", "href": "/products?category=boots" },
    "imageUrl": "…"
  }
}
```

Field rules (enforced by DTO validation on `PUT draft`):
- All text fields: non-null, length-bounded (e.g. eyebrow ≤ 60, headline ≤ 120,
  subtext/text ≤ 400, CTA label ≤ 40). `secondaryCta` is optional (nullable) —
  the hero renders one or two buttons.
- `href` fields: must start with `/` (a site-relative path). The category picker
  produces `/products?category=<slug>`; a custom path is accepted verbatim after
  the `/`-prefix check. No external `http(s)://` links (prevents open-redirect-y
  CTAs and keeps everything on-store).
- `imageUrl`: stored as the backend's own MinIO URL (same form as product
  images — `MINIO_PUBLIC_URL + /content-images/<key>`). Clients rewrite it to
  their `/api/minio` proxy exactly as they already do for product images.
- `categoryTiles`: 0–12 items. Empty array is legal (store falls back to the
  default four static tiles only when the **whole document** is unpublished; a
  published empty array renders zero tiles — an explicit editorial choice).

## Backend design (`walmal` — new `content` module)

Built through the required sequence: **backend-architect → database-designer →
module-builder → test-validator → security-auditor** (touches admin-managed
content + a new upload path, so security-auditor is in scope).

### Module skeleton
`com.walmal.content` with the standard package layout (`api`, `domain`,
`application`, `infrastructure`, `config`). New Maven module `walmal-content`,
aggregated into `walmal-app` like every other module. It depends on
`walmal-common` (for `FileStorageService`, `AuditService`, `ApiResponse`,
`AuthenticatedPrincipal`) only. It does **not** depend on `walmal-product`
despite the category picker — the picker is an **admin-side** concern (the admin
already lists categories via the product API); the backend stores whatever
`href` string it is given and never resolves categories. This keeps the content
module free of a product dependency.

### Data model (Flyway migration `V{next}__content_create_tables.sql`)
```sql
CREATE TABLE content_home (
    status      VARCHAR(16)  PRIMARY KEY
                             CHECK (status IN ('DRAFT','PUBLISHED')),
    content     JSONB        NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by  VARCHAR(255) NOT NULL
);
```
- `status` as PK guarantees at most one DRAFT and one PUBLISHED row.
- Seed **neither** row. Absence of the PUBLISHED row is the signal the store uses
  to fall back to static content (see Store design). First `PUT draft` creates
  the DRAFT row; first `publish` creates the PUBLISHED row.

### Storage (MinIO)
- New bucket `content-images`, public-read policy, created on first upload by the
  existing `MinioFileStorageService.ensureBucketExists` path (no code change to
  infrastructure — it already lazily creates+opens buckets).
- Object key scheme: `home/{section}/{uuid}-{safeFilename}` where section ∈
  `hero | tile | promo`.
- A thin `ContentImageStorageAdapter` (mirrors `ProductImageStorageAdapter`) is
  the only class that calls `FileStorageService` — DIP preserved.

### Service
`HomeContentService` (interface, `application/`) + `HomeContentServiceImpl`:
- `HomeContentDto getPublished()` — published document, or a **null/empty
  sentinel** if no PUBLISHED row (lets the controller return 204/empty and the
  store fall back).
- `HomeContentDto getDraft()` — draft document (falls back to published, then to
  empty, so the editor always opens with *something* sensible).
- `HomeContentDto saveDraft(HomeContentDto, performedBy)` — upsert DRAFT row.
- `void publish(performedBy)` — copy DRAFT → PUBLISHED (upsert); **writes an
  `audit_log` row** (`content_home`, action `UPDATE`/`PUBLISH`) before the write,
  per the Audit Log rule in `CLAUDE.md`.
- `ContentImageDto uploadImage(section, InputStream, filename, contentType, size,
  performedBy)` — store to MinIO, return `{ imageUrl }`.

### REST API (`/api/v1/content`)
| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET`  | `/content/home`        | public | Published document (store SSG/ISR + browser). 204 if never published. |
| `GET`  | `/content/home/draft`  | ADMIN, STAFF **or** valid `previewToken` | Draft document (admin editor + store preview — see Preview). |
| `PUT`  | `/content/home/draft`  | ADMIN, STAFF | Replace the draft document. |
| `POST` | `/content/home/publish`| **ADMIN only** | Promote draft → published. |
| `POST` | `/content/images`      | ADMIN, STAFF | Upload an image (multipart), returns `{ imageUrl }`. |

- `GET /content/home` is the only public endpoint; it is safe to cache and
  contains no sensitive data.
- Publish is ADMIN-only (a live-shop mutation); editing/drafting is ADMIN+STAFF,
  matching the existing product-image endpoints.
- `GET /content/home/draft` is **dual-auth**: a valid ADMIN/STAFF JWT **or** a
  correct `previewToken` query param (option (a) below). Its Spring Security
  config must be built for both from the start — not JWT-only then retrofitted.
  The token grants draft *reads only*; it is never accepted on mutating routes.
- Springdoc annotations on all endpoints (Definition of Done).
- Multipart limit reuses the existing `spring.servlet.multipart` config
  (`max-file-size: 10MB`, `max-request-size: 11MB`) and the Caddy 20 MB edge cap
  — no new config; effective per-image ceiling is 10 MB.

### Testing
- `@DataJpaTest`/integration test in `walmal-content` (Docker-Compose Postgres per
  project convention — Testcontainers is incompatible in this env; see repo
  memory) covering: save draft → get draft; publish → get published; publish with
  no draft → 4xx; JSONB round-trips a variable-length tile array intact; audit row
  written on publish.
- Service unit tests (Mockito) for validation + publish semantics.

## Admin design (`walmal-admin`)

- New Refine resource `content/home` (or a plain route — it is a singleton, not a
  list) added to `App.tsx` `resources` and the sidebar under a new **"Storefront"**
  / "Home Page" group.
- Single editor page `src/pages/home-content/edit.tsx`:
  - Hero form (eyebrow, headline, subtext, primary CTA label+link, optional
    secondary CTA, image upload).
  - Category tiles: a reorderable list (drag handle or up/down buttons),
    **Add tile** / **Remove**, each row = image upload + label + link.
  - Promo form (eyebrow, heading, text, CTA label+link, image upload).
  - Link inputs: a **category `<Select>`** (options from the existing
    `categories` resource) that writes `/products?category=<slug>`, with a
    "custom path" toggle for a free-form `/…` string.
  - Buttons: **Save Draft** (`PUT /content/home/draft`), **Preview** (opens the
    store preview URL in a new tab), **Publish** (`POST /content/home/publish`,
    ADMIN-only — hidden/disabled for STAFF).
- Image uploads reuse the pattern fixed on 2026-07-21: `POST /content/images`
  (multipart, axios auto-boundary), returned `imageUrl` run through
  `resolveMinioUrl` for display. **Note:** the admin nginx `/api/minio` proxy
  (added 2026-07-21) already covers the `content-images` bucket — it proxies the
  whole MinIO host, not just `product-images`, so **no admin nginx change is
  needed**.
- Editor loads via `GET /content/home/draft` so STAFF/ADMIN always see the latest
  working copy.

## Store design (`walmal-store`)

- New server-side fetch `fetchHomeContent()` in `src/lib/api/` calling
  `GET /content/home`. On 204 / network error it returns `null`.
- `HomePage` (`src/app/(shop)/page.tsx`) fetches once and passes the document (or
  `null`) into `Hero`, `CategoryTiles`, `PromoBanner`.
- Each of the three components takes an optional `content` prop:
  - **prop present** → render dynamic data; image URLs run through the existing
    `resolveMinioUrl` (store already has it + the `/api/minio` route).
  - **prop null/absent** → render the **current hardcoded JSX** unchanged. The
    existing literal markup becomes the `default`/fallback branch, so the
    pre-first-publish experience is byte-for-byte today's page.
- Caching: keep `export const revalidate = 3600`; publishing goes live within the
  revalidate window. (Optional future enhancement — an on-publish revalidate
  webhook — is out of scope.)
- **Preview** via Next.js **Draft Mode**:
  - New route `src/app/api/preview/route.ts`: validates a `token` query param
    against `PREVIEW_TOKEN` (new env var, shared with admin), calls
    `draftMode().enable()`, redirects to `/`.
  - New route `.../api/preview/disable` to exit.
  - `fetchHomeContent()` checks `draftMode().isEnabled` and, when true, calls
    `GET /content/home/draft?previewToken=<PREVIEW_TOKEN>` as a **server-to-server
    call from the Next.js server** (route handler / server component), never from
    the browser and never carrying an admin JWT (the store has none). The backend
    validates the token for draft reads only (Open Questions, option (a)).
  - Admin "Preview" button opens
    `https://shop.…/api/preview?token=<PREVIEW_TOKEN>`.
  - **Token delivery is a conscious trade-off:** the admin holds `PREVIEW_TOKEN`
    as a build-time env baked into its client bundle, so anyone who can load the
    (auth-gated) admin can read it, and it transits the preview URL (browser
    history / referrer). Blast radius is limited to *draft home content* and the
    token is rotatable. Accepted for v1; revisit if the preview ever exposes
    anything sensitive.

## Security (security-auditor scope)

- All mutating endpoints role-gated (`@PreAuthorize`); publish ADMIN-only.
- `href` validation rejects non-relative links (no `http(s)://`, must start `/`)
  to prevent malicious CTAs on a public page.
- Uploaded images: content-type check (image/* only) mirroring product-image
  upload; size bounded by existing multipart limits.
- Preview token: a high-entropy secret, not the admin JWT; leaking it exposes
  only draft *home content* (not customer data). Rotatable via env.
- Public `GET /content/home` exposes only editorial content — no PII.

## Rollout

Three sequential deploys (backend must land first so the others have an API):
1. **`walmal`** — new module + migration; deploy via push to `main`
   (CI build → SSH → smoke). Before first publish the endpoint returns 204 and
   nothing changes for shoppers.
2. **`walmal-admin`** — Home Page editor; deploy via push to `master`.
3. **`walmal-store`** — data-driven sections + preview; deploy via push to
   `master`. Static fallback means order-independence after the backend exists,
   but store-after-admin lets the admin drive the first publish.

Each is a live production deploy (explicit confirmation at each push, per session
policy).

## KB / docs updates (required, per `CLAUDE.md`)

- `walmal/docs/kb/SYSTEM.md` — new `/api/v1/content/*` endpoints, `content-images`
  bucket, `PREVIEW_TOKEN` env var, new module in the repo map/module list.
- New ADR `docs/adr/ADR-{n}-content-module.md` (module boundary + JSONB decision).
- `walmal-admin/docs/kb/*` — new Home Page section + that `content-images` reuses
  the existing `/api/minio` proxy.
- `walmal-store/docs/kb/*` — data-driven home + Draft Mode preview.
- `README.md` if any documented numbers change (module count, endpoints).

## Open questions / risks

1. **Preview auth mechanism (main open item).** The store is public and has no
   admin JWT. Options for the draft-content fetch during Draft Mode:
   (a) backend accepts a `GET /content/home/draft?previewToken=…` shared-secret
   (simplest; the store route forwards the token server-side), or
   (b) a dedicated service account JWT the store route uses server-to-server.
   **Recommendation: (a)** — one shared `PREVIEW_TOKEN`, validated by the backend
   for draft reads only. To be finalized in the implementation plan.
2. **Reorder UX** — drag-and-drop vs up/down buttons. Buttons are simpler and
   dependency-free; recommend buttons for v1.
3. **Headline line-breaks** — modeled as `\n` in the string, rendered by
   splitting on `\n` into `<br/>`. Keeps the model plain-text; no HTML injection.
