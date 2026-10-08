package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.catalog.ProductChanges
import dev.axzy.stock.backend.catalog.ChangesLocation
import dev.axzy.stock.backend.catalog.JsonQuantity
import dev.axzy.stock.backend.catalog.CatalogImportService
import dev.axzy.stock.backend.catalog.ImportReportResponse
import dev.axzy.stock.backend.catalog.LocationCreateRequest
import dev.axzy.stock.backend.catalog.LocationRepository
import dev.axzy.stock.backend.catalog.LocationUpdateRequest
import dev.axzy.stock.backend.catalog.DeleteReasonRequest
import dev.axzy.stock.backend.catalog.NewLocation
import dev.axzy.stock.backend.catalog.NewProduct
import dev.axzy.stock.backend.catalog.PageRequest
import dev.axzy.stock.backend.catalog.ProductCreateRequest
import dev.axzy.stock.backend.catalog.ProductRepository
import dev.axzy.stock.backend.catalog.ProductResponse
import dev.axzy.stock.backend.catalog.ProductUpdateRequest
import dev.axzy.stock.backend.catalog.LocationKind
import dev.axzy.stock.backend.catalog.UnitOfMeasureRepository
import dev.axzy.stock.backend.catalog.WarehouseListResponse
import dev.axzy.stock.backend.catalog.WarehouseRepository
import dev.axzy.stock.backend.catalog.aDto
import dev.axzy.stock.backend.catalog.aResponse
import dev.axzy.stock.backend.catalog.translatingDbErrors
import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.error.BadRequestException
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.ForbiddenException
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnauthorizedException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.security.DenyAllPermissionChecker
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
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
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DEL CATÁLOGO Y LAS UBICACIONES
 *
 * Implementa la sección §3 del contrato: productos, ubicaciones y bodegas.
 *
 * **Cada ruta exige su permiso de §11 antes de tocar la base.** La comprobación
 * va lo primero en el manejador, no después de validar el cuerpo: así una
 * petición sin autorización no puede distinguir «el recurso no existe» de «no
 * tienes permiso», que es información que no se regala.
 *
 * El verificador llega por parámetro y **por defecto deniega todo**
 * ([DenyAllPermissionChecker]): la identidad la conecta el módulo de identidad
 * pasando su propio [PermissionChecker], sin tocar este archivo.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Instala las rutas del catálogo.
 *
 * Uso desde `Application.kt`, dentro del bloque `routing { }`:
 *
 * ```kotlin
 * catalogRoutes(dataSource, verificadorDeIdentidad)
 * ```
 *
 * Sin el segundo argumento la implementación por defecto **deniega todo** con
 * 401: es preferible un catálogo inaccesible a un catálogo abierto.
 */
fun Route.catalogRoutes(
    dataSource: DataSource,
    permissionChecker: PermissionChecker = DenyAllPermissionChecker,
) {
    // Una sola conexión lógica para todo el módulo. `Database.connect` no abre
    // conexiones: envuelve el pool, y cada transacción toma la suya.
    val db = org.jetbrains.exposed.sql.Database.connect(dataSource)

    val products = ProductRepository(db)
    val locations = LocationRepository(db)
    val warehouses = WarehouseRepository(db)
    val units = UnitOfMeasureRepository(db)
    val importService = CatalogImportService(db, products, units)

    productRoutes(products, units, importService, permissionChecker)
    locationRoutes(locations, warehouses, permissionChecker)
    warehouseRoutes(warehouses, permissionChecker)
}

// ═════════════════════════════════════════════════════════════════════════════
// PRODUCTOS
// ═════════════════════════════════════════════════════════════════════════════

private fun Route.productRoutes(
    products: ProductRepository,
    units: UnitOfMeasureRepository,
    importService: CatalogImportService,
    verifier: PermissionChecker,
) = route("/v1/products") {

    get {
        call.requirePermission(verifier, "product.view")

        val page = call.page(ProductRepository.SORTABLE_FIELDS, ProductRepository.DEFAULT_ORDER)
        val search = call.request.queryParameters["q"]
        val active = call.filterBoolean("active")

        val result = products.list(page, search, active)
        call.respond(HttpStatusCode.OK, result.aResponse { it.aDto() })
    }

    post {
        call.requirePermission(verifier, "product.create")

        val request = call.receive<ProductCreateRequest>()
        val code = productCode(request.productCode)
        val name = textRequired(request.name, "name", 120)
        val unit = units.byCode(request.unitMeasure)
            ?: throw unitUnknown(request.unitMeasure)

        // El prechequeo da el 409 legible en el caso normal; la garantía la
        // tiene el índice único de la base, que es el que no tiene carrera.
        if (products.existsCode(code)) {
            throw ConflictException(
                code = "DUPLICATE_PRODUCT_CODE",
                message = "Ya existe un producto con el código '$code'.",
            )
        }

        val created = translatingDbErrors {
            products.create(
                data = NewProduct(
                    code = code,
                    name = name,
                    description = request.description?.trim()?.takeIf { it.isNotEmpty() },
                    unitMeasureCode = unit.code,
                ),
                unitOfMeasureId = unit.id,
            )
        }

        call.respond(HttpStatusCode.Created, created.aDto())
    }

    // La ruta constante va antes que la de parámetro. Ktor resuelve por
    // prioridad, pero declararla primero deja claro que `/import` no es un id.
    post("/import") {
        call.requirePermission(verifier, "product.import")

        val (content, dryRun) = call.readImportFile()
        val report: ImportReportResponse = importService.importCatalog(content, dryRun)

        // 200 también en el import real: el cuerpo es el informe, y el informe
        // de una simulación y el de una carga difieren en `dryRun` y `persisted`.
        call.respond(HttpStatusCode.OK, report)
    }

    get("/{id}") {
        call.requirePermission(verifier, "product.view")

        val id = call.routeId()
        val product = products.byId(id) ?: throw productNotFound(id)
        call.respond(HttpStatusCode.OK, product.aDto())
    }

    put("/{id}") {
        call.requirePermission(verifier, "product.edit")

        val id = call.routeId()
        val current = products.byId(id) ?: throw productNotFound(id)
        val request = call.receive<ProductUpdateRequest>()

        // Tolerar el código igual permite el ida y vuelta de un cliente que
        // recibe el objeto del GET y lo devuelve modificado. Cambiarlo se
        // rechaza: está impreso en las etiquetas y en los estantes.
        request.productCode?.let { sent ->
            if (sent != current.productCode) {
                throw UnprocessableException(
                    code = "PRODUCT_CODE_IMMUTABLE",
                    message = "El código de producto no se puede cambiar ('${current.productCode}' → " +
                        "'$sent'): es el código de barras impreso en las etiquetas y en los " +
                        "estantes, y cambiarlo obliga a reetiquetar el almacén. Existe una acción " +
                        "específica para reasignar códigos.",
                    details = mapOf("productCode" to sent),
                )
            }
        }

        val name = textRequired(request.name, "name", 120)
        val unit = units.byCode(request.unitMeasure)
            ?: throw unitUnknown(request.unitMeasure)

        val updated = products.update(
            id = id,
            changes = ProductChanges(
                name = name,
                // En un PUT el cuerpo reemplaza al recurso: si no viene
                // descripción, se guarda sin descripción. Es la única forma de
                // borrarla, y omitirla no puede significar «déjala como está»
                // sin inventar un campo nuevo en el contrato.
                description = request.description?.trim()?.takeIf { it.isNotEmpty() },
                unitOfMeasure = unit.code,
            ),
            unitOfMeasureId = unit.id,
        ) ?: throw productNotFound(id)

        call.respond(HttpStatusCode.OK, updated.aDto())
    }

    delete("/{id}") {
        call.requirePermission(verifier, "product.delete")

        val id = call.routeId()
        // El motivo se exige ANTES de mirar el producto: es un requisito de la
        // petición, no del recurso, y el contrato lo pide siempre.
        val reason = call.reasonRequired()

        val product = products.byId(id) ?: throw productNotFound(id)

        if (!product.active) {
            throw ConflictException(
                code = "PRODUCT_ALREADY_INACTIVE",
                message = "El producto '$id' ya estaba desactivado. Volver a desactivarlo " +
                    "sobrescribiría el motivo del primer borrado y perdería la traza.",
            )
        }

        // Un producto con existencias no se desactiva: el stock quedaría en un
        // producto invisible para el listado y el descuadre aparecería en el
        // siguiente conteo. Primero se ajusta a cero (con motivo) o se traslada.
        if (product.stock.signum() != 0) {
            throw ConflictException(
                code = "PRODUCT_HAS_STOCK",
                message = "El producto '${product.productCode}' tiene existencias y no se " +
                    "puede desactivar. Ajusta o traslada el stock primero.",
                details = mapOf("stock" to JsonQuantity.fromDecimal(product.stock).asText()),
            )
        }

        if (!products.disable(id)) {
            // Carrera: otra petición lo desactivó entre la lectura y el UPDATE.
            throw ConflictException(
                code = "PRODUCT_ALREADY_INACTIVE",
                message = "El producto '$id' ya estaba desactivado.",
            )
        }

        // El motivo no tiene columna en `products` y el esquema no se toca: se
        // registra para que quede rastro del porqué. La traza con autor y fecha
        // corresponde a `audit_log`.
        logger.info(
            "Producto {} ({}) desactivado. Motivo declarado: {}",
            id,
            product.productCode,
            reason,
        )

        call.respond(HttpStatusCode.NoContent)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// UBICACIONES
// ═════════════════════════════════════════════════════════════════════════════

private fun Route.locationRoutes(
    locations: LocationRepository,
    warehouses: WarehouseRepository,
    verifier: PermissionChecker,
) = route("/v1/locations") {

    get {
        call.requirePermission(verifier, "location.view")

        val page = call.page(LocationRepository.SORTABLE_FIELDS, LocationRepository.DEFAULT_ORDER)
        val search = call.request.queryParameters["q"]
        val warehouseId = call.parameterLong("warehouseId")
        val type = call.request.queryParameters["kind"]?.let(::parseLocationKind)
        val active = call.filterBoolean("active")

        val result = locations.list(page, search, warehouseId, type?.name, active)
        call.respond(HttpStatusCode.OK, result.aResponse { it.aDto() })
    }

    post {
        call.requirePermission(verifier, "location.create")

        val request = call.receive<LocationCreateRequest>()
        val warehouse = warehouses.byId(request.warehouseId)
            ?: throw UnprocessableException(
                code = "UNKNOWN_WAREHOUSE",
                message = "La bodega ${request.warehouseId} no existe.",
                details = mapOf("warehouseId" to request.warehouseId.toString()),
            )

        val hall = locationPart(request.hall, "hall")
        val code = locationPart(request.code, "code")
        val position = locationPart(request.position, "position")
        val levels = locationLevels(request.levels)
        val type = request.kind?.let(::parseLocationKind) ?: LocationKind.SHELF

        if (locations.tripleExists(warehouse.id, hall, code, position, null)) {
            throw locationDuplicated(hall, code, position, warehouse.code)
        }

        val created = translatingDbErrors {
            locations.create(
                NewLocation(
                    warehouseId = warehouse.id,
                    hall = hall,
                    code = code,
                    position = position,
                    levels = levels,
                    kind = type.name,
                )
            )
        }

        call.respond(HttpStatusCode.Created, created.aDto())
    }

    get("/{id}") {
        call.requirePermission(verifier, "location.view")

        val id = call.routeId()
        val location = locations.byId(id) ?: throw locationNotFound(id)
        call.respond(HttpStatusCode.OK, location.aDto())
    }

    put("/{id}") {
        call.requirePermission(verifier, "location.edit")

        val id = call.routeId()
        val current = locations.byId(id) ?: throw locationNotFound(id)
        val request = call.receive<LocationUpdateRequest>()

        request.warehouseId?.let { sent ->
            if (sent != current.warehouseId) {
                throw UnprocessableException(
                    code = "WAREHOUSE_IMMUTABLE",
                    message = "Una ubicación no se puede mover de bodega: el stock que " +
                        "contiene cambiaría de bodega sin ningún movimiento de inventario. " +
                        "Crea la ubicación en la bodega de destino y traslada el stock.",
                    details = mapOf("warehouseId" to sent.toString()),
                )
            }
        }

        val hall = request.hall?.let { locationPart(it, "hall") }
        val code = request.code?.let { locationPart(it, "code") }
        val position = request.position?.let { locationPart(it, "position") }
        val levels = request.levels?.let { locationLevels(it) }
        val type = request.kind?.let(::parseLocationKind)

        // La terna resultante se comprueba completa: cambiar solo la posición
        // puede chocar con una ubicación que ya existe.
        val newTriple = Triple(
            hall ?: current.hall,
            code ?: current.code,
            position ?: current.position,
        )
        val changesTriple = newTriple != Triple(current.hall, current.code, current.position)
        if (changesTriple &&
            locations.tripleExists(current.warehouseId, newTriple.first, newTriple.second, newTriple.third, id)
        ) {
            throw locationDuplicated(newTriple.first, newTriple.second, newTriple.third, current.warehouseCode)
        }

        val updated = translatingDbErrors {
            locations.update(
                id = id,
                changes = ChangesLocation(
                    hall = hall,
                    code = code,
                    position = position,
                    levels = levels,
                    kind = type?.name,
                    // `active` se puede volver a poner en true: sin esto, una
                    // ubicación desactivada por error solo se recuperaría por SQL.
                    active = request.active,
                ),
            )
        } ?: throw locationNotFound(id)

        call.respond(HttpStatusCode.OK, updated.aDto())
    }

    delete("/{id}") {
        call.requirePermission(verifier, "location.delete")

        val id = call.routeId()
        // El motivo es opcional aquí (el contrato solo lo exige para productos),
        // pero si viene se registra: es la diferencia entre saber por qué se
        // apagó una ubicación y no saberlo.
        val reason = call.reasonOptional()

        val location = locations.byId(id) ?: throw locationNotFound(id)

        if (!location.active) {
            throw ConflictException(
                code = "LOCATION_ALREADY_INACTIVE",
                message = "La ubicación '$id' ya estaba desactivada.",
            )
        }

        if (location.kind == LocationKind.RECEIVING.name) {
            throw ConflictException(
                code = "RECEIVING_LOCATION_PROTECTED",
                message = "La ubicación de recepción no se puede desactivar: es donde " +
                    "vive lo recibido y no ubicado, y sin ella la recepción no tiene destino.",
            )
        }

        val stock = locations.totalStockOf(id)
        if (stock.signum() != 0) {
            throw ConflictException(
                code = "LOCATION_HAS_STOCK",
                message = "La ubicación '${location.displayName}' tiene existencias y no se " +
                    "puede desactivar. Traslada el stock primero.",
                details = mapOf("stock" to JsonQuantity.fromDecimal(stock).asText()),
            )
        }

        if (!locations.disable(id)) {
            throw ConflictException(
                code = "LOCATION_ALREADY_INACTIVE",
                message = "La ubicación '$id' ya estaba desactivada.",
            )
        }

        logger.info("Ubicación {} ({}) desactivada. Motivo declarado: {}", id, location.displayName, reason)

        call.respond(HttpStatusCode.NoContent)
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// BODEGAS
// ═════════════════════════════════════════════════════════════════════════════

private fun Route.warehouseRoutes(
    warehouses: WarehouseRepository,
    verifier: PermissionChecker,
) = route("/v1/warehouses") {

    get {
        // §11 no lista `GET /v1/warehouses`; §1.4 dice que este catálogo exige
        // `location.view`, que es el permiso que el dominio define para ver el
        // almacén físico. Dejarla sin permiso sería repetir el defecto del
        // legacy: rutas que parecían protegidas y no lo estaban.
        call.requirePermission(verifier, "location.view")

        // Sin paginación, y es deliberado: §1.4 exceptúa las bodegas porque son
        // un catálogo cerrado y diminuto que la interfaz necesita COMPLETO para
        // poblar el desplegable. Devolver solo la primera página obligaría al
        // cliente a pedirlas todas antes de poder dibujar el `select`.
        val search = call.request.queryParameters["q"]
        val active = call.filterBoolean("active")

        val warehousesFound = warehouses.listAll(search, active)
        call.respond(HttpStatusCode.OK, WarehouseListResponse(warehousesFound.map { it.aDto() }))
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// AYUDANTES
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Exige el permiso de §11 y traduce la denegación al código correcto.
 *
 * `UNAUTHENTICATED` es 401 (falta identidad, hay que iniciar sesión) y todo lo
 * demás es 403 con su código propio: `PERMISSION_DENIED`, `SCOPE_INSUFFICIENT`,
 * `POLICY_DENIED` o `SEGREGATION_OF_DUTIES`. Colapsarlos en un 403 genérico es
 * el defecto del legacy: el usuario no sabe si pedir un permiso, esperar o
 * llamar a otra persona.
 */
private suspend fun ApplicationCall.requirePermission(verifier: PermissionChecker, permission: String) {
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
private fun ApplicationCall.page(fieldsSortable: Set<String>, defaultOrder: String): PageRequest =
    PageRequest.analyze(
        page = request.queryParameters["page"],
        size = request.queryParameters["size"],
        sort = request.queryParameters["sort"],
        fieldsSortable = fieldsSortable,
        defaultOrder = defaultOrder,
    )

/** Filtro booleano opcional de la query (`active=true`). */
private fun ApplicationCall.filterBoolean(name: String): Boolean? {
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
private fun ApplicationCall.parameterLong(name: String): Long? {
    val raw = request.queryParameters[name]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return raw.toLongOrNull() ?: throw BadRequestException(
        code = "INVALID_PARAMETER",
        message = "El parámetro '$name' debe ser un número entero. Se recibió '$raw'.",
        details = mapOf(name to raw),
    )
}

/** Identificador de la ruta. Se rechaza con 400 lo que no sea un entero positivo. */
private fun ApplicationCall.routeId(): Long {
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
private suspend fun ApplicationCall.reasonRequired(): String =
    reasonOptional() ?: throw UnprocessableException(
        code = "REASON_REQUIRED",
        message = "El borrado es lógico y exige un motivo: sin él, dentro de seis meses " +
            "nadie sabrá por qué se desactivó el producto.",
        details = mapOf("reason" to "obligatorio"),
    )

private suspend fun ApplicationCall.reasonOptional(): String? {
    val fromBody = runCatching { receive<DeleteReasonRequest>() }.getOrNull()?.reason
    val fromQuery = request.queryParameters["reason"]
    return (fromBody ?: fromQuery)?.trim()?.takeIf { it.isNotEmpty() }
}

/** Valida el código de producto con el objeto de valor del dominio. */
private fun productCode(raw: String): String {
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

private fun textRequired(raw: String?, field: String, maximum: Int): String {
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
private fun locationPart(raw: String?, field: String): String {
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

private val LOCATION_ALPHABET = Regex("^[0-9A-ZÑ]+$")

private fun locationLevels(raw: Int?): Int {
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

private fun parseLocationKind(raw: String): LocationKind =
    runCatching { LocationKind.valueOf(raw.trim().uppercase()) }.getOrElse {
        throw UnprocessableException(
            code = "INVALID_LOCATION_KIND",
            message = "El tipo de ubicación debe ser SHELF o RECEIVING. Se recibió '$raw'.",
            details = mapOf("kind" to raw),
        )
    }

private fun unitUnknown(raw: String) = UnprocessableException(
    code = "UNKNOWN_UNIT_MEASURE",
    message = "La unidad de medida '$raw' no existe en el catálogo de unidades. " +
        "El catálogo es cerrado a propósito: evita el clásico KG/Kg/KGS como tres " +
        "unidades distintas.",
    details = mapOf("unitMeasure" to raw),
)

private fun productNotFound(id: Long) = NotFoundException(
    message = "El producto $id no existe.",
    code = "PRODUCT_NOT_FOUND",
)

private fun locationNotFound(id: Long) = NotFoundException(
    message = "La ubicación $id no existe.",
    code = "LOCATION_NOT_FOUND",
)

private fun locationDuplicated(hall: String, code: String, position: String, warehouse: String) =
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
private suspend fun ApplicationCall.readImportFile(): Pair<ByteArray, Boolean> {
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
