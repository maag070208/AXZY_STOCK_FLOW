package dev.axzy.stock.network.mapper

import dev.axzy.stock.domain.access.AccessUser
import dev.axzy.stock.domain.access.PermissionScope
import dev.axzy.stock.domain.access.Role
import dev.axzy.stock.domain.access.RoleKey
import dev.axzy.stock.network.dto.auth.UserDto

/**
 * Mappers de autenticación: `UserDto` → modelo de acceso del dominio.
 *
 * El objetivo es que el móvil **aplique las mismas reglas de acceso que el
 * backend** (el motor `AccessEngine` vive en `stock-domain` y es el mismo
 * código). Para eso el usuario del contrato tiene que convertirse en el
 * `AccessUser` del dominio sin perder el alcance de cada permiso.
 */
fun UserDto.toAccessUser(): AccessUser = AccessUser(
    id = id,
    username = username,
    active = true,
    // Las claves de rol del contrato (`JEFE_BODEGA`) son las mismas que las del
    // dominio: es el contrato el que se adaptó al modelo, no al revés.
    roles = roles.mapNotNull { key ->
        // Una clave de rol con formato inválido se DESCARTA en lugar de romper el
        // arranque de la sesión: un rol que no se puede construir no concede
        // nada, así que ignorarlo es seguro (por defecto se deniega).
        runCatching { RoleKey.of(key) }.getOrNull()?.let { valid ->
            Role(key = valid, name = key, isSystem = true)
        }
    },
    warehouseIds = warehouses.toSet(),
    // Las excepciones por persona no vienen en este DTO: se resuelven en el
    // servidor al evaluar. Dejarlas vacías es lo correcto, no un olvido.
    exceptions = emptyMap(),
)

/**
 * Los alcances por permiso, ya tipados.
 *
 * **Un alcance desconocido se traduce a `NONE`, y eso es deliberado.** El
 * `PermissionScope` del dominio dice que `NONE` no concede nada y que la
 * escalera es `ALL ⊇ AREA ⊇ OWN ⊇ NONE`. Convertir un valor no reconocido en
 * `ALL` —o en el máximo que se pueda— es exactamente cómo un modelo de permisos
 * se convierte en un agujero: un servidor que añada un alcance nuevo dejaría a
 * todos los clientes antiguos concediendo de más.
 */
fun UserDto.toPermissionScopes(): Map<String, PermissionScope> =
    scopes.mapValues { (_, raw) -> raw.toPermissionScope() }

/** Traduce un alcance del contrato. Desconocido → [PermissionScope.NONE] (denegar). */
fun String.toPermissionScope(): PermissionScope = when (uppercase()) {
    "OWN" -> PermissionScope.OWN
    "AREA" -> PermissionScope.AREA
    "ALL" -> PermissionScope.ALL
    "NONE" -> PermissionScope.NONE
    else -> PermissionScope.NONE
}

/**
 * Los permisos efectivos, ya resueltos como pares `permiso → alcance`.
 *
 * Un permiso listado **sin** entrada en `scopes` se interpreta con alcance
 * mínimo (`OWN`), no con el máximo: el contrato siempre manda los dos campos
 * juntos, y si algún día no lo hace, el lado seguro es el restrictivo.
 */
fun UserDto.toEffectivePermissions(): Map<String, PermissionScope> {
    val byScope = toPermissionScopes()
    return permissions.associateWith { key -> byScope[key] ?: PermissionScope.OWN }
}

/** Indica si el usuario puede ejercer un permiso con al menos el alcance pedido. */
fun UserDto.can(permissionKey: String, required: PermissionScope = PermissionScope.OWN): Boolean {
    val granted = toEffectivePermissions()[permissionKey] ?: PermissionScope.NONE
    return granted.covers(required)
}
