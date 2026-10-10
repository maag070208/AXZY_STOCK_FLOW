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
 * Los casos de uso de recepción (M03, docs/flujos/recepcion.md).
 *
 * La regla que gobierna el módulo es **B1**: una línea con diferencia sin aprobar no
 * se puede ubicar. Vive en el dominio ([InvoiceLine.locate]) y aquí solo se respeta:
 * la aprobación es una decisión sobre el documento entero, no sobre una línea suelta.
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

/** La bandeja de pendientes: sin ella, una discrepancia se queda bloqueada y nadie lo sabe. */
class PendingApprovalInbox(private val invoices: Invoices, private val access: AccessDecider) {

    fun execute(actor: Actor, origin: RequestOrigin): Outcome<List<PendingApproval>> =
        access.authorizeOr(actor, "invoice.view", origin) { invoices.pendingApproval().asRight() }
}

// ── Alta ────────────────────────────────────────────────────────────────────

data class CreatedInvoice(val id: Long, val invoiceNumber: String)

/**
 * Crea una factura con sus líneas.
 *
 * Se resuelven producto y unidad **antes** de escribir, dentro de la transacción: la
 * clave de negocio es lo que manda el cliente y el identificador es lo que guarda la
 * tabla. Una factura sin líneas se rechaza porque no se puede capturar ni ubicar, y
 * bloquearía el flujo desde el primer paso.
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
        // El prechequeo da el error legible en el caso normal; el índice único es el
        // que decide si dos peticiones llegan a la vez.
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
                "El número de factura es obligatorio.",
                mapOf("field" to "invoiceNumber"),
            )
        }
        // La columna es VARCHAR(60): decirlo aquí da un error claro en lugar de un
        // `22001 value too long` de PostgreSQL.
        if (number.length > 60) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                "El número de factura no puede pasar de 60 caracteres.",
                mapOf("field" to "invoiceNumber", "maxLength" to "60"),
            )
        }
        val rawDate = input.receiptDate?.trim()
        if (rawDate.isNullOrBlank()) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                "La fecha de la factura es obligatoria.",
                mapOf("field" to "receiptDate"),
            )
        }
        val date = runCatching { LocalDate.parse(rawDate) }.getOrNull()
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "La fecha de la factura no tiene el formato AAAA-MM-DD.",
                mapOf("field" to "receiptDate", "expected" to "AAAA-MM-DD", "value" to rawDate),
            )
        val type = input.invoiceType?.trim()?.uppercase()?.takeUnless { it.isBlank() } ?: NATIONAL
        if (type !in INVOICE_TYPES) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "El tipo de factura no está en la lista admitida.",
                mapOf("field" to "invoiceType", "value" to type, "allowed" to INVOICE_TYPES.joinToString(",")),
            )
        }
        if (input.lines.isEmpty()) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                "Una factura necesita al menos una línea: sin líneas no se puede capturar ni ubicar.",
                mapOf("field" to "lines"),
            )
        }
        return InvoiceDraft(number, date, type).asRight()
    }

    /** Resuelve producto, unidad y las reglas de empaque de cada línea. */
    private fun resolveLines(lines: List<NewInvoiceLine>): Outcome<List<ResolvedLine>> {
        val resolved = mutableListOf<ResolvedLine>()
        lines.forEachIndexed { index, line ->
            val position = index + 1
            val rawCode = line.productCode?.trim()
            if (rawCode.isNullOrBlank()) {
                return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "Falta el código de producto de la línea $position.",
                    mapOf("field" to "lines[$index].productCode"),
                )
            }
            val code = runCatching { ProductCode.of(rawCode) }.getOrElse {
                return DomainError.InvalidProductCode(rawCode, it.message ?: "código inválido").toFailure().asLeft()
            }
            val product = catalog.product(code)
                ?: return DomainError.InvalidProductCode(
                    rawCode,
                    "no existe en el catálogo o está inactivo",
                ).toFailure().asLeft()

            // La unidad de la línea es la del producto salvo que el cliente diga otra:
            // obligarle a repetir lo que el catálogo ya sabe sería fricción sin motivo.
            val unitMeasureId = line.unitMeasure?.trim()?.takeUnless { it.isBlank() }?.let { unit ->
                catalog.unitMeasureId(unit) ?: return DomainError.UnknownUnitMeasure(unit).toFailure().asLeft()
            } ?: product.unitMeasureId

            val quantity = line.quantity
                ?: return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "Falta la cantidad facturada de la línea $position.",
                    mapOf("field" to "lines[$index].quantity"),
                )
            if (!quantity.isPositive) {
                return DomainError.InvalidQuantity(
                    "La cantidad facturada debe ser mayor que cero (línea $position)",
                ).toFailure().asLeft()
            }

            // Las unidades por caja y las cajas son cosas **discretas**: dos cajas y
            // media no existe. Se exige un entero en lugar de redondear a espaldas del
            // cliente, que es lo que hacía el legacy con `Math.floor`.
            val rawUnitsPerBox = line.unitsPerBox
                ?: return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "Faltan las unidades por caja de la línea $position: sin ellas no se puede validar el empaque.",
                    mapOf("field" to "lines[$index].unitsPerBox"),
                )
            if (!rawUnitsPerBox.isPositive || !rawUnitsPerBox.isWhole) {
                return DomainError.InvalidQuantity(
                    "Las unidades por caja deben ser un entero mayor que cero (línea $position)",
                ).toFailure().asLeft()
            }
            val unitsPerBox = UnitsPerBox.of(rawUnitsPerBox.units)

            val boxQuantity = line.boxQuantity ?: Quantity.ZERO
            if (boxQuantity.units < 0 || !boxQuantity.isWhole) {
                return DomainError.InvalidQuantity(
                    "Las cajas facturadas deben ser un entero no negativo (línea $position)",
                ).toFailure().asLeft()
            }
            val boxes = BoxQuantity.of(boxQuantity.units)

            // Las cajas de la FACTURA tienen que cuadrar exactamente (R-80); las del
            // conteo físico no, porque «1 caja y 30 sueltas» es un faltante legítimo.
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
 * El conteo físico de una línea.
 *
 * El dominio decide el estado —`CAPTURED` si coincide con la factura, `DISCREPANCY`
 * si no— y **sin umbral**: una unidad de diferencia pide aprobación igual que cien.
 * El estado de la factura se recalcula después con el criterio del dominio
 * ([InvoiceLineStatus.blocksInvoiceClosing]), no con una lista escrita aquí.
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

            // Aprobar o ubicar cierra la captura de esa línea: volver a contar lo ya
            // autorizado borraría la decisión de un supervisor.
            if (line.status == InvoiceLineStatus.APPROVED || line.status == InvoiceLineStatus.LOCATED) {
                return@inTransaction failure(
                    FailureKind.CONFLICT, "LINE_ALREADY_RESOLVED",
                    "La línea ya está ${line.status.name.lowercase()} y no se puede volver a capturar.",
                    mapOf("lineId" to lineId.toString(), "status" to line.status.name),
                )
            }
            val quantity = input.physicalQuantity
                ?: return@inTransaction failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "Falta la cantidad contada.",
                    mapOf("field" to "physicalQuantity"),
                )
            val boxes = input.physicalBoxQuantity
                ?: return@inTransaction failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "Faltan las cajas contadas: es lo que permite comprobar que el conteo es coherente.",
                    mapOf("field" to "physicalBoxQuantity"),
                )

            when (val captured = line.capture(quantity, boxes, actor.id)) {
                is Either.Left -> captured.error.toFailure().asLeft()
                is Either.Right -> {
                    // **La mercancía entra al contar, y solo la diferencia.** El conteo es el
                    // momento en que el almacén toma posesión de lo que de verdad llegó —lo
                    // contado, no lo facturado—, y hasta aquí nadie la metía: el saldo tenía que
                    // aparecer por un ajuste a mano, así que ubicar desde RECEPCION se encontraba
                    // cero. Se ingresa la diferencia contra lo ya ingresado por la línea porque
                    // una línea descartada por el supervisor se recuenta (`rejectDiscrepancy`) y
                    // la entrada a ciegas contaría la mercancía dos veces.
                    val yaIngresado = line.receivedQuantity
                    val delta = (captured.value.physicalQuantity ?: Quantity.ZERO) - yaIngresado
                    val persisted = captured.value.copy(receivedQuantity = yaIngresado + delta)
                    // **Se reclama antes de mover.** Dos capturas simultáneas de la misma línea
                    // leerían el mismo saldo ingresado e ingresarían la misma diferencia: el
                    // stock se duplicaría. La condición sobre `received_quantity` hace que solo
                    // una gane; la otra no escribe ni el movimiento.
                    if (invoices.claimCapture(persisted, yaIngresado, clock.instant()) == 0) {
                        return@inTransaction failure(
                            FailureKind.CONFLICT, "LINE_CHANGED",
                            "Otra persona contó esta línea mientras tanto. Vuelve a consultarla " +
                                "antes de contarla otra vez.",
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
                    // La respuesta es la LÍNEA capturada, no la factura.
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
     * Mete en `RECEPCION` lo que se acaba de contar de más, o saca lo que ya no está.
     *
     * · Si lo contado es **más** que lo ingresado, entra la diferencia con `RECEIPT_IN`.
     * · Si es **menos**, la diferencia sale con un ajuste por **error de conteo**: el conteo
     *   anterior estaba alto, y decirlo con el motivo correcto es lo que permite auditar por
     *   qué bajó el inventario en lugar de dejarlo como una entrada que nunca existió.
     */
    private fun enterReceiving(actor: Actor, lineId: Long, line: InvoiceLine, delta: Quantity) {
        val productId = catalog.activeProduct(line.productCode)?.id ?: return
        val warehouseId = actor.user.warehouseIds.singleOrNull() ?: catalog.defaultWarehouseId()
        val receiving = catalog.activeLocation(warehouseId, LocationRef.Receiving)
            ?: error("No existe la ubicación RECEPCION en la bodega $warehouseId.")

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

// ── Aprobación y rechazo ────────────────────────────────────────────────────

/** La decisión del supervisor: el motivo es obligatorio, la nota es opcional. */
data class DecisionInput(val reason: AdjustmentReason?, val note: String?)

/**
 * Aprueba **todas** las discrepancias de una factura.
 *
 * Es la única ruta de aprobación: aprobar por línea obligaría al supervisor a repetir
 * el mismo juicio N veces y dejaría la factura a medias, y era además lo que invitaba
 * a aprobar lo propio sin darse cuenta. Con una sola acción sobre el documento, la
 * segregación de funciones es evidente.
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

            // El dominio valida cada transición antes de escribir: si una no se puede
            // aprobar, no se aprueba ninguna.
            val approved = mutableMapOf<Long, InvoiceLine>()
            pending.forEach { view ->
                when (val result = view.line.approveDiscrepancy(actor.id, reason.name)) {
                    is Either.Left -> return@inTransaction result.error.toFailure().asLeft()
                    is Either.Right -> approved[view.line.id] = result.value
                }
            }

            val affected = invoices.approveDiscrepancies(invoiceId, actor.id, reason.name, clock.instant())
            if (affected != pending.size) {
                // La sentencia lleva la condición (`status = 'DISCREPANCY'`): si el
                // número no cuadra, otra persona las cambió a la vez y esta operación
                // no se puede dar por buena.
                return@inTransaction failure(
                    FailureKind.CONFLICT, "INVALID_TRANSITION",
                    "Las líneas cambiaron mientras se aprobaban: vuelve a revisar la factura.",
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
            // La respuesta es la factura releída: el estado real, con quién aprobó y cuándo.
            invoices.detail(invoiceId)!!.asRight()
        }
    }
}

/**
 * La otra salida de B1: el supervisor **no acepta** la diferencia.
 *
 * La línea vuelve a `PENDING` con el conteo limpio, no se cancela: el número que vio
 * el supervisor puede ser el correcto, y lo que falta es un reconteo. Cancelarla
 * dejaría mercancía en el almacén que nadie contó y una línea muerta que ya no se
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
                    "Las líneas cambiaron mientras se rechazaban: vuelve a revisar la factura.",
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
 * Cierra la factura.
 *
 * El criterio de qué bloquea lo define el dominio
 * ([InvoiceLineStatus.blocksInvoiceClosing]) y es **el mismo que cuenta el listado**:
 * así la lista y la acción no pueden discrepar.
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
                        "Una factura anulada no se cierra.",
                        mapOf("invoiceId" to invoiceId.toString(), "status" to CANCELLED),
                    )
                }
                val blocking = detail.lines.filter { it.line.status.blocksInvoiceClosing }
                if (blocking.isNotEmpty()) {
                    return@inTransaction failure(
                        FailureKind.RULE_VIOLATION, "INVOICE_HAS_PENDING_LINES",
                        "La factura tiene ${blocking.size} línea(s) sin resolver: no se puede cerrar.",
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

// ── Común ───────────────────────────────────────────────────────────────────

/**
 * El estado de la factura a partir de sus líneas, con el criterio del dominio.
 *
 * Una factura sin líneas bloqueantes pasa a `PENDING_TO_LOCATE`: ya está contada y lo
 * que queda es ubicarla. No se toca una factura cerrada ni anulada.
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
    "No existe la factura $invoiceId.",
    mapOf("invoiceId" to invoiceId.toString()),
)

private fun lineNotFound(lineId: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "LINE_NOT_FOUND",
    "No existe la línea $lineId en esta factura.",
    mapOf("lineId" to lineId.toString()),
)

private fun duplicateNumber(number: String): Outcome<Nothing> = failure(
    FailureKind.CONFLICT, "DUPLICATE_INVOICE_NUMBER",
    "Ya existe una factura con el número '$number'.",
    mapOf("invoiceNumber" to number),
)

private fun noPendingApproval(invoiceId: Long): Outcome<Nothing> = failure(
    FailureKind.CONFLICT, "NO_PENDING_APPROVAL",
    "La factura $invoiceId no tiene líneas en discrepancia que resolver.",
    mapOf("invoiceId" to invoiceId.toString()),
)

private fun reasonRequired(action: String = "approve"): Outcome<Nothing> = failure(
    FailureKind.RULE_VIOLATION, "REASON_REQUIRED",
    "La decisión sobre una discrepancia exige un motivo.",
    mapOf("field" to "adjustmentReason", "action" to action),
)

/** Se dicen CUÁLES líneas: un «no puedes» sin ellas obliga a adivinar qué contó uno. */
private fun segregation(lines: List<InvoiceLineView>, action: String): Outcome<Nothing> = failure(
    FailureKind.FORBIDDEN, "SEGREGATION_OF_DUTIES",
    "No puedes $action una discrepancia que tú mismo capturaste: son las líneas " +
        lines.joinToString(", ") { it.line.id.toString() } + ".",
    mapOf("lines" to lines.joinToString(",") { it.line.id.toString() }),
)

/** La cantidad con los tres decimales del contrato, sin notación científica. */
internal fun Quantity.toPlainString(): String = BigDecimal.valueOf(value, Quantity.SCALE).toPlainString()

/**
 * Indica si la cantidad es un número entero de unidades.
 *
 * La cantidad del dominio va en milésimas, así que «entero» es múltiplo de mil. Se
 * necesita para los campos que cuentan cosas discretas —cajas, unidades por caja—:
 * dos cajas y media no existe, y aceptar `2.5` sería redondear a espaldas del cliente.
 */
internal val Quantity.isWhole: Boolean get() = Quantity.of(units) == this
