package dev.axzy.stock.adapters.web.transfer

import dev.axzy.stock.adapters.web.http.PageDto
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.inventory.toContractString
import dev.axzy.stock.application.transfer.InTransitRow
import dev.axzy.stock.application.transfer.TransferDetail
import dev.axzy.stock.application.transfer.TransferHeader
import dev.axzy.stock.application.transfer.TransferLineView
import java.time.Instant

/**
 * El contrato HTTP de traslados (docs/api/traslados.md).
 *
 * El detalle tiene una forma propia —`{ "transfer": {...}, "lines": [...] }`— distinta
 * de la de facturas y órdenes, que devuelven el documento con las líneas dentro. Se
 * respeta porque el contrato la fija.
 */

data class TransferHeaderDto(
    val id: Long,
    val code: String,
    val fromWarehouseId: Long,
    val fromWarehouseCode: String,
    val toWarehouseId: Long,
    val toWarehouseCode: String,
    val status: String,
    val lineCount: Int,
    val totalUnits: String,
    val note: String?,
    val dispatchedBy: Long?,
    val dispatchedByName: String?,
    val dispatchedAt: String?,
    val receivedBy: Long?,
    val receivedByName: String?,
    val receivedAt: String?,
)

fun TransferHeader.toDto(): TransferHeaderDto = TransferHeaderDto(
    id = id,
    code = code,
    fromWarehouseId = fromWarehouseId,
    fromWarehouseCode = fromWarehouseCode,
    toWarehouseId = toWarehouseId,
    toWarehouseCode = toWarehouseCode,
    status = status,
    lineCount = lineCount,
    totalUnits = totalUnits.toContractString(),
    note = note,
    dispatchedBy = dispatchedBy,
    dispatchedByName = dispatchedByName,
    dispatchedAt = dispatchedAt?.toIso(),
    receivedBy = receivedBy,
    receivedByName = receivedByName,
    receivedAt = receivedAt?.toIso(),
)

data class TransferLineDto(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val quantity: String,
    val fromLocationId: Long?,
    val fromLocationDisplayName: String?,
    val note: String?,
)

fun TransferLineView.toDto(): TransferLineDto = TransferLineDto(
    id = line.id,
    productId = line.productId,
    productCode = line.productCode,
    productName = line.productName,
    quantity = line.quantity.toContractString(),
    fromLocationId = line.fromLocationId,
    fromLocationDisplayName = line.fromLocationDisplayName,
    note = line.note,
)

data class TransferDetailDto(val transfer: TransferHeaderDto, val lines: List<TransferLineDto>)

fun TransferDetail.toDto(): TransferDetailDto = TransferDetailDto(header.toDto(), lines.map { it.toDto() })

data class InTransitDto(
    val id: Long,
    val code: String,
    val fromWarehouseCode: String,
    val toWarehouseCode: String,
    val status: String,
    val lineCount: Int,
    val totalUnits: String,
    val dispatchedAt: String?,
    val daysInTransit: Long,
    val dispatchedBy: Long?,
    val dispatchedByName: String?,
)

fun InTransitRow.toDto(): InTransitDto = InTransitDto(
    id = id,
    code = code,
    fromWarehouseCode = fromWarehouseCode,
    toWarehouseCode = toWarehouseCode,
    status = status,
    lineCount = lineCount,
    totalUnits = totalUnits.toContractString(),
    dispatchedAt = dispatchedAt?.toIso(),
    daysInTransit = daysInTransit,
    dispatchedBy = dispatchedBy,
    dispatchedByName = dispatchedByName,
)

// ── Cuerpos de entrada ──────────────────────────────────────────────────────

data class NewTransferLineDto(
    val productCode: String? = null,
    val quantity: java.math.BigDecimal? = null,
    val fromLocationId: Long? = null,
    val note: String? = null,
)

data class NewTransferDto(
    val fromWarehouseId: Long? = null,
    val toWarehouseId: Long? = null,
    val note: String? = null,
    val lines: List<NewTransferLineDto> = emptyList(),
)

/** Un instante en ISO 8601 UTC, que es el formato de la API. */
private fun Instant.toIso(): String = toString()
