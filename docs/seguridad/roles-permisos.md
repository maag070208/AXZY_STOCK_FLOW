# Roles y permisos

> **Normativo.** Modelo de acceso: RBAC + ABAC, denegación por defecto. Última verificación: 2026-10-09.

La decisión está en [ADR 0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md).

## El modelo

```
Identidad → RBAC (rol) → ABAC (atributos) → decisión
```

Vive en el dominio, en `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/`:

| Archivo | Qué contiene |
|---|---|
| `AccessEngine.kt` | el motor: `decide(user, permissionKey, resource, context)` |
| `AccessModel.kt` | `PermissionScope`, `PolicyEffect`, `ResourceContext`, el modelo |
| `PermissionCatalog.kt` | el catálogo de permisos |
| `RoleDefinitions.kt` | los roles y su matriz |
| `StockPolicies.kt` | las políticas ABAC de la casa |

La API lo aplica con `AccessEnginePermissionChecker`
(`backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`), y el
contexto autenticado viaja como objeto explícito, nunca en una thread-local
([api-ktor.md](../arquitectura/api-ktor.md) §1.13).

## Las tres reglas

1. **Denegar por defecto.** Sin decisión explícita, se niega. Solo `AccessDecision.Allowed`
   deja pasar.
2. **`DENY` gana sobre `ALLOW`**, siempre, incluso con prioridad menor
   (`PolicyEffect.DENY`, `AccessModel.kt`).
3. **`PermissionScope`: `NONE < OWN < AREA < ALL`.** Es una escalera: `ALL ⊇ AREA ⊇ OWN ⊇
   NONE`. Conceder `AREA` **no** concede `ALL`; tratar el alcance como un booleano es el
   error que convierte un modelo de permisos en un agujero.

**`AREA` es la clave de la multi-bodega:** significa «su bodega», y qué bodegas son suyas se
determina por la asignación de la **persona**, no por el rol. Eso evita crear un rol por
bodega (`JEFE_BODEGA_NORTE`, `JEFE_BODEGA_SUR`…), que es el error clásico. El esquema nace
multi-bodega aunque se opere con una: [ADR 0007](../adr/0007-multi-bodega-desde-el-esquema.md).

## La regla que impide el 403 inexplicable

**El permiso de una ruta tiene que ser el mismo que el del guard que protege su pantalla.**
Si la ruta exige `inventory.reconcile` y la web la protege con `report.view`, un usuario con
permiso de reportes entra y recibe un `403`. Dos listas que deben coincidir acaban
separándose, y el síntoma aparece solo en producción con un rol concreto.

## Dónde está la matriz por endpoint

**La matriz de permisos por endpoint no vive aquí.** Está en
[../api/contrato.md](../api/contrato.md), junto al recurso que protege, para que el permiso
de una ruta y su documentación se lean en el mismo sitio.
