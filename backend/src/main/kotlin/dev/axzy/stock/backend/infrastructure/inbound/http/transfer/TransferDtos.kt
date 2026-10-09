package dev.axzy.stock.backend.infrastructure.inbound.http.transfer

import kotlinx.serialization.Serializable

/**
 * DTOs de traslados (contrato §7).
 *
 * **Las cantidades van como CADENA decimal de 3 decimales** (`"150.000"`), como
 * todo el contrato (§1.7): JSON no tiene decimales de precisión arbitraria.
 */
@Serializable
data class TransferLineResponse(
    val id: Long,
    val productId: Long,
    val productCode: String,
    val productName: String,
    val quantity: String,
    val fromLocationId: Long? = null,
    val fromLocationDisplayName: String? = null,
    val note: String? = null,
)

/** La cabecera, con los totales derivados de sus líneas. */
@Serializable
data class TransferResponse(
    val id: Long,
    val code: String,
    val fromWarehouseId: Long,
    val fromWarehouseCode: String,
    val toWarehouseId: Long,
    val toWarehouseCode: String,
    val status: String,
    val lineCount: Int,
    val totalUnits: String,
    val note: String? = null,
    val dispatchedBy: Long? = null,
    val dispatchedByName: String? = null,
    val dispatchedAt: String? = null,
    val receivedBy: Long? = null,
    val receivedByName: String? = null,
    val receivedAt: String? = null,
)

/** El detalle: la cabecera **con sus líneas**. */
@Serializable
data class TransferDetailResponse(
    val transfer: TransferResponse,
    val lines: List<TransferLineResponse>,
)

/**
 * Una fila de `GET /v1/transfers/in-transit`.
 *
 * **Los días transcurridos los calcula el SERVIDOR**, no el cliente: un traslado
 * lleva despachado un número de días objetivo, y contarlos en la pantalla con el
 * reloj del equipo daría un número distinto en cada máquina. Un dato de gestión
 * que cambia según quién mire no sirve para decidir.
 */
@Serializable
data class InTransitTransferResponse(
    val id: Long,
    val code: String,
    val fromWarehouseCode: String,
    val toWarehouseCode: String,
    val status: String,
    val lineCount: Int,
    val totalUnits: String,
    val dispatchedAt: String,
    val daysInTransit: Long,
    val dispatchedBy: Long? = null,
    val dispatchedByName: String? = null,
)

/** El sobre de paginación de §1.4, igual que en el resto de listados. */
@Serializable
data class TransferPageResponse(
    val items: List<TransferResponse>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

@Serializable
data class TransferLineRequest(
    val productCode: String,
    val quantity: String,
    val fromLocationId: Long? = null,
    val note: String? = null,
)

@Serializable
data class TransferCreateRequest(
    val fromWarehouseId: Long,
    val toWarehouseId: Long,
    val note: String? = null,
    val lines: List<TransferLineRequest>,
)
