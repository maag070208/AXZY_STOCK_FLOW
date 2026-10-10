# Infrastructure: Docker Compose, PostgreSQL and migrations
> **Normative.** How the environment is started and how the migrations are applied. Decision in [ADR-0016](../adr/0016-docker-compose-para-el-entorno.md). Each claim cites its file. Last revised: 2026-10-10.

## 1. Services

Defined in `docker-compose.yml` (root), Compose project **`stockflow`**.

| Service | Image | What it does | Depends on |
|---|---|---|---|
| `postgres` | `postgres:16` | The `stock` database | — |
| `migrate` | `flyway/flyway:12.4.0` | Applies the pending migrations and **exits** | `postgres` healthy |
| `api` | Built with `api/Dockerfile` (JRE 25) | The REST API | `migrate` finished successfully |
| `web` | *Phase 11* | Serves the web and forwards `/api` to `api` | `api` |

**The project is not called `stock`.** That name is used by the Compose of the previous system
(`stock-postgres`, `stock-adminer`), and two projects with the same name overwrite each other's containers:
an `up` of one removes and recreates the other's. It has happened once; the data survived because it was in
a named volume.

## 2. Network and ports

| Service | Host port | Variable |
|---|---|---|
| `postgres` | `127.0.0.1:5434` | `POSTGRES_PORT` |
| `api` | `127.0.0.1:8081` | `API_PORT` |

Both are published **only on loopback**. The web will reach the API through Compose's internal network.

The API takes the client address from the proxy header only if the request comes from an internal network
(`server.forward-headers-strategy: native`, `api/app/src/main/resources/application.yml`).
The code never reads `X-Forwarded-For` on its own: anyone can send it.

## 3. Database roles and variables

**Two roles, and the API does not use the one that can change the schema.**

| Role | Who uses it | What it can do | Where it is created |
|---|---|---|---|
| `DB_OWNER_USER` (`stock_owner`) | `migrate` only | Everything: it owns the schema | `POSTGRES_USER` of the container |
| `STOCK_API_USER` (`stock_app`) | The API | What the group role `stock_api` grants | `db/init/01-rol-de-la-api.sh` |

The group role `stock_api` is defined by migration `V11`. It gathers what the API needs and nothing else:
it cannot create, alter or drop tables, nor `UPDATE` or `DELETE` on `kardex` and `audit_log`,
nor write to the migration history. The permissions are versioned in the migration; the credentials never go into one. This is checked by
`api/adapters-persistence/src/test/.../ApiRolePrivilegesTest.kt`.

Variables of `.env` (not versioned; the template is `.env.example`):

| Variable | Service | Required | Description |
|---|---|---|---|
| `POSTGRES_DB` | all | Yes | Database name |
| `POSTGRES_PORT`, `API_PORT` | `postgres`, `api` | Yes | Host ports |
| `DB_OWNER_USER`, `DB_OWNER_PASSWORD` | `postgres`, `migrate` | Yes | The schema owner |
| `STOCK_API_USER`, `STOCK_API_PASSWORD` | `postgres`, `api` | Yes | The API user |
| `STOCK_JWT_SECRET` | `api` | Yes | Token signing; at least 32 bytes. Keep it across restarts |
| `ADMIN_BOOTSTRAP_PASSWORD` | `api` | Only with no users | First administrator's password; they are required to change it |
| `STOCK_BUSINESS_ZONE` | `api` | No | Time zone of the schedule policies. Default `America/Mexico_City` |
| `STOCK_VERSION` | `api` | No | The version reported by `/v1/health` |

**No secret has a default value.** In Compose, `${VAR:?message}` makes the command fail if
it is missing. In the API, a property without a default value in `application.yml` prevents startup and the
error names the variable.

## 4. Migrations

```
db/
  migrations/   V1__… to V17__…   The versioned migrations
  callbacks/    afterMigrate.sql  Runs after each `migrate`
  init/         01-rol-de-la-api.sh  Creates the API user when the volume is initialized
```

- **Immutable once applied.** Flyway validates the checksum of each file; if someone edits an
  applied migration, `migrate` fails. A correction is a new migration.
- `baselineOnMigrate` **disabled**: a database without history is not considered migrated.
- **The API does not migrate: it checks.** On startup it reads `flyway_schema_history` and refuses to continue if the
  database is behind `REQUIRED_SCHEMA_VERSION` (`api/app/.../config/SchemaGuard.kt`). A test
  checks that this constant matches the last file in `db/migrations`
  (`SchemaVersionTest.kt`).
- `SqlMigrationGuardTest` (`stock-domain/src/jvmTest`) requires the versions to be sequential and
  that the audit log constraints are not lost.

### What V10 to V17 add

| Migration | What it fixes |
|---|---|
| `V10` | The total per product is recalculated with the product locked. Without it, two simultaneous movements lost a write (205 instead of 215) |
| `V11` | The `stock_api` role; the audit log partitions (function with `SECURITY DEFINER`); the hash chain index |
| `V12` | `audit_log_record` accepts the trace of the access decision. Without it, a `CRITICAL` record could not be saved |
| `V13` | The transfer engine: dispatch, receive and cancel move the balance and leave both halves in the ledger |
| `V14` | The link of a reprint with the original job |
| `V15` | What is already received on the receiving line of an invoice. Without it, locating from receiving found zero |
| `V16` | English identifiers: the role keys (`WAREHOUSE_MANAGER`, `WAREHOUSE_OPERATOR`, `OPERATIONS_MANAGER`), the default warehouse code `MAIN` and the special locations `RECEIVING` and `UNASSIGNED`. Existing rows are renamed, so the grants and users keep their role |
| `V17` | Engine function names and `RAISE` messages in English (`receiving_location`, `dispatch_transfer`, `receive_transfer`, `cancel_transfer`…), with the same bodies and arguments |

**The index of V11, measured.** Each audit log write looks up the previous link of the chain
(`ORDER BY sequence_no DESC LIMIT 1`). Without an index, that read every row of every partition
on each audited write, under a global lock. With 20,000 records in the development database:

| | Rows read | Time |
|---|---|---|
| Without the index (V1–V10) | 20,000 | 1.99 ms, and it grows with each record |
| With the index (V11) | 1 | 0.02 ms |

### Audit log partitions

`audit_log` is partitioned by month. `afterMigrate.sql` ensures the current month's and the next 18
on each deployment. If one were still missing, the row falls into `audit_log_default` instead of
failing.

### Check

```sql
SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;
-- Seventeen rows, all with success = true.
```

## 5. The API image

`api/Dockerfile`, in two stages: the first compiles with JDK 25; the second carries only the JRE and the
jar. The process runs as an unprivileged user. The tests are not run inside the image build: they need Docker and run
before, in CI.

The build context is the root of the repository, filtered by `.dockerignore` so that only the API and domain
code go in.

## 6. Startup and verification

```bash
cp .env.example .env          # once; fill in the empty values
docker compose up -d --build
docker compose ps -a          # migrate: «exited (0)»; postgres and api: «healthy»
curl -s http://127.0.0.1:8081/v1/health
```

To compile and test without Docker Compose you only need a JDK 17 or higher: Gradle downloads the
API's JDK 25 (`settings.gradle.kts`, toolchains plugin). The integration tests do need Docker, for
Testcontainers.

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" ./gradlew test
```

## 7. Backups

Logical backup with `pg_dump -Fc` before migrating an environment with data
([operacion/respaldos.md](../operacion/respaldos.md)). The volume `stockflow_pgdata` is persistent;
`docker compose down -v` deletes it.

## 8. What this document does not cover

- Production deployment, domain and certificates: decided separately.
- The `web` service: arrives in phase 11.
- Printers and scanners: they are not part of the Compose environment.
