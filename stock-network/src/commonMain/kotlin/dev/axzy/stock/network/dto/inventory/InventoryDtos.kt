package dev.axzy.stock.network.dto.inventory

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * INVENTARIO — DTOs de `/v1/inventory` y sus acciones
 *
 * Contrato: docs/historico/CONTRATO_API.md §4.
 *
 * Regla del contrato que condiciona el diseño entero de estos DTOs: **el kardex
 * es append-only**. No existe `PUT` ni `DELETE` sobre un movimiento; las
 * correcciones son movimientos nuevos. Por eso aquí hay cuerpos para crear y
 * ninguno para modificar.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Existencia de un producto en una ubicación.
 *
 * Es una **proyección derivada** de los movimientos, no un número que alguien
 * escriba: en el legacy `store.stock` se escribía por API y de ahí venían los
 * descuadres.
 */
@Serializable
data class StockLevelDto(
    val productId: Long,
    val productCode: String,
    val locationId: Long,
    val locationDisplayName: String,
    val warehouseId: Long,
    val quantity: Double,
)

/** Filtros de `GET /v1/inventory/stock`. */
data class StockQuery(
    val productId: Long? = null,
    val locationId: Long? = null,
    val warehouseId: Long? = null,
    /** Si es `true`, se omiten las filas en cero. */
    val onlyWithStock: Boolean? = null,
)

/**
 * Una entrada del kardex.
 *
 * `quantityAfter` es la cantidad total del producto **después** del movimiento:
 * el servidor la guarda para que reconstruir un saldo histórico no obligue a
 * sumar el ledger entero.
 */
@Serializable
data class KardexEntryDto(
    val id: Long,
    /** Instante ISO 8601 en UTC, con `Z`. */
    val movedAt: String,
    val productCode: String,
    val moveType: KardexMoveType,
    val units: Double,
    val quantityAfter: Double,
    val fromLocationId: Long? = null,
    val fromLocationDisplayName: String? = null,
    val toLocationId: Long? = null,
    val toLocationDisplayName: String? = null,
    val container: String? = null,
    val orderNumber: String? = null,
    val adjustmentReason: String? = null,
    val userId: Long,
    val userName: String,
    val note: String? = null,
    /**
     * **Solo viene en la respuesta de un ajuste**, no en el listado del kardex:
     * es lo que indica si el ajuste quedó aplicado o pendiente de aprobación.
     *
     * AMBIGUO: el contrato dice «el `201` lo indica con `"status":
     * "PENDING_APPROVAL"`» y no fija dónde. Se modela como un campo más del
     * movimiento, que es la lectura más literal y la que no inventa estructura.
     */
    val status: String? = null,
)

/**
 * Tipo de movimiento del ledger, tal como lo nombra el contrato.
 *
 * **Es un tipo de transporte, no el del dominio**: el dominio tiene su propio
 * `MovementType` (con el efecto declarado de cada movimiento) y la traducción
 * entre los dos es un mapper explícito. Duplicar el enum aquí es deliberado:
 * si el backend renombra un movimiento, el mapper deja de compilar y el cambio
 * se ve, en lugar de propagarse solo al dominio.
 */
@Serializable
enum class KardexMoveType {
    @SerialName("INITIAL")
    INITIAL,

    @SerialName("RECEIPT_IN")
    RECEIPT_IN,

    @SerialName("ADJUSTMENT_IN")
    ADJUSTMENT_IN,

    @SerialName("FULFILLMENT_OUT")
    FULFILLMENT_OUT,

    @SerialName("ADJUSTMENT_OUT")
    ADJUSTMENT_OUT,

    @SerialName("LOCATE")
    LOCATE,

    @SerialName("MOVE_WITHIN_WAREHOUSE")
    MOVE_WITHIN_WAREHOUSE,

    @SerialName("TRANSFER_OUT")
    TRANSFER_OUT,

    @SerialName("TRANSFER_IN")
    TRANSFER_IN,

    @SerialName("PENDING_OUT")
    PENDING_OUT,
}

/** Filtros de `GET /v1/inventory/kardex`. */
data class KardexQuery(
    val productCode: String? = null,
    /** Fecha operativa `YYYY-MM-DD`, sin hora. */
    val from: String? = null,
    val to: String? = null,
    val moveType: KardexMoveType? = null,
)

/** Sentido de un ajuste. `OUT` exige que haya existencia suficiente. */
@Serializable
enum class AdjustmentDirection {
    @SerialName("IN")
    IN,

    @SerialName("OUT")
    OUT,
}

/**
 * Motivo del ajuste. **Obligatorio siempre**: un ajuste sin motivo es un
 * agujero de auditoría, y es la diferencia entre una merma y un robo.
 */
@Serializable
enum class AdjustmentReason {
    @SerialName("SHORTAGE")
    SHORTAGE,

    @SerialName("DAMAGE")
    DAMAGE,

    @SerialName("THEFT")
    THEFT,

    @SerialName("SUPPLIER_ERROR")
    SUPPLIER_ERROR,

    @SerialName("COUNT_ERROR")
    COUNT_ERROR,

    @SerialName("RETURN_TO_SUPPLIER")
    RETURN_TO_SUPPLIER,

    @SerialName("EXPIRED")
    EXPIRED,

    @SerialName("OTHER")
    OTHER,
}

/**
 * Origen del ajuste: de qué flujo nació.
 *
 * Es lo que unifica los cuatro orígenes que el legacy tenía como caminos
 * separados. La regla de ajuste es **una sola**, y este campo dice quién la
 * invocó.
 */
@Serializable
enum class AdjustmentOrigin {
    @SerialName("INVOICE_DISCREPANCY")
    INVOICE_DISCREPANCY,

    @SerialName("FULFILLMENT_SHORTAGE")
    FULFILLMENT_SHORTAGE,

    @SerialName("MANUAL_COUNT")
    MANUAL_COUNT,

    @SerialName("DAMAGE_REPORT")
    DAMAGE_REPORT,
}

/**
 * Cuerpo de `POST /v1/inventory/adjustments` — la **regla única `AdjustStock`**.
 *
 * `idempotencyKey` viaja también en la cabecera `Idempotency-Key` (§1.5): el
 * móvil sin señal reintenta, y sin esto un reintento duplica un movimiento.
 * Se envía en los dos sitios porque el contrato lo declara en el cuerpo y §1.5
 * lo exige en la cabecera.
 */
@Serializable
data class AdjustmentRequest(
    val productId: Long,
    val locationId: Long,
    val quantity: Double,
    val direction: AdjustmentDirection,
    val reason: AdjustmentReason,
    val origin: AdjustmentOrigin,
    val note: String? = null,
    val idempotencyKey: String,
)

/**
 * Cuerpo de `POST /v1/inventory/locate`: ubicar mercancía recibida.
 *
 * **El total NO cambia**: es un traslado de la ubicación de recepción al
 * estante. El legacy mutaba el registro de recepción del kardex y por eso su
 * libro dejó de ser un libro.
 */
@Serializable
data class LocateRequest(
    val invoiceLineId: Long,
    val toLocationId: Long,
    val quantity: Double,
    val idempotencyKey: String,
)

/**
 * Cuerpo de `POST /v1/inventory/move`: traslado entre estantes de la misma
 * bodega. `422 SAME_SOURCE_AND_DESTINATION` si origen y destino coinciden.
 */
@Serializable
data class MoveRequest(
    val productId: Long,
    val fromLocationId: Long,
    val toLocationId: Long,
    val quantity: Double,
    val idempotencyKey: String,
)
