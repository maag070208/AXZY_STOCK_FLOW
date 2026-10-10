package dev.axzy.stock.adapters.web.labeling

import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.labeling.CreateLabelTemplate
import dev.axzy.stock.application.labeling.GetLabelTemplate
import dev.axzy.stock.application.labeling.GetPrintJob
import dev.axzy.stock.application.labeling.ListLabelTemplates
import dev.axzy.stock.application.labeling.ListPrintJobs
import dev.axzy.stock.application.labeling.PreviewInput
import dev.axzy.stock.application.labeling.PreviewLabel
import dev.axzy.stock.application.labeling.PrintInput
import dev.axzy.stock.application.labeling.PrintLabels
import dev.axzy.stock.application.labeling.ReprintLabel
import dev.axzy.stock.application.labeling.UpdateLabelTemplate
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.common.Either
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The label templates (docs/api/etiquetas.md).
 *
 * The listing and the detail require `label.template_view`; creation and editing,
 * `label.template_edit`: viewing a template and changing it are different permissions.
 */
@RestController
@RequestMapping("/v1/label-templates")
class LabelTemplateController(
    private val listTemplates: ListLabelTemplates,
    private val getTemplate: GetLabelTemplate,
    private val createTemplate: CreateLabelTemplate,
    private val updateTemplate: UpdateLabelTemplate,
) {

    /** Returns a **plain array without an envelope**: it is a closed catalog that the interface needs whole. */
    @GetMapping
    @RequiresPermission("label.template_view")
    fun list(
        @RequestParam(required = false) onlyActive: Boolean?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = listTemplates.execute(actor, onlyActive ?: false, origin)
        .toResponse { templates -> templates.map { it.toDto() } }

    @PostMapping
    @RequiresPermission("label.template_edit")
    fun create(
        @RequestBody body: LabelTemplateCreateDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = when (val result = createTemplate.execute(actor, body.toInput(), origin)) {
        is Either.Left -> result.error.toResponse()
        is Either.Right -> ResponseEntity.status(HttpStatus.CREATED).body(result.value.toDto())
    }

    @GetMapping("/{id}")
    @RequiresPermission("label.template_view")
    fun detail(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = getTemplate.execute(actor, id, origin).toResponse { it.toDto() }

    @PutMapping("/{id}")
    @RequiresPermission("label.template_edit")
    fun update(
        @PathVariable id: Long,
        @RequestBody body: LabelTemplateUpdateDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = updateTemplate.execute(actor, id, body.toPatch(), origin).toResponse { it.toDto() }
}

/**
 * Preview, printing and queue.
 *
 * **No user endpoint marks a label as printed**: only the printer's
 * confirmation (`PATCH /v1/print-jobs/{id}`), because the system does not
 * autocertifica.
 */
@RestController
@RequestMapping("/v1/labels")
class LabelController(
    private val previewLabel: PreviewLabel,
    private val printLabels: PrintLabels,
    private val listJobs: ListPrintJobs,
    private val getJob: GetPrintJob,
    private val reprintLabel: ReprintLabel,
) {

    @PostMapping("/preview")
    @RequiresPermission("label.print")
    fun preview(
        @RequestBody body: LabelPreviewRequestDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = previewLabel.execute(actor, PreviewInput(body.templateId, body.data), origin)
        .toResponse { it.toDto() }

    /** **202**: the job stays `PENDING` until the printer confirms it. */
    @PostMapping("/print")
    @RequiresPermission("label.print")
    fun print(
        @RequestBody body: LabelPrintRequestDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = when (
        val result = printLabels.execute(
            actor,
            PrintInput(body.templateId, body.copies, body.data),
            origin,
        )
    ) {
        is Either.Left -> result.error.toResponse()
        is Either.Right -> ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(PrintResultDto(result.value.jobIds, result.value.status))
    }

    @GetMapping("/jobs")
    @RequiresPermission("label.queue_view")
    fun jobs(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(page, size, null, setOf("id"), SortSpec("id"))
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val jobStatus = status?.trim()?.uppercase()?.takeUnless { it.isBlank() }
        if (jobStatus != null && jobStatus !in PRINT_STATUSES) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The status is not in the list of allowed values.",
                mapOf("field" to "status", "value" to jobStatus, "allowed" to PRINT_STATUSES.joinToString(",")),
            ).toResponse()
        }
        return listJobs.execute(actor, jobStatus, request, origin).toResponse { it.toDto { job -> job.toDto() } }
    }

    @GetMapping("/jobs/{id}")
    @RequiresPermission("label.queue_view")
    fun job(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = getJob.execute(actor, id, origin).toResponse { it.toDto() }

    /** **202 with the new job**, which is also born `PENDING`. */
    @PostMapping("/jobs/{id}/reprint")
    @RequiresPermission("label.reprint")
    fun reprint(
        @PathVariable id: Long,
        @RequestBody body: ReprintRequestDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = when (val result = reprintLabel.execute(actor, id, body.reason, origin)) {
        is Either.Left -> result.error.toResponse()
        is Either.Right -> ResponseEntity.status(HttpStatus.ACCEPTED).body(result.value.toDto())
    }

    private companion object {
        val PRINT_STATUSES = listOf("PENDING", "SENT", "PRINTED", "FAILED")
    }
}
