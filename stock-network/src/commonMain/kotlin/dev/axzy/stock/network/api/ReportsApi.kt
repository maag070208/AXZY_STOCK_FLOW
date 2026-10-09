package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.DownloadedFile
import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.inventory.KardexQuery
import dev.axzy.stock.network.dto.report.InventoryReportDto
import dev.axzy.stock.network.dto.report.KardexVsProjectionDto
import dev.axzy.stock.network.dto.report.ReportFormat
import dev.axzy.stock.network.error.ApiOutcome
import kotlinx.serialization.json.JsonElement

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/reports` — reportes
 *
 * Contrato: docs/historico/CONTRATO_API.md §9.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ReportsApi internal constructor(private val http: StockHttpClient) {

    /**
     * Existencias por producto y ubicación, **con totales del conjunto
     * filtrado**.
     *
     * Los totales no son de la página: un total que cambia al paginar es una
     * cifra que nadie vuelve a creer.
     */
    suspend fun inventory(query: ListQuery = ListQuery()): ApiOutcome<InventoryReportDto> =
        http.getText("/reports/inventory", query.toParams()).decode()

    /**
     * Exporta el kardex a XLSX o CSV.
     *
     * **Los filtros son los mismos que los del listado**: si el usuario ve 200
     * filas filtradas y exporta, debe recibir esas 200. Por eso recibe el mismo
     * [KardexQuery] que `kardex()`.
     *
     * Devuelve los bytes y el nombre que el servidor puso en el
     * `Content-Disposition`, para guardarlo con ese nombre en lugar de
     * inventarlo.
     */
    suspend fun exportKardex(
        query: KardexQuery = KardexQuery(),
        format: ReportFormat = ReportFormat.XLSX,
    ): ApiOutcome<DownloadedFile> = http.download(
        path = "/reports/kardex/export",
        query = listOf(
            "format" to format.value,
            "productCode" to query.productCode,
            "from" to query.from,
            "to" to query.to,
            "moveType" to query.moveType?.name,
        ),
    )

    /**
     * Resumen de movimientos por día, semana, mes, producto o ubicación.
     *
     * AMBIGUO: el contrato documenta la ruta y sus parámetros pero **no la forma
     * de la respuesta**. Se devuelve el JSON crudo en lugar de inventar un DTO
     * que el backend no ha acordado: un DTO inventado convierte una duda del
     * contrato en un fallo de integración.
     */
    suspend fun movementsSummary(
        from: String? = null,
        to: String? = null,
        groupBy: String? = null,
    ): ApiOutcome<JsonElement> = http.getText(
        path = "/reports/movements-summary",
        query = listOf("from" to from, "to" to to, "groupBy" to groupBy),
    ).decode()

    /**
     * **El reporte de reconciliación**: compara el ledger con las proyecciones.
     *
     * En el legacy esta comprobación no existía, y un descuadre solo se
     * descubría con un conteo físico. Lo primero que hay que mirar de la
     * respuesta es `divergences`.
     */
    suspend fun kardexVsProjection(): ApiOutcome<KardexVsProjectionDto> =
        http.getText("/reports/kardex-vs-projection").decode()
}
