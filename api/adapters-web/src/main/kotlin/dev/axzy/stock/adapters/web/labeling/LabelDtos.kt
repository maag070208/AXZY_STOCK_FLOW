package dev.axzy.stock.adapters.web.labeling

import dev.axzy.stock.adapters.web.http.PageDto
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.application.labeling.LabelFieldSpec
import dev.axzy.stock.application.labeling.LabelPreview
import dev.axzy.stock.application.labeling.LabelTemplateDetail
import dev.axzy.stock.application.labeling.LabelTemplatePatch
import dev.axzy.stock.application.labeling.LabelTemplateSummary
import dev.axzy.stock.application.labeling.NewLabelTemplate
import dev.axzy.stock.application.labeling.PrintJobView
import java.time.Instant

/**
 * El contrato HTTP de etiquetas (docs/api/etiquetas.md).
 *
 * El listado de plantillas **no lleva el ZPL** (es grande y no se necesita) y devuelve
 * un arreglo **sin sobre de paginación**: es un catálogo cerrado que la interfaz
 * necesita completo.
 */

// ── Plantillas ──────────────────────────────────────────────────────────────

data class LabelFieldSpecDto(
    val type: String = "string",
    val label: String = "",
    val required: Boolean = false,
)

fun LabelFieldSpec.toDto(): LabelFieldSpecDto = LabelFieldSpecDto(type, label, required)

fun LabelFieldSpecDto.toSpec(): LabelFieldSpec = LabelFieldSpec(type, label, required)

data class LabelTemplateSummaryDto(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    val size: String?,
    val isActive: Boolean,
    val fieldSchema: Map<String, LabelFieldSpecDto>,
)

fun LabelTemplateSummary.toDto(): LabelTemplateSummaryDto = LabelTemplateSummaryDto(
    id = id,
    code = code,
    name = name,
    description = description,
    size = size,
    isActive = isActive,
    fieldSchema = fieldSchema.mapValues { (_, spec) -> spec.toDto() },
)

data class LabelTemplateDetailDto(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    val size: String?,
    val isActive: Boolean,
    val fieldSchema: Map<String, LabelFieldSpecDto>,
    /** El ZPL **solo va en el detalle**. */
    val zpl: String,
)

fun LabelTemplateDetail.toDto(): LabelTemplateDetailDto = LabelTemplateDetailDto(
    id = summary.id,
    code = summary.code,
    name = summary.name,
    description = summary.description,
    size = summary.size,
    isActive = summary.isActive,
    fieldSchema = summary.fieldSchema.mapValues { (_, spec) -> spec.toDto() },
    zpl = zpl,
)

data class LabelTemplateCreateDto(
    val code: String? = null,
    val name: String? = null,
    val zpl: String? = null,
    val description: String? = null,
    val size: String? = null,
    val fieldSchema: Map<String, LabelFieldSpecDto>? = null,
    val isActive: Boolean? = null,
) {
    fun toInput(): NewLabelTemplate = NewLabelTemplate(
        code = code,
        name = name,
        zpl = zpl,
        description = description,
        size = size,
        fieldSchema = fieldSchema?.mapValues { (_, spec) -> spec.toSpec() },
        isActive = isActive,
    )
}

/** La edición es parcial: lo que no viene, queda como estaba. */
data class LabelTemplateUpdateDto(
    val code: String? = null,
    val name: String? = null,
    val zpl: String? = null,
    val description: String? = null,
    val size: String? = null,
    val fieldSchema: Map<String, LabelFieldSpecDto>? = null,
    val isActive: Boolean? = null,
) {
    fun toPatch(): LabelTemplatePatch = LabelTemplatePatch(
        code = code,
        name = name,
        zpl = zpl,
        description = description,
        size = size,
        fieldSchema = fieldSchema?.mapValues { (_, spec) -> spec.toSpec() },
        isActive = isActive,
    )
}

// ── Previsualización e impresión ────────────────────────────────────────────

data class LabelPreviewRequestDto(
    val templateId: Long? = null,
    val data: Map<String, String>? = null,
)

data class LabelPreviewSizeDto(val widthMm: Double, val heightMm: Double, val copies: Int)

data class LabelPreviewDto(val zpl: String, val preview: LabelPreviewSizeDto)

fun LabelPreview.toDto(): LabelPreviewDto = LabelPreviewDto(
    zpl = zpl,
    preview = LabelPreviewSizeDto(widthMm = widthMm.roundMm(), heightMm = heightMm.roundMm(), copies = 1),
)

/**
 * Los milímetros, con **un decimal**.
 *
 * El tamaño se calcula desde las pulgadas de la plantilla, y `6 × 25.4` no es
 * representable en coma flotante: salía `152.39999999999998`. La API publica una
 * décima de milímetro, que es más precisión de la que tiene cualquier etiqueta.
 */
private fun Double.roundMm(): Double = kotlin.math.round(this * 10.0) / 10.0

data class LabelPrintRequestDto(
    val templateId: Long? = null,
    val copies: Int? = null,
    val data: List<Map<String, String>>? = null,
)

data class PrintResultDto(val jobIds: List<Long>, val status: String)

data class ReprintRequestDto(val reason: String? = null)

// ── La cola ─────────────────────────────────────────────────────────────────

data class PrintJobDto(
    val id: Long,
    val templateCode: String,
    val copies: Int,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val printedAt: String?,
    val createdAt: String,
    val reprintedFromId: Long?,
)

fun PrintJobView.toDto(): PrintJobDto = PrintJobDto(
    id = id,
    templateCode = templateCode,
    copies = copies,
    status = status,
    attempts = attempts,
    lastError = lastError,
    printedAt = printedAt?.toIso(),
    createdAt = createdAt.toIso(),
    reprintedFromId = reprintedFromId,
)

/** Un instante en ISO 8601 UTC, que es el formato de la API. */
private fun Instant.toIso(): String = toString()
