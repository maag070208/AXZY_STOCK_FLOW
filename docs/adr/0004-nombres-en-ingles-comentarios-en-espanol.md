# ADR-0004 — Names in English, comments in Spanish
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — |
| **Replaced by** | [ADR-0018](0018-todo-en-ingles.md) (comments and documentation); codes by [ADR-0017](0017-identificadores-y-datos-en-ingles.md) |

## Context

Identifiers were written in two languages, and the names in Spanish reached the public
API. `comprobar_proyecciones()` returned the columns `total_en_bd`,
`total_esperado` and `diferencia`; the mapper read them by name, the DTO was serialized with
those properties and the contract published those fields (`V8__english_reconciliation_columns.sql`).

Fixing it was not a simple rename: in PostgreSQL the names of a `RETURNS TABLE` are part
of the function's signature, and a `CREATE OR REPLACE` with other output names fails. It was necessary
to drop and recreate the function, and with it the `GRANT` and the calls
(`V8__english_reconciliation_columns.sql`). The case shows that a name in Spanish in the
database is not cosmetic: it leaks to the client.

## Decision

- Identifiers go in English: variables, methods, constants, classes, tables,
  columns, enum values and error codes ([convenciones.md](../guia/convenciones.md) §1).
- Only comments go in Spanish ([convenciones.md](../guia/convenciones.md) §1).
- The comments, the error messages —the operator reads them—, the `displayName`s (interface text),
  the enum values that are in the database and in the contract, nor the error codes, which are public API, are not translated ([convenciones.md](../guia/convenciones.md) §1).
- Table and column names are in English and are not touched ([convenciones.md](../guia/convenciones.md) §1). A
  migration that renames an output column recreates the function
  (`V8__english_reconciliation_columns.sql`).
- The documentation is written in Spanish (`docs/README.md`), like the comments and the commit
  messages.

## Consequences

The code reads the same for anyone who arrives, and an API field is not translated halfway between the
database and the client.

Discipline is paid at the edges: each new field of a response is reviewed before publishing it, because a
name in Spanish in the database reaches the JSON and then it is no longer an internal detail.

Known and bounded debt: the PL/pgSQL functions of the inventory engine keep their Spanish names —
`aplicar_movimiento_a_ubicacion`, `recalcular_total_producto` and
`registrar_movimiento` in `V6`; `comprobar_proyecciones` in `V6` and `V8`—. It is a conscious exception,
documented in `V8__english_reconciliation_columns.sql`: renaming them forces changing the `GRANT`, the call
and the `COMMENT`, and adds nothing to the HTTP contract. Their output columns are in English.

Operational rules that follow:

- The error catalog uses English identifiers and Spanish messages
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`).
- An enum value is not translated because it travels in the database and in the contract
  (`backend/src/main/resources/db/migration/V3__inventory_domain.sql`).

## Alternatives considered

- **Write everything in Spanish.** It is what the previous model partly did, and the names in
  Spanish ended up in public API fields (`V8__english_reconciliation_columns.sql`).
- **Write everything in English, comments included.** The team thinks in Spanish and the reason
  for a decision gets lost when translated.
- **Translate only the API and leave the database in Spanish.** It leaves two names for the same
  field and forces a mapper that translates on every read.

## References

- [convenciones.md](../guia/convenciones.md) §1, §2 and [general.md](../arquitectura/general.md)
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`
- `backend/src/main/resources/db/migration/V3__inventory_domain.sql`
- `backend/src/main/resources/db/migration/V6__unassigned_location_and_inventory_engine.sql`
- `backend/src/main/resources/db/migration/V8__english_reconciliation_columns.sql`
- `gradle/libs.versions.toml`
- `docs/README.md`
