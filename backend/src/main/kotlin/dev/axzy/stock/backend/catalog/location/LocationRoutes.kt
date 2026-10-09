package dev.axzy.stock.backend.catalog.location

import dev.axzy.stock.backend.catalog.JsonQuantity
import dev.axzy.stock.backend.catalog.LocationCreateRequest
import dev.axzy.stock.backend.catalog.LocationUpdateRequest
import dev.axzy.stock.backend.catalog.aDto
import dev.axzy.stock.backend.catalog.aResponse
import dev.axzy.stock.backend.catalog.filterBoolean
import dev.axzy.stock.backend.catalog.locationDuplicated
import dev.axzy.stock.backend.catalog.locationLevels
import dev.axzy.stock.backend.catalog.locationNotFound
import dev.axzy.stock.backend.catalog.locationPart
import dev.axzy.stock.backend.catalog.page
import dev.axzy.stock.backend.catalog.parameterLong
import dev.axzy.stock.backend.catalog.parseLocationKind
import dev.axzy.stock.backend.catalog.product.aDto
import dev.axzy.stock.backend.catalog.reasonOptional
import dev.axzy.stock.backend.catalog.requirePermission
import dev.axzy.stock.backend.catalog.routeId
import dev.axzy.stock.backend.catalog.translatingDbErrors
import dev.axzy.stock.backend.catalog.warehouse.WarehouseRepository
import dev.axzy.stock.backend.catalog.warehouse.aDto
import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.backend.security.PermissionChecker
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

/** Rutas de ubicaciones. */
internal fun Route.locationRoutes(
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
