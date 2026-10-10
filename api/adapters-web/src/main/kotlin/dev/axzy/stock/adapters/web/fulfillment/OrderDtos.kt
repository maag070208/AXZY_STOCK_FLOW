package dev.axzy.stock.adapters.web.fulfillment

import dev.axzy.stock.adapters.web.http.PageDto
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.inventory.toContractString
import dev.axzy.stock.application.fulfillment.ExportAttempt
import dev.axzy.stock.application.fulfillment.OrderDetail
import dev.axzy.stock.application.fulfillment.OrderHeader
import dev.axzy.stock.application.fulfillment.OrderLineView
import java.time.Instant

/**
 * El contrato HTTP de surtido (docs/api/surtido.md).
 *
 * Las cantidades viajan como cadena con tres decimales, y el campo `supplied` de la
 * línea es lo que el legacy llamaba «empacado»: aquí es **lo ya descontado del
 * inventario**, que es lo que permite confirmar sin descontar dos veces.
 */

data class OrderHeaderDto(
    val id: Long,
    val orderNumber: String,
    val packagingDate: String?,
    val status: String,
    val customerCode: String?,
    val concept: String?,
    val converted: Boolean,
    val convertedAt: String?,
    val exportStatus: String?,
    val exportAttempts: Int,
    val exportLastError: String?,
    val totalLines: Int,
    val packagedLines: Int,
)

fun OrderHeader.toDto(): OrderHeaderDto = OrderHeaderDto(
    id = id,
    orderNumber = orderNumber,
    packagingDate = packagingDate?.toString(),
    status = status,
    customerCode = customerCode,
    concept = concept,
    converted = converted,
    convertedAt = convertedAt?.toIso(),
    exportStatus = exportStatus,
    exportAttempts = exportAttempts,
    exportLastError = exportLastError,
    totalLines = totalLines,
    packagedLines = packagedLines,
)

data class OrderLineDto(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val unitMeasure: String,
    val quantity: String,
    /** Lo **ya descontado** del inventario, no «lo empacado». */
    val suppliedQuantity: String,
    val supplied: Boolean,
    val status: String,
    val assignedUserId: Long?,
    val assignedUserName: String?,
    val locationId: Long?,
    val locationDisplayName: String?,
)

fun OrderLineView.toDto(): OrderLineDto = OrderLineDto(
    id = line.id,
    productId = line.productId,
    productCode = line.productCode,
    productName = line.productName,
    unitMeasure = line.unitMeasure,
    quantity = line.quantity.toContractString(),
    suppliedQuantity = line.suppliedQuantity.toContractString(),
    supplied = line.isComplete,
    status = line.status.name,
    assignedUserId = line.assignedUserId,
    assignedUserName = assignedUserName,
    locationId = line.locationId,
    locationDisplayName = locationDisplayName,
)

data class OrderDetailDto(
    val id: Long,
    val orderNumber: String,
    val packagingDate: String?,
    val status: String,
    val customerCode: String?,
    val concept: String?,
    val converted: Boolean,
    val convertedAt: String?,
    val exportStatus: String?,
    val exportAttempts: Int,
    val exportLastError: String?,
    val totalLines: Int,
    val packagedLines: Int,
    val lines: List<OrderLineDto>,
)

fun OrderDetail.toDto(): OrderDetailDto = OrderDetailDto(
    id = header.id,
    orderNumber = header.orderNumber,
    packagingDate = header.packagingDate?.toString(),
    status = header.status,
    customerCode = header.customerCode,
    concept = header.concept,
    converted = header.converted,
    convertedAt = header.convertedAt?.toIso(),
    exportStatus = header.exportStatus,
    exportAttempts = header.exportAttempts,
    exportLastError = header.exportLastError,
    totalLines = header.totalLines,
    packagedLines = header.packagedLines,
    lines = lines.map { it.toDto() },
)

// ── Cuerpos de entrada ──────────────────────────────────────────────────────

data class NewOrderLineDto(
    val productCode: String? = null,
    /**
     * Se acepta número (`10.000`) o cadena (`"10.000"`): el contrato muestra la
     * segunda forma y el resto de la API usa la primera.
     */
    val quantity: java.math.BigDecimal? = null,
)

data class NewOrderDto(
    val orderNumber: String? = null,
    val packagingDate: String? = null,
    val customerCode: String? = null,
    val concept: String? = null,
    val lines: List<NewOrderLineDto> = emptyList(),
)

data class PackageDto(
    val quantity: java.math.BigDecimal? = null,
    val locationId: Long? = null,
)

data class ShortageDto(
    val lineId: Long? = null,
    val quantity: java.math.BigDecimal? = null,
    val reason: String? = null,
    val note: String? = null,
)

data class ExportAttemptDto(val attempts: Int, val status: String, val lastError: String?)

fun ExportAttempt.toDto(): ExportAttemptDto = ExportAttemptDto(attempts, status, lastError)

/** Un instante en ISO 8601 UTC, que es el formato de la API. */
private fun Instant.toIso(): String = toString()
