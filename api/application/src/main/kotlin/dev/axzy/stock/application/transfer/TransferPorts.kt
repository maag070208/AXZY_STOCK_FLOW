package dev.axzy.stock.application.transfer

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Instant

/**
 * Los puertos de traslados (M05, docs/api/traslados.md).
 *
 * **El movimiento lo hace el motor SQL** (`dispatch_transfer`, `receive_transfer`,
 * `cancel_transfer`, migración V13). Este puerto solo lo llama y lee: si el
 * adaptador moviera el saldo a mano sería la segunda ruta de escritura al inventario
 * que el plan prohíbe.
 */

data class TransferHeader(
    val id: Long,
    val code: String,
    val fromWarehouseId: Long,
    val fromWarehouseCode: String,
    val toWarehouseId: Long,
    val toWarehouseCode: String,
    val status: String,
    val lineCount: Int,
    val totalUnits: Quantity,
    val note: String?,
    val dispatchedBy: Long?,
    val dispatchedByName: String?,
    val dispatchedAt: Instant?,
    val receivedBy: Long?,
    val receivedByName: String?,
    val receivedAt: Instant?,
)

data class TransferLineView(val line: TransferLine)

data class TransferDetail(val header: TransferHeader, val lines: List<TransferLineView>)

data class TransferFilter(val status: String?, val warehouseId: Long?)

/** Una línea tal como llega en el alta. */
data class NewTransferLine(
    val productCode: String?,
    val quantity: Quantity?,
    val fromLocationId: Long?,
    val note: String?,
)

data class NewTransfer(
    val fromWarehouseId: Long?,
    val toWarehouseId: Long?,
    val note: String?,
    val lines: List<NewTransferLine>,
)

/** Una línea con su producto ya resuelto. */
data class ResolvedTransferLine(
    val productId: Long,
    val productCode: ProductCode,
    val quantity: Quantity,
    val fromLocationId: Long?,
    val note: String?,
)

data class TransferDraft(
    val code: String,
    val fromWarehouseId: Long,
    val toWarehouseId: Long,
    val note: String?,
)

sealed interface InsertTransferResult {
    data class Inserted(val id: Long) : InsertTransferResult

    data object DuplicateCode : InsertTransferResult
}

/** Un traslado despachado y no recibido, para el informe de tránsito. */
data class InTransitRow(
    val id: Long,
    val code: String,
    val fromWarehouseCode: String,
    val toWarehouseCode: String,
    val status: String,
    val lineCount: Int,
    val totalUnits: Quantity,
    val dispatchedAt: Instant?,
    /**
     * Los días que lleva en tránsito, **contados por el servidor**: con el reloj del
     * equipo, el mismo dato daría un número distinto en cada máquina.
     */
    val daysInTransit: Long,
    val dispatchedBy: Long?,
    val dispatchedByName: String?,
)

/** Lo que el traslado necesita saber de un producto y de una bodega. */
data class TransferProduct(val id: Long, val productCode: String, val productName: String)

data class TransferWarehouse(val id: Long, val code: String)

interface Transfers {
    fun page(filter: TransferFilter, request: PageRequest): Page<TransferHeader>

    fun detail(id: Long): TransferDetail?

    fun numberExists(code: String): Boolean

    /** El correlativo del año: `TR-AAAA-NNNN`. */
    fun nextCode(year: Int): String

    fun insert(draft: TransferDraft, lines: List<ResolvedTransferLine>): InsertTransferResult

    /** Llama al motor: resta del origen y abre el tránsito. Devuelve las líneas movidas. */
    fun dispatch(transferId: Long, userId: Long): Int

    /** Llama al motor: suma en el destino y cierra el tránsito. */
    fun receive(transferId: Long, userId: Long): Int

    /** Llama al motor: devuelve la mercancía a su origen si estaba despachada. */
    fun cancel(transferId: Long, userId: Long): Int

    fun inTransit(): List<InTransitRow>

    /** Las líneas de tránsito abiertas de un traslado: lo que queda por recibir. */
    fun openTransitLines(transferId: Long): Int
}

interface TransferCatalog {
    fun product(code: ProductCode): TransferProduct?

    fun warehouse(id: Long): TransferWarehouse?

    /** La ubicación de recepción de una bodega, que es de donde sale y donde llega. */
    fun receivingLocationId(warehouseId: Long): Long?
}

internal fun transferNotFound(transferId: Long): dev.axzy.stock.application.shared.Outcome<Nothing> =
    dev.axzy.stock.application.shared.failure(
        dev.axzy.stock.application.shared.FailureKind.NOT_FOUND, "TRANSFER_NOT_FOUND",
        "Transfer $transferId does not exist.",
        mapOf("transferId" to transferId.toString()),
    )
