package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import dev.axzy.stock.backend.catalog.DeleteReasonRequest
import dev.axzy.stock.backend.catalog.location.LocationKind
import dev.axzy.stock.backend.catalog.PageRequest
import dev.axzy.stock.domain.value.ProductCode
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import javax.sql.DataSource

/**
 * Helpers de validacion y de respuesta compartidos por las rutas del catalogo.
 *
 * **Estaban al final de `CatalogRoutes.kt`, entre las rutas de tres recursos.** Se extraen
 * aqui para que el archivo de rutas sea el de rutas y este sea el de validacion.
 *
 * Son `internal` y no `private` porque viven en otro archivo que quien los usa: al partir
 * el archivo, `private` los habria dejado inaccesibles y el compilador lo diria con un
 * `Cannot access ...: it is private in file` por cada llamada.
 */
internal suspend fun ApplicationCall.requirePermission(verifier: PermissionChecker, permission: String) {
    when (val decision = verifier.check(this, permission)) {
        PermissionDecision.Permitted -> Unit

        is PermissionDecision.Denied ->
            if (decision.code == "UNAUTHENTICATED") {
                throw UnauthorizedException(decision.code, decision.message)
            } else {
                throw ForbiddenException(decision.code, decision.message)
            }
    }
}

/** Paginación de la petición, validada contra los campos admitidos del endpoint. */
internal fun ApplicationCall.page(fieldsSortable: Set<String>, defaultOrder: String): PageRequest =
    PageRequest.analyze(
        page = request.queryParameters["page"],
        size = request.queryParameters["size"],
        sort = request.queryParameters["sort"],
        fieldsSortable = fieldsSortable,
        defaultOrder = defaultOrder,
    )

/** Filtro booleano opcional de la query (`active=true`). */
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

/** Parámetro de query numérico opcional. */
internal fun ApplicationCall.parameterLong(name: String): Long? {
    val raw = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return raw.toLongOrNull() ?: throw BadRequestException(
        code = "INVALID_PARAMETER",
        message = "El parámetro '$name' debe ser un número entero. Se recibió '$raw'.",
        details = mapOf(name to raw),
    )
}

/** Identificador de la ruta. Se rechaza con 400 lo que no sea un entero positivo. */
internal fun ApplicationCall.routeId(): Long {
    val raw = parameters["id"]
    val id = raw?.toLongOrNull()
    if (id == null || id <= 0L) {
        throw BadRequestException(
            code = "INVALID_ID",
            message = "El identificador de la ruta debe ser un número entero positivo. " +
                "Se recibió '$raw'.",
            details = mapOf("id" to raw.orEmpty()),
        )
    }
    return id
}

/**
 * Motivo del borrado lógico, obligatorio.
 *
 * Se acepta en el cuerpo (`{"reason": "..."}`), que es lo que dice el contrato,
 * **y también** en la query (`?reason=`). El segundo camino no es un capricho:
 * hay proxies y clientes HTTP que descartan el cuerpo de un `DELETE`, y sin él
 * el usuario no podría borrar un producto.
 */
internal suspend fun ApplicationCall.reasonRequired(): String =
    reasonOptional() ?: throw UnprocessableException(
        code = "REASON_REQUIRED",
        message = "El borrado es lógico y exige un motivo: sin él, dentro de seis meses " +
            "nadie sabrá por qué se desactivó el producto.",
        details = mapOf("reason" to "obligatorio"),
    )

internal suspend fun ApplicationCall.reasonOptional(): String? {
    val fromBody = runCatching { receive<DeleteReasonRequest>() }.getOrNull()?.reason
    val fromQuery = request.queryParameters["reason"]
    return (fromBody ?: fromQuery)?.trim()?.takeIf { it.isNotEmpty() }
}

/** Valida el código de producto con el objeto de valor del dominio. */
internal fun productCode(raw: String): String {
    val result = ProductCode.validate(raw)
    val error = result.exceptionOrNull()
    if (error != null) {
        throw UnprocessableException(
            code = "INVALID_PRODUCT_CODE",
            message = error.message ?: "El código de producto no es válido.",
            details = mapOf("productCode" to raw),
        )
    }
    return result.getOrThrow().value
}

internal fun textRequired(raw: String?, field: String, maximum: Int): String {
    val clean = raw?.trim().orEmpty()
    if (clean.isEmpty()) {
        throw UnprocessableException(
            code = "FIELD_REQUIRED",
            message = "El campo '$field' es obligatorio.",
            details = mapOf(field to "obligatorio"),
        )
    }
    if (clean.length > maximum) {
        throw UnprocessableException(
            code = "FIELD_TOO_LONG",
            message = "El campo '$field' admite $maximum caracteres y se recibieron ${clean.length}.",
            details = mapOf(field to clean.length.toString()),
        )
    }
    return clean
}

/**
 * Normaliza una parte de la terna de ubicación.
 *
 * Se pasa a mayúsculas porque la base impone el formato canónico
 * (`locations_code_canonical`) y el alfabeto real del legacy es `0-9`, `A-Z` y
 * `Ñ`. Normalizar aquí convierte una violación de restricción de PostgreSQL en
 * un 422 que explica qué campo está mal.
 */
internal fun locationPart(raw: String?, field: String): String {
    val clean = raw?.trim()?.uppercase().orEmpty()
    if (clean.isEmpty()) {
        throw UnprocessableException(
            code = "FIELD_REQUIRED",
            message = "El campo '$field' de la ubicación es obligatorio.",
            details = mapOf(field to "obligatorio"),
        )
    }
    if (clean.length > 20) {
        throw UnprocessableException(
            code = "FIELD_TOO_LONG",
            message = "El campo '$field' admite 20 caracteres y se recibieron ${clean.length}.",
            details = mapOf(field to clean.length.toString()),
        )
    }
    if (!LOCATION_ALPHABET.matches(clean)) {
        throw UnprocessableException(
            code = "INVALID_LOCATION_PART",
            message = "El campo '$field' solo admite dígitos y letras en mayúsculas " +
                "(A-Z, Ñ). Se recibió '$clean'.",
            details = mapOf(field to clean),
        )
    }
    return clean
}

internal val LOCATION_ALPHABET = Regex("^[0-9A-ZÑ]+$")

internal fun locationLevels(raw: Int?): Int {
    val levels = raw ?: 1
    if (levels < 1 || levels > 99) {
        throw UnprocessableException(
            code = "INVALID_LEVELS",
            message = "El número de niveles debe estar entre 1 y 99. Se recibió $levels.",
            details = mapOf("levels" to levels.toString()),
        )
    }
    return levels
}

internal fun parseLocationKind(raw: String): LocationKind =
    runCatching { LocationKind.valueOf(raw.trim().uppercase()) }.getOrElse {
        throw UnprocessableException(
            code = "INVALID_LOCATION_KIND",
            message = "El tipo de ubicación debe ser SHELF o RECEIVING. Se recibió '$raw'.",
            details = mapOf("kind" to raw),
        )
    }

internal fun unitUnknown(raw: String) = UnprocessableException(
    code = "UNKNOWN_UNIT_MEASURE",
    message = "La unidad de medida '$raw' no existe en el catálogo de unidades. " +
        "El catálogo es cerrado a propósito: evita el clásico KG/Kg/KGS como tres " +
        "unidades distintas.",
    details = mapOf("unitMeasure" to raw),
)

internal fun productNotFound(id: Long) = NotFoundException(
    message = "El producto $id no existe.",
    code = "PRODUCT_NOT_FOUND",
)

internal fun locationNotFound(id: Long) = NotFoundException(
    message = "La ubicación $id no existe.",
    code = "LOCATION_NOT_FOUND",
)

internal fun locationDuplicated(hall: String, code: String, position: String, warehouse: String) =
    ConflictException(
        code = "DUPLICATE_LOCATION",
        message = "Ya existe la ubicación $hall-$code-$position en la bodega $warehouse.",
    )

/**
 * Lee el `multipart/form-data` del import.
 *
 * `dryRun` por defecto es **`true`**: si el cliente olvida el campo, se simula.
 * El olvido inverso —escribir 1.398 productos cuando se quería simular— no
 * tiene vuelta atrás.
 */
internal suspend fun ApplicationCall.readImportFile(): Pair<ByteArray, Boolean> {
    var dryRun = true
    var content: ByteArray? = null

    receiveMultipart().forEachPart { part ->
        when (part) {
            is PartData.FormItem -> if (part.name == "dryRun") {
                dryRun = when (part.value.trim().lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw BadRequestException(
                        code = "INVALID_DRY_RUN",
                        message = "El campo 'dryRun' debe ser true o false. Se recibió '${part.value}'.",
                        details = mapOf("dryRun" to part.value),
                    )
                }
            }

            is PartData.FileItem -> if (part.name == "file") {
                content = part.provider().readRemaining().readByteArray()
            }

            else -> Unit
        }
        part.dispose()
    }

    val file = content ?: throw BadRequestException(
        code = "FILE_REQUIRED",
        message = "Falta la parte 'file' del formulario. El import es " +
            "multipart/form-data con 'file' (CSV o XLSX) y 'dryRun'.",
    )

    if (file.size > FILE_SIZE_LIMIT) {
        throw BadRequestException(
            code = "FILE_TOO_LARGE",
            message = "El archivo supera ${FILE_SIZE_LIMIT / (1024 * 1024)} MB. " +
                "El catálogo real ocupa unos cientos de KB.",
        )
    }

    return file to dryRun
}

/** 20 MB: holgado para el catálogo real y lejos de un archivo que ahogue el servidor. */
private const val FILE_SIZE_LIMIT = 20L * 1024 * 1024
