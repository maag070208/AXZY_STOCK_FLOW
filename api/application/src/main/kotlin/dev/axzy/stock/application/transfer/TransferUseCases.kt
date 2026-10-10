package dev.axzy.stock.application.transfer

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.inventory.InventoryCatalog
import dev.axzy.stock.application.inventory.LocationRef
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
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.transfer.Transfer
import dev.axzy.stock.domain.transfer.TransferLine
import dev.axzy.stock.domain.transfer.TransferStatus
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.Clock
import java.time.Instant
import java.time.Year
import java.time.temporal.ChronoUnit

/**
 * Los casos de uso de traslados (M05, docs/flujos/traslados.md).
 *
 * **Ninguno mueve saldo por su cuenta**: despachar, recibir y cancelar llaman a las
 * funciones del motor (V13), que son las que aplican el movimiento, abren o cierran el
 * tránsito y recalculan el total contando lo que va por el camino.
 *
 * Lo que sí hace la capa de aplicación es **comprobar antes de llamar**: que la
 * transición sea legal (el dominio), que haya saldo en el origen (con el saldo leído
 * **con bloqueo**) y que la bodega de destino tenga dónde recibir. Sin esas
 * comprobaciones, el motor fallaría con un error de restricción que no dice qué pasó.
 */

// ── Consultas ───────────────────────────────────────────────────────────────

class ListTransfers(private val transfers: Transfers, private val access: AccessDecider) {

    fun execute(
        actor: Actor,
        filter: TransferFilter,
        request: PageRequest,
        origin: RequestOrigin,
    ): Outcome<Page<TransferHeader>> = access.authorizeOr(actor, "transfer.view", origin) {
        transfers.page(filter, request).asRight()
    }
}

class GetTransfer(private val transfers: Transfers, private val access: AccessDecider) {

    fun execute(actor: Actor, transferId: Long, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.view", origin) {
            transfers.detail(transferId)?.asRight() ?: transferNotFound(transferId)
        }
}

/**
 * El informe de mercancía en tránsito.
 *
 * Su permiso es **`transit.view` y no `transfer.view`**: quien vigila la deuda de los
 * traslados no tiene por qué poder ver ni crear traslados. Y los días los cuenta el
 * servidor: con el reloj del equipo, el mismo dato daría un número distinto en cada
 * máquina, y un dato de gestión que cambia según quién mire no sirve para decidir.
 */
class InTransitReport(
    private val transfers: Transfers,
    private val access: AccessDecider,
    private val clock: Clock,
) {

    fun execute(actor: Actor, origin: RequestOrigin): Outcome<List<InTransitRow>> =
        access.authorizeOr(actor, "transit.view", origin) {
            transfers.inTransit().map { row ->
                row.copy(daysInTransit = daysSince(row.dispatchedAt))
            }.asRight()
        }

    private fun daysSince(dispatchedAt: Instant?): Long =
        dispatchedAt?.let { ChronoUnit.DAYS.between(it, clock.instant()) } ?: 0
}

// ── Alta ────────────────────────────────────────────────────────────────────

class CreateTransfer(
    private val catalog: TransferCatalog,
    private val transfers: Transfers,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, input: NewTransfer, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.create", origin) { grant ->
            when (val lines = resolveLines(input.lines)) {
                is Either.Left -> lines.error.asLeft()
                is Either.Right -> {
                    val from = input.fromWarehouseId
                    val to = input.toWarehouseId
                    if (from == null || to == null) {
                        return@authorizeOr failure(
                            FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                            "El traslado necesita bodega de origen y de destino.",
                            mapOf("field" to if (from == null) "fromWarehouseId" else "toWarehouseId"),
                        )
                    }
                    // Las dos reglas que no dependen del estado las valida el dominio.
                    when (
                        val valid = Transfer.validate(
                            fromWarehouseId = from,
                            toWarehouseId = to,
                            lines = lines.value.map { line ->
                                TransferLine(
                                    id = 0,
                                    productId = line.productId,
                                    productCode = line.productCode.value,
                                    productName = "",
                                    quantity = line.quantity,
                                )
                            },
                        )
                    ) {
                        is Either.Left -> valid.error.toFailure().asLeft()
                        is Either.Right -> insert(actor, input, from, to, lines.value, origin, grant)
                    }
                }
            }
        }

    private fun insert(
        actor: Actor,
        input: NewTransfer,
        from: Long,
        to: Long,
        lines: List<ResolvedTransferLine>,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ): Outcome<TransferDetail> {
        val draft = TransferDraft(
            code = transfers.nextCode(Year.now(clock).value),
            fromWarehouseId = from,
            toWarehouseId = to,
            note = input.note?.trim()?.takeUnless { it.isBlank() },
        )
        return tx.inTransaction {
            when (val inserted = transfers.insert(draft, lines)) {
                InsertTransferResult.DuplicateCode -> failure(
                    FailureKind.CONFLICT, "DUPLICATE_TRANSFER_CODE",
                    "Ya existe un traslado con el código '${draft.code}'.",
                    mapOf("code" to draft.code),
                )
                is InsertTransferResult.Inserted -> {
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "TRANSFER_CREATED",
                            level = AuditLevel.RELEVANT,
                            entity = AuditEntity("transfer", inserted.id.toString(), draft.code),
                            grant = grant,
                            newState = mapOf("code" to draft.code, "lines" to lines.size, "status" to "DRAFT"),
                        ),
                    )
                    transfers.detail(inserted.id)?.asRight() ?: transferNotFound(inserted.id)
                }
            }
        }
    }

    private fun resolveLines(lines: List<NewTransferLine>): Outcome<List<ResolvedTransferLine>> {
        if (lines.isEmpty()) return DomainError.EmptyTransfer().toFailure().asLeft()
        val resolved = mutableListOf<ResolvedTransferLine>()
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
            resolved += ResolvedTransferLine(
                productId = product.id,
                productCode = code,
                quantity = quantity,
                fromLocationId = line.fromLocationId,
                note = line.note?.trim()?.takeUnless { it.isBlank() },
            )
        }
        return resolved.asRight()
    }
}

// ── Despacho, recepción y cancelación ───────────────────────────────────────

/**
 * Despacha el traslado.
 *
 * Antes de llamar al motor se comprueba, **con el saldo leído con bloqueo** (Q9), que
 * cada línea tenga de dónde salir: sin esa comprobación el motor fallaría con la
 * restricción de saldo no negativo, que no dice cuál línea ni cuánto falta. La
 * comprobación no sustituye al motor: si dos despachos simultáneos pasaran los dos
 * controles, la restricción de la base seguiría impidiendo el saldo negativo.
 */
class DispatchTransfer(
    private val transfers: Transfers,
    private val catalog: TransferCatalog,
    private val ledger: StockLedger,
    private val inventoryCatalog: InventoryCatalog,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, transferId: Long, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.dispatch", origin) { grant ->
            tx.inTransaction {
                val detail = transfers.detail(transferId) ?: return@inTransaction transferNotFound(transferId)
                val transfer = toDomain(detail)
                when (val moved = transfer.dispatch(actor.id)) {
                    is Either.Left -> return@inTransaction moved.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }

                // Saldo suficiente en cada origen, con bloqueo.
                detail.lines.forEach { view ->
                    val originLocation = view.line.fromLocationId
                        ?: catalog.receivingLocationId(detail.header.fromWarehouseId)
                        ?: return@inTransaction failure(
                            FailureKind.RULE_VIOLATION, "NO_RECEIVING_LOCATION",
                            "La bodega de origen no tiene ubicación de recepción.",
                            mapOf("warehouseId" to detail.header.fromWarehouseId.toString()),
                        )
                    val available = ledger.balanceAt(view.line.productId, originLocation, forUpdate = true)
                    if (view.line.quantity > available) {
                        return@inTransaction DomainError.InsufficientStock(
                            available = available.units,
                            requested = view.line.quantity.units,
                            // El nombre visible lo compone el dominio a partir de la
                            // ubicación; si es un estante concreto, se resuelve ahí.
                            location = inventoryCatalog.activeLocation(
                                detail.header.fromWarehouseId,
                                LocationRef.Receiving,
                            )?.displayName ?: originLocation.toString(),
                        ).toFailure().asLeft()
                    }
                }

                transfers.dispatch(transferId, actor.id)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "TRANSFER_DISPATCHED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("transfer", transferId.toString(), detail.header.code),
                        grant = grant,
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to "DISPATCHED", "lines" to detail.lines.size),
                    ),
                )
                transfers.detail(transferId)?.asRight() ?: transferNotFound(transferId)
            }
        }
}

class ReceiveTransfer(
    private val transfers: Transfers,
    private val catalog: TransferCatalog,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, transferId: Long, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.receive", origin) { grant ->
            tx.inTransaction {
                val detail = transfers.detail(transferId) ?: return@inTransaction transferNotFound(transferId)
                val transfer = toDomain(detail)
                when (val moved = transfer.receive(actor.id)) {
                    is Either.Left -> return@inTransaction moved.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }
                // La bodega de destino tiene que tener dónde recibir: si no, la
                // mercancía llegaría a un sitio que no existe.
                catalog.receivingLocationId(detail.header.toWarehouseId)
                    ?: return@inTransaction failure(
                        FailureKind.RULE_VIOLATION, "NO_RECEIVING_LOCATION",
                        "La bodega de destino no tiene ubicación de recepción.",
                        mapOf("warehouseId" to detail.header.toWarehouseId.toString()),
                    )
                if (transfers.openTransitLines(transferId) == 0) {
                    return@inTransaction failure(
                        FailureKind.CONFLICT, "NOTHING_IN_TRANSIT",
                        "No hay mercancía en tránsito que recibir en este traslado.",
                        mapOf("transferId" to transferId.toString()),
                    )
                }

                transfers.receive(transferId, actor.id)
                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "TRANSFER_RECEIVED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("transfer", transferId.toString(), detail.header.code),
                        grant = grant,
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to "RECEIVED"),
                    ),
                )
                transfers.detail(transferId)?.asRight() ?: transferNotFound(transferId)
            }
        }
}

class CancelTransfer(
    private val transfers: Transfers,
    private val catalog: TransferCatalog,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: Clock,
) {

    fun execute(actor: Actor, transferId: Long, origin: RequestOrigin): Outcome<TransferDetail> =
        access.authorizeOr(actor, "transfer.cancel", origin) { grant ->
            tx.inTransaction {
                val detail = transfers.detail(transferId) ?: return@inTransaction transferNotFound(transferId)
                val transfer = toDomain(detail)
                // El dominio decide: desde `RECEIVED` no se cancela, y lo recibido se
                // corrige con un traslado de vuelta, que deja rastro.
                when (val cancelled = transfer.cancel()) {
                    is Either.Left -> return@inTransaction cancelled.error.toFailure().asLeft()
                    is Either.Right -> Unit
                }
                // Si estaba despachado, la vuelta al origen la hace el motor.
                transfers.cancel(transferId, actor.id)

                audit.record(
                    auditEvents.byActor(
                        actor = actor,
                        origin = origin,
                        action = "TRANSFER_CANCELLED",
                        level = AuditLevel.CRITICAL,
                        entity = AuditEntity("transfer", transferId.toString(), detail.header.code),
                        grant = grant,
                        previousState = mapOf("status" to detail.header.status),
                        newState = mapOf("status" to "CANCELLED"),
                    ),
                )
                transfers.detail(transferId)?.asRight() ?: transferNotFound(transferId)
            }
        }
}

/**
 * El traslado del dominio a partir de lo que devolvió la consulta.
 *
 * Existe para que las transiciones se decidan con las reglas del dominio y no con un
 * `if` sobre el estado escrito en la ruta.
 */
internal fun toDomain(detail: TransferDetail): Transfer = Transfer(
    id = detail.header.id,
    code = detail.header.code,
    fromWarehouseId = detail.header.fromWarehouseId,
    toWarehouseId = detail.header.toWarehouseId,
    status = TransferStatus.valueOf(detail.header.status),
    lines = detail.lines.map { it.line },
    note = detail.header.note,
    dispatchedBy = detail.header.dispatchedBy,
    receivedBy = detail.header.receivedBy,
)
