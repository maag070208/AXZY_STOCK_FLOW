# How to start the project

**One command.** Starts PostgreSQL, applies the migrations and starts the API.

```bash
cp .env.example .env      # once; fill in the empty values (see below)
docker compose up -d --build
```

```bash
curl -s http://127.0.0.1:8081/v1/health
# {"status":"UP","schemaVersion":"15","version":"dev","database":{"status":"UP",...}}
```

The detail of each service, port and variable is in
[docs/arquitectura/infraestructura.md](docs/arquitectura/infraestructura.md).

---

## What to fill in `.env`

| Variable | How to generate it |
|---|---|
| `DB_OWNER_PASSWORD` | `openssl rand -hex 24` |
| `STOCK_API_PASSWORD` | `openssl rand -hex 24` |
| `STOCK_JWT_SECRET` | `openssl rand -base64 48` |
| `ADMIN_BOOTSTRAP_PASSWORD` | Whatever you want for the first administrator: at least 12 characters and not a common one |

**No secret has a default value.** If one is missing, `docker compose` or the API stops and
says which one. An environment that starts with known credentials is a vulnerable environment nobody notices.

`.env` is not versioned.

## The first administrator

The first time the API starts against a database with no users, it creates the user `admin` with the
password from `ADMIN_BOOTSTRAP_PASSWORD` and **requires changing it on sign-in**. Until it is changed,
it can only change it, see who it is and sign out.

After it is created, `ADMIN_BOOTSTRAP_PASSWORD` can be left empty: it is no longer used.

## What gets started

| Service | Where | What it is |
|---|---|---|
| `postgres` | `127.0.0.1:5434` | PostgreSQL 16, database `stock` |
| `migrate` | — | Applies `db/migrations` and exits with code 0 |
| `api` | `127.0.0.1:8081` | The API (Kotlin + Spring Boot) |

```bash
docker compose ps -a      # migrate must be «exited (0)»; postgres and api, «healthy»
docker compose logs api   # the startup says how many permissions and roles it synchronized
```

**The API does not migrate: it checks.** If the database is behind the migration the code needs, the
API does not start and says so. Migrations are applied only by the `migrate` service.

## Build and test without Compose

You need a JDK 17 or higher to start Gradle; the JDK 25 of the API is downloaded by Gradle. The
integration tests use Testcontainers, so Docker must be running.

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew verify
```

The real count is in the XML files under `**/build/test-results/`, not in «BUILD SUCCESSFUL».

**Tests that need PostgreSQL fail if there is no Docker; they are not skipped.** A skipped test
counts as passed, and "0 failures" without a database would mean "nothing was tested".

## The web

You need Node 20 or higher and `pnpm`. The API must be running.

```bash
cd web
pnpm install
pnpm dev            # http://127.0.0.1:5173, with a proxy to the API
```

The browser **always talks with relative paths** (`/v1/...`): in development Vite proxies them, and in
production nginx does. That way there is no CORS and no URL that breaks when changing environment. If the
API is not on the default port: `STOCK_API_URL=http://127.0.0.1:9090 pnpm dev`.

| Command | What it does |
|---|---|
| `pnpm dev` | Development server with a proxy to the API |
| `pnpm typecheck` | `tsc --noEmit` |
| `pnpm lint` | `eslint . --max-warnings=0`: enforces the FSD layers and the UI system wrappers |
| `pnpm build` | `tsc --noEmit && vite build` |
| `pnpm test:e2e` | Playwright (30 tests). Sign-in needs the API running; the rest uses the mocked API |

**The first time it forces you to change the password**, which is the documented behavior (I8):
until you change it, the API answers `PASSWORD_CHANGE_REQUIRED` to everything else and the web shows only
that screen. Afterwards the menu appears with Products, Locations, Warehouses and the read modules (Stock, Ledger, Receiving, Picking, Transfers, Labels and Audit), each according to its permissions.

**Browser tests use `E2E_ADMIN_PASSWORD`**, not `ADMIN_BOOTSTRAP_PASSWORD`. The bootstrap one only creates the first administrator and the API forces a change: a test that used it as the current password would break as soon as someone changed it. It goes in `.env` (ignored by git); the template documents it.

**The SWC native binary cache lives inside the project.** The scripts set it with
`SWC_NATIVE_BINDING_CACHE="$PWD/.cache/swc"`; without that, `@swc/core` tries to write a lock in
`~/Library/Caches/swc-native-*` and the build fails with "Failed to load native binding", an error that says
nothing about the cause.

Four domain tests (`CatalogImporterTest`) read the real catalog extract,
`stock-domain/src/jvmTest/resources/catalogo_real.csv`. That file **is not versioned** (it is company data,
see `.gitignore`): in a fresh clone it has to be placed there or those four tests fail saying it is missing.

## Coexisting with the previous system

If the previous Compose is still running on the machine (`stock-postgres` on port 5432,
`stock-adminer` on 8080), there is no conflict: this project is called `stockflow` and uses other ports.
It was not named `stock` precisely because two Compose projects with the same name overwrite each other's containers.

## What is verified and what is not

### Verified by running it (2026-10-10)

- **752 tests, 0 failures, 0 skipped**: 412 of the domain, 11 of the application layer, 91 of
  persistence, 17 of the web layer, 11 of cryptography and 210 of full flow over HTTP, measured with
  `./gradlew verify` on 2026-10-10. Schema at **V15**.
  **`./gradlew test` is not enough**: it does not run the domain tests (its task is called `jvmTest`),
  so the report can look green while reading XML from a previous run. That is why the verification command is `verify`.
- The API image is built and started in Compose; `/v1/health` answers `UP` with the schema at V15.
- Sign-in, current session, renewal with reuse detection, sign-out and password change.
- The API user cannot create tables or modify the ledger or the audit log.
- **Catalog (phase 4)**: products with create, edit and logical delete; locations with the receiving one
  protected; warehouses with only one default; and strict, idempotent catalog import (K1–K6). Each route declares its
  permission and the API does not start if any does not (I5).
- **Stock (phase 5, M02), closed**: adjustments with a reason (the permission depends on the reason), locate
  and move; stock per location and ledger; and the stock reports with totals, movement summary by day/week/month/product/location,
  `kardex-vs-projection` reconciliation and export of the ledger to CSV and XLSX. Everything goes through the SQL
  engine; a code guard prevents any adapter from writing the inventory by hand. Report dates are **operational days** in the
  business's time zone, not UTC days.
- **Receiving (phase 6, M03), closed**: invoices with lines, physical count with its difference
  computed by the database, approval **per invoice** (a single action, and whoever counted cannot
  approve), rejection that returns the line to counting, closing that respects the domain's criterion,
  pending inbox, and the **location known by the invoice line**: locating applies rule B1
  before moving anything and leaves the line `LOCATED`.
- **Picking and ERP (phase 7, M04 + M09), closed**: orders with lines, packing that deducts what was packed,
  confirmation that deducts **only what is missing** and is claimed with a conditional statement (two
  simultaneous confirmations do not deduct twice), shortages through the single adjustment rule,
  and export to the ERP with an attempt counter and failure reason.
- **Transfers (phase 8, M05), closed**: creation, dispatch, receipt, cancellation and the report of goods in transit with their
  days counted by the server. The SQL engine functions (`despachar_traslado`, `recibir_traslado`, `cancelar_traslado`,
  migration V13) move the balance, leave both halves in the ledger and keep the invariant: **goods in the truck still count**.
- **Labels (phase 9, M06), closed**: ZPL templates with validation on save, preview that resolves with the same function
  that prints, enqueueing that resolves before enqueuing, queue by status, reprint linked to the original and **printer
  confirmation** with a service credential —the only route without a user and the only one that marks a label as printed—.
- **Audit (phase 9, M08), closed**: `GET /v1/audit/log` with filters by entity, actor, action,
  level, result, channel and business date range, paginated with the total of the same filter. The
  log is immutable through triggers and **its own query is recorded as an access**.
- **Users and roles (M07), closed**: create, read, edit, role assignment and password reset —which invalidates the open sessions—, and **roles with their permission matrix**: create,
  edit, activate/deactivate and grant. A new role grants exactly what its matrix says, and
  removing or deactivating it takes effect immediately.
- **Migration (M10)**, in what is code: the catalog import is strict and idempotent,
  accepts **CSV, TSV and XLSX** through the same reader, and loads the legacy stock to `SIN-UBICAR` with an
  `INITIAL` movement per product —only for those who had no stock, so that re-importing does not
  duplicate anything—.
- **Web (phase 11, started)**: sign-in and mandatory password change against the real API;
  side menu with the entries the session's permissions allow; and the three catalog screens (products with create/edit/delete and import with simulation, locations and warehouses),
  with the icon package brand (favicon, manifest, shell glyph) and the Lottie loader while the import works. `tsc`, `eslint` (with the layer and wrapper guards
  **tested by failing**) and `vite build` green, and 30 browser tests: 25 with the mocked API (6 of the
  catalog, 6 of the read modules and 13 of the write actions), and 5 against the real API
  (sign-in and session).

```bash
# The catalog, end to end (needs a session with catalog permissions)
curl -s -X POST http://127.0.0.1:8081/v1/products/import   -H "Authorization: Bearer $TOKEN" -F "file=@catalogo.csv" -F "dryRun=true"
```

### Not built yet

- **The ERP connection**: the export attempt fails with `ERP_NOT_CONNECTED` and the counter
  goes up; the ERP adapter is missing (ADR-0013), which depends on knowing its contract (D10).
- **Inside the web**: the screens for the modules that do not have one yet (stock,
  receiving, picking, transfers, labels and audit) are not drawn, and the home page says which are missing.
  The web deployment is not there either (Dockerfile with nginx and its service in Compose). The brand is: the
  icon package is versioned and connected (favicon, manifest and the shell glyph).
- **The app** (Kotlin Multiplatform).
- **The physical count and the cutover** of the legacy stock: these are business work, not code
  ([cutover.md](docs/migracion/cutover.md)).

The order and the definition of done of each one are in
[docs/plan/PLAN-IMPLEMENTACION.md](docs/plan/PLAN-IMPLEMENTACION.md).
