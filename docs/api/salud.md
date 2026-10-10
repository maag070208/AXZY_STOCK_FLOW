# Health
> **Reference.** The state of the service: the public route that the orchestrator and monitoring
> query. Last verified: 2026-10-09.

The route is in `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/health/HealthController.kt`,
the use case in `api/application/.../health/CheckHealth.kt` and the database query in
`api/adapters-persistence/.../health/JdbcDatabaseProbe.kt`.

## `GET /v1/health` — public, no authentication

```json
{
  "status": "UP", "schemaVersion": "15", "version": "1.0.0",
  "database": { "status": "UP", "latencyMs": 3, "error": null }
}
```

`503` if `status = DOWN`. The state is decided by the **database**: the route runs a real and trivial
query, and only a connection or query failure marks the service as down. A health check that answers "ok" without checking anything says the
system is healthy while the database is down; a health check with false negatives makes
an orchestrator restart healthy instances.

**`schemaVersion` is diagnostic information**, not health: if it cannot be read, the state
remains `UP` and the reason is reported in `database.error`. Two questions that are not the same are
distinguished this way: "does the database respond?" and "what schema version does it have?". The version is read
from `flyway_schema_history`; if the history is missing, `schemaVersion` is `null` and `database.error` explains it.

`database.latencyMs` is the latency of the check, in milliseconds. `version`
comes from `STOCK_VERSION` and is `"dev"` if it is not defined.

It is one of the two only **public** routes of the API; the other is the authentication group
(`POST /v1/auth/login`, `/refresh`, `/logout`).

**Besides the health check, the API checks the schema at startup** (`SchemaGuard.kt`): if the database is
behind the migration the code needs, it does not start. The health check reports the version; the startup requires it.

`database.error` carries only the type of the failure, never the message from the driver, which may include the
database host or user.

## Verification

| File | What it covers |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/HttpContractTest.kt` | the health route, `X-Request-Id` and the error contract |
| `api/app/src/test/kotlin/dev/axzy/stock/app/SchemaVersionTest.kt` | that the required version is the last migration |
