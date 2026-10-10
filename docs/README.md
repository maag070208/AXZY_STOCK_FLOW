# StockFlow documentation
> **Index.** Where to start and which document governs each topic. Last revised: 2026-10-09.

StockFlow is the warehouse system of Almacén Integral (Cascar). It is rebuilt from scratch with:

- **API**: Kotlin with Spring Boot, layered, with constructor-based dependency injection.
- **Database**: PostgreSQL 16, with Flyway migrations and the inventory engine in SQL.
- **Environment**: Docker Compose.
- **App**: Kotlin Multiplatform for Android and iOS.
- **Web**: React with AXZY UI System `^1.4.7`.

The previous code (Ktor) is in the git history and in `historico/`. The Node legacy is in `BASE/`.

## Where to start

1. [plan/PLAN-REHACER.md](plan/PLAN-REHACER.md): what is being built, in which phases, and which decisions are still open.
   [plan/PLAN-IMPLEMENTACION.md](plan/PLAN-IMPLEMENTACION.md): the modules, their dependencies and the definition of done of each one.
2. [arquitectura/general.md](arquitectura/general.md): how the parts relate to each other.
3. [flujos/README.md](flujos/README.md): the processes the system must fulfill.

## What governs each topic

| Topic | Normative document | Notes |
|---|---|---|
| Plan and phases | [plan/PLAN-REHACER.md](plan/PLAN-REHACER.md) | Governs the order of work |
| API layers and DI | [arquitectura/api-spring.md](arquitectura/api-spring.md) | Includes the performance rules (§11) |
| Environment and migrations | [arquitectura/infraestructura.md](arquitectura/infraestructura.md) | |
| Versions | [arquitectura/stack.md](arquitectura/stack.md) | Pending versions are pinned in phase 0 |
| App | [arquitectura/app-kmp.md](arquitectura/app-kmp.md) | |
| Web | [arquitectura/web-react.md](arquitectura/web-react.md) and [arquitectura/web-fsd.md](arquitectura/web-fsd.md) | The first for the UI system; the second for the layers |
| Shared domain | [arquitectura/dominio-kmp.md](arquitectura/dominio-kmp.md) | Pending D6 |
| Processes | [flujos/](flujos/README.md) | Governs the modules when they contradict each other |
| Decisions | [adr/](adr/) | One ADR per decision; replacements are marked |
| HTTP contract | [api/](api/convenciones.md) | Consumed by the app and the web |
| Security | [seguridad/](seguridad/roles-permisos.md) | |

## Decisions (ADR)

| ADR | Topic | Status |
|---|---|---|
| [0001](adr/0001-clean-architecture-en-la-api.md) | Clean Architecture in the API | Partially replaced by 0014 (framework) |
| [0002](adr/0002-fsd-en-la-web.md) | FSD in the web | In force |
| [0003](adr/0003-vertical-slice-en-la-app.md) | Vertical slice in the app | In force |
| [0004](adr/0004-nombres-en-ingles-comentarios-en-espanol.md) | Names in English, comments in Spanish | Replaced by 0018 |
| [0005](adr/0005-errores-devuelto-no-lanzado.md) | Errors returned, not thrown | In force |
| [0006](adr/0006-rbac-mas-abac-denegar-por-defecto.md) | RBAC + ABAC, deny by default | In force |
| [0007](adr/0007-multi-bodega-desde-el-esquema.md) | Multi-warehouse from the schema | In force |
| [0008](adr/0008-discrepancias-y-merma.md) | Discrepancies and shrinkage | In force |
| [0009](adr/0009-ubicacion-de-recepcion-real.md) | Real receiving location | In force |
| [0010](adr/0010-rutas-de-servicio-con-api-key.md) | Service routes with an API key | In force |
| [0011](adr/0011-catalogo-fuente-maestra.md) | Catalog as master source | In force |
| [0012](adr/0012-conciliar-stock-heredado-con-conteo.md) | Reconcile legacy stock with a count | In force |
| [0013](adr/0013-ordenes-al-erp-con-reintentos.md) | Orders to the ERP with retries | In force; the ERP client does not exist |
| [0014](adr/0014-api-spring-boot-clean-architecture.md) | API in Spring Boot, layered, with DI | Accepted |
| [0015](adr/0015-postgresql-flyway-motor-en-sql.md) | PostgreSQL with Flyway and the engine in SQL | Accepted |
| [0016](adr/0016-docker-compose-para-el-entorno.md) | Docker Compose, migrations as a service | Accepted |
| [0017](adr/0017-identificadores-y-datos-en-ingles.md) | Identifiers and data in English | Replaced by 0018 |
| [0018](adr/0018-todo-en-ingles.md) | Everything in English | Accepted; replaces 0004 and 0017 |

## Business rules by module

The modules describe each area with its scope, rules and tests. Their code paths point to the
previous code and are remapped when implementing: [modulos/](modulos/README.md).

## Analysis

- [analisis/legacy-entradas-salidas-ajustes-traslados.md](analisis/legacy-entradas-salidas-ajustes-traslados.md):
  performance and integrity findings of the legacy in receipts, outflows, adjustments and transfers, with its
  file and line, and the fix planned in the new design.

## Historical

`historico/` keeps the documents of the previous design (Ktor, the API's Compose, plans from past
phases). They serve as a reference of what was already decided; they do not describe the system being built.
