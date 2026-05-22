# walmal Admin Panel — Design Spec

**Date:** 2026-05-22
**Status:** Approved — ready for implementation planning
**Stack:** Refine.dev v4 · shadcn/ui · Vite · React Router v6 · TypeScript
**Repo:** `walmal-admin` (separate frontend repository, not embedded in Spring Boot)

---

## 1. Overview

Internal admin panel for the walmal omnichannel retail platform. Targets
back-office staff and system administrators. Covers all eight backend modules:
dashboard, users, products, inventory, orders, POS, warehouse, and notifications.

The panel is a pure client-side SPA — SSR and SEO are irrelevant for an
auth-gated internal tool. It communicates exclusively with the existing
Spring Boot REST APIs at `/api/v1/*`.

---

## 2. Tech Stack

| Concern | Choice | Reason |
|---|---|---|
| Meta-framework | Refine.dev v4 | Purpose-built for CRUD admin panels; auto-generates list/create/edit/show views; built-in auth + access control provider contracts |
| Build tool | Vite + React Router v6 | Fast dev server; Refine's recommended Vite template |
| Language | TypeScript | Type safety across API boundary |
| UI components | shadcn/ui | Headless, composable; DataTable, forms, dialogs, badges all available |
| Data tables | TanStack Table (via shadcn DataTable) | Sorting, filtering, pagination without custom code |
| API types | `openapi-typescript` CLI | Auto-generated from `/v3/api-docs` — eliminates API contract bugs |
| HTTP client | Axios | Interceptor support for silent token refresh |
| Data provider | `@refinedev/simple-rest` | Maps Refine CRUD operations to REST conventions |

---

## 3. Folder Structure

```
walmal-admin/
├── public/
├── src/
│   ├── components/
│   │   ├── ui/                        # shadcn/ui output — never edit manually; use CLI
│   │   ├── layout/
│   │   │   ├── AppLayout.tsx          # Shell: sidebar + header + <Outlet />
│   │   │   ├── Sider.tsx              # Role-aware nav menu (reads role from auth provider)
│   │   │   └── Header.tsx             # Current user chip, logout button
│   │   └── shared/
│   │       └── DataTable.tsx          # TanStack Table wrapper — used by all list pages
│   ├── pages/
│   │   ├── dashboard/
│   │   │   └── index.tsx              # Summary cards + charts (Recharts)
│   │   ├── users/
│   │   │   ├── list.tsx
│   │   │   ├── create.tsx
│   │   │   └── edit.tsx               # Role assignment, activate/deactivate
│   │   ├── products/
│   │   │   ├── list.tsx
│   │   │   ├── create.tsx
│   │   │   ├── edit.tsx
│   │   │   └── show.tsx
│   │   ├── categories/
│   │   │   ├── list.tsx
│   │   │   ├── create.tsx
│   │   │   └── edit.tsx
│   │   ├── inventory/
│   │   │   ├── list.tsx               # Stock levels table
│   │   │   ├── adjustment-form.tsx    # Manual stock adjustment
│   │   │   └── reservations.tsx       # Active reservations
│   │   ├── orders/
│   │   │   ├── list.tsx
│   │   │   ├── show.tsx               # Order detail + status timeline
│   │   │   └── edit.tsx               # Status update, cancel
│   │   ├── pos/
│   │   │   ├── terminals/
│   │   │   │   ├── list.tsx
│   │   │   │   └── show.tsx           # Terminal detail + sync status panel
│   │   │   └── sync-conflicts/
│   │   │       └── list.tsx
│   │   ├── warehouse/
│   │   │   ├── tasks/
│   │   │   │   ├── list.tsx
│   │   │   │   └── show.tsx
│   │   │   └── buffer-stock/
│   │   │       └── list.tsx
│   │   └── notifications/
│   │       ├── list.tsx               # Read-only log
│   │       └── show.tsx               # Delivery status detail
│   ├── providers/
│   │   ├── auth-provider.ts           # JWT login/logout/refresh, role extraction
│   │   ├── data-provider.ts           # Refine Simple REST wired to /api/v1
│   │   └── access-control-provider.ts # Static role → resource/action permission map
│   ├── types/
│   │   └── api.ts                     # Auto-generated via openapi-typescript (see §7)
│   ├── lib/
│   │   └── axios-client.ts            # Axios instance: base URL + JWT interceptor
│   └── App.tsx                        # Refine <App> with all providers + resources
├── .env.local                         # VITE_API_BASE_URL=http://localhost:8080
├── vite.config.ts
└── package.json
```

---

## 4. Page List

| Route | File | Refine Action | Primary Endpoint |
|---|---|---|---|
| `/` | `dashboard/index.tsx` | custom | multiple (aggregated) |
| `/users` | `users/list.tsx` | list | `GET /api/v1/auth/users` ⚠️ |
| `/users/create` | `users/create.tsx` | create | `POST /api/v1/auth/users` |
| `/users/:id/edit` | `users/edit.tsx` | edit | `PUT /api/v1/auth/users/:id` |
| `/products` | `products/list.tsx` | list | `GET /api/v1/products` |
| `/products/create` | `products/create.tsx` | create | `POST /api/v1/products` |
| `/products/:id` | `products/show.tsx` | show | `GET /api/v1/products/:id` |
| `/products/:id/edit` | `products/edit.tsx` | edit | `PUT /api/v1/products/:id` |
| `/categories` | `categories/list.tsx` | list | `GET /api/v1/products/categories` |
| `/categories/create` | `categories/create.tsx` | create | `POST /api/v1/products/categories` |
| `/categories/:id/edit` | `categories/edit.tsx` | edit | `PUT /api/v1/products/categories/:id` |
| `/inventory` | `inventory/list.tsx` | list | `GET /api/v1/inventory` |
| `/inventory/adjustment` | `inventory/adjustment-form.tsx` | create | `POST /api/v1/inventory/adjustments` |
| `/inventory/reservations` | `inventory/reservations.tsx` | list | `GET /api/v1/inventory/reservations` |
| `/orders` | `orders/list.tsx` | list | `GET /api/v1/orders` |
| `/orders/:id` | `orders/show.tsx` | show | `GET /api/v1/orders/:id` |
| `/orders/:id/edit` | `orders/edit.tsx` | edit | `PUT /api/v1/orders/:id` |
| `/pos/terminals` | `pos/terminals/list.tsx` | list | `GET /api/v1/pos/terminals` |
| `/pos/terminals/:id` | `pos/terminals/show.tsx` | show | `GET /api/v1/pos/terminals/:id` |
| `/pos/sync-conflicts` | `pos/sync-conflicts/list.tsx` | list | `GET /api/v1/pos/sync-conflicts` |
| `/warehouse/tasks` | `warehouse/tasks/list.tsx` | list | `GET /api/v1/warehouse/tasks` |
| `/warehouse/tasks/:id` | `warehouse/tasks/show.tsx` | show | `GET /api/v1/warehouse/tasks/:id` |
| `/warehouse/buffer-stock` | `warehouse/buffer-stock/list.tsx` | list | `GET /api/v1/warehouse/buffer-stock` |
| `/notifications` | `notifications/list.tsx` | list | `GET /api/v1/notifications` |
| `/notifications/:id` | `notifications/show.tsx` | show | `GET /api/v1/notifications/:id` |

> ⚠️ `GET /api/v1/auth/users` does not currently exist in `AuthController`. This endpoint
> must be implemented in the backend (with `@PreAuthorize("hasRole('ADMIN')")`) and its
> tests must pass before the User Management list page can be built. See §8 Prerequisites.

---

## 5. RBAC Mapping

Frontend access control controls sidebar visibility and direct URL access.
The backend's `@PreAuthorize` annotations remain the authoritative enforcement layer —
the frontend is UX guard only.

| Section | ADMIN | STAFF | WH_MANAGER | WH_STAFF | POS_OPERATOR |
|---|:---:|:---:|:---:|:---:|:---:|
| Dashboard | ✓ | ✓ | ✓ | ✓ | ✓ |
| Users | ✓ | — | — | — | — |
| Products (all actions) | ✓ | ✓ | — | — | — |
| Categories | ✓ | ✓ | — | — | — |
| Inventory — list/reservations | ✓ | ✓ | ✓ | — | — |
| Inventory — adjustment form | ✓ | — | ✓ | — | — |
| Orders | ✓ | ✓ | — | — | — |
| POS Terminals | ✓ | — | — | — | ✓ |
| POS Sync Conflicts | ✓ | — | — | — | ✓ |
| Warehouse Tasks | ✓ | — | ✓ | ✓ | — |
| Warehouse Buffer Stock | ✓ | — | ✓ | — | — |
| Notifications (read-only) | ✓ | ✓ | — | — | — |

**Post-login redirect by role:**

| Role | Redirect destination |
|---|---|
| ADMIN | `/` (Dashboard) |
| STAFF | `/` (Dashboard) |
| WAREHOUSE_MANAGER | `/` (Dashboard) |
| WAREHOUSE_STAFF | `/` (Dashboard) |
| POS_OPERATOR | `/pos/terminals` |

The `login` method in `auth-provider.ts` reads the `role` from `TokenResponse`
and returns the appropriate `redirectTo` path.

**RBAC notes:**
- STAFF cannot adjust inventory — manual adjustments carry audit risk; restricted to ADMIN + WAREHOUSE_MANAGER.
- WAREHOUSE_STAFF sees tasks but not buffer stock — they execute tasks, not make stock level decisions.
- Notifications are read-only for STAFF — they need to see notification history for orders they manage.
- Dashboard is universal; widget data is role-filtered at the API level automatically.

---

## 6. API Integration

### Base URL

```
# .env.local
VITE_API_BASE_URL=http://localhost:8080

# .env.production
VITE_API_BASE_URL=https://api.walmal.com
```

No URL is hardcoded in any component or provider — all reads come from `import.meta.env.VITE_API_BASE_URL`.

### Token Lifecycle

The backend issues:
- **Access token:** signed HS256 JWT, 15-minute TTL
- **Refresh token:** opaque UUID stored in Redis, **7-day TTL, rolling**

`TokenResponse` shape (from `AuthController`):
```typescript
{
  accessToken:  string   // JWT
  refreshToken: string   // opaque UUID
  tokenType:    "Bearer"
  expiresIn:    number   // TTL in seconds (900)
  role:         string   // e.g. "ADMIN", "STAFF"
}
```

Both tokens rotate on every refresh call (rolling refresh). On a successful
`POST /api/v1/auth/refresh`, **both** `accessToken` and `refreshToken` must be
updated in localStorage.

| Action | Endpoint | Payload | localStorage effect |
|---|---|---|---|
| Login | `POST /api/v1/auth/login` | `{ email, password }` | write `accessToken`, `refreshToken`, `role` |
| Refresh | `POST /api/v1/auth/refresh` | `{ refreshToken }` | overwrite **both** tokens + `role` |
| Logout | `POST /api/v1/auth/logout` | `{ refreshToken }` | clear all three keys |

### Token Storage

Tokens are stored in `localStorage`.

**This is an accepted trade-off for an internal admin panel** where the user
population is trusted staff, not the general public. It is explicitly **not
appropriate** for any customer-facing frontend.

Required follow-ups before any public exposure:
1. Add `Content-Security-Policy` headers on the API Gateway (restrict `script-src`)
   to mitigate XSS risk with localStorage tokens.
2. Revisit storage strategy (httpOnly cookie) when a customer-facing frontend is built.

### Axios Client (`src/lib/axios-client.ts`)

Single shared instance used by both the Refine data provider and custom calls (dashboard aggregates).

**Request interceptor:** attach `Authorization: Bearer <accessToken>` to every outgoing request.

**Response interceptor (silent refresh):**
```
On 401 response:
  if (isRefreshing) → queue request, retry after refresh completes
  else:
    set isRefreshing = true
    POST /api/v1/auth/refresh with { refreshToken }
    if success:
      update localStorage (both tokens)
      replay queued requests with new accessToken
    if failure (second 401):
      clear localStorage
      redirect to /login
```

The `isRefreshing` flag prevents multiple simultaneous refresh calls when
concurrent requests all 401 at the same moment.

### Refine Auth Provider (`src/providers/auth-provider.ts`)

| Method | Behaviour |
|---|---|
| `login` | POST `/auth/login`, store `{ accessToken, refreshToken, role }`, redirect POS_OPERATOR → `/pos/terminals`, all others → `/` |
| `logout` | POST `/auth/logout` with `{ refreshToken }`, clear localStorage, redirect → `/login` |
| `check` | Verify `accessToken` present and `expiresIn` not elapsed. Axios interceptor handles silent refresh — `check` only gates initial page load. |
| `getPermissions` | Read `role` from localStorage (set at login). No JWT decoding or extra API call needed — `TokenResponse.role` is the source of truth. |
| `getIdentity` | Call `GET /api/v1/auth/me` — returns `{ id, email, name }` for the Header component. |
| `onError` | On unrecoverable 401 (interceptor exhausted), return `{ logout: true }` to trigger Refine's logout flow. |

### Refine Data Provider (`src/providers/data-provider.ts`)

Uses `@refinedev/simple-rest` with the shared Axios instance as `httpClient`:

```
Base URL: ${VITE_API_BASE_URL}/api/v1

resource: "products"          → /api/v1/products
resource: "orders"            → /api/v1/orders
resource: "auth/users"        → /api/v1/auth/users   (users resource path override)
resource: "products/categories" → /api/v1/products/categories
```

Resource path overrides are declared once in `App.tsx` via Refine's `resources` prop.
No page component ever constructs or hardcodes a URL.

### Access Control Provider (`src/providers/access-control-provider.ts`)

Implements Refine's `can({ resource, action })` contract using a static permission map
derived from the RBAC table in §5:

```typescript
const PERMISSIONS: Record<string, Record<string, string[]>> = {
  ADMIN:             { "*": ["list", "show", "create", "edit", "delete"] },
  STAFF:             { products: ["list","show","create","edit"], categories: ["list","show","create","edit"], inventory: ["list","show"], reservations: ["list"], orders: ["list","show","edit"], notifications: ["list","show"] },
  WAREHOUSE_MANAGER: { inventory: ["list","show","create"], reservations: ["list"], "warehouse/tasks": ["list","show"], "warehouse/buffer-stock": ["list","show"] },
  WAREHOUSE_STAFF:   { "warehouse/tasks": ["list","show"] },
  POS_OPERATOR:      { "pos/terminals": ["list","show"], "pos/sync-conflicts": ["list"] },
}
```

The wildcard `"*"` for ADMIN means new resources are accessible by default without
updating the map. Only non-ADMIN roles require explicit entries.

### Error Handling

| HTTP Status | Axios interceptor | UI behaviour |
|---|---|---|
| 401 (first) | Silent refresh + replay | Transparent to user |
| 401 (after failed refresh) | Clear tokens | Redirect to `/login` |
| 403 | Pass through | `accessControlProvider.can` → 403 page |
| 400 / 422 | Pass through | Refine form handler → field-level error messages |
| 500 | Pass through | Refine notification → toast "An unexpected error occurred" |

---

## 7. Type Generation

API types in `src/types/api.ts` are auto-generated from the Spring Boot OpenAPI spec.
Types are **never hand-written**.

`package.json` script:
```json
"scripts": {
  "generate:types": "openapi-typescript http://localhost:8080/v3/api-docs -o src/types/api.ts"
}
```

Run `npm run generate:types` whenever backend contracts change. The generated file
is committed to the repo so CI does not require a running backend.

---

## 8. Prerequisites (Backend Work Required)

The following backend endpoint does not yet exist and must be implemented before
the corresponding admin panel page can be built:

| Missing Endpoint | Required By | Notes |
|---|---|---|
| `GET /api/v1/auth/users` | `/users` list page | Add to `AuthController` with `@PreAuthorize("hasRole('ADMIN')")`. Should support pagination and filtering by role/status. Integration test required before frontend work begins. |
| `GET /api/v1/auth/users/:id` | `users/edit.tsx` (load current values) | Add to `AuthController` with `@PreAuthorize("hasRole('ADMIN')")`. |
| `PUT /api/v1/auth/users/:id` | `users/edit.tsx` (save changes) | Add to `AuthController` with `@PreAuthorize("hasRole('ADMIN')")`. Payload: role, active status. Writes audit log before update (per platform audit rules). |

---

## 9. Security Trade-offs and Follow-ups

| Item | Status | Owner |
|---|---|---|
| localStorage token storage | Accepted trade-off for internal admin panel only | Review before any public-facing frontend |
| CSP headers on API Gateway | Required follow-up — not in MVP scope | Add `Content-Security-Policy: script-src 'self'` to gateway filter |
| Actuator endpoints role-restriction | Flagged in ADR-9 — not yet restricted | Add `hasRole('ADMIN')` guard before production |
| 3 user management endpoints missing | Backend prerequisite | `GET /api/v1/auth/users`, `GET /api/v1/auth/users/:id`, `PUT /api/v1/auth/users/:id` — implement in `walmal-auth` with integration tests |

---

## 10. Out of Scope

Consistent with `CLAUDE.md` platform constraints:
- No AI/ML features
- No analytics dashboards beyond the summary widgets
- No customer-facing pages (this panel is internal staff only)
- No SMS notifications
- No marketplace or B2B features
