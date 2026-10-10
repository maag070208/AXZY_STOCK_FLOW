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
 * The stock reports (M02, docs/api/reportes.md).
 *
 * They read from the same tables as the inventory queries and share their
 * filters: a report with another filter forces reconciling two views of the same data
 * datos.
 */

/** Stock report filters. */
data class InventoryReportFilter(
    val productCode: ProductCode?,
    val warehouseCode: String?,
    val onlyWithStock: Boolean,
)

/**
 * The report totals, computed over **the whole filter** and not over the
 * page: a total that ignores the filter is worse than not having it, because the
 * user decides with it.
 */
data class InventoryTotals(
    val distinctProducts: Long,
    val totalUnits: Quantity,
)

/**
 * Why the movement summary is grouped.
 *
 * The expressions convert the instant to the **warehouse's time** before truncating
 * (`AT TIME ZONE :zone`): a movement at 21:00 on the 9th is from the 9th, even though in
 * UTC it is already the 10th. A summary grouped in UTC puts the afternoon's movements on the
 * next day and does not match what happened on the floor.
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
    /** The value grouped by: an ISO date, a code or a visible name. */
    val key: String,
    /** Its display form. They are separated because a date sorts as ISO text. */
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

/** A row out of balance between the ledger and the projection. */
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

    /** Only the out-of-balance products: the healthy state is an empty list. */
    fun divergences(): List<Divergence>

    /**
     * The ledger rows to export, in chunks.
     *
     * An `offset` that advances is what keeps the file from loading the whole ledger
     * into memory; the budget is **one query for each `chunkSize`**
     * rows**, never one per row (R-P2).
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
             * The totals are the sum of the groups, and they are computed here instead of with
             * a second query for two reasons: the arithmetic of `Quantity` is
             * exact (integer thousandths) and so it cannot happen that the envelope says one
             * thing and the rows another. The summary is not paginated: the groups are those of the
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
