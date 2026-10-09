package dev.axzy.stock.backend.catalog.location

import dev.axzy.stock.backend.application.shared.Page
import dev.axzy.stock.backend.application.shared.PageRequest
import dev.axzy.stock.backend.catalog.JsonQuantity
import dev.axzy.stock.backend.catalog.locationDuplicated
import dev.axzy.stock.backend.catalog.locationLevels
import dev.axzy.stock.backend.catalog.locationNotFound
import dev.axzy.stock.backend.catalog.locationPart
import dev.axzy.stock.backend.catalog.parseLocationKind
import dev.axzy.stock.backend.catalog.translatingDbErrors
import dev.axzy.stock.backend.catalog.warehouse.WarehouseRepository
import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.UnprocessableException

/**
 * Reglas del catálogo de ubicaciones. Las rutas traducen HTTP a estos métodos.
 */
class LocationService(
    private val locations: LocationRepository,
    private val warehouses: WarehouseRepository,
) {

    fun list(page: PageRequest, search: String?, warehouseId: Long?, kind: String?, active: Boolean?): Page<LocationRecord> =
        locations.list(page, search, warehouseId, kind, active)

    fun byId(id: Long): LocationRecord = locations.byId(id) ?: throw locationNotFound(id)

    fun create(
        warehouseId: Long,
        hall: String,
        code: String,
        position: String,
        levels: Int?,
        kind: String?,
    ): LocationRecord {
        val warehouse = warehouses.byId(warehouseId)
            ?: throw UnprocessableException(
                code = "UNKNOWN_WAREHOUSE",
                message = "La bodega $warehouseId no existe.",
                details = mapOf("warehouseId" to warehouseId.toString()),
            )

        val validHall = locationPart(hall, "hall")
        val validCode = locationPart(code, "code")
        val validPosition = locationPart(position, "position")
        val validLevels = locationLevels(levels)
        val type = kind?.let(::parseLocationKind) ?: LocationKind.SHELF

        if (locations.tripleExists(warehouse.id, validHall, validCode, validPosition, null)) {
            throw locationDuplicated(validHall, validCode, validPosition, warehouse.code)
        }

        return translatingDbErrors {
            locations.create(
                NewLocation(
                    warehouseId = warehouse.id,
                    hall = validHall,
                    code = validCode,
                    position = validPosition,
                    levels = validLevels,
                    kind = type.name,
                ),
            )
        }
    }

    /**
     * [sentWarehouseId] se rechaza si cambia: una ubicación no cambia de bodega sin movimiento
     * de inventario. [active] permite reactivar: sin esto, una ubicación desactivada por error
     * solo se recuperaría por SQL.
     */
    fun update(
        id: Long,
        sentWarehouseId: Long?,
        hall: String?,
        code: String?,
        position: String?,
        levels: Int?,
        kind: String?,
        active: Boolean?,
    ): LocationRecord {
        val current = locations.byId(id) ?: throw locationNotFound(id)

        sentWarehouseId?.let { sent ->
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

        val validHall = hall?.let { locationPart(it, "hall") }
        val validCode = code?.let { locationPart(it, "code") }
        val validPosition = position?.let { locationPart(it, "position") }
        val validLevels = levels?.let { locationLevels(it) }
        val type = kind?.let(::parseLocationKind)

        // La terna resultante se comprueba completa: cambiar solo la posición puede chocar con
        // una ubicación que ya existe.
        val newTriple = Triple(
            validHall ?: current.hall,
            validCode ?: current.code,
            validPosition ?: current.position,
        )
        val changesTriple = newTriple != Triple(current.hall, current.code, current.position)
        if (changesTriple &&
            locations.tripleExists(current.warehouseId, newTriple.first, newTriple.second, newTriple.third, id)
        ) {
            throw locationDuplicated(newTriple.first, newTriple.second, newTriple.third, current.warehouseCode)
        }

        return translatingDbErrors {
            locations.update(
                id = id,
                changes = ChangesLocation(
                    hall = validHall,
                    code = validCode,
                    position = validPosition,
                    levels = validLevels,
                    kind = type?.name,
                    active = active,
                ),
            )
        } ?: throw locationNotFound(id)
    }

    /**
     * Desactiva la ubicación. Las reglas van aquí: ya inactiva, la de recepción (protegida) y las
     * existencias.
     */
    fun disable(id: Long, reason: String?) {
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
    }
}
