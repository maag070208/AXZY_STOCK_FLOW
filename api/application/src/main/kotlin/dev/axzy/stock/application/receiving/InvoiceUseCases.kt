package dev.axzy.stock.application.receiving

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.application.inventory.MovementCommand
import dev.axzy.stock.application.inventory.LocationRef
import dev.axzy.stock.application.inventory.InventoryCatalog
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
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.receiving.InvoiceLine
import dev.axzy.stock.domain.receiving.InvoiceLineStatus
import dev.axzy.stock.domain.value.BoxQuantity
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.UnitsPerBox
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate

/**
 * The receiving use cases (M03, docs/flujos/recepcion.md).
 *
 * The rule that governs the module is **B1**: a line with an unapproved difference cannot
 * be located. It lives in the domain ([InvoiceLine.locate]) and here it is only respected:
 * approval is a decision on the whole document, not on a single line.
 */

// ── Consultas ───────────────────────────────────────────────────────────────

class ListInvoices(private val invoices: Invoices, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        filter: InvoiceFilter,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<Page<InvoiceHeader>> = access.authorizeOr(actor, "invoice.view", origin) {
        invoices.page(filter, request).asRight()
    }
}

class GetInvoice(private val invoices: Invoices, private val access: AccessDecider) {

    fun execute(actor: Actor, invoiceId: Long, origin: RequestOrigin): Outcome<InvoiceDetail> =
        access.authorizeOr(actor, "invoice.view", origin) {
            invoices.detail(invoiceId)?.asRight() ?: notFound(invoiceId)
        }
}

/** The pending inbox: without it, a discrepancy gets stuck and nobody knows. */
class PendingApprovalInbox(private val invoices: Invoices, private val access: AccessDecider) {

    fun execute(actor: Actor, origin: RequestOrigin): Outcome<List<PendingApproval>> =
        access.authorizeOr(actor, "invoice.view", origin) { invoices.pendingApproval().asRight() }
}

// ── Alta ────────────────────────────────────────────────────────────────────

data class CreatedInvoice(val id: Long, val invoiceNumber: String)

/**
 * Creates an invoice with its lines.
 *
 * Product and unit are resolved **before** writing, inside the transaction: the
 * business key is what the client sends and the identifier is what the
 * table stores. An invoice without lines is rejected because it cannot be counted or located, and
 * it would block the flow from the first step.
 */
class CreateInvoice(
    private val catalog: ReceivingCatalog,
    private val invoices: Invoices,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(actor: Actor, input: NewInvoice, origin: RequestOrigin): Outcome<CreatedInvoice> =
        access.authorizeOr(actor, "invoice.create", origin) {
            when (val header = validateHeader(input)) {
                is Either.Left -> header.error.asLeft()
                is Either.Right -> when (val lines = resolveLines(input.lines)) {
                    is Either.Left -> lines.error.asLeft()
                    is Either.Right -> insert(actor, header.value, lines.value, origin)
                }
            }
        }

    private fun insert(
        actor: Actor,
        draft: InvoiceDraft,
        lines: List<ResolvedLine>,
        origin: RequestOrigin,
    ): Outcome<CreatedInvoice> {
        // The pre-check gives the readable error in the normal case; the unique index is the
        // one that decides whether two requests arrive at the same time.
        if (invoices.numberExists(draft.invoiceNumber)) return duplicateNumber(draft.invoiceNumber)

        return tx.inTransaction {
            when (val inserted = invoices.insert(draft, lines)) {
                InsertResult.DuplicateNumber -> duplicateNumber(draft.invoiceNumber)
                is InsertResult.Inserted -> {
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "INVOICE_CREATED",
                            level = AuditLevel.RELEVANT,
                            entity = AuditEntity("invoice", inserted.id.toString(), draft.invoiceNumber),
                            newState = mapOf(
                                "invoiceNumber" to draft.invoiceNumber,
                                "receiptDate" to draft.receiptDate.toString(),
                                "lines" to lines.size.toString(),
                            ),
                        ),
                    )
                    CreatedInvoice(inserted.id, draft.invoiceNumber).asRight()
                }
            }
        }
    }

    private fun validateHeader(input: NewInvoice): Outcome<InvoiceDraft> {
        val number = input.invoiceNumber?.trim()
        if (number.isNullOrBlank()) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                "The invoice number is required.",
                mapOf("field" to "invoiceNumber"),
            )
        }
        // The column is VARCHAR(60): saying so here gives a clear error instead of a
        // `22001 value too long` from PostgreSQL.
        if (number.length > 60) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                "The invoice number cannot exceed 60 characters.",
                mapOf("field" to "invoiceNumber", "maxLength" to "60"),
            )
        }
        val rawDate = input.receiptDate?.trim()
        if (rawDate.isNullOrBlank()) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                "The invoice date is required.",
                mapOf("field" to "receiptDate"),
            )
        }
        val date = runCatching { LocalDate.parse(rawDate) }.getOrNull()
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The invoice date is not in the format YYYY-MM-DD.",
                mapOf("field" to "receiptDate", "expected" to "AAAA-MM-DD", "value" to rawDate),
            )
        val type = input.invoiceType?.trim()?.uppercase()?.takeUnless { it.isBlank() } ?: NATIONAL
        if (type !in INVOICE_TYPES) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The invoice type is not in the list of allowed values.",
                mapOf("field" to "invoiceType", "value" to type, "allowed" to INVOICE_TYPES.joinToString(",")),
            )
        }
        if (input.lines.isEmpty()) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                "An invoice needs at least one line: without lines nothing can be counted or located.",
                mapOf("field" to "lines"),
            )
        }
        return InvoiceDraft(number, date, type).asRight()
    }

    /** Resolves product, unit and the packing rules of each line. */
    private fun resolveLines(lines: List<NewInvoiceLine>): Outcome<List<ResolvedLine>> {
        val resolved = mutableListOf<ResolvedLine>()
        lines.forEachIndexed { index, line ->
            val position = index + 1
            val rawCode = line.productCode?.trim()
            if (rawCode.isNullOrBlank()) {
                return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The product code of line $position is missing.",
                    mapOf("field" to "lines[$index].productCode"),
                )
            }
            val code = runCatching { ProductCode.of(rawCode) }.getOrElse {
                return DomainError.InvalidProductCode(rawCode, it.message ?: "invalid code").toFailure().asLeft()
            }
            val product = catalog.product(code)
                ?: return DomainError.InvalidProductCode(
                    rawCode,
                    "does not exist in the catalog or is inactive",
                ).toFailure().asLeft()

            // The line's unit is the product's unless the client says another: making them repeat
            // what the catalog already knows would be friction for no reason.
            val unitMeasureId = line.unitMeasure?.trim()?.takeUnless { it.isBlank() }?.let { unit ->
                catalog.unitMeasureId(unit) ?: return DomainError.UnknownUnitMeasure(unit).toFailure().asLeft()
            } ?: product.unitMeasureId

            val quantity = line.quantity
                ?: return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The invoiced quantity of line $position is missing.",
                    mapOf("field" to "lines[$index].quantity"),
                )
            if (!quantity.isPositive) {
                return DomainError.InvalidQuantity(
                    "The invoiced quantity must be greater than zero (line $position)",
                ).toFailure().asLeft()
            }

            // Units per box and boxes are **discrete** things: two boxes and a half do not
            // exist. An integer is required instead of rounding behind the
            // client's back, which is what the legacy did with `Math.floor`.
            val rawUnitsPerBox = line.unitsPerBox
                ?: return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The units per box of line $position are missing: without them the packing cannot be validated.",
                    mapOf("field" to "lines[$index].unitsPerBox"),
                )
            if (!rawUnitsPerBox.isPositive || !rawUnitsPerBox.isWhole) {
                return DomainError.InvalidQuantity(
                    "Units per box must be a whole number greater than zero (line $position)",
                ).toFailure().asLeft()
            }
            val unitsPerBox = UnitsPerBox.of(rawUnitsPerBox.units)

            val boxQuantity = line.boxQuantity ?: Quantity.ZERO
            if (boxQuantity.units < 0 || !boxQuantity.isWhole) {
                return DomainError.InvalidQuantity(
                    "The invoiced boxes must be a non-negative whole number (line $position)",
                ).toFailure().asLeft()
            }
            val boxes = BoxQuantity.of(boxQuantity.units)

            // The invoice's boxes must add up exactly (R-80); those of the physical count do not,
            // because "1 box and 30 loose" is a legitimate shortage.
            when (val packing = InvoiceLine.validatePackingSpecification(quantity, unitsPerBox)) {
                is Either.Left -> return packing.error.toFailure().asLeft()
                is Either.Right -> Unit
            }
            if (!boxes.isConsistentWith(quantity, unitsPerBox)) {
                return DomainError.InconsistentBoxCount(
                    quantity = quantity.units,
                    boxes = boxes.value,
                    unitsPerBox = unitsPerBox.value,
                ).toFailure().asLeft()
            }

            resolved += ResolvedLine(
                productId = product.id,
                productCode = code,
                unitMeasureId = unitMeasureId,
                container = line.container?.trim()?.takeUnless { it.isBlank() },
                quantity = quantity,
                boxQuantity = boxQuantity,
                unitsPerBox = unitsPerBox,
            )
        }
        return resolved.asRight()
    }
}

// ── Captura ─────────────────────────────────────────────────────────────────

data class CaptureInput(val physicalQuantity: Quantity?, val physicalBoxQuantity: BoxQuantity?)

/**
 * The physical count of a line.
 *
 * The domain decides the status —`CAPTURED` if it matches the invoice, `DISCREPANCY`
 * if not— and **with no threshold**: a one-unit difference requires approval just like a hundred.
 * The invoice status is recalculated afterwards with the domain's criterion
 * ([InvoiceLineStatus.blocksInvoiceClosing]), not with a list written here.
 */
class CaptureLine(
    private val invoices: Invoices,
    private val ledger: StockLedger,
    private val catalog: InventoryCatalog,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        actor: Actor,
        invoiceId: Long,
        lineId: Long,
        input: CaptureInput,
        origin: RequestOrigin,
    ): Outcome<InvoiceLineView> = access.authorizeOr(actor, "invoice.capture", origin) {
        tx.inTransaction {
            val detail = invoices.detail(invoiceId) ?: return@inTransaction notFound(invoiceId)
            val view = detail.lines.firstOrNull { it.line.id == lineId }
                ?: return@inTransaction lineNotFound(lineId)
            val line = view.line

            // Approving or locating closes the count of that line: recounting what was already
            // authorized would erase a supervisor's decision.
            if (line.status == InvoiceLineStatus.APPROVED || line.status == InvoiceLineStatus.LOCATED) {
                return@inTransaction failure(
                    FailureKind.CONFLICT, "LINE_ALREADY_RESOLVED",
                    "The line is already ${line.status.name.lowercase()} and cannot be counted again.",
                    mapOf("lineId" to lineId.toString(), "status" to line.status.name),
                )
            }
            val quantity = input.physicalQuantity
                ?: return@inTransaction failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The counted quantity is required.",
                    mapOf("field" to "physicalQuantity"),
                )
            val boxes = input.physicalBoxQuantity
                ?: return@inTransaction failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "The counted boxes are missing: they are what allows checking that the count is consistent.",
                    mapOf("field" to "physicalBoxQuantity"),
                )

            when (val captured = line.capture(quantity, boxes, actor.id)) {
                is Either.Left -> captured.error.toFailure().asLeft()
                is Either.Right -> {
                    // **The goods enter on counting, and only the difference.** Counting is the
                    // moment the warehouse takes possession of what really arrived —what was counted,
                    // not what was invoiced— and until now nobody put it in: the balance had to
                    // appear through a manual adjustment, so locating from RECEIVING found
                    // zero. The difference is entered against what the line already entered, because
                    // a line discarded by the supervisor is recounted (`rejectDiscrepancy`) and
                    // a blind entry would count the goods twice.
                    val yaIngresado = line.receivedQuantity
                    val delta = (captured.value.physicalQuantity ?: Quantity.ZERO) - yaIngresado
                    val persisted = captured.value.copy(receivedQuantity = yaIngresado + delta)
                    // **It is claimed before moving.** Two simultaneous counts of the same line would
                    // read the same entered balance and enter the same difference: the
                    // stock would double. The condition on `received_quantity` means that only
                    // one wins; the other one writes neither the movement nor the line.
                    if (invoices.claimCapture(persisted, yaIngresado, clock.instant()) == 0) {
                        return@inTransaction failure(
                            FailureKind.CONFLICT, "LINE_CHANGED",
                            "Someone else counted this line in the meantime. Look it up again " +
                                "before counting it again.",
                            mapOf("lineId" to lineId.toString()),
                        )
                    }
                    if (!delta.isZero) enterReceiving(actor, lineId, persisted, delta)
                    val updated = detail.lines.map { if (it.line.id == lineId) persisted else it.line }
                    val status = invoiceStatusFor(updated, detail.header.status)
                    if (status != detail.header.status) invoices.setStatus(invoiceId, status, clock.instant())

                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "INVOICE_LINE_CAPTURED",
                            level = AuditLevel.RELEVANT,
                            entity = AuditEntity("invoice_line", lineId.toString(), detail.header.invoiceNumber),
                            previousState = mapOf(
                                "status" to line.status.name,
                                "receivedQuantity" to yaIngresado.toPlainString(),
                            ),
                            newState = mapOf(
                                "status" to persisted.status.name,
                                "physicalQuantity" to persisted.physicalQuantity?.toPlainString(),
                                "difference" to persisted.difference?.toPlainString(),
                                "invoiceStatus" to status,
                                "enteredNow" to delta.toPlainString(),
                            ),
                        ),
                    )
                    // The response is the LINE counted, not the invoice.
                    view.copy(
                        line = persisted,
                        difference = persisted.difference,
                        capturedByName = actor.displayName,
                        capturedAt = clock.instant(),
                    ).asRight()
                }
            }
        }
    }

    /**
     * Puts into `RECEIVING` what was just counted in excess, or takes out what is no longer there.
     *
     * · If what was counted is **more** than what was entered, the difference comes in with `RECEIPT_IN`.
     * · If it is **less**, the difference goes out with an adjustment for a **counting error**: the previous count
     *   was too high, and saying so with the right reason is what lets one audit why
     *   the inventory went down instead of leaving it as an entry that never existed.
     */
    private fun enterReceiving(actor: Actor, lineId: Long, line: InvoiceLine, delta: Quantity) {
        val productId = catalog.activeProduct(line.productCode)?.id ?: return
        val warehouseId = actor.user.warehouseIds.singleOrNull() ?: catalog.defaultWarehouseId()
        val receiving = catalog.activeLocation(warehouseId, LocationRef.Receiving)
            ?: error("Location RECEIVING does not exist in warehouse $warehouseId.")

        if (delta.isPositive) {
            ledger.apply(
                MovementCommand(
                    productId = productId,
                    warehouseId = warehouseId,
                    moveType = MovementType.RECEIPT_IN,
                    units = delta,
                    toLocationId = receiving.id,
                    userId = actor.id,
                ),
            )
        } else {
            ledger.apply(
                MovementCommand(
                    productId = productId,
                    warehouseId = warehouseId,
                    moveType = MovementType.ADJUSTMENT_OUT,
                    units = Quantity.ofScaled(-delta.value),
                    fromLocationId = receiving.id,
                    userId = actor.id,
                    adjustmentReason = AdjustmentReason.COUNT_ERROR,
                ),
            )
        }
    }
}

// ── Approval and rejection ─────────────────────────────────────────────────

/** The supervisor's decision: the reason is mandatory, the note is optional. */
data class DecisionInput(val reason: AdjustmentReason?, val note: String?)

/**
 * Approves **all** the discrepancies of an invoice.
 *
 * It is the only approval route: approving line by line would force the supervisor to repeat
 * the same judgment N times and would leave the invoice half-done, and it was also what invited
 * approving one's own without noticing. With a single action on the document, the
 * segregation of duties is evident.
 */
class ApproveInvoice(
    private val invoices: Invoices,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        actor: Actor,
        invoiceId: Long,
        input: DecisionInput,
        origin: RequestOrigin,
    ): Outcome<InvoiceDetail> = access.authorizeOr(actor, "invoice.approve", origin) { grant ->
        tx.inTransaction {
            val detail = invoices.detail(invoiceId) ?: return@inTransaction notFound(invoiceId)
            val pending = detail.lines.filter { it.line.status == InvoiceLineStatus.DISCREPANCY }
            if (pending.isEmpty()) return@inTransaction noPendingApproval(invoiceId)

            val mine = pending.filter { it.line.capturedBy == actor.id }
            if (mine.isNotEmpty()) return@inTransaction segregation(mine, "aprobar")
            val reason = input.reason ?: return@inTransaction reasonRequired()

            // The domain validates each transition before writing: if one cannot be
            // approved, none is approved.
            val approved = mutableMapOf<Long, InvoiceLine>()
            pending.forEach { view ->
                when (val result = view.line.approveDiscrepancy(actor.id, reason.name)) {
                    is Either.Left -> return@inTransaction result.error.toFailure().asLeft()
                    is Either.Right -> approved[view.line.id] = result.value
                }
            }

            val affected = invoices.approveDiscrepancies(invoiceId, actor.id, reason.name, clock.instant())
            if (affected != pending.size) {
                // The statement carries the condition (`status = 'DISCREPANCY'`): if the
                // number does not match, another person changed them at the same time and this operation
                // cannot be accepted.
                return@inTransaction failure(
                    FailureKind.CONFLICT, "INVALID_TRANSITION",
                    "The lines changed while they were being approved: review the invoice again.",
                    mapOf("expected" to pending.size.toString(), "affected" to affected.toString()),
                )
            }

            val updated = detail.lines.map { approved[it.line.id] ?: it.line }
            val status = invoiceStatusFor(updated, detail.header.status)
            if (status != detail.header.status) invoices.setStatus(invoiceId, status, clock.instant())

            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "INVOICE_APPROVED",
                    level = AuditLevel.CRITICAL,
                    entity = AuditEntity("invoice", invoiceId.toString(), detail.header.invoiceNumber),
                    grant = grant,
                            previousState = mapOf("lines" to pending.map { it.line.id }, "status" to "DISCREPANCY"),
                    newState = mapOf("lines" to pending.map { it.line.id }, "status" to "APPROVED"),
                    reason = reason.name,
                    note = input.note,
                ),
            )
            // The response is the invoice re-read: the real state, with who approved and when.
            invoices.detail(invoiceId)!!.asRight()
        }
    }
}

/**
 * The other exit of B1: the supervisor **does not accept** the difference.
 *
 * The line goes back to `PENDING` with a clean count; it is not cancelled: the number the
 * supervisor saw may be the correct one, and what is missing is a recount. Cancelling it would
 * leave goods in the warehouse that nobody counted and a dead line that no longer
 * puede capturar.
 */
class RejectInvoiceDiscrepancy(
    private val invoices: Invoices,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        actor: Actor,
        invoiceId: Long,
        input: DecisionInput,
        origin: RequestOrigin,
    ): Outcome<InvoiceDetail> = access.authorizeOr(actor, "invoice.reject", origin) { grant ->
        tx.inTransaction {
            val detail = invoices.detail(invoiceId) ?: return@inTransaction notFound(invoiceId)
            val pending = detail.lines.filter { it.line.status == InvoiceLineStatus.DISCREPANCY }
            if (pending.isEmpty()) return@inTransaction noPendingApproval(invoiceId)

            val mine = pending.filter { it.line.capturedBy == actor.id }
            if (mine.isNotEmpty()) return@inTransaction segregation(mine, "rechazar")
            val reason = input.reason ?: return@inTransaction reasonRequired(action = "reject")

            pending.forEach { view ->
                when (val result = view.line.rejectDiscrepancy(reason.name)) {
                    is Either.Left -> return@inTransaction result.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }
            }

            val affected = invoices.rejectDiscrepancies(invoiceId, clock.instant())
            if (affected != pending.size) {
                return@inTransaction failure(
                    FailureKind.CONFLICT, "INVALID_TRANSITION",
                    "The lines changed while they were being rejected: review the invoice again.",
                    mapOf("expected" to pending.size.toString(), "affected" to affected.toString()),
                )
            }

            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "INVOICE_REJECTED",
                    level = AuditLevel.CRITICAL,
                    entity = AuditEntity("invoice", invoiceId.toString(), detail.header.invoiceNumber),
                    grant = grant,
                            previousState = mapOf("lines" to pending.map { it.line.id }, "status" to "DISCREPANCY"),
                    newState = mapOf("lines" to pending.map { it.line.id }, "status" to "PENDING"),
                    reason = reason.name,
                    note = input.note,
                ),
            )
            invoices.detail(invoiceId)!!.asRight()
        }
    }
}

// ── Cierre ──────────────────────────────────────────────────────────────────

/**
 * Closes the invoice.
 *
 * The rule for what blocks is defined by the domain
 * ([InvoiceLineStatus.blocksInvoiceClosing]) and it is **the same one the listing counts**:
 * so the list and the action cannot disagree.
 */
class CloseInvoice(
    private val invoices: Invoices,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, invoiceId: Long, origin: RequestOrigin): Outcome<InvoiceDetail> =
        access.authorizeOr(actor, "invoice.close", origin) {
            tx.inTransaction {
                val detail = invoices.detail(invoiceId) ?: return@inTransaction notFound(invoiceId)
                if (detail.header.status == COMPLETED) {
                    return@inTransaction failure(
                        FailureKind.CONFLICT, "INVOICE_ALREADY_CLOSED",
                        "La factura ${detail.header.invoiceNumber} ya estaba cerrada.",
                        mapOf("invoiceId" to invoiceId.toString()),
                    )
                }
                if (detail.header.status == CANCELLED) {
                    return@inTransaction failure(
                        FailureKind.CONFLICT, "INVALID_TRANSITION",
                        "A voided invoice cannot be closed.",
                        mapOf("invoiceId" to invoiceId.toString(), "status" to CANCELLED),
                    )
                }
                val blocking = detail.lines.filter { it.line.status.blocksInvoiceClosing }
                if (blocking.isNotEmpty()) {
                    return@inTransaction failure(
                        FailureKind.RULE_VIOLATION, "INVOICE_HAS_PENDING_LINES",
                        "The invoice has ${blocking.size} unresolved line(s): it cannot be closed.",
                        mapOf("pendingLines" to blocking.size.toString(), "lines" to blocking.joinToString(",") { it.line.id.toString() }),
                    )
                }

                invoices.close(invoiceId, actor.id, clock.instant())
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "INVOICE_CLOSED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("invoice", invoiceId.toString(), detail.header.invoiceNumber),
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to COMPLETED, "lines" to detail.lines.size),
                    ),
                )
                invoices.detail(invoiceId)!!.asRight()
            }
        }
}

// ── Common ──────────────────────────────────────────────────────────────

/**
 * The invoice status from its lines, with the domain's rule.
 *
 * An invoice without blocking lines moves to `PENDING_TO_LOCATE`: it is already counted and what
 * remains is to locate it. A closed or cancelled invoice is not touched.
 */
internal fun invoiceStatusFor(lines: List<InvoiceLine>, current: String): String = when {
    current == COMPLETED || current == CANCELLED -> current
    lines.none { it.status.blocksInvoiceClosing } -> PENDING_TO_LOCATE
    current == PENDING -> IN_PROGRESS
    else -> current
}

internal const val NATIONAL = "NATIONAL"
internal const val PENDING = "PENDING"
internal const val IN_PROGRESS = "IN_PROGRESS"
internal const val PENDING_TO_LOCATE = "PENDING_TO_LOCATE"
internal const val COMPLETED = "COMPLETED"
internal const val CANCELLED = "CANCELLED"

internal val INVOICE_TYPES = listOf(NATIONAL, "INTERNATIONAL")

private fun notFound(invoiceId: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "INVOICE_NOT_FOUND",
    "Invoice $invoiceId does not exist.",
    mapOf("invoiceId" to invoiceId.toString()),
)

private fun lineNotFound(lineId: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "LINE_NOT_FOUND",
    "Line $lineId does not exist on this invoice.",
    mapOf("lineId" to lineId.toString()),
)

private fun duplicateNumber(number: String): Outcome<Nothing> = failure(
    FailureKind.CONFLICT, "DUPLICATE_INVOICE_NUMBER",
    "An invoice with the number '$number' already exists.",
    mapOf("invoiceNumber" to number),
)

private fun noPendingApproval(invoiceId: Long): Outcome<Nothing> = failure(
    FailureKind.CONFLICT, "NO_PENDING_APPROVAL",
    "Invoice $invoiceId has no discrepant lines to resolve.",
    mapOf("invoiceId" to invoiceId.toString()),
)

private fun reasonRequired(action: String = "approve"): Outcome<Nothing> = failure(
    FailureKind.RULE_VIOLATION, "REASON_REQUIRED",
    "A decision on a discrepancy requires a reason.",
    mapOf("field" to "adjustmentReason", "action" to action),
)

/** It says WHICH lines: a "you cannot" without them forces guessing what one counted. */
private fun segregation(lines: List<InvoiceLineView>, action: String): Outcome<Nothing> = failure(
    FailureKind.FORBIDDEN, "SEGREGATION_OF_DUTIES",
    "You cannot $action a discrepancy you counted yourself: these are the lines " +
        lines.joinToString(", ") { it.line.id.toString() } + ".",
    mapOf("lines" to lines.joinToString(",") { it.line.id.toString() }),
)

/** The quantity with the contract's three decimals, without scientific notation. */
internal fun Quantity.toPlainString(): String = BigDecimal.valueOf(value, Quantity.SCALE).toPlainString()

/**
 * Indicates whether the quantity is a whole number of units.
 *
 * The domain quantity is in thousandths, so "whole" is a multiple of a thousand. It is
 * needed for the fields that count discrete things —boxes, units per box—:
 * two and a half boxes do not exist, and accepting `2.5` would round behind the client's back.
 */
internal val Quantity.isWhole: Boolean get() = Quantity.of(units) == this
