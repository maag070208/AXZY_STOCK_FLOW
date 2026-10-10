# OWASP and the legacy findings
> **Reference.** Which security finding the legacy had, which control replaces it in the new system
> and what is verified. What has not been checked is marked as such. Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** Review of the previous code. The vulnerabilities found are checked again in the new code. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

## Legacy findings and their status

Findings from [historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md) §2.2-ter.

| # | Finding in `BASE/` | Control in the new system | Status |
|---|---|---|---|
| S1 | Three routes without authentication (`GET /kardo`, `PATCH /prints`, `DELETE /invoices/detail/:id`) | System routes use a service key ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)); line deletion does not exist | Verified by reading the routes. No documented automatic sweep of 401/403 |
| S2 | `role === 1` as a permission bypass in four places | Deny by default; the permission comes from the catalog, not from a role number ([ADR-0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md)) | Verified: the text remains only in comments of `identity/IdentityTables.kt` and `identity/IdentityRepository.kt` |
| S3 | JWT of 24 h, no logout or revocation | Rotated refresh with reuse detection (`TOKEN_REUSED`); the family is revoked | Verified by test name: `SessionModelTest` |
| S4 | SQL by concatenation (`kardo.service.ts:25`) | Data access with Exposed over the database; no SQL built as text | Not verified with a full search of raw SQL in the backend |
| S5 | Credentials in the code (seed, external validator, SQL Server password in `docker-compose.yml`) | The initial administrator is generated or passed by variable; there is no password in the code | See [operacion/entornos.md](../historico/OPERACION-ENTORNOS-ANTERIOR.md). The `infra/` of the new repo does not contain the legacy password |
| S6 | `console.log` with configuration and request bodies | Structured logging of the API | Not verified |
| S7 | Authorization errors returned as 401 | 401 only for invalid identity; 403 for permissions | Verified by the error catalog (`docs/api/errores.md`) |
| S8 | No attempt limit on login | Lock after attempts (`login_attempts`, `ACCOUNT_LOCKED`) and `RATE_LIMITED` | Verified by existence in `identity/AuthRoutes.kt` and `V4__sessions.sql`. The thresholds are not documented |

## Controls that are not legacy findings

- **Immutable audit log.** The log does not accept `UPDATE` nor `DELETE` ([seguridad/auditoria.md](auditoria.md)).
- **Segregation of duties.** Whoever captures a discrepancy does not approve it ([ADR-0008](../adr/0008-discrepancias-y-merma.md)).
- **Secrets outside the repo.** The signing key and the initial password are generated or come from environment variables ([operacion/entornos.md](../historico/OPERACION-ENTORNOS-ANTERIOR.md)).

## Pending

- Automatic authentication sweep over all the `/v1` routes, with its test.
- Full search for SQL built as text in `backend/src/main`.
- Review of the logs to confirm they do not print sensitive data (S6).
- Lock thresholds and request limits, documented with their values.
