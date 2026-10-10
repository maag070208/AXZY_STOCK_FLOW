package dev.axzy.stock.application.receiving

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import java.time.Instant
import java.time.LocalDate

/**
 * The receiving ports (M03, docs/flujos/recepcion.md).
 *
 * The invoice is a document with lines, and both are read together: the listing
 * returns **only the header** with its counters —which the database calculates— and the detail
 * adds the lines. Separating them is what allows a listing of 200 invoices not to
 * bring 2,400 lines that nobody is going to look at.
 */

/** The header of an invoice, with the counters the database calculates. */
data class InvoiceHeader(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: LocalDate,
    val invoiceType: String,
    val status: String,
    val customerCode: String?,
    val concept: String?,
    val totalLines: Int,
    /**
     * The lines that **prevent closing**: uncounted, in discrepancy or assigned
     * halfway. It is the same criterion the closing applies, calculated in the database, so
     * the list and the action cannot disagree.
     */
    val pendingApprovalLines: Int,
    val createdAt: Instant,
)

/** A line with what the response needs and the domain line does not have: names. */
data class InvoiceLineView(
    val line: InvoiceLine,
    val productId: Long,
    val productName: String,
    val container: String?,
    val boxQuantity: Quantity,
    val difference: Quantity?,
    val capturedByName: String?,
    val capturedAt: Instant?,
    val approvedByName: String?,
    val approvedAt: Instant?,
    val locationDisplayName: String?,
    /** The visible name of whoever has the line assigned, so the client does not have to resolve it. */
    val assignedUserName: String? = null,
)

data class InvoiceDetail(val header: InvoiceHeader, val lines: List<InvoiceLineView>)

/** Invoice listing filters. */
data class InvoiceFilter(val status: String?, val query: String?)

/** A line as it arrives on creation. */
data class NewInvoiceLine(
    val productCode: String?,
    val container: String?,
    val unitMeasure: String?,
    val quantity: Quantity?,
    val boxQuantity: Quantity?,
    val unitsPerBox: Quantity?,
)

/**
 * The invoice as it arrives on creation: unvalidated.
 *
 * The fields are optional on purpose, so that the use case says **which** one is missing
 * instead of the client receiving a deserialization error without a field.
 */
data class NewInvoice(
    val invoiceNumber: String?,
    val receiptDate: String?,
    val invoiceType: String?,
    val lines: List<NewInvoiceLine>,
)

/** The header, already validated, ready to write. */
data class InvoiceDraft(
    val invoiceNumber: String,
    val receiptDate: LocalDate,
    val invoiceType: String,
)

/**
 * A creation line with its identifiers already resolved.
 *
 * The product and the unit are resolved before writing: the business key
 * (`productCode`) is what the client sends, and the `id` is what the table stores.
 */
data class ResolvedLine(
    val productId: Long,
    val productCode: ProductCode,
    val unitMeasureId: Long,
    val container: String?,
    val quantity: Quantity,
    val boxQuantity: Quantity,
    /** Units per box: a positive integer, and the domain type guarantees it. */
    val unitsPerBox: UnitsPerBox,
)

/** An invoice with lines awaiting a decision, for the inbox. */
data class PendingApproval(
    val invoiceId: Long,
    val invoiceNumber: String,
    val receiptDate: LocalDate,
    val status: String,
    val pendingLines: Int,
    /** Sum of the **absolute** differences: positive even when goods are missing. */
    val totalDifference: Quantity,
)

interface Invoices {
    fun page(filter: InvoiceFilter, request: PageRequest): Page<InvoiceHeader>

    fun detail(id: Long): InvoiceDetail?

    /** The business key is unique; the pre-check gives the readable error in the normal case. */
    fun numberExists(invoiceNumber: String): Boolean

    fun insert(draft: InvoiceDraft, lines: List<ResolvedLine>): InsertResult

    /** The lines of an invoice, without the names: that is what the transitions need. */
    fun linesOf(invoiceId: Long): List<InvoiceLine>

    fun lineOf(invoiceId: Long, lineId: Long): InvoiceLine?

    /** Writes a line: capture, approval or rejection already decided by the domain. */
    fun replaceLine(line: InvoiceLine, at: Instant): Unit

    /**
     * Claims the line's count **against the entered balance that was read**.
     *
     * It is a *compare-and-set*, and it exists because of what it writes: counting puts goods into
     * `RECEIVING` by the **difference** against what was already entered, so if two counts of the
     * same line read the same balance, both enter the same difference and **the stock
     * is doubled**. The `WHERE` requires that `received_quantity` is still the one that was read: the
     * first one wins and the second changes zero rows.
     *
     * Returning zero **is not a user error**: it means another person counted that line
     * in the meantime, and the use case says so with its own code.
     */
    fun claimCapture(line: InvoiceLine, expectedReceived: Quantity, at: Instant): Int

    /**
     * Assigns the line to a counter in **a single conditional statement**.
     *
     * The `WHERE` requires that the line is still in `PENDING`: if another person counted it between the read
     * and the write, no work already done is assigned. It returns how many rows changed.
     */
    fun assignLine(lineId: Long, userId: Long, at: Instant): Int

    fun setStatus(invoiceId: Long, status: String, at: Instant): Unit

    /** Closes the invoice: besides the status, it records who reviewed it and when. */
    fun close(invoiceId: Long, actorId: Long, at: Instant): Unit

    /**
     * Approves **all** the discrepancy lines of an invoice, in one statement.
     *
     * The condition travels in the `WHERE` (`status = 'DISCREPANCY'`) and the number of affected rows is
     * compared with the expected one: if it does not match, someone changed them at the same time and the
     * operation is not accepted.
     */
    fun approveDiscrepancies(invoiceId: Long, approverId: Long, reason: String, at: Instant): Int

    /**
     * Returns to counting all the discrepancy lines of an invoice, in one
     * statement: the counted quantity, the boxes and who counted are cleared, so as to
     * force a recount. It is what [dev.axzy.stock.domain.receiving.InvoiceLine.rejectDiscrepancy] does.
     */
    fun rejectDiscrepancies(invoiceId: Long, at: Instant): Int

    fun pendingApproval(): List<PendingApproval>
}

/** What the invoice needs to know about a catalog product. */
data class ReceivingProduct(val id: Long, val unitMeasureId: Long)

/** Where a product's and a unit's identifiers come from. */
interface ReceivingCatalog {
    fun product(code: ProductCode): ReceivingProduct?

    fun unitMeasureId(code: String): Long?
}

/** The result of the creation: an identifier, or that the number already existed. */
sealed interface InsertResult {
    data class Inserted(val id: Long) : InsertResult

    /**
     * The invoice number is unique and was already taken.
     *
     * It is a result and not an exception because the pre-check is not enough: between the
     * check and the `INSERT` another request can fit, and the unique index is the one that
     * decides. Returning it as a value keeps a race from ending in a `500`.
     */
    data object DuplicateNumber : InsertResult
}
