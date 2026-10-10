# Almacén Integral

Warehouse system: product and location catalog, stock and ledger,
invoice receiving with physical count, order picking, transfers between
locations and warehouses, labels and reports.

The system is being rebuilt. What exists today is the documentation, the shared domain and the
API with its infrastructure; the app and the web come later.

## Start

One command: **[ARRANQUE.md](ARRANQUE.md)**.

## The parts

| Part | Folder | What it is | Status |
|---|---|---|---|
| Domain | `stock-domain/` | Pure business rules, Kotlin Multiplatform, no frameworks | Built |
| API | `api/` | Kotlin + Spring Boot over PostgreSQL, layered | Phases 0 to 10 closed; the ERP without adapter |
| Database | `db/` | Flyway migrations `V1`–`V15`, and the API role | Built |
| Environment | `docker-compose.yml` | PostgreSQL, migrations and API | Built |
| Web | `web/` | React 19 + Vite + Tailwind + AXZY UI System, in FSD layers | Entry and catalog; grows with each module |
| Brand | `brand/` | App icons (Android and iOS) and the art package manifest | Versioned; the web already uses it ([visual identity](docs/guia/identidad-visual.md)) |
| App | — | Kotlin Multiplatform (Android and iOS) | Pending |
| Legacy | `BASE/` | The previous system (Node + React + Expo). **Reference only: not modified** | — |

## Documentation

**The index is in [docs/README.md](docs/README.md).** Where to start:

1. [docs/plan/PLAN-REHACER.md](docs/plan/PLAN-REHACER.md): what is being built and why.
2. [docs/plan/PLAN-IMPLEMENTACION.md](docs/plan/PLAN-IMPLEMENTACION.md): modules, phases and the definition of done.
3. [docs/arquitectura/api-spring.md](docs/arquitectura/api-spring.md): how the API is organized.
4. [docs/flujos/](docs/flujos/README.md): the processes the system must fulfill.

Three rules summarize how it is written:

1. Each document declares its type on line 2: **Normative**, **Plan**, **Reference** or **Historical**.
2. Each rule lives in **one place only**; the other documents link to it.
3. Each technical claim cites the code file that backs it.

Concrete figures (tests, versions) live in the documents under `docs/` and in
[ARRANQUE.md](ARRANQUE.md), not here: a number on the front page goes stale and nobody updates it.
