# ADR-0017 — Identifiers and data in English
> **Normative.** Accepted decision. Last verified: 2026-10-10.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-10 |
| **Replaces** | — |
| **Replaced by** | [ADR-0018](0018-todo-en-ingles.md) (the pending exceptions) |
| **Complements** | [ADR-0004](0004-nombres-en-ingles-comentarios-en-espanol.md) |

## Context

ADR 0004 left two exceptions in Spanish: the inventory engine functions and the
code values that live in the database and in the contract (roles, warehouses, locations). That
left codes such as `OPERADOR_ALMACEN`, `JEFE_BODEGA`, `PRINCIPAL`, `RECEPCION` or `SINUBICAR`
in the API, in the data and in the interface. The project owner asked that everything that is Spanish
move to English.

## Decision

- **Role keys, warehouse codes and location codes go in English**, in the database, in the code, in the
  tests and in the contract:

  | Before | Now |
  |---|---|
  | `OPERADOR_ALMACEN` | `WAREHOUSE_OPERATOR` |
  | `JEFE_BODEGA` | `WAREHOUSE_MANAGER` |
  | `GERENTE_OPERACIONES` | `OPERATIONS_MANAGER` |
  | `PRINCIPAL` | `MAIN` |
  | `RECEPCION` | `RECEIVING` |
  | `SINUBICAR` (visible: `SIN-UBICAR`) | `UNASSIGNED` |
  | `SUCURSAL` (tests only) | `BRANCH` |

- **The visible role names** (`displayName`, `oneLiner`) and the default warehouse go in English. They are interface
  text; the web interface translates them with i18n, and the database stores them in English so that the API answers
  the same as the code.
- **The data changes go in a new migration** (`V16__english_identifiers.sql`). Applied migrations
  are not edited: changing them breaks Flyway's checksum.
- **The role tables are renamed by `id`**, so that the grants and the users already assigned
  keep pointing to the same role.

## Consequences

- A database that already started with the old keys is corrected when `V16` is applied; `SchemaGuard`
  requires version 16.
- **Left in Spanish, for now**: the code comments, the documentation in `docs/`, the error messages the API returns
  (the operator reads them, ADR-0004) and the names of the PL/pgSQL engine functions (`despachar_traslado`,
  `recalcular_total_producto`…). Renaming them requires rewriting their bodies because they call each other; it stays
  as a separate step.
- The web interface does not show these codes in Spanish: it translates each key with i18n.
