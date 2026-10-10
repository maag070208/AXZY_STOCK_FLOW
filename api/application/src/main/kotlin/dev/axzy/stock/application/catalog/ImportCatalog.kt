package dev.axzy.stock.application.catalog

import dev.axzy.stock.application.access.AccessDecider
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
    val stockPersisted: Boolean get() = false
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
                    FailureKind.INVALID_REQUEST, "EMPTY_FILE", "El archivo no tiene filas de datos.",
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
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "CATALOG_IMPORTED",
                            level = AuditLevel.CRITICAL,
                            entity = AuditEntity("catalog", null, "Catálogo de productos"),
                            grant = grant,
                            newState = mapOf(
                                "totalRows" to report.totalRows,
                                "created" to summary.created,
                                "updated" to summary.updated,
                                "skipped" to summary.skipped,
                                "totalStock" to report.totalStock.toString(),
                                "stockPersisted" to false,
                            ),
                        ),
                    )
                    resultOf(report, summary).asRight()
                }
            }
        }

    private fun resultOf(report: ImportReport, summary: UpsertSummary) = CatalogImportResult(
        dryRun = report.isDryRun,
        totalRows = report.totalRows,
        accepted = report.acceptedCount,
        rejected = report.rejectedCount,
        totalStock = report.totalStock,
        zeroStockCount = report.zeroStockCount,
        unitMeasures = report.unitMeasures,
        rejections = report.rejected.map { DomainError.ImportInvalidRows.RejectedRow(it.rowNumber, it.reason) },
        created = summary.created,
        updated = summary.updated,
        skipped = summary.skipped,
    )
}
