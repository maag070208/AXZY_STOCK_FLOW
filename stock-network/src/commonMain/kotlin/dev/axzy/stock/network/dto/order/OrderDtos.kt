package dev.axzy.stock.network.dto.order

import dev.axzy.stock.network.dto.inventory.AdjustmentReason
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SURTIDO — DTOs de `/v1/orders`
 *
 * Contrato: CONTRATO_API.md §6.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Una orden de surtido.
 *
 * `converted` y `exportStatus`/`exportAttempts`/`exportLastError` son el estado
 * de la exportación al ERP: **el reintento es manual e idempotente** (la clave
 * es el `orderNumber` convertido), así que el cliente necesita ver si ya se
 * intentó y por qué falló. En el legacy ese estado vivía en un log.
 */
@Serializable
data class OrderDto(
    val id: Long,
    val orderNumber: String,
    /** Fecha operativa `YYYY-MM-DD`. */
    val packagingDate: String,
    val status: OrderStatus = OrderStatus.PENDING,
    val customerCode: String? = null,
    val concept: String? = null,
    val converted: Boolean = false,
    val convertedAt: String? = null,
    val exportStatus: String? = null,
    val exportAttempts: Int = 0,
    val exportLastError: String? = null,
    val totalLines: Int = 0,
    val packagedLines: Int = 0,
)

/** Estados de una orden. Conjunto cerrado según el contrato. */
@Serializable
enum class OrderStatus {
    @SerialName("PENDING")
    PENDING,

    @SerialName("IN_PROGRESS")
    IN_PROGRESS,

    @SerialName("PACKAGED")
    PACKAGED,

    @SerialName("PENDING_TO_OUT")
    PENDING_TO_OUT,

    @SerialName("COMPLETED")
    COMPLETED,

    @SerialName("CANCELLED")
    CANCELLED,
}

/**
 * Cuerpo de `POST /v1/orders`.
 *
 * AMBIGUO: el contrato documenta la ruta pero no el cuerpo del alta. Se
 * incluyen los campos que la respuesta declara más las líneas, que son
 * imprescindibles para crear una orden con contenido.
 */
@Serializable
data class CreateOrderRequest(
    val packagingDate: String,
    val customerCode: String? = null,
    val concept: String? = null,
    val lines: List<CreateOrderLineRequest> = emptyList(),
)

/** AMBIGUO: línea del alta de orden, no documentada en el contrato. */
@Serializable
data class CreateOrderLineRequest(
    val productId: Long,
    val quantity: Double,
)

/**
 * Cuerpo de `POST /v1/orders/{id}/lines/{lineId}/package`.
 *
 * Descuenta stock y escribe `FULFILLMENT_OUT` en el kardex. Si no alcanza,
 * `422 INSUFFICIENT_STOCK` con `details.available`, **y se ofrece la opción de
 * faltante**, que es un ajuste con origen `FULFILLMENT_SHORTAGE`, no un segundo
 * flujo.
 */
@Serializable
data class PackageLineRequest(
    val quantity: Double,
    val locationId: Long,
    val idempotencyKey: String,
)

/**
 * Resultado de `POST /v1/orders/{id}/export`.
 *
 * La exportación es **idempotente**: la clave es el `orderNumber` convertido,
 * así que reintentar no duplica nada en el ERP. `nextRetryAt` es lo que permite
 * a la UI decir «se reintenta a las 21:40» en lugar de «falló».
 */
@Serializable
data class OrderExportResultDto(
    val attempts: Int = 0,
    val status: String,
    val lastError: String? = null,
    val nextRetryAt: String? = null,
)

/**
 * Cuerpo de `POST /v1/orders/{id}/shortage`.
 *
 * Delega en la regla única de ajuste: el faltante **no es un camino aparte**,
 * es un ajuste con origen `FULFILLMENT_SHORTAGE`. Por eso reutiliza
 * [AdjustmentReason] en lugar de tener su propio catálogo de motivos.
 */
@Serializable
data class ShortageRequest(
    val lineId: Long,
    val quantity: Double,
    val reason: AdjustmentReason = AdjustmentReason.SHORTAGE,
    val note: String? = null,
)
