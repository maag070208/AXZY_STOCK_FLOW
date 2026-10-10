package dev.axzy.stock.application.fulfillment

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.fulfillment.OrderLine
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Instant
import java.time.LocalDate

/**
 * The fulfillment (M04) and ERP-export (M09) ports.
 *
 * The order **does not touch the inventory when created**: stock leaves when packing and
 * confirming, which are acts with a responsible person. Every stock write goes through the
 * same engine that receiving uses (`record_movement`).
 */

data class OrderHeader(
    val id: Long,
    val orderNumber: String,
    val packagingDate: LocalDate?,
    val status: String,
    val customerCode: String?,
    val concept: String?,
    val converted: Boolean,
    val convertedAt: Instant?,
    val exportStatus: String?,
    val exportAttempts: Int,
    val exportLastError: String?,
    val totalLines: Int,
    val packagedLines: Int,
)

data class OrderLineView(
    val line: OrderLine,
    val unitMeasure: String,
    val assignedUserName: String?,
    val locationDisplayName: String?,
)

data class OrderDetail(val header: OrderHeader, val lines: List<OrderLineView>)

data class OrderFilter(val status: String?, val query: String?)

/** A line as it arrives on creation. */
data class NewOrderLine(val productCode: String?, val quantity: Quantity?)

/** The order as it arrives on creation: the number and the date are optional. */
data class NewOrder(
    val orderNumber: String?,
    val packagingDate: String?,
    val customerCode: String?,
    val concept: String?,
    val lines: List<NewOrderLine>,
)

/** A creation line with its product already resolved. */
data class ResolvedOrderLine(
    val productId: Long,
    val productCode: ProductCode,
    val unitMeasureId: Long,
    val quantity: Quantity,
)

/** The header, already validated, ready to write. */
data class OrderDraft(
    val orderNumber: String,
    val packagingDate: LocalDate?,
    val customerCode: String?,
    val concept: String?,
)

/** What the order needs to know about a product and a location. */
data class OrderProduct(val id: Long, val unitMeasureId: Long)

data class OrderLocation(val id: Long, val warehouseId: Long, val displayName: String)

/** The result of the creation: an identifier, or that the number already existed. */
sealed interface InsertOrderResult {
    data class Inserted(val id: Long) : InsertOrderResult

    data object DuplicateNumber : InsertOrderResult
}

interface Orders {
    fun page(filter: OrderFilter, request: PageRequest): Page<OrderHeader>

    fun detail(id: Long): OrderDetail?

    fun orderNumberOf(id: Long): String?

    fun numberExists(orderNumber: String): Boolean

    /** The yearly sequence number: `ORD-YYYY-NNNN`, as in the legacy. */
    fun nextOrderNumber(year: Int): String

    fun insert(draft: OrderDraft, lines: List<ResolvedOrderLine>): InsertOrderResult

    fun lineOf(orderId: Long, lineId: Long): OrderLineView?

    fun replaceLine(line: OrderLine, locationId: Long?, at: Instant): Unit

    /**
     * Assigns the line in **a single conditional statement**.
     *
     * The `WHERE` requires the line to still be in an assignable state, so if packing
     * fulfilled it between the read and the write, no work is assigned to anyone on goods
     * that already left: it returns how many rows changed, and zero means it changed underneath.
     */
    fun assignLine(lineId: Long, userId: Long, locationId: Long?, at: Instant): Int

    fun setStatus(orderId: Long, status: String, at: Instant): Unit

    /**
     * **Claims the confirmation** in a single conditional statement.
     *
     * It is what stops confirming twice at once from deducting twice (S3): the
     * `WHERE` requires the order to still be in a state before confirmation, so
     * only one of the two transactions gets the row. It returns how many changed: if
     * it is zero, another one already confirmed it.
     */
    fun claimConfirmation(orderId: Long, at: Instant): Int

    fun recordExport(orderId: Long, converted: Boolean, status: String, error: String?, at: Instant): Unit
}

/** Where an order line's product and location come from. */
interface OrderCatalog {
    fun product(code: ProductCode): OrderProduct?

    fun location(id: Long): OrderLocation?
}

/** The result of an attempt to send to the ERP. */
sealed interface ErpResult {
    /** The ERP received it. */
    data object Sent : ErpResult

    /**
     * The attempt failed, with a **readable and storable** reason.
     *
     * The reason is stored in `export_last_error` and the manual retry shows it
     * again: a "failed" without a cause forces looking at the server logs.
     */
    data class Failed(val reason: String) : ErpResult
}

/**
 * The connection to the ERP.
 *
 * **It does not exist today** ([ADR-0013], `docs/guia/lo-no-verificado.md`): the wired
 * implementation returns `ERP_NOT_CONNECTED` and the attempt counter reflects it. The
 * port is here so that the day there is an ERP only one adapter has to be written, and
 * so that the use case already applies the rules that do not depend on it: the confirmation
 * is not rolled back if the sending fails.
 */
interface ErpClient {
    fun send(order: OrderDetail): ErpResult
}
