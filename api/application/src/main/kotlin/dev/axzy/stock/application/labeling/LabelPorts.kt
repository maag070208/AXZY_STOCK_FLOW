package dev.axzy.stock.application.labeling

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.labeling.LabelField
import dev.axzy.stock.domain.labeling.LabelSize
import dev.axzy.stock.domain.labeling.LabelTemplate
import java.time.Instant

/**
 * Los puertos de etiquetas (M06, docs/api/etiquetas.md).
 *
 * **La plantilla se resuelve con la función del dominio** ([LabelTemplate.resolve]),
 * la misma que usa la previsualización y el encolado. Si se resolviera aparte, la vista
 * previa podría enseñar algo distinto de lo que se imprime, y se aprobaría lo que se ve.
 */

/** La plantilla para el listado: **sin el ZPL**, que es grande y no se necesita. */
data class LabelTemplateSummary(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    val size: String?,
    val isActive: Boolean,
    val fieldSchema: Map<String, LabelFieldSpec>,
)

/** La especificación de un campo de la plantilla, tal como viaja en el `fieldSchema`. */
data class LabelFieldSpec(
    val type: String = "string",
    val label: String = "",
    val required: Boolean = false,
)

data class LabelTemplateDetail(
    val summary: LabelTemplateSummary,
    val zpl: String,
    val fields: List<LabelField>,
)

data class NewLabelTemplate(
    val code: String?,
    val name: String?,
    val zpl: String?,
    val description: String?,
    val size: String?,
    val fieldSchema: Map<String, LabelFieldSpec>?,
    val isActive: Boolean?,
)

/** La edición parcial: lo que no viene, queda como estaba. */
data class LabelTemplatePatch(
    val code: String?,
    val name: String?,
    val zpl: String?,
    val description: String?,
    val size: String?,
    val fieldSchema: Map<String, LabelFieldSpec>?,
    val isActive: Boolean?,
)

/** Un trabajo de impresión, con el código de su plantilla y no su identificador. */
data class PrintJobView(
    val id: Long,
    val templateId: Long,
    val templateCode: String,
    val copies: Int,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val printedAt: Instant?,
    val createdAt: Instant,
    val reprintedFromId: Long?,
)

data class NewPrintJob(
    val templateId: Long,
    val payload: Map<String, String>,
    val copies: Int,
    val createdBy: Long,
    val reprintedFromId: Long?,
)

interface LabelTemplates {
    /** El listado es un **arreglo sin sobre**: es un catálogo cerrado que la interfaz necesita completo. */
    fun list(onlyActive: Boolean): List<LabelTemplateSummary>

    fun findTemplate(id: Long): LabelTemplateDetail?

    fun findByCode(code: String): LabelTemplate?

    fun codeExists(code: String): Boolean

    fun insert(new: NewLabelTemplate): Long

    fun update(id: Long, patch: LabelTemplatePatch): Unit
}

interface PrintJobs {
    fun insert(job: NewPrintJob): Long

    fun findJob(id: Long): PrintJobView?

    /** Los datos de un trabajo, para que una reimpresión imprima lo mismo. */
    fun payloadOf(jobId: Long): Map<String, String>?

    fun page(status: String?, request: PageRequest): Page<PrintJobView>

    /** El trabajo original, para enlazar una reimpresión. */
    fun exists(id: Long): Boolean

    /** Solo lo hace la confirmación de la impresora: el sistema no se autocertifica. */
    fun markPrinted(id: Long, at: Instant): Int

    fun markFailed(id: Long, error: String, at: Instant): Int
}

/** El tamaño en milímetros de una plantilla, calculado por el servidor. */
internal fun sizeOf(size: String?): LabelSize = LabelSize.parse(size)
