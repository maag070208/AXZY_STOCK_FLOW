package dev.axzy.stock.backend.infrastructure.inbound.http.transfer

import dev.axzy.stock.backend.application.transfer.InTransitRow
import dev.axzy.stock.backend.application.transfer.NewTransfer
import dev.axzy.stock.backend.application.transfer.NewTransferLine
import dev.axzy.stock.backend.application.transfer.TransferDetail
import dev.axzy.stock.backend.application.transfer.TransferHeader
import dev.axzy.stock.backend.application.transfer.TransferLineView

/*
 * Traducción entre el modelo de aplicación y el contrato HTTP. Las cantidades salen como texto con
 * tres decimales, y las fechas como instante ISO-8601: es el formato del contrato, no un detalle.
 */

internal fun TransferHeader.toResponse() = TransferResponse(
    id = id,
    code = code,
    fromWarehouseId = fromWarehouseId,
    fromWarehouseCode = fromWarehouseCode,
    toWarehouseId = toWarehouseId,
    toWarehouseCode = toWarehouseCode,
    status = status,
    lineCount = lineCount,
    totalUnits = totalUnits.setScale(3).toPlainString(),
    note = note,
    dispatchedBy = dispatchedBy,
    dispatchedByName = dispatchedByName,
    dispatchedAt = dispatchedAt?.toString(),
    receivedBy = receivedBy,
    receivedByName = receivedByName,
    receivedAt = receivedAt?.toString(),
)

internal fun TransferLineView.toResponse() = TransferLineResponse(
    id = id,
    productId = productId,
    productCode = productCode,
    productName = productName,
    quantity = quantity.setScale(3).toPlainString(),
    fromLocationId = fromLocationId,
    fromLocationDisplayName = fromLocationDisplay,
    note = note,
)

internal fun TransferDetail.toResponse() = TransferDetailResponse(
    transfer = transfer.toResponse(),
    lines = lines.map { it.toResponse() },
)

internal fun InTransitRow.toResponse() = InTransitTransferResponse(
    id = id,
    code = code,
    fromWarehouseCode = fromWarehouseCode,
    toWarehouseCode = toWarehouseCode,
    status = status,
    lineCount = lineCount,
    totalUnits = totalUnits.setScale(3).toPlainString(),
    dispatchedAt = dispatchedAt.toString(),
    daysInTransit = daysInTransit,
    dispatchedBy = dispatchedBy,
    dispatchedByName = dispatchedByName,
)

internal fun TransferCreateRequest.toNew() = NewTransfer(
    fromWarehouseId = fromWarehouseId,
    toWarehouseId = toWarehouseId,
    note = note,
    lines = lines.map { it.toNew() },
)

internal fun TransferLineRequest.toNew() = NewTransferLine(
    productCode = productCode,
    quantity = quantity,
    fromLocationId = fromLocationId,
    note = note,
)
