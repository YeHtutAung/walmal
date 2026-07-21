# Editable Home Page CMS — Storefront (walmal-store) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make the storefront home page (hero / category tiles / promo) render the admin-managed content from the backend, with a byte-for-byte static fallback before the first publish, and a Next.js Draft Mode preview of the unpublished draft.

**Architecture:** A server-side `fetchHomeContent()` reads `GET /api/v1/content/home` (published) — or the draft when Draft Mode is on — and `HomePage` passes it into the three section components, which render dynamic data or fall back to today's hardcoded JSX. Image URLs go through the store's existing `resolveMinioUrl` + `/api/minio` proxy. Preview is a token-gated `/api/preview` route that enables Draft Mode.

**Tech Stack:** Next.js 16 App Router (RSC), TypeScript, Tailwind. `draftMode()` from `next/headers` (async in Next 15+). Server fetch mirrors `fetchProductsSSG` in `src/lib/api/products.ts`.

**Spec:** `walmal/docs/superpowers/specs/2026-07-21-editable-home-page-cms-design.md`

**Repo:** `C:\YHA\006_Claude_Workspace\walmal-store` — feature branch `feat/home-content` from `master` (do NOT commit on master).

## Backend API (live, Plan 1)
- `GET /api/v1/content/home` → 200 `{ data: HomeContent }`, or **204** if never published.
- `GET /api/v1/content/home/draft?previewToken=<token>` → 200 `{ data: HomeContent }` (server-to-server; the token equals the store's `CONTENT_PREVIEW_TOKEN`).

`HomeContent` (mirror as a TS type in the store): `{ hero:{eyebrow?,headline,subtext?,primaryCta:{label,href},secondaryCta?:{label,href}|null,imageUrl?}, categoryTiles:{label,href,imageUrl?}[], promo:{eyebrow?,heading,text?,cta:{label,href},imageUrl?} }`. Headlines may contain `\n` (render as `<br/>`). Image URLs are internal MinIO URLs — pass through `resolveMinioUrl` (from `src/lib/minio-url.ts`).

## Env
- `CONTENT_PREVIEW_TOKEN` — **server-side** (NOT `NEXT_PUBLIC_`). Used by `/api/preview` to validate the incoming token AND by `fetchHomeContent` to authorize the draft fetch. Must equal the backend's `CONTENT_PREVIEW_TOKEN` and the admin's `VITE_PREVIEW_TOKEN`.

---

## File Structure
- Create `src/lib/api/home-content.ts` — `fetchHomeContent()` (published or draft-when-preview) + the `HomeContent` type.
- Create `src/app/api/preview/route.ts` — enable Draft Mode (token-gated) → redirect `/`.
- Create `src/app/api/preview/disable/route.ts` — exit Draft Mode.
- Modify `src/app/(shop)/page.tsx` — fetch once, pass content to the three components.
- Modify `src/components/home/hero.tsx`, `category-tiles.tsx`, `promo-banner.tsx` — accept optional content prop; dynamic-or-fallback.
- (Deploy) Modify `walmal/docker-compose.prod.yml` store service env + `.env.production.example` — add `CONTENT_PREVIEW_TOKEN`.

---

## Task 1: `fetchHomeContent()`

**Files:** `src/lib/api/home-content.ts`

- [ ] **Step 1:** Define the `HomeContent`/`Hero`/`CategoryTile`/`Promo`/`Cta` TS types (as above). Implement:
```ts
import { draftMode } from 'next/headers'
export async function fetchHomeContent(): Promise<HomeContent | null> {
  const base = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080/api/v1'
  const { isEnabled } = await draftMode()
  const url = isEnabled
    ? `${base}/content/home/draft?previewToken=${encodeURIComponent(process.env.CONTENT_PREVIEW_TOKEN ?? '')}`
    : `${base}/content/home`
  try {
    const res = await fetch(url, isEnabled ? { cache: 'no-store' } : { next: { revalidate: 3600 } })
    if (res.status === 204 || !res.ok) return null
    const json = await res.json()
    return (json?.data as HomeContent) ?? null
  } catch { return null }
}
```
(Mirror `fetchProductsSSG`'s base-URL + revalidate pattern. Draft mode uses `no-store` so preview is always fresh.)
- [ ] **Step 2:** Typecheck (`npm run build` or `npx tsc --noEmit`). Commit `feat(home): fetchHomeContent (published + draft-preview)`.

## Task 2: Data-drive the three components (dynamic-or-fallback)

**Files:** `src/components/home/hero.tsx`, `category-tiles.tsx`, `promo-banner.tsx`

- [ ] **Step 1: `hero.tsx`** — add prop `{ content?: HomeContent['hero'] }`. If `content` is present, render its values: eyebrow, headline (split on `\n` → `<br/>`), subtext, `resolveMinioUrl(content.imageUrl)` for the bg `<img>` (keep the current gradient overlays), primary CTA (`content.primaryCta.label`→`href`), and the secondary CTA only if `content.secondaryCta` is non-null. If `content` is absent, render the EXACT current hardcoded JSX unchanged (keep it as the fallback branch). Don't change styling/classes.
- [ ] **Step 2: `category-tiles.tsx`** — add prop `{ tiles?: HomeContent['categoryTiles'] }`. This component renders the `CATEGORIES` array TWICE: a desktop 4-up image grid AND a mobile horizontal chip rail (which also has a hardcoded "All" chip). If `tiles?.length`, drive BOTH renderings from `tiles` (image `resolveMinioUrl(tile.imageUrl)`, `tile.label`, link `tile.href`) — preserve the mobile "All" chip; else render the current static array in both. Cleanest: compute `const cats = tiles?.length ? tiles.map(...) : STATIC_CATEGORIES` once and feed both branches. Keep the existing grid/chip markup.
- [ ] **Step 3: `promo-banner.tsx`** — add prop `{ content?: HomeContent['promo'] }`. Dynamic values or the current static JSX fallback (eyebrow, heading split on `\n`, text, `resolveMinioUrl(imageUrl)`, cta label/href).
- [ ] **Step 4:** Typecheck + lint. Commit `feat(home): data-drive hero/category-tiles/promo with static fallback`.

## Task 3: Wire `page.tsx`

**Files:** `src/app/(shop)/page.tsx`

- [ ] **Step 1:** In `HomePage`, add `const home = await fetchHomeContent()` alongside the existing product fetch. Pass `<Hero content={home?.hero} />`, `<CategoryTiles tiles={home?.categoryTiles} />`, `<PromoBanner content={home?.promo} />`. Leave the product rails (`ProductRail`, `BestSellers`) and `revalidate = 3600` unchanged. (Note: when Draft Mode is on, `fetchHomeContent` uses `no-store`, which opts the request out of the static cache for the preview render — expected.)
- [ ] **Step 2:** Typecheck + lint. Commit `feat(home): render admin-managed home content on the storefront`.

## Task 4: Draft Mode preview routes

**Files:** `src/app/api/preview/route.ts`, `src/app/api/preview/disable/route.ts`

- [ ] **Step 1: `preview/route.ts`:**
```ts
import { draftMode } from 'next/headers'
import { NextRequest, NextResponse } from 'next/server'
export async function GET(req: NextRequest) {
  const token = req.nextUrl.searchParams.get('token') ?? ''
  const expected = process.env.CONTENT_PREVIEW_TOKEN ?? ''
  // constant-time-ish compare; reject if unset or mismatched
  if (!expected || token !== expected) {
    return new NextResponse('Invalid preview token', { status: 401 })
  }
  ;(await draftMode()).enable()
  return NextResponse.redirect(new URL('/', req.url))
}
```
- [ ] **Step 2: `preview/disable/route.ts`:** `(await draftMode()).disable()` then redirect to `/`.
- [ ] **Step 3:** Typecheck + lint. Commit `feat(home): Draft Mode preview routes (token-gated)`.

## Task 5: Manual verification + deploy env wiring

**Prereq:** backend on :8080 (`CONTENT_PREVIEW_TOKEN=test-preview`), store dev `CONTENT_PREVIEW_TOKEN=test-preview NEXT_PUBLIC_API_URL=http://localhost:8080/api/v1 npm run dev` (:3000). Use the admin (Plan 2) or curl to create/publish content.

- [ ] **Step 1 (published path):** With nothing published (`GET /content/home` = 204), load `http://localhost:3000/` → the home page renders **identically to before** (static fallback). Then publish a doc (via admin or `PUT draft` + `POST publish`), wait past revalidate or restart dev, reload → the hero/tiles/promo now show the published content; confirm images load via `/api/minio` (network 200) and the headline `\n` renders as a line break.
- [ ] **Step 2 (preview path):** Save a DRAFT that differs from published. Visit `http://localhost:3000/api/preview?token=test-preview` → redirects to `/` in Draft Mode → the page shows the DRAFT (not published) content. Visit `/api/preview/disable` → back to published. A wrong token → 401.
- [ ] **Step 3 (deploy env):** In `walmal/docker-compose.prod.yml`, add `CONTENT_PREVIEW_TOKEN: ${CONTENT_PREVIEW_TOKEN:?see .env.production.example}` to the **store** service `environment:` (the var is already in `.env.production.example` from Plan 1's backend work — confirm it's there; the same value now feeds app, store, and the admin build). Update `walmal-store/docs/kb/*` (data-driven home + preview route + the server-side `CONTENT_PREVIEW_TOKEN`).
- [ ] **Step 4:** Commit `chore(home): store preview token env + KB`.

## Deploy (separate, gated)
walmal-store deploys via push to `master` (CI build → SSH pull+restart `store`). Live production deploy — explicit user confirmation at push time. Order across repos: backend first (done/pending), then admin + store; the store's static fallback means it is safe to deploy at any time (no change to shoppers until the first publish).
