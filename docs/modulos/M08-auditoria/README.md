# M08 — Audit: log and traceability
> **Normative.** Last verified: 2026-10-10 (M08 closed).

| | |
|---|---|
| **Code** | M08 |
| **Folder** | `docs/modulos/M08-auditoria/` |
| **Documented** | Yes |
| **Finished (backend)** | Yes |
| **Web** | `AuditPage` |
| **App** | No screen |

## 1. Objective

Answer who did what, when and why, for any write in the system.

## 2. Scope

| Inside | Outside |
|---|---|
| Record of each write, with previous and new state | Technical metrics (they are not audit) |
| Query of the log | Export to external systems |
| Immutability through triggers | |

## 3. Business rules

| # | Rule | Where it lives |
|---|---|---|
| R1 | Every write goes through the log | [seguridad/auditoria.md](../../seguridad/auditoria.md) |
| R2 | The log does not accept `UPDATE` nor `DELETE` | Triggers in `V2__audit_and_traceability.sql` |
| R3 | The integrity chain can be verified | `V7__audit_write_and_verify.sql` |
| R4 | Retention by level: critical permanent, relevant 3 years, access 1 year, technical 30 days | [seguridad/auditoria.md](../../seguridad/auditoria.md) |

## 4. Data model

| Table | Migration | What it holds |
|---|---|---|
| `audit_log`, `audit_log_default` | `V2` | Log and its default partition |
| `access_counter` | `V2` | Aggregated accesses (level 3) |
| `config_versions` | `V2` | Versions of the access configuration |

## 5. API

Contract in [api/auditoria.md](../../api/auditoria.md): `GET /v1/audit/log`, with filters
by entity, actor, action, level, result, channel and business date range, and
pagination with the total calculated with the same filter. There is no `PUT` nor `DELETE`, on purpose:
the table is immutable through triggers.

**The query is also recorded** (`AUDIT_QUERIED`, level `ACCESS`), and it is recorded **after** reading: if the record failed, the query has already been made, and what must not
remain is the fact without a trace.

## 6. Permissions

`audit.view`. See [seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Audit log

This module is the log. Its own query is also recorded as access.

## 8. Tests

`AuditTest` in `stock-domain`. Immutability and coverage tests in the backend
([seguridad/auditoria.md](../../seguridad/auditoria.md) §9).

## 9. Acceptance criteria

- [x] The log does not accept modifications (triggers of `V2`).
- [x] Log with channel, permission and filter, tested in the browser (`web/e2e/flow.spec.ts`).
- [ ] Verification of the integrity chain from the web. There is no test.
