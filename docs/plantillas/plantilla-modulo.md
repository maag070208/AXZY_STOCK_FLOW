# M0X — Capability name
> **Normative.** One document per business module. Status and date in the table below. Last verified: YYYY-MM-DD.

| | |
|---|---|
| **Code** | M0X |
| **Folder** | `docs/modulos/M0X-name/` |
| **Documented** | No |
| **Finished** | No |
| **Owner** | — |

## 1. Objective

What business problem this module solves, in two or three sentences. No history or narration of conversations:
what it does today, not how it got here.

## 2. Scope

| Inside | Outside |
|---|---|
| What the module does | What looks like part of this module and is not |

## 3. Business rules

One rule per row. **Each rule cites the file where it lives**, and it lives only there: if
another document needs the rule, it links to it.

| # | Rule | Where it lives |
|---|---|---|
| R1 | | `stock-domain/src/...` |

## 4. Data model

Tables that it uses or owns, and what belongs to whom.

| Table | Migration | What it holds |
|---|---|---|
| | `V…__*.sql` | |

## 5. API

| Method and route | What it does | Permission |
|---|---|---|
| | | |

The detail of each endpoint is in `docs/api/`, not here.

## 6. Permissions

Which permissions govern the module and with which scope (warehouse, location, own).

## 7. Audit log

What this module writes to `audit_log` and in which transaction. If it writes nothing, say so.

## 8. Tests

What is tested and at which level. Link to `docs/pruebas/estrategia-pruebas.md` for the
levels; do not repeat them.

| Level | File | What it covers |
|---|---|---|
| | | |

## 9. Acceptance criteria

Verifiable list: what has to be true for the module to be considered finished.

- [ ]

The **screens** of the module and the **decisions** that govern it do not have their own section:
the screens are in the table of [modulos/README.md](../modulos/README.md) and each decision
is linked to its ADR in the rule that uses it.
