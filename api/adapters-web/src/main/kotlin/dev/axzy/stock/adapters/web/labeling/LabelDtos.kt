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
 * The HTTP contract of labels (docs/api/etiquetas.md).
 *
 * The template listing **does not carry the ZPL** (it is large and not needed) and returns
 * a **plain array without a pagination envelope**: it is a closed catalog that the interface
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
    /** The ZPL **goes only in the detail**. */
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

/** Editing is partial: what is not sent stays as it was. */
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

// ── Preview and printing ─────────────────────────────────────────────────

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
 * Millimeters, with **one decimal**.
 *
 * The size is computed from the template's inches, and `6 × 25.4` is not
 * representable in floating point: it came out as `152.39999999999998`. The API publishes a
 * tenth of a millimeter, which is more precision than any label has.
 */
private fun Double.roundMm(): Double = kotlin.math.round(this * 10.0) / 10.0

data class LabelPrintRequestDto(
    val templateId: Long? = null,
    val copies: Int? = null,
    val data: List<Map<String, String>>? = null,
)

data class PrintResultDto(val jobIds: List<Long>, val status: String)

data class ReprintRequestDto(val reason: String? = null)

// ── The queue ─────────────────────────────────────────────────────────────────

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

/** An instant in ISO 8601 UTC, which is the API's format. */
private fun Instant.toIso(): String = toString()
