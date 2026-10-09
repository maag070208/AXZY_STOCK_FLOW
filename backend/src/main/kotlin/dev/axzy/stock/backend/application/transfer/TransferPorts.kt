package dev.axzy.stock.backend.application.transfer

import dev.axzy.stock.backend.application.audit.AuditEntry
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.transfer.TransferStatus
import java.math.BigDecimal

/**
 * Puerto de persistencia de traslados.
 *
 * La lectura va fuera de transacción. La escritura va **siempre** dentro de [inTransaction]:
 * no hay forma de tocar el inventario sin una transacción que lo englobe, y por eso el caso
 * de uso no puede dejar un movimiento a medias.
 */
interface TransferRepository {
    fun find(id: Long): TransferDetailResponse?
    fun list(filter: TransferFilter, offset: Int, limit: Int): List<TransferResponse>
    fun count(filter: TransferFilter): Long
    fun inTransit(): List<InTransitTransferResponse>

    /** Ejecuta [work] en una única transacción: si lanza, todo se revierte, también el movimiento. */
    fun <T> inTransaction(work: TransferWork.() -> T): T
}

/**
 * Lo que un caso de uso puede hacer dentro de la transacción. Cada operación es un hecho de
 * inventario o de estado: el caso de uso decide el orden y las reglas; la implementación,
 * cómo se escribe.
 */
interface TransferWork {
    fun status(id: Long): TransferStatus?
    fun route(id: Long): TransferRoute
    fun lines(id: Long): List<TransferLine>

    /** Un producto activo por código, o `null` si no existe o está inactivo. */
    fun activeProduct(productCode: String): ActiveProduct?

    /** La ubicación de recepción de una bodega, o `null` si no tiene. */
    fun receivingLocation(warehouseId: Long): Long?

    fun nextCode(): String

    /** Inserta la cabecera en `DRAFT` y sus líneas. Devuelve el id. */
    fun insertDraft(code: String, route: TransferRoute, note: String?, userId: Long?, lines: List<TransferLine>): Long

    /** Saca la mercancía del origen y la deja en tránsito, en la misma transacción. */
    fun moveOut(transferId: Long, route: TransferRoute, line: TransferLine, userId: Long?)

    /** Las filas de tránsito abiertas (no recibidas) de un traslado. */
    fun openTransit(transferId: Long): List<OpenTransit>

    /**
     * Cierra una fila de tránsito llevando la mercancía a [toLocation] de [warehouseId].
     * [toLocation] es `null` solo al devolver un despacho cuya línea no tenía origen.
     */
    fun land(transit: OpenTransit, transferId: Long, warehouseId: Long, toLocation: Long?, userId: Long?)

    fun markDispatched(id: Long, userId: Long?)
    fun markReceived(id: Long, userId: Long?)
    fun markCancelled(id: Long)

    /** Escribe en la bitácora dentro de esta transacción: si la operación se revierte, el registro también. */
    fun audit(entry: AuditEntry): Long
}

/** Filtros del listado. `status` es el texto del enum, ya validado por la ruta. */
data class TransferFilter(val status: String?, val warehouseId: Long?)

data class TransferRoute(val fromWarehouseId: Long, val toWarehouseId: Long)

data class ActiveProduct(val id: Long, val name: String)

data class OpenTransit(
    val transitId: Long,
    val productId: Long,
    val quantity: BigDecimal,
    val fromLocationId: Long?,
)
