package dev.axzy.stock.application.labeling

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.labeling.LabelField
import dev.axzy.stock.domain.labeling.LabelSize
import dev.axzy.stock.domain.labeling.LabelTemplate
import java.time.Instant

/**
 * The label ports (M06, docs/api/etiquetas.md).
 *
 * **The template is resolved with the domain function** ([LabelTemplate.resolve]),
 * the same one used by the preview and the enqueueing. If it were resolved separately, the preview
 * could show something different from what is printed, and what is seen would be approved.
 */

/** The template for the listing: **without the ZPL**, which is large and not needed. */
data class LabelTemplateSummary(
    val id: Long,
    val code: String,
    val name: String,
    val description: String?,
    val size: String?,
    val isActive: Boolean,
    val fieldSchema: Map<String, LabelFieldSpec>,
)

/** The specification of a template field, as it travels in the `fieldSchema`. */
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

/** Partial editing: what is not sent stays as it was. */
data class LabelTemplatePatch(
    val code: String?,
    val name: String?,
    val zpl: String?,
    val description: String?,
    val size: String?,
    val fieldSchema: Map<String, LabelFieldSpec>?,
    val isActive: Boolean?,
)

/** A print job, with its template's code and not its identifier. */
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
    /** The listing is a **plain array**: it is a closed catalog that the interface needs whole. */
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

    /** The data of a job, so that a reprint prints the same thing. */
    fun payloadOf(jobId: Long): Map<String, String>?

    fun page(status: String?, request: PageRequest): Page<PrintJobView>

    /** The original job, to link a reprint. */
    fun exists(id: Long): Boolean

    /** Only the printer's confirmation does it: the system does not self-certify. */
    fun markPrinted(id: Long, at: Instant): Int

    fun markFailed(id: Long, error: String, at: Instant): Int
}

/** The size in millimeters of a template, computed by the server. */
internal fun sizeOf(size: String?): LabelSize = LabelSize.parse(size)
