package dev.axzy.stock.backend.application.inventory

import kotlinx.serialization.Serializable

/*
 * La forma de las peticiones y respuestas de inventario (contrato §4 y §9).
 *
 * Las cantidades de las respuestas van como **cadena decimal** (`"5193.000"`) y no como número:
 * un `Double` compararía `5193.000` con `5193` como iguales, y el reporte de reconciliación
 * existe justo para ver diferencias de ese tamaño.
 */

@Serializable
data class AdjustmentRequestDto(
    val productCode: String,
    val locationCode: String,
    /** Número decimal con 3 decimales. La dirección indica si suma o resta. */
    val quantity: Double,
    /** `INCREASE` o `DECREASE`. */
    val direction: String,
    val reason: String,
    val origin: String,
    val note: String? = null,
    /**
     * Quién aprueba, si el ajuste lo requiere. Va en el cuerpo y no se deduce del token porque
     * **tiene que ser otra persona**: el dominio rechaza que coincida con quien solicita, y eso
     * es la segregación de funciones.
     */
    val approverId: Long? = null,
)

/** El cuerpo de ubicar y de mover: de una ubicación a otra, sin cambiar el total. */
@Serializable
data class StockMoveRequestDto(
    val productCode: String,
    val fromLocationCode: String,
    val toLocationCode: String,
    val quantity: Double,
)

@Serializable
data class MovementResponse(
    val kardexIds: List<Long>,
    val status: String,
    val total: String,
    val message: String? = null,
)

@Serializable
data class StockByLocationDto(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val locationId: Long,
    val locationDisplayName: String,
    val warehouseId: Long,
    val warehouseCode: String,
    val quantity: String,
)

@Serializable
data class KardexDto(
    val id: Long,
    val movedAt: String,
    val productCode: String,
    val productName: String,
    val moveType: String,
    val units: String,
    val quantityAfter: String,
    val fromLocationDisplayName: String? = null,
    val toLocationDisplayName: String? = null,
    val container: String? = null,
    val orderNumber: String? = null,
    val adjustmentReason: String? = null,
    val adjustmentOrigin: String? = null,
    val userId: Long? = null,
    val userName: String? = null,
    val note: String? = null,
)

/**
 * Una fila del reporte de reconciliación: un producto cuyo saldo proyectado no coincide con lo
 * que dice el ledger.
 *
 * Los nombres salen de las columnas del `RETURNS TABLE` de `comprobar_proyecciones()`, que la
 * migración `V8` pasó a inglés. El mapper las lee por nombre: si se renombra una y no la otra,
 * lo detecta `DivergenceMapperTest`, no el compilador.
 */
@Serializable
data class DivergenceDto(
    val productId: Long,
    val productCode: String,
    /** Lo que dice la proyección `product_stock`, que es un valor derivado. */
    val projectedTotal: String,
    /** Lo que dice el ledger, que es la **fuente de verdad**. */
    val ledgerTotal: String,
    /** `projectedTotal - ledgerTotal`. El signo importa y se conserva. */
    val difference: String,
)

@Serializable
data class ReconciliationReport(
    val checkedAt: String,
    /**
     * Veredicto explícito del servidor: `true` cuando **no hay ninguna divergencia**. Es un
     * booleano y no un `items.isEmpty()` en el cliente a propósito: contar el arreglo allí es
     * otro cálculo que puede divergir del del servidor.
     */
    val balanced: Boolean,
    val divergences: Int,
    val items: List<DivergenceDto>,
)
