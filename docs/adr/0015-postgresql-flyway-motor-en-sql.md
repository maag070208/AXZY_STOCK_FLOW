# ADR-0015 — PostgreSQL 16 with Flyway, and the inventory engine in SQL functions
> **Normative.** Accepted decision.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — |
| **Replaced by** | — |

## Context

The inventory conservation rules (that the balance is never negative, that the total matches
the locations and the transit, that the ledger only grows) live today in PostgreSQL functions
(`aplicar_movimiento_a_ubicacion`, `recalcular_total_producto`, `registrar_movimiento`). That
decision gave the strongest guarantee of the system: any write, wherever it comes from,
goes through the same rule. The review of October 9 found that this guarantee had a concurrency gap
(the total was lost with two simultaneous writes), and that it is fixed in the
database itself with a lock, not in the application code.

## Decision

1. **PostgreSQL 16** as the only database.
2. **Flyway** versions the schema. The migrations are files `V<n>__description.sql` in
   `app` or in an infrastructure module, **immutable once applied**: a correction is
   a new migration (such as `V10`), never an edit of an existing one.
3. **The inventory engine stays in SQL functions.** The API calls those functions with
   `JdbcClient` (Spring), not with an ORM that writes the tables directly. The ORM is not used
   for the inventory.
4. **Explicit locking where there are concurrent writes.** Recalculating the total of a product takes
   `SELECT … FOR UPDATE` on the product. Confirming an order locks the order. See
   [flujos/existencias-y-ajustes.md](../flujos/existencias-y-ajustes.md) and
   [flujos/surtido-y-erp.md](../flujos/surtido-y-erp.md).
5. **Migrations `V1`–`V10` are reused** as the initial Flyway schema (plan D7). They are applied to a new
   database with clean history; they are not edited.

## Consequences

- The critical rules are verified with tests against a real PostgreSQL. A test that is skipped when there is no
  database gives an empty green: the integration tests fail if the database is not there.
- A change to an inventory rule is an SQL migration, with its test, and not only Kotlin code. Whoever
  changes the engine must read the previous migration that defines it.
- The logic in SQL is less portable than in Kotlin. It is accepted because PostgreSQL is the only
  intended target.

## Discarded alternatives

- **Move the inventory rule to Kotlin** (with JPA or plain JDBC). It would leave a rule that any direct
  write to the tables can skip, which is exactly what the functions prevent.
- **Liquibase** instead of Flyway. Both work; Flyway was already in use and the history of
  `V1`–`V10` is kept as it is.
- **An ORM for the inventory** (Hibernate). The ORM's flush and write order do not match the engine, and the
  concurrency would become implicit.
