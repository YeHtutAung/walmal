# Editable Home Page CMS — Admin Editor (walmal-admin) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add a "Home Page" editor to the walmal-admin SPA that edits the storefront home document (hero / category tiles / promo) via the backend content API, with image uploads, add/remove/reorder tiles, a category-aware link picker, and Save Draft / Preview / Publish.

**Architecture:** A singleton editor page (not a CRUD list resource) at `/home-content`, using `apiClient` directly against the `/api/v1/content/*` endpoints shipped in Plan 1. Reuses the admin's existing `resolveMinioUrl` + FormData upload pattern. Publish is ADMIN-only in the UI (backend enforces it regardless). Preview opens the storefront's Draft Mode route (built in Plan 3).

**Tech Stack:** Vite + React + TypeScript + Refine + Tailwind + shadcn-style UI components; `apiClient` (axios) from `src/lib/axios-client.ts`.

**Spec:** `walmal/docs/superpowers/specs/2026-07-21-editable-home-page-cms-design.md`

**Repo:** `C:\YHA\006_Claude_Workspace\walmal-admin` — work on a feature branch `feat/home-content-editor` (branch from `master`; do NOT commit on `master`).

## Backend API this consumes (live, from Plan 1)
- `GET /api/v1/content/home/draft` → `{ data: HomeContent }` (ADMIN/STAFF JWT via apiClient). Returns the draft, or a populated DEFAULT if none.
- `PUT /api/v1/content/home/draft` body `HomeContent` → 200. (Bean-validated: headline/heading/cta labels non-blank; all `href` must start with `/`.)
- `POST /api/v1/content/home/publish` → 204 (ADMIN only; STAFF gets 403).
- `POST /api/v1/content/images?section=hero|tile|promo` multipart `file` → 201 `{ data: { imageUrl } }`.

`HomeContent` shape (TS mirror):
```ts
interface Cta { label: string; href: string }
interface Hero { eyebrow?: string; headline: string; subtext?: string; primaryCta: Cta; secondaryCta?: Cta | null; imageUrl?: string | null }
interface CategoryTile { label: string; href: string; imageUrl?: string | null }
interface Promo { eyebrow?: string; heading: string; text?: string; cta: Cta; imageUrl?: string | null }
interface HomeContent { hero: Hero; categoryTiles: CategoryTile[]; promo: Promo }
```
Image URLs come back as `http://minio:9000/...` (prod) / `http://localhost:9000/...` (dev) — always render through `resolveMinioUrl` (already in `src/lib/minio-url.ts`, and the nginx `/api/minio` proxy already covers the `content-images` bucket).

---

## File Structure
- Create `src/pages/home-content/edit.tsx` — the singleton editor page (orchestrates state + Save/Preview/Publish).
- Create `src/pages/home-content/types.ts` — the `HomeContent` TS interfaces above.
- Create `src/components/home-content/ImageUploadField.tsx` — reusable: shows current image (via `resolveMinioUrl`) + an "Upload" button that POSTs to `/content/images?section=…` and calls back with the returned `imageUrl`.
- Create `src/components/home-content/LinkField.tsx` — a category `<Select>` (options from categories) that writes `/products?category=<slug>`, plus a "custom path" toggle for a free `/…` string.
- Create `src/components/home-content/TilesEditor.tsx` — the variable-length tile list (add / remove / move up-down; each row = ImageUploadField + label input + LinkField).
- Modify `src/App.tsx` — add the resource entry + the `/home-content` route (CanAccess gated).
- Modify `src/components/layout/Sider.tsx` — add a "Storefront" nav group → "Home Page" item.
- Modify `src/providers/access-control-provider.ts` — grant `content/home` to ADMIN + STAFF.
- Modify `src/vite-env.d.ts` — declare `VITE_STORE_URL` and `VITE_PREVIEW_TOKEN`.
- (Deploy) Modify `walmal/docker-compose.prod.yml` admin build args + `walmal-admin` CI build to pass `VITE_STORE_URL` / `VITE_PREVIEW_TOKEN` — see Task 6.

---

## Task 1: Types + ImageUploadField (reusable upload control)

**Files:** `src/pages/home-content/types.ts`, `src/components/home-content/ImageUploadField.tsx`

- [ ] **Step 1:** Create `types.ts` with the `Cta/Hero/CategoryTile/Promo/HomeContent` interfaces above.
- [ ] **Step 2:** Create `ImageUploadField.tsx`. Props: `{ label: string; section: 'hero'|'tile'|'promo'; value?: string|null; onChange: (imageUrl: string) => void }`. Renders: the current image via `<img src={resolveMinioUrl(value) ?? ''}>` (or a placeholder if none) + a hidden `<input type="file" accept="image/*">` triggered by an "Upload" button. On file select: `const fd = new FormData(); fd.append('file', file); const { data } = await apiClient.post('/api/v1/content/images?section='+section, fd); onChange(data.data.imageUrl);` with an uploading state + error text. Mirror the exact upload code in `src/pages/products/edit.tsx` (`handleUpload`), which is the proven pattern (axios auto-sets the multipart boundary).
- [ ] **Step 3:** Typecheck + lint: `npm run build` and `npx eslint src/components/home-content/ImageUploadField.tsx src/pages/home-content/types.ts`. Expected: clean.
- [ ] **Step 4:** Commit `feat(home-content): types + reusable ImageUploadField`.

## Task 2: LinkField (category picker + custom path)

**Files:** `src/components/home-content/LinkField.tsx`

- [ ] **Step 1:** Create `LinkField.tsx`. Props `{ value: string; onChange: (href: string) => void }`. Fetch categories once with `useList({ resource: 'categories', pagination: { pageSize: 100 } })` (the resource already exists). Render a `<Select>`: options = `Custom path…` + one per category (value `/products?category=<slug>`, label category name). If the current `value` matches a category option → select it; else switch to a "custom" text `<Input>` where the user types a `/…` path. Emit the chosen string via `onChange`. Show a small hint that links must start with `/`.
- [ ] **Step 2:** Typecheck + lint. Commit `feat(home-content): category-aware LinkField`.

## Task 3: TilesEditor (variable list, reorder)

**Files:** `src/components/home-content/TilesEditor.tsx`

- [ ] **Step 1:** Create `TilesEditor.tsx`. Props `{ tiles: CategoryTile[]; onChange: (tiles: CategoryTile[]) => void }`. Render each tile as a row: `ImageUploadField(section='tile')` + label `<Input>` + `LinkField` + Remove button + Up/Down buttons (disabled at ends). "Add tile" appends a blank `{label:'',href:'/products',imageUrl:null}`. Reorder swaps array elements. Cap at 12 (hide "Add" at 12 — backend `@Size(max=12)`). All mutations go through `onChange` (immutably).
- [ ] **Step 2:** Typecheck + lint. Commit `feat(home-content): TilesEditor with add/remove/reorder`.

## Task 4: The editor page (state + Hero/Promo forms + Save/Preview/Publish)

**Files:** `src/pages/home-content/edit.tsx`

- [ ] **Step 1:** Create `edit.tsx`. On mount, `apiClient.get('/api/v1/content/home/draft')` → hydrate a `useState<HomeContent>`; on error show a message. Render:
  - **Hero** section: inputs for eyebrow, headline (textarea — `\n` allowed), subtext; `ImageUploadField(section='hero')`; primary CTA label `<Input>` + `LinkField`; a "secondary CTA" toggle that adds/removes the optional secondary `{label, href}`.
  - **Category Tiles**: `<TilesEditor>`.
  - **Promo** section: eyebrow, heading (textarea), text; `ImageUploadField(section='promo')`; CTA label + `LinkField`.
  - Buttons: **Save Draft** → `PUT /api/v1/content/home/draft` with the state; on 400 surface the field errors (parse the `ApiResponse` error body); on success toast "Draft saved". **Preview** → `window.open(`${import.meta.env.VITE_STORE_URL}/api/preview?token=${import.meta.env.VITE_PREVIEW_TOKEN}`, '_blank')`. **Publish** → only rendered for ADMIN (read role via `useGetIdentity()` / the auth store); `POST /api/v1/content/home/publish`; confirm via a dialog first; toast "Published".
- [ ] **Step 2:** Keep the file focused — if it grows past ~250 lines, the Hero and Promo blocks may be extracted into `HeroForm.tsx`/`PromoForm.tsx` (same pattern as TilesEditor). Report as DONE_WITH_CONCERNS if you split beyond the plan.
- [ ] **Step 3:** Typecheck + lint. Commit `feat(home-content): singleton editor page (save/preview/publish)`.

## Task 5: Wire route + nav + access control + env types

**Files:** `src/App.tsx`, `src/components/layout/Sider.tsx`, `src/providers/access-control-provider.ts`, `src/vite-env.d.ts`

- [ ] **Step 1:** `App.tsx` — add a resource `{ name: "content/home", list: "/home-content", meta: { label: "Home Page" } }` and a route:
```tsx
<Route path="/home-content" element={
  <CanAccess resource="content/home" action="list" fallback={<Navigate to="/" replace />}>
    <HomeContentEditPage />
  </CanAccess>
} />
```
(import `HomeContentEditPage` from `@/pages/home-content/edit`).
- [ ] **Step 2:** `Sider.tsx` — add a new nav group `{ label: "Storefront", items: [{ label: "Home Page", path: "/home-content", resource: "content/home", icon: <pick a lucide icon e.g. LayoutTemplate> }] }`.
- [ ] **Step 3:** `access-control-provider.ts` — add `content/home` to the allowed resources for `ADMIN` and `STAFF` (match the existing rolePerms structure). Verify CASHIER/CUSTOMER etc. do NOT get it.
- [ ] **Step 4:** `vite-env.d.ts` — add `readonly VITE_STORE_URL: string` and `readonly VITE_PREVIEW_TOKEN: string` to the `ImportMetaEnv` interface.
- [ ] **Step 5:** `npm run build` (typecheck+bundle) + `npm run lint` → clean. Commit `feat(home-content): route, sidebar nav, access control, env types`.

## Task 6: Manual verification against the local backend + deploy env wiring

**Prereq:** the Plan 1 backend running locally (`walmal-app` on :8080 with `CONTENT_PREVIEW_TOKEN=test-preview`, Docker services up), admin dev server with `VITE_API_BASE_URL=http://localhost:8080 VITE_STORE_URL=http://localhost:3000 VITE_PREVIEW_TOKEN=test-preview npm run dev`.

- [ ] **Step 1:** Drive the flow in a browser (login as `admin_test`/`AdminPass123!`): open **Home Page** from the sidebar; the editor loads the DEFAULT draft; edit the hero headline; upload an image to the hero (confirm it renders via `/api/minio`); add a tile, reorder it, pick a category link; **Save Draft** (expect success); **Publish** (expect success). Confirm via `GET /api/v1/content/home` that the published doc reflects the edits. Confirm the **Publish** button is hidden when logged in as a STAFF user (if a STAFF test account exists; otherwise verify the backend returns 403 for STAFF and note it).
- [ ] **Step 2:** Confirm an invalid save surfaces the error (e.g. clear the headline → Save → 400 → field error shown).
- [ ] **Step 3:** **Deploy env wiring:** in `walmal/docker-compose.prod.yml`, the `admin` service is CI-built; add `VITE_STORE_URL` and `VITE_PREVIEW_TOKEN` as build args in the walmal-admin CI workflow (`.github/workflows/ci.yml` `build-args:`, mirroring how `VITE_API_BASE_URL` is passed), sourced from repo variables/secrets. Document that `VITE_PREVIEW_TOKEN` must equal the backend's `CONTENT_PREVIEW_TOKEN`. Update `walmal-admin/docs/kb/*` (new Home Page editor; the `content-images` bucket reuses the existing `/api/minio` proxy — no nginx change).
- [ ] **Step 4:** Commit `chore(home-content): CI build args for store URL + preview token; KB`.

## Deploy (separate, gated)
walmal-admin deploys via push to `master` (CI build → SSH pull+restart `admin`). Live production deploy — explicit user confirmation at push time. `VITE_PREVIEW_TOKEN`/`VITE_STORE_URL` repo vars must be set before the build, and they only take effect once Plan 3's store preview route exists (the Preview button 404s until then — acceptable interim).
