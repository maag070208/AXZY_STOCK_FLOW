# ADR-0006 — RBAC + ABAC, denegar por defecto

> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

En el legacy el rol admin no tenía permisos en base de datos y su acceso se resolvía con un
`if (role === 1)` hardcodeado; además había permisos sembrados que ninguna ruta exigía
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/RoleDefinitions.kt`,
`backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`).

El modelo tampoco podía expresar «puede sobre su bodega» ni «puede solo dentro del horario».
Y había dos listas que debían coincidir —el permiso que exige la ruta y el guard que protege
su pantalla—; cuando se separaron, un usuario con permiso de reportes entraba a la pantalla y
recibía un 403 ([roles-permisos.md](../seguridad/roles-permisos.md)).

## Decisión

- La decisión de acceso sigue este orden: identidad → RBAC (rol → permisos) → ABAC
  (políticas sobre atributos del recurso) → decisión ([roles-permisos.md](../seguridad/roles-permisos.md),
  `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessEngine.kt`).
- **Denegar por defecto.** Sin decisión explícita, se niega (`AccessEngine.kt`,
  `PermissionChecker.kt`).
- **`DENY` gana sobre `ALLOW`**, siempre (`AccessEngine.kt`).
- El alcance es una escalera: `NONE < OWN < AREA < ALL`. Conceder `AREA` no concede `ALL`
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessModel.kt`).
- Cada endpoint exige el permiso que necesita, no el rol. El permiso de la ruta tiene que ser
  el mismo que el del guard que protege su pantalla ([roles-permisos.md](../seguridad/roles-permisos.md) y [api-ktor.md](../arquitectura/api-ktor.md) §1.13).
- ABAC no concede lo que RBAC no concedió: sube el listón, no lo baja
  (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/StockPolicies.kt`).
- El usuario autenticado viaja como argumento explícito (`AccessUser`), nunca en una variable
  thread-local: una thread-local se pierde cuando el trabajo salta de hilo, y con coroutines
  el salto es la norma ([api-ktor.md](../arquitectura/api-ktor.md) §1.13).
- La implementación por defecto del verificador deniega todo (`DenyAllPermissionChecker`): un
  verificador permisivo convierte un olvido de configuración en un agujero de seguridad
  (`PermissionChecker.kt`).
- La matriz de roles declara solo las celdas concedidas; lo ausente es `NONE`, así que un
  permiso nuevo en el catálogo no se concede por olvido (`RoleDefinitions.kt`).
- Toda denegación se registra con sus seis dimensiones (`PermissionChecker.kt`).

## Consecuencias

El modelo expresa el alcance por bodega (`AREA`) y las condiciones por atributo sin
multiplicar roles: no hay roles por bodega (`RoleDefinitions.kt`, `AccessModel.kt`). El
catálogo de permisos y los roles se siembran desde el dominio, no se escriben a mano en SQL
(`backend/src/main/kotlin/dev/axzy/stock/backend/identity/IdentitySeeder.kt`,
`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`).

Se paga que hay dos niveles que razonar: un permiso puede estar concedido y aun así denegarse
por política. Una política `DENY` mal escrita bloquea trabajo legítimo, y por eso el motor
admite modo sombra para observarla antes de activarla (`AccessEngine.kt`).

Reglas operativas que se derivan:

- No se comprueba el rol: se comprueba la clave `recurso.accion` del catálogo.
- Un fallo al auditar una denegación no cambia la decisión: el acceso se sigue denegando
  (`PermissionChecker.kt`).
- Un permiso nuevo se añade al catálogo del dominio y al sembrado, y el rol que deba tenerlo
  se declara explícitamente en la matriz (`PermissionCatalog.kt`, `IdentitySeeder.kt`).
- El alcance `AREA` se resuelve contra las bodegas de la persona, no contra el rol
  (`AccessModel.kt`).

## Alternativas consideradas

- **Comprobar solo el rol.** No expresa alcance ni atributos, y el legacy ya demostró que el
  `if (role === 1)` se rompe al reordenar el sembrado (`RoleDefinitions.kt`,
  `PermissionChecker.kt`).
- **Una ACL por usuario.** No escala, no se audita y hace imposible explicar por qué alguien
  tiene un permiso.
- **Permitir por defecto.** Un olvido de configuración se convierte en acceso abierto; es el
  defecto del legacy que `DenyAllPermissionChecker` corrige (`PermissionChecker.kt`).
- **Dejar que ABAC conceda.** Escalaría privilegios hacia arriba y rompería la garantía de que
  RBAC es el techo (`StockPolicies.kt`).

## Referencias

- `backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`
- `backend/src/main/kotlin/dev/axzy/stock/backend/identity/IdentitySeeder.kt`
- `backend/src/main/resources/db/migration/V1__identity.sql`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessEngine.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/AccessModel.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/PermissionCatalog.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/RoleDefinitions.kt`
- `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/access/StockPolicies.kt`
- [api-ktor.md](../arquitectura/api-ktor.md) §1.13, [auditoria.md](../seguridad/auditoria.md) y [roles-permisos.md](../seguridad/roles-permisos.md)
