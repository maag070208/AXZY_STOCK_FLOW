package dev.axzy.stock.application.fulfillment

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.inventory.AdjustStock
import dev.axzy.stock.application.inventory.AdjustmentInput
import dev.axzy.stock.application.inventory.MovementCommand
import dev.axzy.stock.application.inventory.StockLedger
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
import dev.axzy.stock.domain.fulfillment.Order
import dev.axzy.stock.domain.fulfillment.OrderLine
import dev.axzy.stock.domain.fulfillment.OrderLineStatus
import dev.axzy.stock.domain.fulfillment.OrderStatus
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Clock
import java.time.LocalDate
import java.time.Year

/**
 * Los casos de uso de surtido (M04) y la exportación al ERP (M09).
 *
 * Dos reglas mandan sobre el resto y están en el dominio:
 *
 * · **Empacar descuenta contra lo PENDIENTE, no contra lo pedido** (S1): empacar 7 de
 *   10 dos veces sumaría 14.
 * · **Confirmar descuenta `quantity - suppliedQuantity`**, no `quantity` (S2): lo
 *   empacado ya salió, y volver a descontarlo completo dejaría el inventario corto y
 *   el descuadre se confundiría con una merma.
 */

// ── Consultas ───────────────────────────────────────────────────────────────

class ListOrders(private val orders: Orders, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        filter: OrderFilter,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<Page<OrderHeader>> = access.authorizeOr(actor, "order.view", origin) {
        orders.page(filter, request).asRight()
    }
}

class GetOrder(private val orders: Orders, private val access: AccessDecider) {

    fun execute(actor: Actor, orderId: Long, origin: RequestOrigin): Outcome<OrderDetail> =
        access.authorizeOr(actor, "order.view", origin) {
            orders.detail(orderId)?.asRight() ?: orderNotFound(orderId)
        }
}

// ── Alta ────────────────────────────────────────────────────────────────────

class CreateOrder(
    private val catalog: OrderCatalog,
    private val orders: Orders,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, input: NewOrder, origin: RequestOrigin): Outcome<OrderDetail> =
        access.authorizeOr(actor, "order.create", origin) { grant ->
            if (input.lines.isEmpty()) {
                return@authorizeOr failure(
                    FailureKind.RULE_VIOLATION, "EMPTY_ORDER",
                    "Una orden necesita al menos una línea.",
                    mapOf("field" to "lines"),
                )
            }
            when (val header = resolveHeader(input)) {
                is Either.Left -> header.error.asLeft()
                is Either.Right -> when (val lines = resolveLines(input.lines)) {
                    is Either.Left -> lines.error.asLeft()
                    is Either.Right -> insert(actor, header.value, lines.value, origin, grant)
                }
            }
        }

    private fun insert(
        actor: Actor,
        draft: OrderDraft,
        lines: List<ResolvedOrderLine>,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ): Outcome<OrderDetail> {
        if (orders.numberExists(draft.orderNumber)) return duplicateNumber(draft.orderNumber)

        return tx.inTransaction {
            when (val inserted = orders.insert(draft, lines)) {
                InsertOrderResult.DuplicateNumber -> duplicateNumber(draft.orderNumber)
                is InsertOrderResult.Inserted -> {
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "ORDER_CREATED",
                            level = AuditLevel.RELEVANT,
                            entity = AuditEntity("order", inserted.id.toString(), draft.orderNumber),
                            grant = grant,
                            newState = mapOf("orderNumber" to draft.orderNumber, "lines" to lines.size),
                        ),
                    )
                    orders.detail(inserted.id)?.asRight() ?: orderNotFound(inserted.id)
                }
            }
        }
    }

    /** El número y la fecha son opcionales: si no vienen, los pone el servidor. */
    private fun resolveHeader(input: NewOrder): Outcome<OrderDraft> {
        val rawDate = input.packagingDate?.trim()
        val date = if (rawDate.isNullOrBlank()) {
            LocalDate.now(clock)
        } else {
            runCatching { LocalDate.parse(rawDate) }.getOrNull()
                ?: return failure(
                    FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                    "La fecha de empaque no tiene el formato AAAA-MM-DD.",
                    mapOf("field" to "packagingDate", "expected" to "AAAA-MM-DD", "value" to rawDate),
                )
        }
        val number = input.orderNumber?.trim()?.takeUnless { it.isBlank() }
            ?: orders.nextOrderNumber(Year.now(clock).value)
        if (number.length > 40) {
            return failure(
                FailureKind.RULE_VIOLATION, "FIELD_TOO_LONG",
                "El número de orden no puede pasar de 40 caracteres.",
                mapOf("field" to "orderNumber", "maxLength" to "40"),
            )
        }
        return OrderDraft(
            orderNumber = number,
            packagingDate = date,
            customerCode = input.customerCode?.trim()?.takeUnless { it.isBlank() },
            concept = input.concept?.trim()?.takeUnless { it.isBlank() },
        ).asRight()
    }

    private fun resolveLines(lines: List<NewOrderLine>): Outcome<List<ResolvedOrderLine>> {
        val resolved = mutableListOf<ResolvedOrderLine>()
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
                ?: return DomainError.InvalidProductCode(rawCode, "no existe en el catálogo o está inactivo")
                    .toFailure().asLeft()
            val quantity = line.quantity
                ?: return failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "Falta la cantidad de la línea $position.",
                    mapOf("field" to "lines[$index].quantity"),
                )
            if (!quantity.isPositive) {
                return DomainError.InvalidQuantity("La cantidad de la línea $position debe ser mayor que cero")
                    .toFailure().asLeft()
            }
            resolved += ResolvedOrderLine(product.id, code, product.unitMeasureId, quantity)
        }
        return resolved.asRight()
    }
}

// ── Empaque ─────────────────────────────────────────────────────────────────

data class PackageInput(val quantity: Quantity?, val locationId: Long?)

/**
 * Empaca una cantidad de una línea: **descuenta stock y escribe el kardex**.
 *
 * La cantidad se valida contra lo pendiente de la línea (S1) y el saldo se lee con
 * bloqueo para que dos empaques simultáneos no dejen el estante en negativo.
 */
class PackageLine(
    private val catalog: OrderCatalog,
    private val orders: Orders,
    private val ledger: StockLedger,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(
        actor: Actor,
        orderId: Long,
        lineId: Long,
        input: PackageInput,
        origin: RequestOrigin,
    ): Outcome<OrderDetail> = access.authorizeOr(actor, "order.package", origin) { grant ->
        tx.inTransaction {
            val detail = orders.detail(orderId) ?: return@inTransaction orderNotFound(orderId)
            val view = detail.lines.firstOrNull { it.line.id == lineId }
                ?: return@inTransaction lineNotFound(lineId)
            val line = view.line

            if (!OrderStatus.valueOf(detail.header.status).allowsPackaging) {
                return@inTransaction failure(
                    FailureKind.CONFLICT, "INVALID_TRANSITION",
                    "Una orden ${detail.header.status.lowercase()} no admite empaque.",
                    mapOf("orderId" to orderId.toString(), "status" to detail.header.status),
                )
            }
            val amount = input.quantity
                ?: return@inTransaction failure(
                    FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                    "Falta la cantidad a empacar.",
                    mapOf("field" to "quantity"),
                )

            // El dominio decide si la operación es legal y cuánto queda pendiente.
            val packaged = when (val result = line.packageQuantity(amount)) {
                is Either.Left -> return@inTransaction result.error.toFailure().asLeft()
                is Either.Right -> result.value
            }
            val location = (input.locationId ?: line.locationId)?.let { catalog.location(it) }
                ?: return@inTransaction locationRequired(lineId)

            when (val moved = move(actor, packaged, location, amount, detail)) {
                is Either.Left -> return@inTransaction moved.error.asLeft()
                is Either.Right -> Unit
            }

            orders.replaceLine(packaged, location.id, clock.instant())
            val updated = detail.lines.map { if (it.line.id == lineId) packaged else it.line }
            val status = recalculatedStatusOf(detail, updated)
            if (status != detail.header.status) orders.setStatus(orderId, status, clock.instant())

            audit.record(
                auditEvents.byActor(
                    actor = actor,
                    origin = origin,
                    action = "ORDER_LINE_PACKAGED",
                    level = AuditLevel.CRITICAL,
                    entity = AuditEntity("order_line", lineId.toString(), detail.header.orderNumber),
                    grant = grant,
                    previousState = mapOf("suppliedQuantity" to line.suppliedQuantity.toPlain(), "location" to view.locationDisplayName),
                    newState = mapOf(
                        "suppliedQuantity" to packaged.suppliedQuantity.toPlain(),
                        "location" to location.displayName,
                    ),
                ),
            )
            orders.detail(orderId)!!.asRight()
        }
    }

    /** El movimiento de salida, con el saldo leído **con bloqueo** (Q9). */
    private fun move(
        actor: Actor,
        line: OrderLine,
        location: OrderLocation,
        amount: Quantity,
        detail: OrderDetail,
    ): Either<dev.axzy.stock.application.shared.Failure, Unit> {
        val available = ledger.balanceAt(line.productId, location.id, forUpdate = true)
        if (amount > available) {
            return DomainError.InsufficientStock(
                available = available.units,
                requested = amount.units,
                location = location.displayName,
            ).toFailure().copy(
                // El contrato pide la línea en el detalle: sin ella, con diez líneas
                // el cliente no sabe de cuál le están hablando.
                details = mapOf(
                    "available" to available.units.toString(),
                    "requested" to amount.units.toString(),
                    "lineId" to line.id.toString(),
                    "orderNumber" to detail.header.orderNumber,
                ),
            ).asLeft()
        }
        ledger.apply(
            MovementCommand(
                productId = line.productId,
                warehouseId = location.warehouseId,
                moveType = MovementType.FULFILLMENT_OUT,
                units = amount,
                fromLocationId = location.id,
                orderNumber = detail.header.orderNumber,
                userId = actor.id,
            ),
        )
        return Unit.asRight()
    }

    private fun locationRequired(lineId: Long) = failure(
        FailureKind.RULE_VIOLATION, "LOCATION_REQUIRED",
        "La línea $lineId no tiene ubicación de la que sacar la mercancía.",
        mapOf("lineId" to lineId.toString()),
    )
}

// ── Confirmación ────────────────────────────────────────────────────────────

/**
 * Confirma la orden: descuenta **lo que falta** de cada línea y dispara la
 * exportación.
 *
 * La confirmación se **reclama** con una sentencia condicional antes de mover nada:
 * dos confirmaciones simultáneas no pueden descontar dos veces (S3). Y si el ERP no
 * responde, la confirmación **no se revierte** —el stock ya salió—: el intento queda
 * registrado para el reintento manual.
 */
class ConfirmOrder(
    private val orders: Orders,
    private val ledger: StockLedger,
    private val catalog: OrderCatalog,
    private val erp: ErpClient,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, orderId: Long, origin: RequestOrigin): Outcome<OrderDetail> =
        access.authorizeOr(actor, "order.confirm", origin) { grant ->
            tx.inTransaction {
                val detail = orders.detail(orderId) ?: return@inTransaction orderNotFound(orderId)

                // El dominio comprueba que la orden admita la confirmación.
                val order = Order(
                    id = detail.header.id,
                    orderNumber = detail.header.orderNumber,
                    status = OrderStatus.valueOf(detail.header.status),
                    lines = detail.lines.map { it.line },
                    converted = detail.header.converted,
                )
                when (val confirmed = order.confirm()) {
                    is Either.Left -> return@inTransaction confirmed.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }

                // El reclamo atómico: solo una transacción se lleva la fila.
                if (orders.claimConfirmation(orderId, clock.instant()) == 0) {
                    return@inTransaction DomainError.OrderAlreadyConfirmed(detail.header.orderNumber)
                        .toFailure().asLeft()
                }

                detail.lines.forEach { view ->
                    val pending = view.line.pendingQuantity
                    if (pending <= Quantity.ZERO) return@forEach
                    val location = view.line.locationId?.let { catalog.location(it) }
                        ?: return@inTransaction locationRequiredFor(view.line.id)
                    val available = ledger.balanceAt(view.line.productId, location.id, forUpdate = true)
                    if (pending > available) {
                        // Un `Left` revierte la transacción entera: ninguna línea
                        // queda descontada a medias (S4), y la orden vuelve a su estado.
                        return@inTransaction DomainError.InsufficientStock(
                            available = available.units,
                            requested = pending.units,
                            location = location.displayName,
                        ).toFailure().copy(
                            details = mapOf(
                                "available" to available.units.toString(),
                                "requested" to pending.units.toString(),
                                "lineId" to view.line.id.toString(),
                                "orderNumber" to detail.header.orderNumber,
                            ),
                        ).asLeft()
                    }
                    ledger.apply(
                        MovementCommand(
                            productId = view.line.productId,
                            warehouseId = location.warehouseId,
                            moveType = MovementType.FULFILLMENT_OUT,
                            units = pending,
                            fromLocationId = location.id,
                            orderNumber = detail.header.orderNumber,
                            userId = actor.id,
                        ),
                    )
                    orders.replaceLine(
                        view.line.copy(
                            suppliedQuantity = view.line.quantity,
                            status = OrderLineStatus.SUPPLIED,
                        ),
                        location.id,
                        clock.instant(),
                    )
                }

                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "ORDER_CONFIRMED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("order", orderId.toString(), detail.header.orderNumber),
                        grant = grant,
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to PENDING_TO_OUT, "lines" to detail.lines.size),
                    ),
                )
                // La exportación va **después**: si falla, la confirmación ya está hecha.
                export(actor, orderId, origin, grant)
                orders.detail(orderId)!!.asRight()
            }
        }

    private fun locationRequiredFor(lineId: Long) = failure(
        FailureKind.RULE_VIOLATION, "LOCATION_REQUIRED",
        "La línea $lineId no tiene ubicación de la que sacar la mercancía.",
        mapOf("lineId" to lineId.toString()),
    )

    /** Un intento de envío al ERP, con su contador. Nunca revierte la confirmación. */
    private fun export(
        actor: Actor,
        orderId: Long,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ) {
        val current = orders.detail(orderId) ?: return
        val result = if (current.header.converted) {
            // Ya estaba en el ERP: no se manda una segunda salida, solo se cuenta.
            ErpResult.Sent
        } else {
            erp.send(current)
        }
        val success = result is ErpResult.Sent
        val error = (result as? ErpResult.Failed)?.reason
        orders.recordExport(orderId, success, if (success) SENT else FAILED, error, clock.instant())
        audit.record(
            auditEvents.byActor(
                actor = actor,
                origin = origin,
                action = "ORDER_EXPORTED",
                level = AuditLevel.CRITICAL,
                entity = AuditEntity("order", orderId.toString(), current.header.orderNumber),
                grant = grant,
                newState = mapOf(
                    "status" to if (success) SENT else FAILED,
                    "attempts" to current.header.exportAttempts + 1,
                ),
                errorMessage = error,
            ),
        )
    }
}

// ── Exportación manual ──────────────────────────────────────────────────────

data class ExportAttempt(val attempts: Int, val status: String, val lastError: String?)

/**
 * El reintento manual de la exportación.
 *
 * Es **idempotente por el `orderNumber` convertido**: si la orden ya se exportó, no se
 * crea una segunda salida y solo se cuenta el intento.
 */
class ExportOrder(
    private val orders: Orders,
    private val erp: ErpClient,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, orderId: Long, origin: RequestOrigin): Outcome<ExportAttempt> =
        access.authorizeOr(actor, "order.convert", origin) { grant ->
            tx.inTransaction {
                val detail = orders.detail(orderId) ?: return@inTransaction orderNotFound(orderId)
                val order = Order(
                    id = detail.header.id,
                    orderNumber = detail.header.orderNumber,
                    status = OrderStatus.valueOf(detail.header.status),
                    lines = detail.lines.map { it.line },
                    converted = detail.header.converted,
                )
                // Exportar algo que no se ha surtido mandaría al ERP una salida que no existe.
                when (val recorded = order.recordExportAttempt(success = false, error = null)) {
                    is Either.Left -> return@inTransaction recorded.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }

                val result = if (detail.header.converted) ErpResult.Sent else erp.send(detail)
                val success = result is ErpResult.Sent
                val error = (result as? ErpResult.Failed)?.reason
                orders.recordExport(orderId, success, if (success) SENT else FAILED, error, clock.instant())

                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "ORDER_EXPORT_RETRIED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("order", orderId.toString(), detail.header.orderNumber),
                        grant = grant,
                        newState = mapOf("attempts" to detail.header.exportAttempts + 1, "status" to if (success) SENT else FAILED),
                        errorMessage = error,
                    ),
                )
                ExportAttempt(
                    attempts = detail.header.exportAttempts + 1,
                    status = if (success) SENT else FAILED,
                    lastError = error,
                ).asRight()
            }
        }
}

// ── Faltante ────────────────────────────────────────────────────────────────

data class ShortageInput(
    val lineId: Long?,
    val quantity: Quantity?,
    val reason: AdjustmentReason?,
    val note: String?,
)

/**
 * Registra un faltante durante el surtido.
 *
 * **Delega en la regla única de ajuste** con origen `FULFILLMENT_SHORTAGE`: el surtido
 * no reimplementa cómo se mueve el inventario, porque con el tiempo una regla exigiría
 * aprobación y la otra no. El permiso que vale es el del ajuste
 * (`inventory.adjust_shortage`), que es el que decide el caso de uso delegado.
 */
class RegisterShortage(
    private val orders: Orders,
    private val adjustStock: AdjustStock,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    fun execute(
        actor: Actor,
        orderId: Long,
        input: ShortageInput,
        idempotencyKey: java.util.UUID?,
        origin: RequestOrigin,
    ): Outcome<OrderDetail> = access.authorizeOr(actor, "order.package", origin) { grant ->
        val detail = orders.detail(orderId) ?: return@authorizeOr orderNotFound(orderId)
        val lineId = input.lineId ?: return@authorizeOr failure(
            FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
            "Falta la línea del faltante.",
            mapOf("field" to "lineId"),
        )
        val view = detail.lines.firstOrNull { it.line.id == lineId }
            ?: return@authorizeOr lineNotFound(lineId)
        val location = view.line.locationId
            ?: return@authorizeOr failure(
                FailureKind.RULE_VIOLATION, "LOCATION_REQUIRED",
                "La línea $lineId no tiene ubicación de la que descontar el faltante.",
                mapOf("lineId" to lineId.toString()),
            )
        val locationView = view.locationDisplayName
            ?: return@authorizeOr failure(
                FailureKind.RULE_VIOLATION, "LOCATION_REQUIRED",
                "La línea $lineId no tiene ubicación legible.",
                mapOf("lineId" to lineId.toString()),
            )

        // El ajuste delegado hace sus propias comprobaciones —cantidad, motivo y
        // permiso— y escribe el movimiento y su bitácora.
        val adjustment = adjustStock.execute(
            actor = actor,
            input = AdjustmentInput(
                productCode = view.line.productCode,
                locationCode = locationView,
                quantity = input.quantity,
                direction = AdjustmentDirection.DECREASE.name,
                // Sin defecto: un faltante es un ajuste y el motivo es obligatorio (S8).
                reason = input.reason?.name,
                origin = AdjustmentOrigin.FULFILLMENT_SHORTAGE.name,
                note = input.note,
                approverId = null,
            ),
            idempotencyKey = idempotencyKey,
            origin = origin,
        )
        when (adjustment) {
            is Either.Left -> adjustment.error.asLeft()
            is Either.Right -> {
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "ORDER_SHORTAGE_REGISTERED",
                        level = AuditLevel.RELEVANT,
                        entity = AuditEntity("order_line", lineId.toString(), detail.header.orderNumber),
                        grant = grant,
                        newState = mapOf(
                            "quantity" to input.quantity?.value?.toString(),
                            "location" to locationView,
                            "locationId" to location.toString(),
                        ),
                    ),
                )
                orders.detail(orderId)?.asRight() ?: orderNotFound(orderId)
            }
        }
    }
}

// ── Común ───────────────────────────────────────────────────────────────────

/**
 * El estado que le corresponde a la cabecera según sus líneas.
 *
 * El criterio es **uno** ([Order.recalculatedStatus]) y no una lista por ruta: un
 * estado que cada endpoint asigna a mano acaba diciendo cosas distintas según por
 * dónde se entró, que es exactamente el defecto del legacy.
 */
internal fun recalculatedStatusOf(detail: OrderDetail, lines: List<OrderLine>): String =
    Order(
        id = detail.header.id,
        orderNumber = detail.header.orderNumber,
        status = OrderStatus.valueOf(detail.header.status),
        lines = lines,
        converted = detail.header.converted,
    ).recalculatedStatus().status.name

internal const val SENT = "SENT"
internal const val FAILED = "FAILED"
internal const val PENDING_TO_OUT = "PENDING_TO_OUT"

internal fun Quantity.toPlain(): String = java.math.BigDecimal.valueOf(value, Quantity.SCALE).toPlainString()

internal fun orderNotFound(orderId: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "ORDER_NOT_FOUND",
    "No existe la orden $orderId.",
    mapOf("orderId" to orderId.toString()),
)

internal fun lineNotFound(lineId: Long): Outcome<Nothing> = failure(
    FailureKind.NOT_FOUND, "ORDER_LINE_NOT_FOUND",
    "No existe la línea $lineId en esta orden.",
    mapOf("lineId" to lineId.toString()),
)

internal fun duplicateNumber(number: String): Outcome<Nothing> = failure(
    FailureKind.CONFLICT, "DUPLICATE_ORDER_NUMBER",
    "Ya existe una orden con el número '$number'.",
    mapOf("orderNumber" to number),
)
