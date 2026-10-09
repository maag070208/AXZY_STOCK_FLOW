package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.client.stockJson
import dev.axzy.stock.network.dto.common.ListQuery
import dev.axzy.stock.network.dto.common.Page
import dev.axzy.stock.network.dto.label.LabelJobDto
import dev.axzy.stock.network.dto.label.LabelJobStatus
import dev.axzy.stock.network.dto.label.LabelPreviewResponse
import dev.axzy.stock.network.dto.label.LabelTemplateDetailDto
import dev.axzy.stock.network.dto.label.LabelTemplateDto
import dev.axzy.stock.network.dto.label.PreviewLabelRequest
import dev.axzy.stock.network.dto.label.PrintLabelsRequest
import dev.axzy.stock.network.dto.label.PrintLabelsResponse
import dev.axzy.stock.network.dto.label.ReprintLabelRequest
import dev.axzy.stock.network.error.ApiOutcome
import kotlinx.serialization.encodeToString

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/label-templates` y `/v1/labels` — etiquetas e impresión
 *
 * Contrato: docs/historico/CONTRATO_API.md §8.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LabelsApi internal constructor(private val http: StockHttpClient) {

    /**
     * Plantillas, **sin `zpl`**: el cuerpo de la plantilla es grande y no se
     * necesita para pintar la lista.
     */
    suspend fun templates(query: ListQuery = ListQuery()): ApiOutcome<Page<LabelTemplateDto>> =
        http.getText("/label-templates", query.toParams()).decode()

    /** Detalle de la plantilla: aquí **sí** viene el `zpl`. */
    suspend fun template(id: Long): ApiOutcome<LabelTemplateDetailDto> =
        http.getText("/label-templates/$id").decode()

    /**
     * Resuelve el ZPL **sin imprimir**. Es lo que permite mostrar la vista previa
     * y, en el móvil, decidir si la etiqueta cabe antes de gastar papel.
     */
    suspend fun preview(request: PreviewLabelRequest): ApiOutcome<LabelPreviewResponse> =
        http.postText("/labels/preview", stockJson.encodeToString(request)).decode()

    /**
     * Encola la impresión: responde `202` con `jobIds`.
     *
     * La respuesta llega como `PENDING` y **la etiqueta no se marca impresa
     * hasta que la impresora confirma**. El legacy la marcaba sin esperar la
     * respuesta, así que si la Zebra estaba apagada la etiqueta se perdía para
     * siempre.
     */
    suspend fun print(request: PrintLabelsRequest): ApiOutcome<PrintLabelsResponse> =
        http.postText("/labels/print", stockJson.encodeToString(request)).decode()

    suspend fun job(id: Long): ApiOutcome<LabelJobDto> = http.getText("/labels/jobs/$id").decode()

    /** Trabajos de impresión, filtrables por estado (p. ej. `FAILED`). */
    suspend fun jobs(
        status: LabelJobStatus? = null,
        query: ListQuery = ListQuery(),
    ): ApiOutcome<Page<LabelJobDto>> = http.getText(
        path = "/labels/jobs",
        query = listOf("status" to status?.name) + query.toParams(),
    ).decode()

    /**
     * Reimprime.
     *
     * Exige motivo y **genera un trabajo nuevo enlazado al original**: la
     * trazabilidad de una reimpresión exige saber quién la pidió y por qué, y
     * reutilizar el registro la perdería.
     */
    suspend fun reprint(jobId: Long, request: ReprintLabelRequest): ApiOutcome<LabelJobDto> =
        http.postText(
            path = "/labels/jobs/$jobId/reprint",
            body = stockJson.encodeToString(request),
        ).decode()
}
