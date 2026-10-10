package dev.axzy.stock.adapters.web.reports

import dev.axzy.stock.adapters.web.http.FailureException
import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.inventory.InventoryFilters
import dev.axzy.stock.application.inventory.KardexFilter
import dev.axzy.stock.application.reports.ExportFormat
import dev.axzy.stock.application.reports.ExportKardex
import dev.axzy.stock.application.reports.InventoryReport
import dev.axzy.stock.application.reports.InventoryReportFilter
import dev.axzy.stock.application.reports.KardexReconciliation
import dev.axzy.stock.application.reports.MovementSummary
import dev.axzy.stock.application.reports.MovementSummaryFilter
import dev.axzy.stock.application.reports.SummaryGroup
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.common.Either
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody

/**
 * Los reportes (docs/api/reportes.md).
 *
 * Los tres listados exigen `report.view`; la exportación, `report.export`. **Son los
 * mismos permisos que los guards de las pantallas**: un permiso distinto hace que el
 * usuario entre a la pantalla y reciba `403` al cargarla.
 */
@RestController
@RequestMapping("/v1/reports")
class ReportController(
    private val inventoryReport: InventoryReport,
    private val movementSummary: MovementSummary,
    private val reconciliation: KardexReconciliation,
    private val exportKardex: ExportKardex,
) {

    @GetMapping("/inventory")
    @RequiresPermission("report.view")
    fun inventory(
        @RequestParam(required = false) productCode: String?,
        @RequestParam(required = false) warehouseCode: String?,
        @RequestParam(required = false) onlyWithStock: Boolean?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, null, setOf("productCode"), SortSpec("productCode"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val code = when (val parsed = InventoryFilters.productCode(productCode)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        val filter = InventoryReportFilter(code, warehouseCode?.trim()?.ifBlank { null }, onlyWithStock ?: true)

        return inventoryReport.execute(actor, filter, request, origin).toResponse { it.toDto() }
    }

    @GetMapping("/movements-summary")
    @RequiresPermission("report.view")
    fun movementsSummary(
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) groupBy: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        // Las dos fechas son obligatorias: un resumen sin rango recorrería todo el
        // kardex, que es justo lo que el reporte existe para evitar.
        if (from.isNullOrBlank()) {
            return failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "The start date is missing.", mapOf("from" to ""))
                .toResponse()
        }
        if (to.isNullOrBlank()) {
            return failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "The end date is missing.", mapOf("to" to ""))
                .toResponse()
        }
        val fromDate = when (val parsed = InventoryFilters.date(from, "from")) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value!!
        }
        val toDate = when (val parsed = InventoryFilters.date(to, "to")) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value!!
        }
        when (val range = InventoryFilters.range(fromDate, toDate)) {
            is Either.Left -> return range.error.toResponse()
            is Either.Right -> Unit
        }
        val group = SummaryGroup.parse(groupBy)
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The value of 'groupBy' is not in the list of allowed values.",
                mapOf("groupBy" to (groupBy ?: ""), "allowed" to SummaryGroup.entries.joinToString(",") { it.name }),
            ).toResponse()

        val filter = MovementSummaryFilter(fromDate, toDate, group)
        return movementSummary.execute(actor, filter, origin)
            .toResponse { it.toDto(from = fromDate.toString(), to = toDate.toString(), groupBy = group.name) }
    }

    @GetMapping("/kardex-vs-projection")
    @RequiresPermission("report.view")
    fun kardexVsProjection(actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        reconciliation.execute(actor, origin).toResponse { it.toDto() }

    /**
     * La exportación del kardex.
     *
     * Se responde **por trozos**: el archivo se escribe a medida que se leen las
     * filas, así que un kardex de un año no se carga en memoria. El `StreamingResponseBody`
     * tiene que escribir y cerrar; el filtro es exactamente el del listado.
     */
    @GetMapping("/kardex/export")
    @RequiresPermission("report.export")
    fun exportKardex(
        @RequestParam(required = false) format: String?,
        @RequestParam(required = false) productCode: String?,
        @RequestParam(required = false) moveType: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<StreamingResponseBody> {
        /*
         * El tipo declarado es `StreamingResponseBody` y no `Any` a propósito: Spring
         * elige el escritor por el tipo DECLARADO del método, y con `ResponseEntity<Any>`
         * no encuentra convertidor y responde un `500` diciendo que no sabe escribir
         * una lambda. Un archivo se escribe a medida que se lee, así que el cuerpo es
         * un flujo y el contrato de error se lanza como excepción, que el manejador
         * global ya traduce al mismo cuerpo de siempre.
         */
        val exportFormat = ExportFormat.parse(format)
            ?: throw FailureException(
                Failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                    "The format is required and only accepts csv or xlsx.",
                    mapOf(
                        "format" to (format ?: ""),
                        "allowed" to ExportFormat.entries.joinToString(",") { it.name.lowercase() },
                    ),
                ),
            )

        val code = when (val parsed = InventoryFilters.productCode(productCode)) {
            is Either.Left -> throw FailureException(parsed.error)
            is Either.Right -> parsed.value
        }
        val type = when (val parsed = InventoryFilters.moveType(moveType)) {
            is Either.Left -> throw FailureException(parsed.error)
            is Either.Right -> parsed.value
        }
        val fromDate = when (val parsed = InventoryFilters.date(from, "from")) {
            is Either.Left -> throw FailureException(parsed.error)
            is Either.Right -> parsed.value
        }
        val toDate = when (val parsed = InventoryFilters.date(to, "to")) {
            is Either.Left -> throw FailureException(parsed.error)
            is Either.Right -> parsed.value
        }
        when (val range = InventoryFilters.range(fromDate, toDate)) {
            is Either.Left -> throw FailureException(range.error)
            is Either.Right -> Unit
        }

        val filter = KardexFilter(code, type, fromDate, toDate)
        return when (val planned = exportKardex.execute(actor, filter, exportFormat, origin)) {
            is Either.Left -> throw FailureException(planned.error)
            is Either.Right -> ResponseEntity.ok()
                .header(
                    HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"kardex.${exportFormat.name.lowercase()}\"",
                )
                .contentType(mediaTypeOf(exportFormat))
                .body(StreamingResponseBody { out -> KardexExportWriter.write(planned.value, out) })
        }
    }

    private fun mediaTypeOf(format: ExportFormat): MediaType = when (format) {
        ExportFormat.CSV -> MediaType.parseMediaType("text/csv; charset=UTF-8")
        ExportFormat.XLSX -> MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    }
}
