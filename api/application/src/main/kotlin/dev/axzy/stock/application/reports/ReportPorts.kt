package dev.axzy.stock.application.reports

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.inventory.KardexLine
import dev.axzy.stock.application.inventory.StockLine
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.LocalDate

/**
 * Los reportes de existencias (M02, docs/api/reportes.md).
 *
 * Leen de las mismas tablas que las consultas de inventario y comparten sus
 * filtros: un reporte con otro filtro obliga a reconciliar dos vistas de los mismos
 * datos.
 */

/** Filtros del reporte de existencias. */
data class InventoryReportFilter(
    val productCode: ProductCode?,
    val warehouseCode: String?,
    val onlyWithStock: Boolean,
)

/**
 * Los totales del reporte, calculados sobre **el filtro entero** y no sobre la
 * página: un total que ignora el filtro es peor que no tenerlo, porque el usuario
 * decide con él.
 */
data class InventoryTotals(
    val distinctProducts: Long,
    val totalUnits: Quantity,
)

/**
 * Por qué se agrupa el resumen de movimientos.
 *
 * Las expresiones convierten el instante a la **hora del almacén** antes de truncar
 * (`AT TIME ZONE :zone`): un movimiento de las 21:00 del día 9 es del día 9, aunque en
 * UTC sea ya el 10. Un resumen agrupado en UTC tiene los movimientos de la tarde en el
 * día siguiente y no cuadra con lo que pasó en el piso.
 */
enum class SummaryGroup(val sql: String) {
    DAY("date_trunc('day', k.moved_at AT TIME ZONE :zone)"),
    WEEK("date_trunc('week', k.moved_at AT TIME ZONE :zone)"),
    MONTH("date_trunc('month', k.moved_at AT TIME ZONE :zone)"),
    PRODUCT("p.product_code"),
    LOCATION("location_id"),
    ;

    companion object {
        fun parse(raw: String?): SummaryGroup? = when (raw?.trim()?.lowercase()) {
            null, "" -> DAY
            else -> entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
        }
    }
}

data class MovementSummaryFilter(
    val from: LocalDate,
    val to: LocalDate,
    val groupBy: SummaryGroup,
)

data class SummaryLine(
    /** El valor por el que se agrupa: una fecha ISO, un código o un nombre visible. */
    val key: String,
    /** Su forma para mostrar. Se separan porque una fecha se ordena como texto ISO. */
    val keyLabel: String,
    val inUnits: Quantity,
    val outUnits: Quantity,
    val movements: Int,
) {
    val netUnits: Quantity get() = inUnits - outUnits
}

data class SummaryTotals(
    val inUnits: Quantity,
    val outUnits: Quantity,
    val movements: Int,
) {
    val netUnits: Quantity get() = inUnits - outUnits
}

/** Una fila descuadrada entre el ledger y la proyección. */
data class Divergence(
    val productId: Long,
    val productCode: String,
    val projectedTotal: Quantity,
    val ledgerTotal: Quantity,
    val difference: Quantity,
)

data class Reconciliation(val checkedAt: java.time.Instant, val items: List<Divergence>) {
    val balanced: Boolean get() = items.isEmpty()
    val divergences: Int get() = items.size
}

interface ReportQueries {
    fun inventory(filter: InventoryReportFilter, request: PageRequest): Page<StockLine>

    fun inventoryTotals(filter: InventoryReportFilter): InventoryTotals

    fun movementSummary(filter: MovementSummaryFilter): List<SummaryLine>

    /** Solo los productos descuadrados: el estado sano es una lista vacía. */
    fun divergences(): List<Divergence>

    /**
     * Las filas del kardex para exportar, en trozos.
     *
     * Un `offset` que avanza es lo que hace que el archivo no cargue el kardex
     * entero en memoria; el presupuesto es **una consulta por cada `chunkSize`
     * filas**, nunca una por fila (R-P2).
     */
    fun kardexChunk(filter: KardexFilter, offset: Int, chunkSize: Int): List<KardexLine>
}

class InventoryReport(private val queries: ReportQueries, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        filter: InventoryReportFilter,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<InventoryReportResult> =
        access.authorizeOr(actor, "report.view", origin) {
            InventoryReportResult(
                page = queries.inventory(filter, request),
                totals = queries.inventoryTotals(filter),
            ).asRight()
        }
}

data class InventoryReportResult(val page: Page<StockLine>, val totals: InventoryTotals)

class MovementSummary(private val queries: ReportQueries, private val access: AccessDecider) {

    fun execute(actor: Actor, filter: MovementSummaryFilter, origin: RequestOrigin): Outcome<MovementSummaryResult> =
        access.authorizeOr(actor, "report.view", origin) {
            val items = queries.movementSummary(filter)
            /*
             * Los totales son la suma de los grupos, y se calculan aquí en vez de con
             * una segunda consulta por dos razones: la aritmética de `Quantity` es
             * exacta (milésimas enteras) y así no puede pasar que el sobre diga una
             * cosa y las filas otra. El resumen no se pagina: los grupos son los del
             * filtro entero.
             */
            MovementSummaryResult(
                items = items,
                totals = SummaryTotals(
                    inUnits = items.fold(Quantity.ZERO) { acc, line -> acc + line.inUnits },
                    outUnits = items.fold(Quantity.ZERO) { acc, line -> acc + line.outUnits },
                    movements = items.sumOf { it.movements },
                ),
            ).asRight()
        }
}

data class MovementSummaryResult(val items: List<SummaryLine>, val totals: SummaryTotals)

class KardexReconciliation(
    private val queries: ReportQueries,
    private val access: AccessDecider,
    private val clock: java.time.Clock,
) {
    fun execute(actor: Actor, origin: RequestOrigin): Outcome<Reconciliation> =
        access.authorizeOr(actor, "report.view", origin) {
            Reconciliation(checkedAt = clock.instant(), items = queries.divergences()).asRight()
        }
}
