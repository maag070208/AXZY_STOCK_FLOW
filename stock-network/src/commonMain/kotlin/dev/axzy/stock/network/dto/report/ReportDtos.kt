package dev.axzy.stock.network.dto.report

import dev.axzy.stock.network.dto.common.Page
import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * REPORTES — DTOs de `/v1/reports` y sus variantes
 *
 * Contrato: docs/historico/CONTRATO_API.md §9. Todos devuelven el mismo sobre de paginación,
 * salvo las exportaciones, que devuelven un archivo.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Una fila del reporte de existencias. */
@Serializable
data class InventoryReportRowDto(
    val productCode: String,
    val name: String,
    val locationDisplayName: String,
    val quantity: Double,
    val warehouseCode: String,
)

/** Los totales del reporte, **calculados sobre el filtro completo**, no sobre la página. */
@Serializable
data class InventoryReportTotalsDto(
    val distinctProducts: Int = 0,
    val totalUnits: Double = 0.0,
)

/**
 * El reporte de existencias.
 *
 * No se reutiliza `Page<T>` porque este reporte **añade `totals` al sobre**.
 * Se modela tal cual y se ofrece [toPage] para no perder la comodidad del sobre
 * donde haga falta. Los totales son del conjunto filtrado entero: un total
 * calculado sobre la página visible haría que el número cambiara al paginar, y
 * ese es el tipo de cifra que nadie vuelve a creer.
 */
@Serializable
data class InventoryReportDto(
    val items: List<InventoryReportRowDto> = emptyList(),
    val totals: InventoryReportTotalsDto? = null,
    val page: Int = Page.FIRST_PAGE,
    val size: Int = Page.DEFAULT_SIZE,
    val totalItems: Int = 0,
    val totalPages: Int = 0,
) {
    /** El mismo contenido visto como sobre genérico. */
    fun toPage(): Page<InventoryReportRowDto> = Page(
        items = items,
        page = page,
        size = size,
        totalItems = totalItems,
        totalPages = totalPages,
    )
}

/**
 * Formato de exportación de `GET /v1/reports/kardex/export?format=xlsx|csv`.
 *
 * Los filtros de la exportación **son los mismos que los del listado**: si el
 * usuario ve 200 filas filtradas y exporta, debe recibir esas 200. Por eso el
 * cliente reutiliza el mismo `KardexQuery` en las dos llamadas.
 */
enum class ReportFormat(val value: String) {
    XLSX("xlsx"),
    CSV("csv"),
}

/**
 * El reporte de reconciliación: kardex contra proyecciones.
 *
 * En el legacy esta comprobación **no existía**, y el descuadre solo se
 * descubría con un conteo físico. `divergences` es lo primero que hay que mirar:
 * si no es cero, alguien escribió stock sin pasar por el ledger.
 */
@Serializable
data class KardexVsProjectionDto(
    val checkedAt: String? = null,
    val productsChecked: Int = 0,
    val divergences: Int = 0,
    val items: List<KardexProjectionItemDto> = emptyList(),
)

/** Una fila de la reconciliación. `difference` idealmente `0.000`. */
@Serializable
data class KardexProjectionItemDto(
    val productCode: String,
    /** Suma algebraica del ledger. */
    val ledgerNet: Double,
    /** Lo que dice la proyección de existencias. */
    val projected: Double,
    val difference: Double,
)
