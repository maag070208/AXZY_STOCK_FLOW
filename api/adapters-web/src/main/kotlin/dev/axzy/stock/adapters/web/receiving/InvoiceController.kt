package dev.axzy.stock.adapters.web.receiving

import dev.axzy.stock.adapters.web.http.FailureException
import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.adapters.web.http.toDto
import dev.axzy.stock.adapters.web.http.toResponse
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.receiving.ApproveInvoice
import dev.axzy.stock.application.receiving.CaptureInput
import dev.axzy.stock.application.receiving.AssignInvoiceLine
import dev.axzy.stock.application.receiving.CaptureLine
import dev.axzy.stock.application.receiving.CloseInvoice
import dev.axzy.stock.application.receiving.CreateInvoice
import dev.axzy.stock.application.receiving.DecisionInput
import dev.axzy.stock.application.receiving.GetInvoice
import dev.axzy.stock.application.receiving.InvoiceDetail
import dev.axzy.stock.application.receiving.InvoiceFilter
import dev.axzy.stock.application.receiving.ListInvoices
import dev.axzy.stock.application.receiving.NewInvoice
import dev.axzy.stock.application.receiving.NewInvoiceLine
import dev.axzy.stock.application.receiving.PendingApprovalInbox
import dev.axzy.stock.application.receiving.RejectInvoiceDiscrepancy
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.Quantity
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
import java.math.BigDecimal

/**
 * Receiving invoices (docs/api/recepcion.md).
 *
 * The controller translates and validates the format; **the rules are in the use case and
 * in the domain**. The approval is a single route over the whole document: there is no
 * per-line variant, and that is what makes segregation of duties evident.
 */
@RestController
@RequestMapping("/v1/invoices")
class InvoiceController(
    private val listInvoices: ListInvoices,
    private val getInvoice: GetInvoice,
    private val createInvoice: CreateInvoice,
    private val captureLine: CaptureLine,
    private val assignInvoiceLine: AssignInvoiceLine,
    private val approveInvoice: ApproveInvoice,
    private val rejectDiscrepancy: RejectInvoiceDiscrepancy,
    private val closeInvoice: CloseInvoice,
    private val pendingApprovalInbox: PendingApprovalInbox,
) {

    @GetMapping
    @RequiresPermission("invoice.view")
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) sort: String?,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val analyzed = PageRequest.analyze(
            page,
            size,
            sort,
            setOf("receiptDate", "invoiceNumber"),
            SortSpec("receiptDate"),
        )
        val request = when (analyzed) {
            is Either.Left -> return analyzed.error.toResponse()
            is Either.Right -> analyzed.value
        }
        val invoiceStatus = status?.trim()?.uppercase()?.takeUnless { it.isBlank() }
        if (invoiceStatus != null && invoiceStatus !in INVOICE_STATUSES) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The status is not in the list of allowed values.",
                mapOf("field" to "status", "value" to invoiceStatus, "allowed" to INVOICE_STATUSES.joinToString(",")),
            ).toResponse()
        }
        val filter = InvoiceFilter(invoiceStatus, q?.trim()?.takeUnless { it.isBlank() })

        return listInvoices.execute(actor, filter, request, origin).toResponse { it.toDto { header -> header.toDto() } }
    }

    /** **201 with the reference, not the full invoice**: the detail is requested separately. */
    @PostMapping
    @RequiresPermission("invoice.create")
    fun create(
        @RequestBody body: NewInvoiceDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = when (
        val result = createInvoice.execute(
            actor,
            NewInvoice(
                invoiceNumber = body.invoiceNumber,
                receiptDate = body.receiptDate,
                invoiceType = body.invoiceType,
                lines = body.lines.map { line ->
                    NewInvoiceLine(
                        productCode = line.productCode,
                        container = line.container,
                        unitMeasure = line.unitMeasure,
                        quantity = line.quantity?.let { quantityOf(it) },
                        boxQuantity = line.boxQuantity?.let { quantityOf(it) },
                        unitsPerBox = line.unitsPerBox?.let { quantityOf(it) },
                    )
                },
            ),
            origin,
        )
    ) {
        is Either.Left -> result.error.toResponse()
        is Either.Right -> ResponseEntity.status(HttpStatus.CREATED)
            .body(CreatedInvoiceDto(result.value.id, result.value.invoiceNumber))
    }

    @GetMapping("/pending-approval")
    @RequiresPermission("invoice.view")
    fun pendingApproval(actor: Actor, origin: RequestOrigin): ResponseEntity<Any> =
        pendingApprovalInbox.execute(actor, origin).toResponse { ItemsDto(it.map { item -> item.toDto() }) }

    @GetMapping("/{id}")
    @RequiresPermission("invoice.view")
    fun detail(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = getInvoice.execute(actor, id, origin).toResponse { it.toDto() }

    /** **200 with the line** counted, not the invoice. */
    @PostMapping("/{id}/lines/{lineId}/capture")
    @RequiresPermission("invoice.capture")
    fun capture(
        @PathVariable id: Long,
        @PathVariable lineId: Long,
        @RequestBody body: CaptureDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> {
        val quantity = body.physicalQuantity?.let { quantityOf(it) }
        val boxes = when (val parsed = boxesOf(body.physicalBoxQuantity)) {
            is Either.Left -> return parsed.error.toResponse()
            is Either.Right -> parsed.value
        }
        return captureLine.execute(actor, id, lineId, CaptureInput(quantity, boxes), origin)
            .toResponse { it.toDto() }
    }

    /**
     * Assigns the line to the person who is going to count it.
     *
     * `userId` is optional: without it, the caller **assigns to themselves**, which is the daily case
     * of the counter. With it, a supervisor distributes the count.
     */
    @PutMapping("/{id}/lines/{lineId}/assign")
    @RequiresPermission("invoice.assign")
    fun assign(
        @PathVariable id: Long,
        @PathVariable lineId: Long,
        @RequestBody body: AssignLineDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = assignInvoiceLine
        .execute(actor, id, lineId, body.userId, origin)
        .toResponse { it.toDto() }

    @PostMapping("/{id}/approve")
    @RequiresPermission("invoice.approve")
    fun approve(
        @PathVariable id: Long,
        @RequestBody body: DecisionDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = decision(approveInvoice, actor, id, body, origin)

    @PostMapping("/{id}/reject-discrepancy")
    @RequiresPermission("invoice.reject")
    fun rejectDiscrepancy(
        @PathVariable id: Long,
        @RequestBody body: DecisionDto,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = decision(rejectDiscrepancy, actor, id, body, origin)

    @PostMapping("/{id}/close")
    @RequiresPermission("invoice.close")
    fun close(
        @PathVariable id: Long,
        actor: Actor,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = closeInvoice.execute(actor, id, origin).toResponse { it.toDto() }

    /** The two decisions share a shape: a mandatory reason and an optional note. */
    private fun decision(
        useCase: ApproveInvoice,
        actor: Actor,
        id: Long,
        body: DecisionDto,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = runDecision { useCase.execute(actor, id, inputOf(body), origin) }

    private fun decision(
        useCase: RejectInvoiceDiscrepancy,
        actor: Actor,
        id: Long,
        body: DecisionDto,
        origin: RequestOrigin,
    ): ResponseEntity<Any> = runDecision { useCase.execute(actor, id, inputOf(body), origin) }

    private fun runDecision(block: () -> Outcome<InvoiceDetail>): ResponseEntity<Any> =
        when (val result = block()) {
            is Either.Left -> result.error.toResponse()
            is Either.Right -> ResponseEntity.ok(result.value.toDto())
        }

    private fun inputOf(body: DecisionDto): DecisionInput {
        val raw = body.adjustmentReason?.trim()?.uppercase()?.takeUnless { it.isBlank() }
        return DecisionInput(reason = raw?.let { AdjustmentReason.entries.firstOrNull { r -> r.name == it } }, note = body.note)
    }

    private companion object {
        val INVOICE_STATUSES = listOf("PENDING", "IN_PROGRESS", "PENDING_TO_LOCATE", "COMPLETED", "CANCELLED")
    }
}

/**
 * The quantity that arrives as a JSON number, without losing precision.
 *
 * If it cannot be converted, it **throws** the failure: it is a malformed request, and the
 * global handler already knows how to translate it into the usual error body.
 */
private fun quantityOf(raw: BigDecimal): Quantity =
    Quantity.parse(raw.toPlainString()).getOrElse {
        throw FailureException(Failure(FailureKind.RULE_VIOLATION, "INVALID_QUANTITY", it.message ?: "Invalid quantity."))
    }

/** Boxes are a whole count: half a box does not exist. */
private fun boxesOf(raw: BigDecimal?): Outcome<BoxQuantity?> {
    if (raw == null) return null.asRight()
    val quantity = quantityOf(raw)
    if (quantity.units < 0 || Quantity.of(quantity.units) != quantity) {
        return failure(
            FailureKind.RULE_VIOLATION, "INVALID_QUANTITY",
            "The counted boxes must be a non-negative whole number.",
            mapOf("field" to "physicalBoxQuantity"),
        )
    }
    return BoxQuantity.of(quantity.units).asRight()
}

/** Without `userId`, the assignment is to the caller. */
data class AssignLineDto(val userId: Long? = null)
