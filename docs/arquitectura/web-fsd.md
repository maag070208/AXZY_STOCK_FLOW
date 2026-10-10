# Web — Feature-Sliced Design (`web/`)
> **Normative.** Architecture of the React web and its import boundaries.
> Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** FSD layers in force for the new web ([web-react.md](web-react.md)). The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

The decision is in [ADR 0002](../adr/0002-fsd-en-la-web.md).

## 2.1 The architecture: FSD (Feature-Sliced Design)

```
web/src/
├── app/        providers, router, guards, global styles
├── pages/      one folder per screen
├── widgets/    large reusable blocks (layout, table)
├── features/   actions with business meaning (login, capture)
├── entities/   business models and their data access (catalog, reception)
└── shared/     api, ui, lib, config — without business logic
```

The folders exist as they are in the tree (`web/src/app`, `web/src/pages`,
`web/src/widgets`, `web/src/features`, `web/src/entities`, `web/src/shared`).

## 2.2 The dependency rule — the only one that really matters

> **A layer can only import from the layers below it.**

```
app  →  pages  →  widgets  →  features  →  entities  →  shared
```

**Forbidden**, and it is the reason FSD exists:

- A **page** does not import from another page.
- A **feature** does not import from another feature.
- **`shared/` does not import from anything.** If `shared/` imports from `entities/`, it is broken.
- **`pages` does not import from `app`**, except the store types with `import type`.

**Why:** without this rule, two screens end up coupled and changing one breaks the other without
anyone seeing it until deployment.

**The rule is enforced by the linter, not by discipline.**
`web/eslint.config.js` declares the elements of `boundaries/elements` (`app`, `shared`,
`entities`, `features`, `widgets`, `pages`) and a `boundaries/dependencies` policy with
`default: "disallow"` and the relations allowed layer by layer:

| From | May import |
|---|---|
| `app` | everything (`app`, `shared`, `entities`, `features`, `widgets`, `pages`) |
| `shared` | only `shared` |
| `entities` | `entities`, `shared` |
| `features` | `app`, `entities`, `shared` |
| `widgets` | `app`, `features`, `entities`, `shared` |
| `pages` | `app`, `widgets`, `features`, `entities`, `shared` |

`app` appears in `features`, `widgets` and `pages` for a concrete and bounded reason: the store
types (`RootState`, `AppDispatch`) are imported with `import type` to type the `useSelector` and the
`dispatch` calls. The linter does not distinguish an `import type`, so the relation is allowed and the discipline
is enforced by review: **never a value from `app` from a lower layer**.

`pnpm lint` (`eslint . --max-warnings=0`, `web/package.json`) fails if it is broken, and **it was checked that it
fails**: with a probe that imported `@pages` from `shared`, the linter answered
`There is no policy allowing dependencies from elements of type "shared" to elements of type "pages"`
(docs/pruebas/verificacion.md §2).

**The second linter guard.** A page, a feature or an entity **cannot import `ITDataTable` or `ITPage`**
from the UI system: the wrappers of `shared/ui` are used instead
([web-react.md](web-react.md) §4). It is enforced by a `no-restricted-syntax` rule, and it was also checked
by making it fail.

## 2.3 Concrete rules the compiler does not detect

**1. Routes go without `/v1`.** `API_CONSTANTS.BASE_URL` is already `/v1`
(`web/src/shared/api/constants/API_CONSTANTS.ts`).

```ts
get("/invoices/pending-approval")       // ✅
get("/v1/invoices/pending-approval")    // ❌ → /v1/v1/... → 404
```

**Neither `tsc` nor `eslint` detects this**: neither validates a URL at runtime. An error in a
relative route **is only seen by calling it**. That is why: **it is always tested with `curl` before writing
the interface** (see [verificacion.md](../pruebas/verificacion.md)).

**2. The visible name of a location is composed by the server.** The client never concatenates
`hall + code + position`. If the label format changes, the backend, the web and the mobile app do not
all have to change at once. The composition is in
`backend/src/main/kotlin/dev/axzy/stock/backend/application/shared/LocationDisplayName.kt`
(`locationDisplayName`).

**3. Server data is decimal `string`, not `number`.** `"5193.000"`. Typing against the
contract instead of against the real response renders `undefined`.

**4. A screen without an endpoint does not show invented rows.** It says what it will do and that the endpoint
does not exist. **A table with fake data is worse than an empty screen**, because the user
decides with it.
