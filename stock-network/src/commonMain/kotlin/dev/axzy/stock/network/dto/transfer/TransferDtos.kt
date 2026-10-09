package dev.axzy.stock.network.dto.transfer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TRASLADOS ENTRE BODEGAS — DTOs de `/v1/transfers`
 *
 * Contrato: docs/historico/CONTRATO_API.md §7.
 *
 * La invariante que estos DTOs representan: `Σ stock_by_location +
 * Σ stock_in_transit` **se conserva en cada transición**. La mercancía en el
 * camión sigue contando en el inventario; si se modelara como dos movimientos
 * instantáneos, el total bajaría durante el viaje y los reportes mentirían.
 * Por eso el estado `IN_TRANSIT` es un estado propio y no un hueco entre dos
 * llamadas.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Un traslado, con quién lo despachó y quién lo recibió. */
@Serializable
data class TransferDto(
    val id: Long,
    val code: String,
    val fromWarehouseId: Long,
    val fromWarehouseCode: String? = null,
    val toWarehouseId: Long,
    val toWarehouseCode: String? = null,
    val status: TransferStatus = TransferStatus.DRAFT,
    val lineCount: Int = 0,
    val totalUnits: Double = 0.0,
    val dispatchedBy: Long? = null,
    val dispatchedAt: String? = null,
    val receivedBy: Long? = null,
    val receivedAt: String? = null,
)

/**
 * Los cinco estados de §2.6. `DRAFT` → `DISPATCHED` → `IN_TRANSIT` → `RECEIVED`,
 * con `CANCELLED` como salida.
 */
@Serializable
enum class TransferStatus {
    @SerialName("DRAFT")
    DRAFT,

    @SerialName("DISPATCHED")
    DISPATCHED,

    @SerialName("IN_TRANSIT")
    IN_TRANSIT,

    @SerialName("RECEIVED")
    RECEIVED,

    @SerialName("CANCELLED")
    CANCELLED,
}

/**
 * Un traslado en tránsito, con su responsable y los días transcurridos.
 *
 * **Un traslado nunca recibido es una deuda visible, no un dato que se pierde.**
 * De ahí que `daysInTransit` sea parte de la respuesta: es lo que hace que el
 * listado sirva para reclamar, no solo para mirar.
 *
 * AMBIGUO: el contrato describe estos dos campos en prosa («con su responsable
 * y los días transcurridos») y **no fija sus nombres JSON**. Se eligen
 * `responsibleName` y `daysInTransit`, que son los nombres con los que la web
 * los va a mostrar.
 */
@Serializable
data class InTransitTransferDto(
    val id: Long,
    val code: String,
    val fromWarehouseId: Long,
    val fromWarehouseCode: String? = null,
    val toWarehouseId: Long,
    val toWarehouseCode: String? = null,
    val status: TransferStatus = TransferStatus.IN_TRANSIT,
    val lineCount: Int = 0,
    val totalUnits: Double = 0.0,
    val dispatchedBy: Long? = null,
    val dispatchedAt: String? = null,
    val responsibleName: String? = null,
    val daysInTransit: Long? = null,
)

/**
 * Cuerpo de `POST /v1/transfers`.
 *
 * AMBIGUO: el contrato documenta la ruta pero no el cuerpo. Se incluye el
 * origen, el destino y las líneas, que es lo mínimo que un traslado necesita.
 */
@Serializable
data class CreateTransferRequest(
    val fromWarehouseId: Long,
    val toWarehouseId: Long,
    val lines: List<CreateTransferLineRequest> = emptyList(),
)

/** AMBIGUO: línea del alta de traslado, no documentada en el contrato. */
@Serializable
data class CreateTransferLineRequest(
    val productId: Long,
    val fromLocationId: Long,
    val quantity: Double,
)

/**
 * Cuerpo de `POST /v1/transfers/{id}/cancel`.
 *
 * AMBIGUO: el contrato documenta la ruta y no el cuerpo. Se envía `reason`
 * opcional en lugar de no enviar cuerpo, porque cancelar un traslado sin motivo
 * es exactamente el tipo de acción que la auditoría necesita registrar.
 */
@Serializable
data class CancelTransferRequest(
    val reason: String? = null,
)
