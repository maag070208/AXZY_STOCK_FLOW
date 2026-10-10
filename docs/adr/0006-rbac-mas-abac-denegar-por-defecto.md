# ADR-0006 — RBAC + ABAC, deny by default
> **Normative.** Accepted decision. Last verified: 2026-10-09.

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-10-09 |
| **Replaces** | — |
| **Replaced by** | — |

## Context

In the legacy the admin role had no database permissions and its access was resolved with a
hardcoded `if (role === 1)`; there were also seeded permissions that no route required
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/RoleDefinitions.kt`,
`backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`).

The model also could not express "can act on its warehouse" or "can act only within the schedule".
And there were two lists that had to match —the permission the route requires and the guard that protects its screen—;
when they separated, a user with the reports permission entered the screen and
received a 403 ([roles-permisos.md](../seguridad/roles-permisos.md)).

## Decision

- The access decision follows this order: identity → RBAC (role → permissions) → ABAC
  (policies over resource attributes) → decision ([roles-permisos.md](../seguridad/roles-permisos.md),
  `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessEngine.kt`).
- **Deny by default.** Without an explicit decision, it is denied (`AccessEngine.kt`,
  `PermissionChecker.kt`).
- **`DENY` wins over `ALLOW`**, always (`AccessEngine.kt`).
- The scope is a ladder: `NONE < OWN < AREA < ALL`. Granting `AREA` does not grant `ALL`
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessModel.kt`).
- Each endpoint requires the permission it needs, not the role. The route's permission has to be
  the same as the guard's that protects its screen ([roles-permisos.md](../seguridad/roles-permisos.md) and [api-ktor.md](../historico/API-KTOR.md) §1.13).
- ABAC does not grant what RBAC did not grant: it raises the bar, it does not lower it
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/StockPolicies.kt`).
- The authenticated user travels as an explicit argument (`AccessUser`), never in a thread-local variable:
  a thread-local is lost when the work jumps threads, and with coroutines the jump is the norm
  ([api-ktor.md](../historico/API-KTOR.md) §1.13).
- The default implementation of the checker denies everything (`DenyAllPermissionChecker`): a permissive
  checker turns a configuration oversight into a security hole
  (`PermissionChecker.kt`).
- The role matrix declares only the granted cells; what is absent is `NONE`, so a new permission
  in the catalog is not granted by oversight (`RoleDefinitions.kt`).
- Every denial is recorded with its six dimensions (`PermissionChecker.kt`).

## Consequences

The model expresses scope per warehouse (`AREA`) and attribute conditions without
multiplying roles: there are no roles per warehouse (`RoleDefinitions.kt`, `AccessModel.kt`). The
permission catalog and the roles are seeded from the domain, not written by hand in SQL
(`backend/src/main/kotlin/dev/axzy/stock/backend/identity/IdentitySeeder.kt`,
`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`).

The price is that there are two levels to reason about: a permission can be granted and still be denied
by policy. A badly written `DENY` policy blocks legitimate work, and that is why the engine
supports shadow mode to observe it before activating it (`AccessEngine.kt`).

Operational rules that follow:

- The role is not checked: the key `recurso.accion` of the catalog is checked.
- A failure to audit a denial does not change the decision: access is still denied
  (`PermissionChecker.kt`).
- A new permission is added to the domain catalog and to the seed, and the role that must have it
  is declared explicitly in the matrix (`PermissionCatalog.kt`, `IdentitySeeder.kt`).
- The `AREA` scope is resolved against the person's warehouses, not against the role
  (`AccessModel.kt`).

## Alternatives considered

- **Check only the role.** It does not express scope or attributes, and the legacy already showed that
  the `if (role === 1)` breaks when the seed is reordered (`RoleDefinitions.kt`,
  `PermissionChecker.kt`).
- **A per-user ACL.** It does not scale, it is not audited and it makes it impossible to explain why someone
  has a permission.
- **Allow by default.** A configuration oversight becomes open access; it is the legacy defect that
  `DenyAllPermissionChecker` fixes (`PermissionChecker.kt`).
- **Let ABAC grant.** It would escalate privileges upward and break the guarantee that RBAC is the ceiling
  (`StockPolicies.kt`).

## References

- `backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/identity/IdentitySeeder.kt`
- `backend/src/main/resources/db/migration/V1__identity.sql`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessEngine.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessModel.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/RoleDefinitions.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/StockPolicies.kt`
- [api-ktor.md](../historico/API-KTOR.md) §1.13, [auditoria.md](../seguridad/auditoria.md) and [roles-permisos.md](../seguridad/roles-permisos.md)
