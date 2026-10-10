package dev.axzy.stock.adapters.web.inventory

import dev.axzy.stock.application.inventory.KardexLine
import dev.axzy.stock.application.inventory.MovementResult
import dev.axzy.stock.application.inventory.MovementStatus
import dev.axzy.stock.application.inventory.StockLine
import dev.axzy.stock.adapters.web.http.PageDto
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal
import java.time.Instant

/**
 * El contrato HTTP de inventario (docs/api/inventario.md).
 *
 * Las cantidades viajan **como cadena con tres decimales** (`"100.000"`), que es la
 * regla general de la API: JSON no tiene decimales de precisión arbitraria y un
 * `number` de JavaScript invita a sumarlas en coma flotante. La excepción es el
 * catálogo, que las manda como número porque la interfaz solo las muestra.
 */
internal fun Quantity.toContractString(): String = BigDecimal.valueOf(value, Quantity.SCALE).toPlainString()

// ── Existencias ─────────────────────────────────────────────────────────────

data class StockLineDto(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val locationId: Long,
    val locationDisplayName: String,
    val warehouseId: Long,
    val warehouseCode: String,
    val quantity: String,
)

fun StockLine.toDto(): StockLineDto = StockLineDto(
    productId = productId,
    productCode = productCode,
    productName = productName,
    locationId = locationId,
    locationDisplayName = locationDisplayName,
    warehouseId = warehouseId,
    warehouseCode = warehouseCode,
    quantity = quantity.toContractString(),
)

// ── Kardex ──────────────────────────────────────────────────────────────────

data class KardexLineDto(
    val id: Long,
    val movedAt: String,
    val productCode: String,
    val productName: String,
    val moveType: String,
    val units: String,
    val quantityAfter: String,
    val fromLocationDisplayName: String?,
    val toLocationDisplayName: String?,
    val container: String?,
    val orderNumber: String?,
    val adjustmentReason: String?,
    val adjustmentOrigin: String?,
    val userId: Long?,
    val userName: String?,
    val note: String?,
)

fun KardexLine.toDto(): KardexLineDto = KardexLineDto(
    id = id,
    // ISO 8601 en UTC: es el formato que el contrato fija para los instantes.
    movedAt = movedAt.toString(),
    productCode = productCode,
    productName = productName,
    moveType = moveType,
    units = units.toContractString(),
    quantityAfter = quantityAfter.toContractString(),
    fromLocationDisplayName = fromLocationDisplayName,
    toLocationDisplayName = toLocationDisplayName,
    container = container,
    orderNumber = orderNumber,
    adjustmentReason = adjustmentReason,
    adjustmentOrigin = adjustmentOrigin,
    userId = userId,
    userName = userName,
    note = note,
)

// ── Movimientos ─────────────────────────────────────────────────────────────

/** Los campos son opcionales para que el caso de uso diga CUÁL falta. */
data class AdjustmentRequestDto(
    val productCode: String? = null,
    val locationCode: String? = null,
    val quantity: BigDecimal? = null,
    val direction: String? = null,
    val reason: String? = null,
    val origin: String? = null,
    val note: String? = null,
    val approverId: Long? = null,
)

data class MovementRequestDto(
    val productCode: String? = null,
    val fromLocationCode: String? = null,
    val toLocationCode: String? = null,
    val quantity: BigDecimal? = null,
    /**
     * La línea de factura de la que sale la mercancía, si viene de una recepción.
     * Opcional: ubicar mercancía que no viene de una factura sigue siendo posible.
     */
    val invoiceLineId: Long? = null,
)

data class MovementResponseDto(
    val kardexIds: List<Long>,
    val status: String,
    /** `null` cuando el total no cambia (ubicar y mover) o cuando el ajuste queda pendiente. */
    val total: String?,
    val message: String?,
)

fun MovementResult.toDto(): MovementResponseDto = MovementResponseDto(
    kardexIds = kardexIds,
    status = status.name,
    total = total?.toContractString(),
    message = message,
)

/** El estado HTTP que corresponde al resultado: creado o aceptado pendiente de aprobación. */
val MovementResult.isPendingApproval: Boolean get() = status == MovementStatus.PENDING_APPROVAL
