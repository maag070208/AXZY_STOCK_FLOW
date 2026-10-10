package dev.axzy.stock.application.catalog

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.inventory.InventoryCatalog
import dev.axzy.stock.application.inventory.LocationRef
import dev.axzy.stock.application.inventory.MovementCommand
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.catalog.CatalogImporter
import dev.axzy.stock.domain.catalog.CatalogRow
import dev.axzy.stock.domain.catalog.ValidatedProduct
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.catalog.ImportReport
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.domain.value.Quantity

/**
 * The import report, as the API returns it.
 *
 * `stockPersisted` is **always `false`** in this phase, and it is not an oversight: the
 * file carries the legacy total and does not say which shelf each piece is on.
 * Loading it into a location would misstate the inventory from day one; it is reconciled with a
 * count before the cutover (ADR-0012). The `INITIAL` movement and the stock
 * belong to phase 5.
 */
data class CatalogImportResult(
    val dryRun: Boolean,
    /**
     * How many products **did not** have stock and received their initial load.
     *
     * The initial load is the inherited total placed in `UNASSIGNED`: it does not say which
     * shelf each piece is on, and that is resolved by counting ([ADR-0012]). It is only loaded
     * to whoever has nothing: reimporting the file cannot duplicate the stock (K2).
     */
    val stockLoaded: Int,
    /** How many already had stock and were left as they were. */
    val stockSkipped: Int,
    val totalRows: Int,
    val accepted: Int,
    val rejected: Int,
    val totalStock: Quantity,
    val zeroStockCount: Int,
    val unitMeasures: List<String>,
    val rejections: List<DomainError.ImportInvalidRows.RejectedRow>,
    val created: Int,
    val updated: Int,
    val skipped: Int,
) {
    val persisted: Int get() = created + updated
    val stockPersisted: Boolean get() = stockLoaded > 0
}

/**
 * Imports the initial catalog file (docs/flujos/catalogo.md §4).
 *
 * **Strict**: one invalid row rejects the whole file and nothing is written.
 * It corrects nothing by guessing; a tolerant importer hides capture errors that
 * later reappear as miscounted stock.
 *
 * **Idempotent**: the `upsert` goes by `product_code`, so importing the same file twice does not
 * duplicate products (K2). Those that already exist unchanged are counted as `skipped`, which is what
 * counted as `skipped`, which is what makes visible that the second time it did
 * nothing, instead of saying "1,398 updated".
 */
class ImportCatalog(
    private val products: Products,
    private val units: UnitsOfMeasure,
    private val ledger: StockLedger,
    private val inventory: InventoryCatalog,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(
        actor: Actor,
        rows: List<CatalogRow>,
        dryRun: Boolean,
        origin: RequestOrigin,
    ): Outcome<CatalogImportResult> =
        access.authorizeOr(actor, "product.import", origin) { grant ->
            if (rows.isEmpty()) {
                return@authorizeOr failure(
                    FailureKind.INVALID_REQUEST, "EMPTY_FILE", "The file has no data rows.",
                )
            }

            // The closed catalog of units belongs to the database, not the code: a
            // new unit is added with a migration, not with a deployment.
            val importer = CatalogImporter(units.all().map { it.code }.toSet())
            val report = importer.import(rows, dryRun = dryRun)

            when {
                report.hasRejections -> DomainError.ImportInvalidRows(
                    report.rejected.map { DomainError.ImportInvalidRows.RejectedRow(it.rowNumber, it.reason) },
                ).toFailure().asLeft()

                dryRun -> resultOf(report, UpsertSummary(0, 0, 0)).asRight()

                else -> tx.inTransaction {
                    val summary = products.upsertAll(report.accepted)
                    val stock = loadInitialStock(actor, report.accepted)
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "CATALOG_IMPORTED",
                            level = AuditLevel.CRITICAL,
                            entity = AuditEntity("catalog", null, "Product catalog"),
                            grant = grant,
                            newState = mapOf(
                                "totalRows" to report.totalRows,
                                "created" to summary.created,
                                "updated" to summary.updated,
                                "skipped" to summary.skipped,
                                "totalStock" to report.totalStock.toString(),
                                "stockLoaded" to stock.loaded,
                                "stockSkipped" to stock.skipped,
                                "stockLocation" to stock.location,
                            ),
                        ),
                    )
                    resultOf(report, summary, stock).asRight()
                }
            }
        }

    /**
     * The initial stock load: the inherited total, to `UNASSIGNED`.
     *
     * **Only to whoever has nothing.** The legacy file carries a total per product and does not
     * say which shelf each piece is on ([ADR-0012]); what this load does is leave
     * that total in the unassigned balance so that the physical count can distribute it. Loading it into a
     * product that **already has stock** would double the inventory by merely reimporting the
     * file, so it is skipped and counted separately: the report says how many were skipped, instead of
     * hiding it.
     *
     * The movement is `INITIAL` in the ledger, with the unassigned location and the user who
     * imported: it is left with a responsible person and a date.
     */
    private fun loadInitialStock(actor: Actor, accepted: List<ValidatedProduct>): StockLoad {
        val conStock = accepted.filter { it.stock.isPositive }
        if (conStock.isEmpty()) return StockLoad(0, 0, null)

        val warehouseId = inventory.defaultWarehouseId()
        val unassigned = inventory.activeLocation(warehouseId, LocationRef.Unassigned)
            ?: error(
                "Location UNASSIGNED does not exist in warehouse $warehouseId. The schema seeds it (V6); " +
                    "without it there is nowhere to put the inherited stock.",
            )

        var loaded = 0
        var skipped = 0
        conStock.forEach { product ->
            val actual = products.findByCode(product.code)?.id
            if (actual == null) {
                skipped++
                return@forEach
            }
            if (ledger.productTotal(actual).isPositive) {
                skipped++
                return@forEach
            }
            ledger.apply(
                MovementCommand(
                    productId = actual,
                    warehouseId = warehouseId,
                    moveType = MovementType.INITIAL,
                    units = product.stock,
                    toLocationId = unassigned.id,
                    userId = actor.id,
                ),
            )
            loaded++
        }
        return StockLoad(loaded, skipped, unassigned.displayName)
    }

    private data class StockLoad(val loaded: Int, val skipped: Int, val location: String?)

    private fun resultOf(report: ImportReport, summary: UpsertSummary, stock: StockLoad = StockLoad(0, 0, null)) = CatalogImportResult(
        dryRun = report.isDryRun,
        totalRows = report.totalRows,
        accepted = report.acceptedCount,
        rejected = report.rejectedCount,
        totalStock = report.totalStock,
        stockLoaded = stock.loaded,
        stockSkipped = stock.skipped,
        zeroStockCount = report.zeroStockCount,
        unitMeasures = report.unitMeasures,
        rejections = report.rejected.map { DomainError.ImportInvalidRows.RejectedRow(it.rowNumber, it.reason) },
        created = summary.created,
        updated = summary.updated,
        skipped = summary.skipped,
    )
}
