# Roles and permissions
> **Normative.** Access model: RBAC + ABAC, deny by default. Last verified: 2026-10-09.
> **Reconstruction note (2026-10-10).** Permission matrix in force; the code source is `api/` and `stock-domain/`. The rules remain in force; the code paths it cites belong to the previous system and are remapped to the new code (see [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

The decision is in [ADR 0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md).

## The model

```
Identity → RBAC (role) → ABAC (attributes) → decision
```

It lives in the domain, in `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/`:

| File | What it contains |
|---|---|
| `AccessEngine.kt` | the engine: `decide(user, permissionKey, resource, context)` |
| `AccessModel.kt` | `PermissionScope`, `PolicyEffect`, `ResourceContext`, the model |
| `PermissionCatalog.kt` | the permission catalog |
| `RoleDefinitions.kt` | the roles and their matrix |
| `StockPolicies.kt` | the house ABAC policies |

The API applies it with `AccessEnginePermissionChecker`
(`backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`), and the
authenticated context travels as an explicit object, never in a thread-local
([api-ktor.md](../historico/API-KTOR.md) §1.13).

## The three rules

1. **Deny by default.** Without an explicit decision, it is denied. Only `AccessDecision.Allowed`
   lets it through.
2. **`DENY` beats `ALLOW`**, always, even with a lower priority
   (`PolicyEffect.DENY`, `AccessModel.kt`).
3. **`PermissionScope`: `NONE < OWN < AREA < ALL`.** It is a ladder: `ALL ⊇ AREA ⊇ OWN ⊇
   NONE`. Granting `AREA` does **not** grant `ALL`; treating the scope as a boolean is the
   error that turns a permission model into a hole.

**`AREA` is the key of multi-warehouse:** it means "their warehouse", and which warehouses are theirs is
determined by the assignment of the **person**, not by the role. That avoids creating a role per
warehouse (`WAREHOUSE_MANAGER_NORTH`, `WAREHOUSE_MANAGER_SOUTH`…), which is the classic error. The schema is
born multi-warehouse even if it is operated with one: [ADR 0007](../adr/0007-multi-bodega-desde-el-esquema.md).

## The rule that prevents the unexplained 403

**The permission of a route has to be the same as the one of the guard that protects its screen.**
If the route requires `inventory.reconcile` and the web protects it with `report.view`, a user with
the reports permission enters and receives a `403`. Two lists that must match end up
separating, and the symptom appears only in production with a specific role.

## Where the matrix per endpoint is

**The permission matrix per endpoint does not live here.** It is in
[../api/contrato.md](../api/contrato.md), next to the resource it protects, so that the permission
of a route and its documentation are read in the same place.
