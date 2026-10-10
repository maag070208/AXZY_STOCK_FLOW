# API: Kotlin with Spring Boot, layered and constructor injection
> **Normative.** How the API is organized. Decisions in [ADR-0014](../adr/0014-api-spring-boot-clean-architecture.md) and [ADR-0015](../adr/0015-postgresql-flyway-motor-en-sql.md). Each claim cites the file that backs it. Last revised: 2026-10-10 (phase 4).

## 1. Modules and dependencies

The API is a Gradle build with six modules (`settings.gradle.kts`). Each module is a layer, and the
dependency only points toward the domain:

| Module | Contains | May depend on | May not depend on |
|---|---|---|---|
| `stock-domain` | Rules, states, errors, access engine. Shared with the app (D6) | Kotlin stdlib, kotlinx-datetime, kotlinx-serialization | Any framework |
| `api/application` | Use cases and ports | `stock-domain` | Spring, JDBC, JSON, adapters |
| `api/adapters-persistence` | The outbound ports over PostgreSQL | `application`, Spring JDBC, Jackson | `adapters-web`, `adapters-security` |
| `api/adapters-security` | Cryptography: JWT, Argon2id, refresh tokens | `application`, Spring Security crypto and JOSE | `adapters-web`, `adapters-persistence` |
| `api/adapters-web` | Controllers and the error contract | `application`, Spring Web | `adapters-persistence`, `adapters-security` |
| `api/app` | Startup, wiring and HTTP security | All of the above | — |

```
                 ┌──→ adapters-web ─────────┐
app ─────────────┼──→ adapters-persistence ─┼──→ application ──→ stock-domain
                 └──→ adapters-security ────┘
```

The three adapters **do not know each other**. A controller never calls a repository: it calls
a use case, and the use case calls a port.

There is no `domain` module of the API's own: with the shared domain (D6), `stock-domain` **is** the
domain layer. It is Kotlin Multiplatform and is compiled to Java 17 bytecode so that Android can also load it
(`stock-domain/build.gradle.kts`).

### How it is verified

- **The compiler**: each `build.gradle.kts` declares only what its layer can see
  (`api/*/build.gradle.kts`). A forbidden dependency does not compile.
- **`verifyDomainPurity`** (`stock-domain/build.gradle.kts`): fails if the domain declares or imports a
  framework.
- **`ArchitectureTest`** (`api/app/src/test/kotlin/dev/axzy/stock/app/ArchitectureTest.kt`): seven
  rules over the compiled classes. It repeats the above in case a transitive dependency gets in.

## 2. Dependency injection

**Rule: objects are created in a single place, `api/app`, and passed through the constructor.**

- Use cases and adapters are Kotlin classes **without Spring annotations**. They receive
  what they need in the constructor.
- Spring only scans the package `dev.axzy.stock.app` (`StockApplication.kt`). Nothing from another layer is
  registered just because it carries an annotation: everything is an explicit `@Bean` in `api/app/.../config/`.
- The only classes outside `app` with Spring annotations are the controllers
  (`@RestController`) and the error handler (`@RestControllerAdvice`), and even those are instantiated by `app`.
- `ArchitectureTest` fails if a `@Configuration` or a `@Bean` appears outside `app`.

| File in `api/app/.../config/` | What it wires |
|---|---|
| `PersistenceConfig.kt` | The outbound ports: transactions, audit log, repositories |
| `IdentityConfig.kt` | Cryptography, access engine and session use cases |
| `WebConfig.kt` | Controllers, argument resolvers and the `X-Request-Id` filter |
| `SecurityConfig.kt` | The HTTP security chain |
| `SchemaGuard.kt` | The schema version check at startup |
| `StockProperties.kt` | The typed configuration (`stock.*` in `application.yml`) |
| `CatalogConfig.kt` | The catalog (M01): inbound ports and routes |
| `InventoryConfig.kt` | Stock (M02): the engine, the queries and the routes |
| `ReportConfig.kt` | The stock reports and the ledger export |
| `ReceivingConfig.kt` | Receiving (M03): invoices, capture and discrepancy decisions |
| `FulfillmentConfig.kt` | Picking (M04) and the ERP port (M09), currently without connection |
| `TransferConfig.kt` | Transfers (M05), over the engine functions of migration V13 |
| `LabelConfig.kt` | Labels (M06): templates, preview, queue and reprint |
| `AuditConfig.kt` | The audit log query (M08), which also records its own access |
| `UserAdminConfig.kt` | Users and roles (M07): create, read, edit and reset |
| `RoutePermissionAudit.kt` | I5: a route that does not declare what it requires prevents startup |

A use case does not know that Spring exists. `Login` is built the same way in a test as in production:

```kotlin
// api/app/.../config/IdentityConfig.kt
@Bean
fun login(users: UserAccounts, attempts: LoginAttempts, hasher: PasswordHasher, sessions: SessionIssuer,
          codec: RefreshTokenCodec, tx: TransactionRunner, clock: Clock) =
    Login(users, attempts, hasher, sessions, codec, tx, clock)
```

## 3. Ports

- **Inbound** (use cases): one class per operation with an `execute` method. Verb and object, in
  English: `Login`, `RefreshSession`, `ChangePassword` (`api/application/.../identity/`).
- **Outbound**: interfaces in `application` with business names: `UserAccounts`, `RefreshTokens`,
  `LoginAttempts`, `AuditRecorder`, `AccessCatalogStore`.
- A port **does not expose database types** (neither `ResultSet` nor rows).

## 4. Errors

- An expected failure **is returned**, not thrown (ADR-0005). Every use case returns
  `Outcome<A>`, which is `Either<Failure, A>` (`api/application/.../shared/Failure.kt`).
- `Failure` carries a `kind` (what class of failure it is, without talking about HTTP), a stable `code` and a
  `message` for a person.
- `DomainError.toFailure()` translates the domain errors with an **exhaustive** `when`: a new error
  in the domain stops compiling until someone decides what it means for the client.
- `adapters-web` translates `kind` into a status code (`http/ErrorResponses.kt`) and always answers
  with the same body `{ code, message, details }` ([api/errores.md](../api/errores.md)).
- What is not a failure of a use case also respects the contract (`http/GlobalErrorHandler.kt`):
  unreadable JSON, nonexistent route, method not allowed and unexpected error. The last one answers
  `500 INTERNAL_ERROR` with an incident identifier and **without** the internal detail.
- A programming failure (an impossible state) **is thrown**: it is a bug and it has to be visible.

## 5. Transactions

- The boundary is set by the use case with `TransactionRunner.inTransaction { }`
  (`api/application/.../shared/TransactionRunner.kt`).
- **A `Left` rolls back the transaction.** It is implemented by `JdbcTransactionRunner`
  (`api/adapters-persistence/.../shared/`) over `TransactionTemplate`.
- What has to remain written even if the use case fails goes in a separate transaction and this is stated
  in the port's contract. There are two cases: an access denial (`AccessDenialLog`,
  `PROPAGATION_REQUIRES_NEW`) and the revocation due to reuse of a token (`RefreshSession`).
- `@Transactional` is **not used** in any layer.
- Slow work that does not need the database is done **before** opening the transaction. `ChangePassword`
  computes the Argon2id hash first, so as not to hold a connection in the meantime.

## 6. Identity and authorization

These are two different questions, and different layers answer them.

**Who is calling?** The HTTP security chain (`api/app/.../config/SecurityConfig.kt`) verifies the
token and leaves in the context a `TokenIdentity` with two pieces of data: the user's id and their credentials stamp. Everything requires identity except four public routes declared in that file.

**What can they do?** It is decided by the use case with `AccessDecider`
(`api/application/.../access/AccessDecider.kt`), which uses the domain engine (`AccessEngine`):
identity → RBAC → ABAC, denying by default. The controller does not decide permissions.

Rules the code enforces:

- **The token only identifies.** It carries no roles or permissions (`JwtAccessTokens.kt`). The `Actor` is
  rebuilt from the database on every request (`ActorArgumentResolver` → `ResolveActor`), so removing
  a role or deactivating an account takes effect on the next request.
- **Credentials stamp.** The token carries the instant, down to milliseconds, of the account's last password change. If it no longer matches the one in the database, the token stops working. It is an exact
  comparison, not a date comparison.
- **The `Actor` is an argument.** It reaches the use case as a parameter, never from a global context.
- **The policies' time zone belongs to the server** (`stock.business-zone`). A schedule policy evaluated with the
  zone the client states can be bypassed by changing a header.
- **Every denial is recorded in the audit log**, in its own transaction.

Flows and tests in [flujos/autenticacion-y-permisos.md](../flujos/autenticacion-y-permisos.md).

## 7. Persistence

- `adapters-persistence` uses `JdbcClient` with hand-written SQL. There is no ORM
  ([ADR-0015](../adr/0015-postgresql-flyway-motor-en-sql.md)).
- The API connects with a user who is a member of the `stock_api` role (migration V11). That role cannot
  create or alter tables, nor do `UPDATE` or `DELETE` on `kardex` and `audit_log`
  ([infraestructura.md](infraestructura.md) §3).
- Inventory writes call the engine functions. The API never writes
  `stock_by_location` or `product_stock` directly.
- The audit log is written with `audit_log_record` (V12), which chains the hash of each record
  (`audit/JdbcAuditRecorder.kt`).
- A list that is inserted or synchronized travels as **one** JSON document and the database expands it with
  `jsonb_to_recordset`: one statement per table, not one per row (`identity/JdbcAccessCatalogStore.kt`).
- Dates are set by the application's clock (`Clock`), not by the database's, when a rule depends on them
  (lock window, token expiry). Mixing the two clocks makes the rule depend on which server is ahead.

## 8. Package structure

One package per business concept within each module, not per technical type:

```
dev.axzy.stock.domain.{access, audit, catalog, fulfillment, identity, inventory, receiving, transfer, …}
dev.axzy.stock.application.{access, audit, catalog, health, identity, shared}
dev.axzy.stock.adapters.persistence.{audit, catalog, health, identity, shared}
dev.axzy.stock.adapters.security
dev.axzy.stock.adapters.web.{catalog, health, http, identity, security}
dev.axzy.stock.app.config
```

## 9. Tests

| Level | What it tests | Where | Database |
|---|---|---|---|
| Domain | Rules, states, errors (408 tests) | `stock-domain/src/commonTest`, `jvmTest` | No |
| Application | Use cases and cross-cutting rules (pagination, visible name of a location; 11) | `api/application/src/test` | No |
| Persistence | Adapters, SQL functions, role permissions, query budget —including the user listing, which does not grow with the rows—, concurrency (including the capture claim), operational day, the transfer engine and write guards (91) | `api/adapters-persistence/src/test` | Real PostgreSQL |
| Web | Controllers, error contract, CSV/XLSX export and reading the catalog Excel, without starting the server (17) | `api/adapters-web/src/test` | No |
| Cryptography | Argon2id, token signing and verification (11) | `api/adapters-security/src/test` | No |
| Architecture | The rules of §1 and §2, and that every route declares its permission (I5) | `api/app/src/test/.../ArchitectureTest.kt`, `RoutePermissionTest.kt` | No |
| Full flow | The flows of [flujos/](../flujos/README.md) over real HTTP (210) | `api/app/src/test` | Real PostgreSQL |

**The test database** is PostgreSQL 16 in Testcontainers with the real migrations
(`api/adapters-persistence/src/testFixtures/.../TestDatabase.kt`). The code under test connects
with a user of the `stock_api` role, so the tests exercise the permissions for real.

**The flow tests** (`ApiTest.kt`) start the complete application: Tomcat on a free port,
the security chain, the real wiring and the real database. Only the clock is replaced, to advance the
time.

**A test that needs the database fails if there is none**; it is not skipped. If Docker is not available,
Testcontainers throws an error and the build breaks.

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew verify  # 752 tests on 2026-10-10 (includes the domain jvmTest)
```

The build detects the socket of the active Docker context (Colima, OrbStack, Docker Desktop), so
`DOCKER_HOST` does not need to be exported (`build.gradle.kts`).

## 10. What is not done

- There is no service per entity that mixes rules and SQL.
- There are no DTO layers that repeat the domain: an HTTP DTO exists only when the contract needs it.
- There is no `Manager`, `Helper` or `Util` with business rules.
- There is no server session nor cookies: the API is stateless.

## 11. Performance and consistency: verifiable rules

They come from the findings of the legacy
([analisis/legacy-entradas-salidas-ajustes-traslados.md](../analisis/legacy-entradas-salidas-ajustes-traslados.md)).
Each one has a test; the ones that do not have one yet indicate the phase.

| Rule | Incorrect form (legacy) | Correct form | Test |
|---|---|---|---|
| R-P1. No query inside a loop | `findUnique` of a user per line (`invoice.service.ts:178`) | One query with a `JOIN`, and in-memory mapping | `IdentityPersistenceTest`: loading the actor is 2 queries with 1 role or with 3 |
| R-P2. No unbounded listing | `findMany()` of the whole table and filter in JavaScript (`kardex.service.ts:587`) | Filter and `LIMIT` in the query | `CatalogPersistenceTest`: a listing is 2 statements with 1 row or with 50 |
| R-P3. The balance is not recalculated from history | Summing the whole ledger on every operation (`kardex.service.ts:408`) | Read the projection | `InventoryPersistenceTest`: the total comes from `product_stock` |
| R-P4. Every multi-step write goes in one transaction | `addKardex` writes the ledger and then the stock (`kardex.service.ts:241-258`) | A single `inTransaction { }` | `JdbcAuditRecorderTest` (U1): a `Left` leaves nothing written |
| R-P5. No operation is left unawaited | `orderDetail.map((item) => addKardexOut(...))` without `await` (`order.service.ts:350`) | Sequential calls inside the transaction | Phase 7 |
| R-P6. The balance is changed by the database with a lock | Reading the balance, computing and writing the absolute value | `UPDATE … SET quantity = quantity + :delta` in the SQL function | `InventoryEngineConcurrencyTest` (A3, A4) and `InventoryWriteGuardTest` (no adapter writes the balance) |
| R-P7. A movement is not modified | `kardex.update` and `updateMany` that change `moveType` and `moveDate` | Only `INSERT`; the database rejects `UPDATE` and `DELETE` | `InventoryEngineConcurrencyTest` (A9) and `ApiRolePrivilegesTest` |
| R-P8. No log contains full stock | `console.log` with the stock state (`order.service.ts:396`) | Log with identifiers and counts | Phase 5 review |
| R-P9. A synchronization is one statement per table | One statement per permission and per cell on every startup | `jsonb_to_recordset` | `IdentityPersistenceTest`: the whole seed is ≤ 12 statements |
| R-P10. The audit chain does not scan the table | `ORDER BY sequence_no DESC LIMIT 1` without an index: it read all rows on every write | Index on `sequence_no` (V11) | `ApiRolePrivilegesTest` checks that the index exists; measurement in [infraestructura.md](infraestructura.md) §4 |

**Performance criterion.** Each listing and each inventory operation has a budget of SQL
statements per call, which is set with the first measurement of the module. `StatementCounter`
(`testFixtures`) counts the statements in the tests. Time figures are not set before measuring.
