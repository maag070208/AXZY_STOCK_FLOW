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
 * El contrato HTTP de los reportes (docs/api/reportes.md).
 *
 * Las cantidades viajan como cadena con tres decimales, como en el resto de la API
 * salvo el catálogo; en el XLSX de la exportación van como **número**, porque una
 * columna de texto no se suma y Excel la contaría como cero.
 */

// ── Reporte de existencias ──────────────────────────────────────────────────

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

// ── Resumen de movimientos ──────────────────────────────────────────────────

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

// ── Reconciliación ──────────────────────────────────────────────────────────

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
    // ISO 8601 en UTC, el formato que el contrato fija para los instantes.
    checkedAt = Instant.ofEpochMilli(checkedAt.toEpochMilli()).toString(),
    // El veredicto lo da el servidor: contar el arreglo en la pantalla sería un
    // segundo cálculo que puede discrepar del primero.
    balanced = balanced,
    divergences = divergences,
    items = items.map { it.toDto() },
)
