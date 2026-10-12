package dev.axzy.stock.mobile.core.receiving

import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import kotlinx.serialization.Serializable

// The shapes of `docs/api/recepcion.md`, with only the fields the app reads. Quantities travel as decimal text.

@Serializable
internal data class InvoicePageDto(val items: List<InvoiceHeaderDto> = emptyList())

@Serializable
internal data class InvoiceHeaderDto(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val status: String,
    val totalLines: Int,
    val pendingApprovalLines: Int,
)

@Serializable
internal data class InvoiceDetailDto(
    val id: Long,
    val invoiceNumber: String,
    val receiptDate: String,
    val status: String,
    val totalLines: Int,
    val pendingApprovalLines: Int,
    val lines: List<InvoiceLineDto> = emptyList(),
)

@Serializable
internal data class InvoiceLineDto(
    val id: Long,
    val productCode: String,
    val productName: String = "",
    val quantity: String,
    val unitsPerBox: String,
    val physicalQuantity: String? = null,
    val physicalBoxQuantity: String? = null,
    val locatedQuantity: String = "0",
    val status: String,
    val capturedBy: Long? = null,
    val approvedBy: Long? = null,
    val adjustmentReason: String? = null,
    val assignedUserId: Long? = null,
)

@Serializable
internal data class CaptureRequest(val physicalQuantity: Long, val physicalBoxQuantity: Long)

// The mappings build the domain's own types, and those refuse what they do not accept. A value the domain refuses
// throws IllegalArgumentException here; the repository reports it as an unexpected answer instead of showing it.

internal fun InvoiceHeaderDto.toHeader() = InvoiceHeader(
    id = id,
    number = invoiceNumber,
    receiptDate = receiptDate,
    status = InvoiceStatus.valueOf(status),
    totalLines = totalLines,
    blockingLines = pendingApprovalLines,
)

internal fun InvoiceDetailDto.toDetail() = InvoiceDetail(
    header = InvoiceHeader(
        id = id,
        number = invoiceNumber,
        receiptDate = receiptDate,
        status = InvoiceStatus.valueOf(status),
        totalLines = totalLines,
        blockingLines = pendingApprovalLines,
    ),
    lines = lines.map { it.toLine() },
    productNames = lines.filter { it.productName.isNotBlank() }.associate { it.id to it.productName },
)

internal fun InvoiceLineDto.toLine() = InvoiceLine(
    id = id,
    productCode = ProductCode.of(productCode),
    invoicedQuantity = Quantity.parse(quantity).getOrThrow(),
    unitsPerBox = UnitsPerBox.of(unitsPerBox.toWholeNumber()),
    status = InvoiceLineStatus.valueOf(status),
    physicalQuantity = physicalQuantity?.let { Quantity.parse(it).getOrThrow() },
    physicalBoxQuantity = physicalBoxQuantity?.let { BoxQuantity.of(it.toWholeNumber()) },
    locatedQuantity = Quantity.parse(locatedQuantity).getOrThrow(),
    capturedBy = capturedBy,
    approvedBy = approvedBy,
    adjustmentReason = adjustmentReason,
    assignedUserId = assignedUserId,
)

/** A decimal text that the domain holds as a whole number: units per box, counted boxes. A fraction is refused. */
private fun String.toWholeNumber(): Long {
    val quantity = Quantity.parse(this).getOrThrow()
    require(quantity == Quantity.of(quantity.units)) { "Expected a whole number, got '$this'" }
    return quantity.units
}
