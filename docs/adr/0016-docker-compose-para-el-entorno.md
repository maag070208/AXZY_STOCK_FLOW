# ADR-0016 — Docker Compose for the environment, with migrations as a separate service
> **Normative.** Accepted decision. Details in [arquitectura/infraestructura.md](../arquitectura/infraestructura.md).

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The previous startup required three terminals and scripts that derived credentials from a
local file. A startup with `STOCK_DB_RUN_MIGRATIONS=false` by default left the development
database without migration `V9` for weeks: transfers answered 500 without anyone seeing it until
they were tested.

## Decision

1. **Docker Compose** starts the full environment with one command: PostgreSQL, the migrations service,
   the API and the web.
2. **The migrations are a service of their own** (`migrate`), which runs Flyway and exits. The API
   **depends** on `migrate` finishing successfully (`condition: service_completed_successfully`). The
   API does not migrate at startup: a startup that migrates can surprise, and the previous API's did.
3. **No default values for secrets.** Compose reads the credentials from a `.env` file
   that is not versioned; if a required variable is missing, the service does not start. The
   `.env.example` file documents each variable.
4. **The database exposes its port only to `localhost`**, and the API and the web communicate through the
   internal Compose network.

## Consequences

- A single command starts everything; the same composition serves CI and a test shop-floor machine.
- The migrations become visible as a step of their own: if they fail, the failure is in `migrate`,
  not hidden inside the API startup.
- Compose does not replace production deployment; that is outside this scope and is decided
  separately.

## Discarded alternatives

- **Migrate when the API starts.** It was already tried and left the database out of date without warning.
- **Shell scripts** like the earlier ones in `scripts/`. They depended on the machine and were not
  reproducible in CI.
