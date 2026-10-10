package dev.axzy.stock.application.transfer

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Instant

/**
 * The transfer ports (M05, docs/api/traslados.md).
 *
 * **The movement is done by the SQL engine** (`dispatch_transfer`, `receive_transfer`,
 * `cancel_transfer`, migration V13). This port only calls and reads it: if the
 * adapter moved the balance by hand it would be the second write path to the inventory
 * that the plan forbids.
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

/** A line as it arrives on creation. */
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

/** A line with its product already resolved. */
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

/** A dispatched and not received transfer, for the transit report. */
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
     * The days it has been in transit, **counted by the server**: with the machine's clock, the
     * same data would give a different number on each machine.
     */
    val daysInTransit: Long,
    val dispatchedBy: Long?,
    val dispatchedByName: String?,
)

/** What the transfer needs to know about a product and a warehouse. */
data class TransferProduct(val id: Long, val productCode: String, val productName: String)

data class TransferWarehouse(val id: Long, val code: String)

interface Transfers {
    fun page(filter: TransferFilter, request: PageRequest): Page<TransferHeader>

    fun detail(id: Long): TransferDetail?

    fun numberExists(code: String): Boolean

    /** The yearly sequence number: `TR-YYYY-NNNN`. */
    fun nextCode(year: Int): String

    fun insert(draft: TransferDraft, lines: List<ResolvedTransferLine>): InsertTransferResult

    /** Calls the engine: subtracts from the origin and opens the transit. Returns the lines moved. */
    fun dispatch(transferId: Long, userId: Long): Int

    /** Calls the engine: adds to the destination and closes the transit. */
    fun receive(transferId: Long, userId: Long): Int

    /** Calls the engine: returns the goods to their origin if they were dispatched. */
    fun cancel(transferId: Long, userId: Long): Int

    fun inTransit(): List<InTransitRow>

    /** The open transit lines of a transfer: what is still to be received. */
    fun openTransitLines(transferId: Long): Int
}

interface TransferCatalog {
    fun product(code: ProductCode): TransferProduct?

    fun warehouse(id: Long): TransferWarehouse?

    /** A warehouse's receiving location, which is where it leaves from and where it arrives. */
    fun receivingLocationId(warehouseId: Long): Long?
}

internal fun transferNotFound(transferId: Long): dev.axzy.stock.application.shared.Outcome<Nothing> =
    dev.axzy.stock.application.shared.failure(
        dev.axzy.stock.application.shared.FailureKind.NOT_FOUND, "TRANSFER_NOT_FOUND",
        "Transfer $transferId does not exist.",
        mapOf("transferId" to transferId.toString()),
    )
