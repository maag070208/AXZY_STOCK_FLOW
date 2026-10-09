package dev.axzy.stock.backend.infrastructure.inbound.http.shared

import dev.axzy.stock.backend.application.shared.PageRequest
import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.backend.security.userFromToken
import io.ktor.server.application.ApplicationCall

/*
 * Lo que TODAS las rutas hacen igual: autorizar, identificar a quien llama y leer la
 * paginación. Estaba copiado en cada archivo de rutas, y dos copias de una regla de acceso
 * acaban separándose: así fue como la misma falta de token daba 401 en el catálogo y 403 en
 * el inventario.
 */

/**
 * Exige un permiso y traduce la denegación al estado del contrato (§1.2).
 *
 * `UNAUTHENTICATED` es **401** y todo lo demás **403**, con su código propio. Al cliente le
 * importa la diferencia: ante un 401 manda a iniciar sesión; ante un 403, a pedir un permiso.
 */
internal suspend fun ApplicationCall.requirePermission(checker: PermissionChecker, permission: String) {
    when (val decision = checker.check(this, permission)) {
        PermissionDecision.Permitted -> Unit
        is PermissionDecision.Denied ->
            if (decision.code == "UNAUTHENTICATED") {
                throw UnauthorizedException(decision.code, decision.message)
            } else {
                throw ForbiddenException(decision.code, decision.message)
            }
    }
}

/**
 * Quién ejecuta la acción, **leído del token** y nunca de un parámetro: un `?userId=` lo
 * escribiría cualquiera, y el responsable de un movimiento es un dato de auditoría.
 */
internal fun ApplicationCall.requireUser(message: String = "Se requiere autenticación."): Long =
    userFromToken() ?: throw UnauthorizedException(code = "UNAUTHENTICATED", message = message)

/** Un identificador numérico de la ruta, o `400 INVALID_ID` con el mensaje del recurso. */
internal fun ApplicationCall.pathLong(name: String, message: String): Long =
    parameters[name]?.toLongOrNull() ?: throw BadRequestException("INVALID_ID", message)

// ── Paginación ───────────────────────────────────────────────────────────────

/** Paginación sin orden: `page` desde 0 y `size` de 1 a 200. */
internal data class SimplePage(val page: Int, val size: Int) {
    val offset: Int get() = page * size

    fun totalPages(totalItems: Long): Int = if (size == 0) 0 else ((totalItems + size - 1) / size).toInt()
}

/**
 * Lee `page` y `size`. Un valor fuera de rango se **rechaza** en lugar de recortarse: recortar
 * hace creer al cliente que tiene todos los datos (§1.4).
 */
internal fun ApplicationCall.simplePage(): SimplePage {
    val rawPage = request.queryParameters["page"]
    val rawSize = request.queryParameters["size"]

    val page = rawPage?.trim()?.toIntOrNull()
        ?: if (rawPage.isNullOrBlank()) 0 else throw invalidPagination("page", rawPage)
    val size = rawSize?.trim()?.toIntOrNull()
        ?: if (rawSize.isNullOrBlank()) 50 else throw invalidPagination("size", rawSize)

    if (page < 0) throw invalidPagination("page", rawPage)
    if (size < 1 || size > 200) throw invalidPagination("size", rawSize)

    return SimplePage(page, size)
}

private fun invalidPagination(parameter: String, value: String?) = BadRequestException(
    code = "INVALID_PAGINATION",
    message = "El parámetro '$parameter' no es válido. Se recibió '$value'. " +
        "`page` empieza en 0 y `size` admite de 1 a 200.",
    details = mapOf(parameter to (value ?: "")),
)

/** Paginación con orden, validada contra los campos que admite el endpoint. */
internal fun ApplicationCall.pageRequest(fieldsSortable: Set<String>, defaultOrder: String): PageRequest =
    PageRequest.analyze(
        page = request.queryParameters["page"],
        size = request.queryParameters["size"],
        sort = request.queryParameters["sort"],
        fieldsSortable = fieldsSortable,
        defaultOrder = defaultOrder,
    )

// ── Filtros de la query ──────────────────────────────────────────────────────

/** Filtro booleano opcional (`active=true`). Un valor que no es booleano es un 400, no un filtro ignorado. */
internal fun ApplicationCall.filterBoolean(name: String): Boolean? {
    val raw = request.queryParameters[name]?.trim() ?: return null
    return when (raw.lowercase()) {
        "true" -> true
        "false" -> false
        else -> throw BadRequestException(
            code = "INVALID_PARAMETER",
            message = "El parámetro '$name' debe ser true o false. Se recibió '$raw'.",
            details = mapOf(name to raw),
        )
    }
}

/** Entero opcional de la query. */
internal fun ApplicationCall.parameterLong(name: String): Long? {
    val raw = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return raw.toLongOrNull() ?: throw BadRequestException(
        code = "INVALID_PARAMETER",
        message = "El parámetro '$name' debe ser un número entero. Se recibió '$raw'.",
        details = mapOf(name to raw),
    )
}

/** Un filtro de estado en mayúsculas, validado contra los valores reales del enum de la base. */
internal fun ApplicationCall.statusFilter(allowed: List<String>): String? {
    val status = request.queryParameters["status"]?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (status !in allowed) {
        throw BadRequestException(
            code = "INVALID_PARAMETER",
            message = "El parámetro 'status' no admite el valor '$status'.",
            details = mapOf("status" to status, "allowed" to allowed.joinToString(", ")),
        )
    }
    return status
}
