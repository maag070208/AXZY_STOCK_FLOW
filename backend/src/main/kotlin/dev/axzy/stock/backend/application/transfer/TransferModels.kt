package dev.axzy.stock.backend.application.transfer

import java.math.BigDecimal
import java.time.Instant

/** Entrada del caso de uso «crear traslado». Sin tipos HTTP: lo traduce la ruta. */
data class NewTransfer(
    val fromWarehouseId: Long,
    val toWarehouseId: Long,
    val note: String? = null,
    val lines: List<NewTransferLine>,
)

data class NewTransferLine(
    val productCode: String,
    val quantity: String,
    val fromLocationId: Long? = null,
    val note: String? = null,
)

/** La cabecera de un traslado con sus totales, tal como la lee la base. */
data class TransferHeader(
    val id: Long,
    val code: String,
    val fromWarehouseId: Long,
    val fromWarehouseCode: String,
    val toWarehouseId: Long,
    val toWarehouseCode: String,
    val status: String,
    val lineCount: Int,
    val totalUnits: BigDecimal,
    val note: String?,
    val dispatchedBy: Long?,
    val dispatchedByName: String?,
    val dispatchedAt: Instant?,
    val receivedBy: Long?,
    val receivedByName: String?,
    val receivedAt: Instant?,
)

/** Una línea del traslado, con la ubicación de origen ya resuelta para mostrarla. */
data class TransferLineView(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val quantity: BigDecimal,
    val fromLocationId: Long?,
    val fromLocationDisplay: String?,
    val note: String?,
)

data class TransferDetail(
    val transfer: TransferHeader,
    val lines: List<TransferLineView>,
)

/** Un traslado despachado y no recibido: una deuda visible. */
data class InTransitRow(
    val id: Long,
    val code: String,
    val fromWarehouseCode: String,
    val toWarehouseCode: String,
    val status: String,
    val lineCount: Int,
    val totalUnits: BigDecimal,
    val dispatchedAt: Instant,
    val daysInTransit: Long,
    val dispatchedBy: Long?,
    val dispatchedByName: String?,
)

/** Filtros del listado. `status` es el texto del enum, ya validado por la ruta. */
data class TransferFilter(
    val status: String?,
    val warehouseId: Long?,
)
