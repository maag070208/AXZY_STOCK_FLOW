package dev.axzy.stock.backend.application.inventory

import dev.axzy.stock.backend.application.audit.AuditEntry
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Instant

/**
 * Lo que el inventario necesita de la persistencia.
 *
 * Las consultas se piden sueltas. Lo que mueve existencias se pide dentro de
 * [inTransaction]: todo lo que haga el bloque se confirma o se revierte junto.
 */
interface InventoryRepository {

    fun stockByLocation(filter: StockFilter, offset: Int, limit: Int): List<StockByLocationDto>

    fun countStock(filter: StockFilter): Long

    fun kardex(filter: KardexFilter, offset: Int, limit: Int): List<KardexDto>

    fun countKardex(filter: KardexFilter): Long

    /** Los productos cuya proyección no coincide con el ledger. Vacío es el estado sano. */
    fun divergences(): List<DivergenceDto>

    fun <T> inTransaction(work: InventoryWork.() -> T): T
}

/** Las operaciones disponibles dentro de una transacción de inventario. */
interface InventoryWork {

    /** El id del producto, solo si existe y está activo. */
    fun activeProductId(code: ProductCode): Long?

    /** La ubicación, solo si existe y está activa. Acepta el nombre visible de las especiales. */
    fun activeLocation(code: LocationCode): StockLocation?

    /**
     * El saldo del producto en una ubicación. Se lee **dentro** de la transacción: leerlo fuera
     * abriría una ventana en la que cambia entre la comprobación y el movimiento.
     */
    fun balance(productId: Long, locationId: Long): Quantity

    /** El total del producto, que es la suma derivada de sus ubicaciones. */
    fun total(productId: Long): Quantity

    /**
     * Escribe el movimiento: ajusta las ubicaciones, recalcula el total y añade la fila del
     * kardex. Las tres cosas van juntas; si una falla, no queda ninguna.
     *
     * @return el id de la fila del kardex.
     */
    fun move(movement: StockMovement): Long

    fun audit(entry: AuditEntry): Long
}

data class StockFilter(
    val productCode: String? = null,
    val locationCode: String? = null,
    val onlyWithStock: Boolean = true,
)

data class KardexFilter(
    val productCode: String? = null,
    val type: String? = null,
    val from: Instant? = null,
    val until: Instant? = null,
)

data class StockLocation(val id: Long, val warehouseId: Long)

/**
 * Un movimiento por escribir. Sale de [fromLocationId] y entra en [toLocationId]; un ajuste solo
 * lleva uno de los dos, un traslado lleva los dos.
 */
data class StockMovement(
    val productId: Long,
    val warehouseId: Long,
    val type: MovementType,
    val units: Quantity,
    val fromLocationId: Long? = null,
    val toLocationId: Long? = null,
    val reason: AdjustmentReason? = null,
    val origin: AdjustmentOrigin? = null,
    val userId: Long? = null,
    val approverId: Long? = null,
    /** Clave de idempotencia del cliente. Sin ella el movimiento recibe una propia. */
    val idempotencyKey: String? = null,
    val note: String? = null,
)
