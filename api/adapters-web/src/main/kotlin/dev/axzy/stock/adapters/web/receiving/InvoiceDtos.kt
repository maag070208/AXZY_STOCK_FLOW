package dev.axzy.stock.adapters.web.receiving

import dev.axzy.stock.adapters.web.http.PageDto
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.inventory.toContractString
import dev.axzy.stock.application.receiving.InvoiceDetail
import dev.axzy.stock.application.receiving.InvoiceHeader
import dev.axzy.stock.application.receiving.InvoiceLineView
import dev.axzy.stock.application.receiving.PendingApproval
import java.time.Instant
import java.time.LocalDate

/**
 * The HTTP contract of receiving (docs/api/recepcion.md).
 *
 * Quantities travel as a string with three decimals, as in the rest of the API
 * except the catalog. The `difference` **is computed by the server**: it is a
 * generated column in the database, and the legacy always returned zero.
 */

// ── Facturas ────────────────────────────────────────────────────────────────

data class InvoiceHeaderDto(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String,
    val status: String,
    val customerCode: String?,
    val concept: String?,
    val totalLines: Int,
    val pendingApprovalLines: Int,
    val createdAt: String,
)

fun InvoiceHeader.toDto(): InvoiceHeaderDto = InvoiceHeaderDto(
    id = id,
    invoiceNumber = invoiceNumber,
    receiptDate = receiptDate.toString(),
    invoiceType = invoiceType,
    status = status,
    customerCode = customerCode,
    concept = concept,
    totalLines = totalLines,
    pendingApprovalLines = pendingApprovalLines,
    createdAt = createdAt.toString(),
)

data class InvoiceLineDto(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val container: String?,
    val quantity: String,
    val boxQuantity: String,
    val unitsPerBox: String,
    val physicalQuantity: String?,
    val physicalBoxQuantity: String?,
    val locatedQuantity: String,
    /** `physicalQuantity - quantity`. Positive if more arrived. The server computes it. */
    val difference: String?,
    val status: String,
    val capturedBy: Long?,
    val capturedByName: String?,
    val capturedAt: String?,
    val approvedBy: Long?,
    val approvedByName: String?,
    val approvedAt: String?,
    val adjustmentReason: String?,
    val locationDisplayName: String?,
    /** Who has the line assigned to count it, and their visible name. */
    val assignedUserId: Long?,
    val assignedUserName: String?,
)

fun InvoiceLineView.toDto(): InvoiceLineDto = InvoiceLineDto(
    id = line.id,
    productId = productId,
    productCode = line.productCode.value,
    productName = productName,
    container = container,
    quantity = line.invoicedQuantity.toContractString(),
    boxQuantity = boxQuantity.toContractString(),
    unitsPerBox = line.unitsPerBox.value.toString(),
    physicalQuantity = line.physicalQuantity?.toContractString(),
    physicalBoxQuantity = line.physicalBoxQuantity?.value?.toString(),
    locatedQuantity = line.locatedQuantity.toContractString(),
    difference = difference?.toContractString(),
    status = line.status.name,
    capturedBy = line.capturedBy,
    capturedByName = capturedByName,
    capturedAt = capturedAt?.toIso(),
    approvedBy = line.approvedBy,
    approvedByName = approvedByName,
    approvedAt = approvedAt?.toIso(),
    adjustmentReason = line.adjustmentReason,
    assignedUserId = line.assignedUserId,
    assignedUserName = assignedUserName,
    locationDisplayName = locationDisplayName,
)

data class InvoiceDetailDto(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val invoiceType: String,
    val status: String,
    val customerCode: String?,
    val concept: String?,
    val totalLines: Int,
    val pendingApprovalLines: Int,
    val createdAt: String,
    val lines: List<InvoiceLineDto>,
)

fun InvoiceDetail.toDto(): InvoiceDetailDto = InvoiceDetailDto(
    id = header.id,
    invoiceNumber = header.invoiceNumber,
    receiptDate = header.receiptDate.toString(),
    invoiceType = header.invoiceType,
    status = header.status,
    customerCode = header.customerCode,
    concept = header.concept,
    totalLines = header.totalLines,
    pendingApprovalLines = header.pendingApprovalLines,
    createdAt = header.createdAt.toString(),
    lines = lines.map { it.toDto() },
)

// ── Alta ────────────────────────────────────────────────────────────────────

data class NewInvoiceLineDto(
    val productCode: String? = null,
    val container: String? = null,
    val unitMeasure: String? = null,
    val quantity: java.math.BigDecimal? = null,
    val boxQuantity: java.math.BigDecimal? = null,
    val unitsPerBox: java.math.BigDecimal? = null,
)

data class NewInvoiceDto(
    val invoiceNumber: String? = null,
    val receiptDate: String? = null,
    val invoiceType: String? = null,
    val lines: List<NewInvoiceLineDto> = emptyList(),
)

data class CreatedInvoiceDto(val id: Long, val invoiceNumber: String)

// ── Capture and decisions ──────────────────────────────────────────────────

data class CaptureDto(
    val physicalQuantity: java.math.BigDecimal? = null,
    val physicalBoxQuantity: java.math.BigDecimal? = null,
)

data class DecisionDto(val adjustmentReason: String? = null, val note: String? = null)

// ── Pending inbox ───────────────────────────────────────────────────────

data class PendingApprovalDto(
    val invoiceId: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val status: String,
    val pendingLines: Int,
    /** Sum of the **absolute** differences: positive even when goods are missing. */
    val totalDifference: String,
)

fun PendingApproval.toDto(): PendingApprovalDto = PendingApprovalDto(
    invoiceId = invoiceId,
    invoiceNumber = invoiceNumber,
    receiptDate = receiptDate.toString(),
    status = status,
    pendingLines = pendingLines,
    totalDifference = totalDifference.toContractString(),
)

/** The inbox does **not** use the pagination envelope: it is `{ "items": [...] }`. */
data class ItemsDto<T>(val items: List<T>)

/** An instant in ISO 8601 UTC, which is the API's format. */
private fun Instant.toIso(): String = toString()

/** Receiving dates in the contract's format. */
internal fun LocalDate.toContractString(): String = toString()
