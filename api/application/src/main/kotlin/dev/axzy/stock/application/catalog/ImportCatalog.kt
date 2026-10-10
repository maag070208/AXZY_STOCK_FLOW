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
 * El informe de la importación, tal como lo devuelve la API.
 *
 * `stockPersisted` es **siempre `false`** en esta fase, y no es un olvido: el
 * archivo trae el total del legacy y no dice en qué estantería está cada pieza.
 * Cargarlo a una ubicación inventaría mal desde el primer día; se concilia con
 * conteo antes del corte (ADR-0012). El movimiento `INITIAL` y las existencias
 * son de la fase 5.
 */
data class CatalogImportResult(
    val dryRun: Boolean,
    /**
     * Cuántos productos **no** tenían existencias y recibieron su carga inicial.
     *
     * La carga inicial es el total heredado puesto en `UNASSIGNED`: no dice en qué
     * estantería está cada pieza, y eso se resuelve con conteo ([ADR-0012]). Solo se carga
     * a quien no tiene nada: reimportar el archivo no puede duplicar el stock (K2).
     */
    val stockLoaded: Int,
    /** Cuántos ya tenían existencias y se dejaron como estaban. */
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
 * Importa el archivo inicial del catálogo (docs/flujos/catalogo.md §4).
 *
 * **Estricta**: una fila inválida rechaza el archivo entero y no se escribe nada.
 * No corrige nada adivinando; un importador tolerante esconde errores de captura
 * que después reaparecen como stock mal contado.
 *
 * **Idempotente**: el `upsert` va por `product_code`, así que importar dos veces
 * el mismo archivo no duplica productos (K2). Los que ya existen sin cambios se
 * cuentan como `skipped`, que es lo que hace visible que la segunda vez no hizo
 * nada en lugar de decir «1.398 actualizados».
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

            // El catálogo cerrado de unidades es de la base, no del código: una
            // unidad nueva se añade con una migración, no con un despliegue.
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
     * La carga inicial de existencias: el total heredado, a `UNASSIGNED`.
     *
     * **Solo a quien no tiene nada.** El archivo del legacy trae un total por producto y no
     * dice en qué estantería está cada pieza ([ADR-0012]); lo que hace esta carga es dejar
     * ese total en el saldo sin ubicar para que el conteo físico lo reparta. Cargarlo a un
     * producto que **ya tiene existencias** duplicaría el inventario con solo reimportar el
     * archivo, así que se salta y se cuenta aparte: el informe dice cuántos se saltaron, en
     * lugar de esconderlo.
     *
     * El movimiento es `INITIAL` en el kardex, con la ubicación sin ubicar y el usuario que
     * importó: queda con responsable y fecha.
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
