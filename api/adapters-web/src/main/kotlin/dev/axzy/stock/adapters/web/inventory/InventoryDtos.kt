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
 * The HTTP contract of inventory (docs/api/inventario.md).
 *
 * Quantities travel **as a string with three decimals** (`"100.000"`), which is the general
 * rule of the API: JSON has no arbitrary-precision decimals, and a JavaScript
 * `number` invites adding them in floating point. The exception is the
 * catalog, which sends them as a number because the interface only displays them.
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
    // ISO 8601 in UTC: it is the format the contract fixes for instants.
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

/** The fields are optional so that the use case says WHICH one is missing. */
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
     * The invoice line the goods leave from, if they come from a receipt.
     * Optional: locating goods that do not come from an invoice is still possible.
     */
    val invoiceLineId: Long? = null,
)

data class MovementResponseDto(
    val kardexIds: List<Long>,
    val status: String,
    /** `null` when the total does not change (locating and moving) or when the adjustment is left pending. */
    val total: String?,
    val message: String?,
)

fun MovementResult.toDto(): MovementResponseDto = MovementResponseDto(
    kardexIds = kardexIds,
    status = status.name,
    total = total?.toContractString(),
    message = message,
)

/** The HTTP status that corresponds to the result: created, or accepted pending approval. */
val MovementResult.isPendingApproval: Boolean get() = status == MovementStatus.PENDING_APPROVAL
