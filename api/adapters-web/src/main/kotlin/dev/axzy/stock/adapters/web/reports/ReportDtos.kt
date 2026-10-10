package dev.axzy.stock.adapters.web.reports

import dev.axzy.stock.adapters.web.inventory.toContractString
import dev.axzy.stock.application.inventory.StockLine
import dev.axzy.stock.application.reports.Divergence
import dev.axzy.stock.application.reports.InventoryReportResult
import dev.axzy.stock.application.reports.InventoryTotals
import dev.axzy.stock.application.reports.MovementSummaryResult
import dev.axzy.stock.application.reports.Reconciliation
import dev.axzy.stock.application.reports.SummaryLine
import dev.axzy.stock.application.reports.SummaryTotals
import java.time.Instant

/**
 * The HTTP contract of the reports (docs/api/reportes.md).
 *
 * Quantities travel as a string with three decimals, as in the rest of the API
 * except the catalog; in the XLSX export they go as a **number**, because a
 * text column is not summed and Excel would count it as zero.
 */

// ── Stock report ──────────────────────────────────────────────────────

data class InventoryReportLineDto(
    val productCode: String,
    val name: String,
    val locationDisplayName: String,
    val quantity: String,
    val warehouseCode: String,
)

fun StockLine.toReportDto(): InventoryReportLineDto = InventoryReportLineDto(
    productCode = productCode,
    name = productName,
    locationDisplayName = locationDisplayName,
    quantity = quantity.toContractString(),
    warehouseCode = warehouseCode,
)

data class InventoryTotalsDto(val distinctProducts: Long, val totalUnits: String)

fun InventoryTotals.toDto(): InventoryTotalsDto =
    InventoryTotalsDto(distinctProducts, totalUnits.toContractString())

data class InventoryReportDto(
    val items: List<InventoryReportLineDto>,
    val totals: InventoryTotalsDto,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

fun InventoryReportResult.toDto(): InventoryReportDto = InventoryReportDto(
    items = page.items.map { it.toReportDto() },
    totals = totals.toDto(),
    page = page.page,
    size = page.size,
    totalItems = page.totalItems,
    totalPages = page.totalPages,
)

// ── Movement summary ──────────────────────────────────────────────────

data class SummaryLineDto(
    val key: String,
    val keyLabel: String,
    val inUnits: String,
    val outUnits: String,
    val netUnits: String,
    val movements: Int,
)

fun SummaryLine.toDto(): SummaryLineDto = SummaryLineDto(
    key = key,
    keyLabel = keyLabel,
    inUnits = inUnits.toContractString(),
    outUnits = outUnits.toContractString(),
    netUnits = netUnits.toContractString(),
    movements = movements,
)

data class SummaryTotalsDto(
    val inUnits: String,
    val outUnits: String,
    val netUnits: String,
    val movements: Int,
)

fun SummaryTotals.toDto(): SummaryTotalsDto =
    SummaryTotalsDto(inUnits.toContractString(), outUnits.toContractString(), netUnits.toContractString(), movements)

data class MovementSummaryDto(
    val from: String,
    val to: String,
    val groupBy: String,
    val totals: SummaryTotalsDto,
    val items: List<SummaryLineDto>,
)

fun MovementSummaryResult.toDto(from: String, to: String, groupBy: String): MovementSummaryDto =
    MovementSummaryDto(from, to, groupBy, totals.toDto(), items.map { it.toDto() })

// ── Reconciliation ──────────────────────────────────────────────────────────

data class DivergenceDto(
    val productId: Long,
    val productCode: String,
    val projectedTotal: String,
    val ledgerTotal: String,
    val difference: String,
)

fun Divergence.toDto(): DivergenceDto = DivergenceDto(
    productId = productId,
    productCode = productCode,
    projectedTotal = projectedTotal.toContractString(),
    ledgerTotal = ledgerTotal.toContractString(),
    difference = difference.toContractString(),
)

data class ReconciliationDto(
    val checkedAt: String,
    val balanced: Boolean,
    val divergences: Int,
    val items: List<DivergenceDto>,
)

fun Reconciliation.toDto(): ReconciliationDto = ReconciliationDto(
    // ISO 8601 in UTC, the format the contract fixes for instants.
    checkedAt = Instant.ofEpochMilli(checkedAt.toEpochMilli()).toString(),
    // The verdict is given by the server: counting the array on the screen would be a
    // second calculation that can disagree with the first.
    balanced = balanced,
    divergences = divergences,
    items = items.map { it.toDto() },
)
