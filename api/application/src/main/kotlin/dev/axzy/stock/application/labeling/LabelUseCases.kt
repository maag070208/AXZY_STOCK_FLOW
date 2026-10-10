package dev.axzy.stock.application.labeling

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.labeling.LabelTemplate
import java.time.Clock

/**
 * The label use cases (M06, docs/flujos/etiquetas.md).
 *
 * Two rules govern the rest:
 *
 * · **The ZPL is validated on save** (`^XA` and `^XZ`): without the closing, the printer does not
 *   give an error —it leaves the label blank or the job in the queue— and that is discovered
 *   gastando papel.
 * · **A label is not marked printed until the printer confirms it.** No use case
 *   of this layer writes `PRINTED`: only the printer's confirmation does it,
 *   because the system does not self-certify.
 */

// ── Plantillas ──────────────────────────────────────────────────────────────

class ListLabelTemplates(private val templates: LabelTemplates, private val access: AccessDecider) {

    fun execute(actor: Actor, onlyActive: Boolean, origin: RequestOrigin): Outcome<List<LabelTemplateSummary>> =
        access.authorizeOr(actor, "label.template_view", origin) { templates.list(onlyActive).asRight() }
}

class GetLabelTemplate(private val templates: LabelTemplates, private val access: AccessDecider) {

    fun execute(actor: Actor, id: Long, origin: RequestOrigin): Outcome<LabelTemplateDetail> =
        access.authorizeOr(actor, "label.template_view", origin) {
            templates.findTemplate(id)?.asRight() ?: templateNotFound(id)
        }
}

class CreateLabelTemplate(
    private val templates: LabelTemplates,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, input: NewLabelTemplate, origin: RequestOrigin): Outcome<LabelTemplateDetail> =
        access.authorizeOr(actor, "label.template_edit", origin) { grant ->
            val code = input.code?.trim()
            if (code.isNullOrBlank()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The label template code is required.",
                    mapOf("field" to "code"),
                )
            }
            val name = input.name?.trim()
            if (name.isNullOrBlank()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The template name is required.",
                    mapOf("field" to "name"),
                )
            }
            when (val valid = validateZpl(input.zpl)) {
                is Either.Left -> return@authorizeOr valid.error.asLeft()
                is Either.Right -> Unit
            }
            if (input.fieldSchema?.keys?.any { it.isBlank() } == true) {
                return@authorizeOr failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                    "Schema field names cannot be empty.",
                    mapOf("field" to "fieldSchema"),
                )
            }
            if (templates.codeExists(code)) {
                return@authorizeOr failure(
                    FailureKind.CONFLICT, "DUPLICATE_LABEL_TEMPLATE_CODE",
                    "A template with the code '$code' already exists.",
                    mapOf("code" to code),
                )
            }

            tx.inTransaction {
                val id = templates.insert(input.copy(code = code, name = name))
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "LABEL_TEMPLATE_CREATED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("label_template", id.toString(), code),
                        grant = grant,
                        newState = mapOf("code" to code, "size" to input.size, "isActive" to (input.isActive ?: true)),
                    ),
                )
                templates.findTemplate(id)?.asRight() ?: templateNotFound(id)
            }
        }
}

class UpdateLabelTemplate(
    private val templates: LabelTemplates,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, id: Long, patch: LabelTemplatePatch, origin: RequestOrigin): Outcome<LabelTemplateDetail> =
        access.authorizeOr(actor, "label.template_edit", origin) { grant ->
            val current = templates.findTemplate(id) ?: return@authorizeOr templateNotFound(id)
            patch.zpl?.let { zpl ->
                when (val valid = validateZpl(zpl)) {
                    is Either.Left -> return@authorizeOr valid.error.asLeft()
                    is Either.Right -> Unit
                }
            }
            patch.code?.trim()?.takeIf { it.isNotBlank() }?.let { code ->
                if (code != current.summary.code && templates.codeExists(code)) {
                    return@authorizeOr failure(
                        FailureKind.CONFLICT, "DUPLICATE_LABEL_TEMPLATE_CODE",
                        "A template with the code '$code' already exists.",
                        mapOf("code" to code),
                    )
                }
            }

            tx.inTransaction {
                templates.update(id, patch)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "LABEL_TEMPLATE_UPDATED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("label_template", id.toString(), current.summary.code),
                        grant = grant,
                        previousState = mapOf("zpl" to current.zpl.take(MAX_ZPL_IN_AUDIT), "isActive" to current.summary.isActive),
                        newState = mapOf("isActive" to (patch.isActive ?: current.summary.isActive)),
                    ),
                )
                templates.findTemplate(id)?.asRight() ?: templateNotFound(id)
            }
        }
}

// ── Preview and enqueueing ─────────────────────────────────────────────

data class LabelPreview(val zpl: String, val templateCode: String, val widthMm: Double, val heightMm: Double)

data class PreviewInput(val templateId: Long?, val data: Map<String, String>?)

/**
 * Resolves the ZPL **without printing**.
 *
 * The resolution is done by the domain, the same function the enqueueing uses: if it were
 * resolved separately, the preview could show something different from what is printed.
 * A missing mandatory field returns `MISSING_LABEL_FIELD` and **not a half-finished label**:
 * leaving the placeholder would print `{{productCode}}` on the shelf, and substituting it with
 * blank would print a label that looks correct and is not.
 */
class PreviewLabel(private val templates: LabelTemplates, private val access: AccessDecider) {

    fun execute(actor: Actor, input: PreviewInput, origin: RequestOrigin): Outcome<LabelPreview> =
        access.authorizeOr(actor, "label.print", origin) {
            val templateId = input.templateId ?: return@authorizeOr failure(
                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                "The template is missing.",
                mapOf("field" to "templateId"),
            )
            val detail = templates.findTemplate(templateId) ?: return@authorizeOr templateNotFound(templateId)
            when (val resolved = detail.template().resolve(input.data ?: emptyMap())) {
                is Either.Left -> resolved.error.toFailure().asLeft()
                is Either.Right -> {
                    val size = sizeOf(detail.summary.size)
                    LabelPreview(resolved.value, detail.summary.code, size.widthMm, size.heightMm).asRight()
                }
            }
        }
}

data class PrintInput(val templateId: Long?, val copies: Int?, val data: List<Map<String, String>>?)

data class PrintResult(val jobIds: List<Long>, val status: String)

/**
 * Enqueues the print.
 *
 * **The job stays `PENDING` until the printer confirms**, and the ZPL is resolved
 * **before** enqueueing: a job with incomplete data would be created anyway and would have to be
 * discovered when printing, with the label already in the queue.
 */
class PrintLabels(
    private val templates: LabelTemplates,
    private val jobs: PrintJobs,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, input: PrintInput, origin: RequestOrigin): Outcome<PrintResult> =
        access.authorizeOr(actor, "label.print", origin) { grant ->
            val templateId = input.templateId ?: return@authorizeOr failure(
                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                "The template is missing.",
                mapOf("field" to "templateId"),
            )
            val copies = input.copies ?: 1
            if (copies < 1 || copies > MAX_COPIES) {
                return@authorizeOr failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                    "The copies must be between 1 and $MAX_COPIES.",
                    mapOf("field" to "copies", "value" to copies.toString(), "max" to MAX_COPIES.toString()),
                )
            }
            if (input.data.isNullOrEmpty()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "There are no labels to print.",
                    mapOf("field" to "data"),
                )
            }
            val detail = templates.findTemplate(templateId) ?: return@authorizeOr templateNotFound(templateId)
            if (!detail.summary.isActive) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "LABEL_TEMPLATE_INACTIVE",
                    "Template '${detail.summary.code}' is inactive.",
                    mapOf("templateId" to templateId.toString(), "code" to detail.summary.code),
                )
            }

            // Everything is resolved BEFORE enqueueing: if one label cannot be resolved,
            // none is enqueued.
            val payloads = mutableListOf<Map<String, String>>()
            input.data.forEach { data ->
                when (val resolved = detail.template().resolve(data)) {
                    is Either.Left -> return@authorizeOr resolved.error.toFailure().asLeft()
                    is Either.Right -> payloads += data
                }
            }

            tx.inTransaction {
                val ids = payloads.map { payload ->
                    jobs.insert(
                        NewPrintJob(
                            templateId = templateId,
                            payload = payload,
                            copies = copies,
                            createdBy = actor.id,
                            reprintedFromId = null,
                        ),
                    )
                }
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "LABEL_PRINT_REQUESTED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("print_job", ids.first().toString(), detail.summary.code),
                        grant = grant,
                        newState = mapOf("jobs" to ids, "copies" to copies, "status" to "PENDING"),
                    ),
                )
                PrintResult(ids, PENDING).asRight()
            }
        }
}

// ── The queue ─────────────────────────────────────────────────────────────────

class ListPrintJobs(private val jobs: PrintJobs, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        status: String?,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<Page<PrintJobView>> = access.authorizeOr(actor, "label.queue_view", origin) {
        jobs.page(status, request).asRight()
    }
}

class GetPrintJob(private val jobs: PrintJobs, private val access: AccessDecider) {

    fun execute(actor: Actor, id: Long, origin: RequestOrigin): Outcome<PrintJobView> =
        access.authorizeOr(actor, "label.queue_view", origin) {
            jobs.findJob(id)?.asRight() ?: jobNotFound(id)
        }
}

/**
 * Reprints: **a new job linked to the original**, with its reason.
 *
 * It does not reuse the record: the traceability of a reprint requires knowing who
 * asked and why, and reusing it would erase the earlier fact. The permission is
 * `label.reprint` and not `label.print` because it uses up a second label.
 */
class ReprintLabel(
    private val jobs: PrintJobs,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, jobId: Long, reason: String?, origin: RequestOrigin): Outcome<PrintJobView> =
        access.authorizeOr(actor, "label.reprint", origin) { grant ->
            val original = jobs.findJob(jobId) ?: return@authorizeOr jobNotFound(jobId)
            val motivo = reason?.trim()
            if (motivo.isNullOrBlank()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "REASON_REQUIRED",
                    "Reprinting a label requires a reason: it stays in the audit log with who requested it.",
                    mapOf("field" to "reason"),
                )
            }

            tx.inTransaction {
                val newId = jobs.insert(
                    NewPrintJob(
                        templateId = original.templateId,
                        // The reprint prints **the same thing**: the payload of the
                        // original is copied instead of leaving the job without data.
                        payload = jobs.payloadOf(jobId) ?: emptyMap(),
                        copies = original.copies,
                        createdBy = actor.id,
                        reprintedFromId = jobId,
                    ),
                )
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "LABEL_REPRINTED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("print_job", newId.toString(), original.templateCode),
                        grant = grant,
                        previousState = mapOf("jobId" to jobId, "status" to original.status),
                        newState = mapOf("jobId" to newId, "status" to PENDING),
                        reason = motivo,
                    ),
                )
                jobs.findJob(newId)?.asRight() ?: jobNotFound(newId)
            }
        }
}

// ── Common ──────────────────────────────────────────────────────────────

/**
 * The ZPL has to open and close.
 *
 * Without the closing, the printer **does not give an error**: it leaves the label blank or the job
 * in the queue, and that is discovered by wasting paper. That is why it is validated on save and not on
 * imprimir.
 */
private fun validateZpl(zpl: String?): Outcome<String> {
    if (zpl.isNullOrBlank()) {
        return failure(
            FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
            "The template ZPL is required.",
            mapOf("field" to "zpl"),
        )
    }
    if (!zpl.contains("^XA") || !zpl.contains("^XZ")) {
        return failure(
            FailureKind.RULE_VIOLATION, "INVALID_ZPL",
            "The ZPL must contain '^XA' and '^XZ': without the closing the printer reports no error, " +
                "leaves the label blank and is discovered by wasting paper.",
            mapOf("field" to "zpl"),
        )
    }
    return zpl.asRight()
}

/** The domain template, to resolve with the same function that prints. */
internal fun LabelTemplateDetail.template(): LabelTemplate = LabelTemplate(
    id = summary.id,
    code = summary.code,
    name = summary.name,
    description = summary.description,
    zpl = zpl,
    size = summary.size,
    fields = fields,
    isActive = summary.isActive,
)

private fun templateNotFound(id: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "LABEL_TEMPLATE_NOT_FOUND",
    "Template $id does not exist.",
    mapOf("templateId" to id.toString()),
)

private fun jobNotFound(id: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "PRINT_JOB_NOT_FOUND",
    "Print job $id does not exist.",
    mapOf("jobId" to id.toString()),
)

private const val MAX_COPIES = 100
private const val PENDING = "PENDING"
private const val MAX_ZPL_IN_AUDIT = 200
