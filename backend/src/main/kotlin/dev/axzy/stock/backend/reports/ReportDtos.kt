package dev.axzy.stock.backend.reports

import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * DTOs DE REPORTES (§9 del contrato)
 *
 * **Las cantidades van como CADENA decimal de 3 decimales** (`"100.000"`), no
 * como número, por la misma razón que en el resto del contrato (§1.7): JSON no
 * tiene decimales de precisión arbitraria y un `number` de JavaScript invitaría
 * al cliente a sumar en coma flotante. En un reporte, además, **la suma es lo
 * único que el usuario va a hacer con el dato**, así que el error no sería
 * cosmético: sería un total que no cuadra.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Una fila de existencias por producto y ubicación (§9). */
@Serializable
data class InventoryReportItem(
    val productCode: String,
    val name: String,
    /** El nombre visible de la ubicación, compuesto por el SERVIDOR. */
    val locationDisplayName: String,
    val quantity: String,
    val warehouseCode: String,
)

/** Totales del reporte de existencias, calculados sobre TODO el filtro. */
@Serializable
data class InventoryReportTotals(
    /** Productos DISTINTOS con existencia, no filas. */
    val distinctProducts: Long,
    val totalUnits: String,
)

@Serializable
data class InventoryReportResponse(
    val items: List<InventoryReportItem>,
    val totals: InventoryReportTotals,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

/**
 * Una fila del resumen de movimientos, ya agrupada.
 *
 * `key` y `keyLabel` se separan **a propósito** y el contrato lo explica: una
 * fecha se ORDENA como texto ISO (`2026-10-07`) y se MUESTRA formateada según el
 * idioma. Si se mandara una sola, el servidor estaría decidiendo el formato de
 * presentación del cliente; y si se mandara solo la de mostrar, el orden
 * alfabético de las fechas dejaría de coincidir con el cronológico.
 */
@Serializable
data class MovementsSummaryItem(
    val key: String,
    val keyLabel: String,
    val inUnits: String,
    val outUnits: String,
    val netUnits: String,
    val movements: Long,
)

@Serializable
data class MovementsSummaryTotals(
    val inUnits: String,
    val outUnits: String,
    val netUnits: String,
    val movements: Long,
)

@Serializable
data class MovementsSummaryResponse(
    val from: String,
    val to: String,
    val groupBy: String,
    val totals: MovementsSummaryTotals,
    val items: List<MovementsSummaryItem>,
)
