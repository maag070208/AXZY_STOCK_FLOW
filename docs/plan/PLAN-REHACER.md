# Plan to rebuild StockFlow: API in Kotlin with Spring Boot, KMP app and React web
> **Plan.** What is rebuilt, in which order and which decisions remain open. It is the document
> that governs the sequence; the normative documents (architecture, flows, ADR) describe the destination.
> Last revised: 2026-10-09.

> **Breakdown by module and build order:** [PLAN-IMPLEMENTACION.md](PLAN-IMPLEMENTACION.md).

## 1. Starting point

At the start, the repository had only documentation and the legacy (`BASE/`). The previous code (Ktor,
Exposed, Compose) was in the git history and served as a **behavior reference**, not as a code base.
What was kept:

| What is kept | Where it is | Why |
|---|---|---|
| Business rules (invariants, states, permissions) | `docs/adr/0005`–`0013`, `docs/modulos/` | They are business decisions, not framework ones |
| Data schema and SQL engine (inventory functions) | `db/migrations/V1`–`V17` | The engine lives in the database; V1–V9 come from the history and V10–V17 were written afterwards |
| HTTP contract | `docs/api/` | Consumed by the app and the web |
| Visual design | `docs/guia/identidad-visual.md` | It is still the reference for the web |

What was **not** kept is the Ktor architecture (`docs/historico/`): the hand-wiring, the version stack
and the decisions taken by the previous framework.

**Status as of 2026-10-10.** The API exists and runs in Compose, with 752 tests in green and the schema at
V17. The module-by-module progress is in [PLAN-IMPLEMENTACION.md](PLAN-IMPLEMENTACION.md). The app and the web
are still in progress.

## 2. Destination

| Part | Technology | Architecture |
|---|---|---|
| **API** | Kotlin + Spring Boot, PostgreSQL 16, Docker | Clean Architecture with constructor-based dependency injection ([arquitectura/api-spring.md](../arquitectura/api-spring.md)) |
| **App** | Kotlin Multiplatform (Android and iOS) | Vertical slice with a shared core ([arquitectura/app-kmp.md](../arquitectura/app-kmp.md)) |
| **Web** | React 19 + Vite + TypeScript + AXZY UI System | Feature-Sliced Design ([arquitectura/web-fsd.md](../arquitectura/web-fsd.md)) |
| **Database** | PostgreSQL 16 | Versioned migrations with Flyway ([arquitectura/infraestructura.md](../arquitectura/infraestructura.md)) |
| **Local deployment** | Docker Compose | One command starts database, migrations, API and web ([arquitectura/infraestructura.md](../arquitectura/infraestructura.md)) |

The flows each part must fulfill are in [flujos/](../flujos/README.md).

## 3. Requirements that come from the review of October 9

The review of the previous backend found defects that the plan **requires** avoiding. Each one
has a test that prevents it:

| # | Requirement | Test that prevents it |
|---|---|---|
| Q1 | The total per product is recalculated with the product locked (`SELECT … FOR UPDATE`); two concurrent movements cannot lose a write. | Concurrency test with two real transactions (see [flujos/existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §5) |
| Q2 | An export to the ERP is **never** recorded as successful without a real dispatch. While the client does not exist, the attempt stays `FAILED` with reason `ERP_NOT_CONNECTED`. | Test of the export without ERP ([flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md) §5) |
| Q3 | The confirmation of an order locks the order and uses an idempotency key; a double submission does not deduct twice. | Test of simultaneous double confirmation ([flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md) §5) |
| Q4 | All migrations are applied in the same order in all environments; no environment starts with pending migrations without it being noticed. | Compose startup migration and check of `flyway_schema_history` ([arquitectura/infraestructura.md](../arquitectura/infraestructura.md) §4) |
| Q5 | Integration tests run against a real PostgreSQL, they are not silently skipped. | A test that fails if there is no database (inherited from the `PostgresGuardTest` canary) |
| Q6 | The ledger only grows: no operation modifies or deletes a movement. Correcting means adding a compensating movement. | Test A9 of [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §7; origin analysis C1 in [analisis/legacy-entradas-salidas-ajustes-traslados.md](../analisis/legacy-entradas-salidas-ajustes-traslados.md) |
| Q7 | The balance is changed by the database with a lock. No application calculates an absolute balance and writes it (read-modify-write). | Tests A3 and A4 of [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §7; analysis C2 and C5 |
| Q8 | An outflow deducts what is missing to pick (`quantity − supplied_quantity`), inside a transaction that locks the order, with a single confirmation operation. No write is left without `await`. | Tests S2 and S3 of [surtido-y-erp.md](../flujos/surtido-y-erp.md) §7; analysis C3 and C6 |
| Q9 | Availability is checked inside the same transaction that writes, with the balance locked. Never with a prior read. | Test A3 of [existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) §7; analysis C4 |
| Q10 | The balance comes from the projection, not from the history. A listing makes a fixed number of queries, without N+1. | Phase 5 performance tests; analysis A1 to A4 |

## 4. Decisions

The decisions marked **Taken** are already in an ADR. The **Proposal** ones need
confirmation before the phase where they apply.

| # | Decision | Status | Where |
|---|---|---|---|
| D1 | API in Kotlin with Spring Boot | Taken | [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md) |
| D2 | Constructor-based dependency injection, wiring only in the startup module | Taken | [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md) |
| D3 | PostgreSQL 16 with Flyway; the inventory engine stays in SQL functions | Taken | [ADR-0015](../adr/0015-postgresql-flyway-motor-en-sql.md) |
| D4 | Local deployment with Docker Compose | Taken | [ADR-0016](../adr/0016-docker-compose-para-el-entorno.md) |
| D5 | ERP: without a client, the export fails with a reason; success is not simulated | Taken (Q2) | [flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md) |
| D6 | **Shared domain** between the API and the app (KMP module with the rules) | **Taken (2026-10-10): yes** | [arquitectura/dominio-kmp.md](../arquitectura/dominio-kmp.md) |
| D7 | Reuse migrations `V1`–`V10` as the initial Flyway schema, instead of redesigning the schema | **Taken (2026-10-10): yes** | §5, phase 1 |
| D8 | API versions: Spring Boot 4.1.x, Kotlin 2.4.20, JDK 25 LTS | **Taken (2026-10-10)**; confirm in the official source before pinning the lockfile | [arquitectura/stack.md](../arquitectura/stack.md) |
| D9 | The web keeps FSD | **Taken (2026-10-10): yes**, with the layer rule enforced by the linter | [arquitectura/web-fsd.md](../arquitectura/web-fsd.md) |
| D10 | ERP identifier (`external_id`) | **Pending**: depends on what the ERP accepts | [ADR-0013](../adr/0013-ordenes-al-erp-con-reintentos.md) |

## 5. Phases

Each phase ends with a criterion that can be verified. The next phase is not started without
meeting the previous one's.

| Phase | Deliverable | Exit criterion |
|---|---|---|
| **0. Documentation and decisions** | This plan, the normative documents and the new ADRs. D6–D10 resolved. | Review of the normative documents without open decisions that block phase 1 |
| **1. Infrastructure** | Compose with PostgreSQL 16 and the `migrate` service. Flyway with `V1`–`V10` (D7). | `docker compose up` leaves the database migrated; `flyway_schema_history` lists the 10 versions |
| **2. Base API** | Multimodule Gradle project, startup, health, configuration by variables without insecure default values, error handling. | `GET /v1/health` answers; startup fails if a required variable is missing |
| **3. Identity** | Login, refresh with rotation, logout, password change, lock, RBAC + ABAC denying by default. | Tests of the [authentication](../flujos/autenticacion-y-permisos.md) flows in green |
| **4. Catalog and warehouses** | Products, locations, warehouses and strict import of the Excel. | Import with an invalid row rejects the entire file |
| **5. Stock** | Inventory engine, ledger, adjustments, locate and move, reconciliation reports. Q1 in force. | Q1 concurrency test in green |
| **6. Receiving** | Invoices, capture, discrepancies, approval per invoice, segregation of duties. | No line in discrepancy can be located |
| **7. Picking and ERP** | Orders, packing, shortages, confirmation, export. Q2 and Q3 in force. | Q2 and Q3 tests in green |
| **8. Transfers** | Between locations and between warehouses, transit, receipt. | Transfer receipt covered by a test (today it is not) |
| **9. Labels and audit** | Templates, print queue with service key, immutable audit log. | Every write of the flows leaves a record |
| **10. KMP app** | Shared core with the rules, floor screens (receiving, locate, pick, scan). | Compiles on Android and iOS; receiving flow tested on a device |
| **11. React web** | Administration, supervision, stock, ledger, reports. | Main flows covered by browser tests in CI |

Phases 10 and 11 start when the contract of the API of the previous phase is stable.

## 6. What is needed to start phase 1

1. Resolve D6 and D7 (see §4).
2. Pin the versions of D8 and note them in [arquitectura/stack.md](../arquitectura/stack.md).
3. Have PostgreSQL 16 and Docker available on the development machine.

## 7. Known risks

| Risk | Mitigation |
|---|---|
| Rewriting the rules when migrating to the new framework and the behavior changes | The tests of the flows are written before the code, starting from [flujos/](../flujos/README.md) |
| The shared domain (D6) complicates the build of three parts | It is decided in phase 0; if rejected, the API duplicates the rules and it is documented which copy is the reference |
| The real ERP arrives late and the export stays in `FAILED` for a long time | It is the honest behavior and is foreseen by ADR-0013; the manual retry exists since phase 7 |
| The versions of Spring Boot or Compose change before starting | D8 is pinned in phase 0 with the version current at the time and the date is recorded |
