package dev.axzy.stock.application.inventory

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.application.shared.toOutcome
import dev.axzy.stock.domain.access.ResourceContext
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.Product
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentPolicy
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.AdjustmentRequest
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.util.UUID

/** El resultado de un movimiento, tal como lo devuelve el contrato. */
enum class MovementStatus { APPLIED, PENDING_APPROVAL }

data class MovementResult(
    val kardexIds: List<Long>,
    val status: MovementStatus,
    /** Total del producto tras el movimiento. `null` cuando el total no cambia (ubicar, mover). */
    val total: Quantity?,
    val message: String? = null,
)

/** Un ajuste, ya traducido de la petición HTTP. */
data class AdjustmentInput(
    val productCode: String?,
    val locationCode: String?,
    val quantity: Quantity?,
    val direction: String?,
    val reason: String?,
    val origin: String?,
    val note: String?,
    val approverId: Long?,
)

/**
 * El permiso de un ajuste **depende de la dirección y del motivo**.
 *
 * No existe un permiso genérico de «ajustar», y es deliberado: aumentar no es lo
 * mismo que mermar, y un robo no es un error de conteo. Un permiso único daría a
 * cualquiera la capacidad de tapar un robo con un ajuste que él mismo firma
 * (docs/api/inventario.md).
 */
fun adjustmentPermission(direction: AdjustmentDirection, reason: AdjustmentReason): String = when {
    direction == AdjustmentDirection.INCREASE -> "inventory.adjust_increase"
    reason == AdjustmentReason.THEFT -> "inventory.adjust_theft"
    reason == AdjustmentReason.DAMAGE -> "inventory.adjust_damage"
    reason == AdjustmentReason.SHORTAGE -> "inventory.adjust_shortage"
    else -> "inventory.adjust_count_error"
}

/**
 * Ajusta existencias: la regla única que unifica los cuatro orígenes del legacy
 * (docs/flujos/existencias-y-ajustes.md §4).
 *
 * El orden no es casual:
 *
 *  1. **Validar y autorizar antes de tocar la base.** Un ajuste que no se puede
 *     hacer no debe abrir conexión.
 *  2. **Bloquear el saldo dentro de la transacción** y decidir con ese número. El
 *     `CHECK` de la tabla también impide el saldo negativo, pero su mensaje dice
 *     «stock_non_negative»; el caso de uso puede decir **cuánto hay** (Q9).
 *  3. **Escribir solo si la decisión es aplicar.** Un ajuste pendiente de
 *     aprobación **no toca el inventario**: un cambio de stock sin autorizar es
 *     justo lo que la aprobación evita.
 */
class AdjustStock(
    private val catalog: InventoryCatalog,
    private val ledger: StockLedger,
    private val accounts: UserAccounts,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
) {

    /** Lo que hace falta para aplicar un ajuste, ya validado. */
    private data class Parsed(
        val code: ProductCode,
        val location: LocationRef,
        val quantity: Quantity,
        val direction: AdjustmentDirection,
        val reason: AdjustmentReason,
        val origin: AdjustmentOrigin,
        val note: String?,
        val approverId: Long?,
    )

    fun execute(
        actor: Actor,
        input: AdjustmentInput,
        idempotencyKey: UUID?,
        origin: RequestOrigin,
    ): Outcome<MovementResult> =
        parse(input).flatMap { parsed ->
            val permission = adjustmentPermission(parsed.direction, parsed.reason)
            access.authorizeOr(actor, permission, origin) { grant ->
                // La bodega de la petición: la del usuario si tiene una sola, y si no
                // la predeterminada. El contrato de movimientos no lleva `warehouseId`
                // porque mover entre bodegas es un traslado (M05), no un movimiento.
                val warehouseId = actor.user.warehouseIds.singleOrNull() ?: catalog.defaultWarehouseId()
                val location = catalog.activeLocation(warehouseId, parsed.location)
                    ?: return@authorizeOr DomainError.LocationNotFound(parsed.location.displayName).toFailure().asLeft()

                // Segundo control, ya con la bodega REAL de la ubicación: el alcance
                // `AREA`/`OWN` se comprueba sobre el recurso, no sobre una suposición.
                access.authorizeOr(
                    actor, permission, origin,
                    resource = ResourceContext(warehouseId = location.warehouseId, locationKind = location.kind.name),
                    entityType = "location",
                    entityId = location.id.toString(),
                ) { scopedGrant ->
                    val product = catalog.activeProduct(parsed.code)
                        ?: return@authorizeOr DomainError.ProductNotFound(parsed.code.value).toFailure().asLeft()
                    apply(actor, product, location, parsed, idempotencyKey, origin, scopedGrant)
                }
            }
        }

    private fun apply(
        actor: Actor,
        product: Product,
        location: Location,
        parsed: Parsed,
        idempotencyKey: UUID?,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ): Outcome<MovementResult> = tx.inTransaction {
        // ── Idempotencia: repetir la clave devuelve el MISMO movimiento (A8) ──
        val alreadyApplied = idempotencyKey?.let { ledger.movementIdOf(it) }
        if (alreadyApplied != null) {
            return@inTransaction MovementResult(
                kardexIds = listOf(alreadyApplied),
                status = MovementStatus.APPLIED,
                total = ledger.productTotal(product.id),
                message = "This movement was already applied: the idempotency key was repeated.",
            ).asRight()
        }

        // El saldo se lee CON BLOQUEO cuando el ajuste resta: así el número que se
        // comprueba sigue siendo verdad al escribir (Q9).
        val available = if (parsed.direction == AdjustmentDirection.DECREASE) {
            ledger.balanceAt(product.id, location.id, forUpdate = true)
        } else {
            null
        }

        AdjustmentRequest.create(
            productCode = parsed.code,
            locationLabel = location.displayName,
            quantity = parsed.quantity,
            direction = parsed.direction,
            reason = parsed.reason,
            origin = parsed.origin,
            note = parsed.note,
            requestedBy = actor.id,
            idempotencyKey = idempotencyKey?.toString(),
        ).toOutcome().flatMap { request ->
            // **Quien aprueba tiene que poder aprobar.** Hasta ahora bastaba con mandar el
            // `approverId` de otro: cualquiera con el permiso del motivo podía firmar su propio
            // ajuste con el identificador de un supervisor, y el permiso
            // `inventory.approve_adjustment` —que el catálogo concede, documenta y audita— no lo
            // comprobaba nadie. Un permiso que no se comprueba es un permiso que no existe.
            val sinPermisoDeAprobar = approverDenial(parsed.approverId, location.warehouseId, origin)
            if (sinPermisoDeAprobar != null) {
                sinPermisoDeAprobar.asLeft()
            } else when (val decision = AdjustmentPolicy.decide(request, parsed.approverId, available)) {
                is AdjustmentPolicy.Decision.Reject -> decision.error.toFailure().asLeft()

                // Pendiente de aprobación: NO se escribe nada, ni kardex ni bitácora.
                is AdjustmentPolicy.Decision.PendingApproval -> MovementResult(
                    kardexIds = emptyList(),
                    status = MovementStatus.PENDING_APPROVAL,
                    total = null,
                    message = decision.reason,
                ).asRight()

                AdjustmentPolicy.Decision.Apply -> {
                    val isIncrease = parsed.direction == AdjustmentDirection.INCREASE
                    val kardexId = ledger.apply(
                        MovementCommand(
                            productId = product.id,
                            warehouseId = location.warehouseId,
                            moveType = parsed.direction.movementType,
                            units = parsed.quantity,
                            fromLocationId = if (isIncrease) null else location.id,
                            toLocationId = if (isIncrease) location.id else null,
                            adjustmentReason = parsed.reason,
                            adjustmentOrigin = parsed.origin,
                            userId = actor.id,
                            clientUuid = idempotencyKey,
                            note = parsed.note?.trim()?.ifBlank { null },
                        ),
                    )
                    val total = ledger.productTotal(product.id)
                    audit.record(
                        auditEvents.byActor(
                            actor = actor,
                            origin = origin,
                            action = "INVENTORY_ADJUSTED",
                            // Mueve inventario: nivel crítico y en la MISMA transacción (U1).
                            level = AuditLevel.CRITICAL,
                            entity = AuditEntity("product", product.id.toString(), parsed.code.value),
                            grant = grant,
                            previousState = mapOf(
                                "location" to location.displayName,
                                "available" to available?.toString(),
                            ),
                            newState = mapOf(
                                "kardexId" to kardexId,
                                "total" to total.toString(),
                                "direction" to parsed.direction.name,
                                "reason" to parsed.reason.name,
                            ),
                            reason = parsed.note?.trim()?.ifBlank { null },
                            idempotencyKey = idempotencyKey?.toString(),
                        ),
                    )
                    MovementResult(listOf(kardexId), MovementStatus.APPLIED, total).asRight()
                }
            }
        }
    }

    // ── Validación ──────────────────────────────────────────────────────────

    private fun parse(input: AdjustmentInput): Outcome<Parsed> =
        parseEnum(input.direction, AdjustmentDirection.entries, "direction").flatMap { direction: AdjustmentDirection ->
            parseReason(input.reason).flatMap { reason: AdjustmentReason ->
                parseEnum(input.origin, AdjustmentOrigin.entries, "origin").flatMap { origin: AdjustmentOrigin ->
                    InventoryFilters.productCode(input.productCode).flatMap { code: ProductCode? ->
                        if (code == null) {
                            failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "The product code is required.")
                        } else {
                            InventoryFilters.locationRef(input.locationCode).flatMap { ref: LocationRef? ->
                                when {
                                    ref == null -> failure(
                                        FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                                        "The location code is required.",
                                    )
                                    input.quantity == null -> failure(
                                        FailureKind.RULE_VIOLATION, "INVALID_QUANTITY",
                                        "The quantity is required.",
                                    )
                                    else -> Parsed(
                                        code, ref, input.quantity, direction, reason, origin,
                                        input.note, input.approverId,
                                    ).asRight()
                                }
                            }
                        }
                    }
                }
            }
        }

    /**
     * El motivo **no es un parámetro cualquiera**: sin él, un ajuste no se puede
     * auditar dentro de un año, y por eso su ausencia es `REASON_REQUIRED` (A5) y no
     * un `INVALID_PARAMETER`. Un motivo que no está en la lista sí es un parámetro
     * inválido: el cliente mandó algo, y no es lo que se admite.
     */
    private fun parseReason(raw: String?): Outcome<AdjustmentReason> {
        if (raw.isNullOrBlank()) {
            return failure(
                FailureKind.RULE_VIOLATION, "REASON_REQUIRED",
                "Every stock adjustment requires a reason.",
            )
        }
        val allowed = AdjustmentReason.entries.joinToString(",") { it.name }
        val value = AdjustmentReason.entries.firstOrNull { it.name == raw.trim().uppercase() }
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The value of 'reason' is not in the list of allowed values.",
                mapOf("reason" to raw, "allowed" to allowed),
            )
        return value.asRight()
    }

    private fun <E : Enum<E>> parseEnum(raw: String?, values: List<E>, field: String): Outcome<E> {
        val allowed = values.joinToString(",") { it.name }
        if (raw == null) {
            return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "Falta '$field'. Se admiten: $allowed.",
                mapOf(field to "", "allowed" to allowed),
            )
        }
        val value = values.firstOrNull { it.name == raw.trim().uppercase() }
            ?: return failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "The value of '$field' is not in the list of allowed values.",
                mapOf(field to raw, "allowed" to allowed),
            )
        return value.asRight()
    }

    /**
     * El fallo del aprobador, si lo hay.
     *
     * Devuelve `null` cuando no hace falta aprobador o cuando el que viene puede aprobar. El
     * alcance se comprueba **en la bodega de la ubicación del ajuste**: un `AREA` solo aprueba
     * en la suya.
     */
    private fun approverDenial(approverId: Long?, warehouseId: Long, origin: RequestOrigin): Failure? {
        if (approverId == null) return null
        val approver = accounts.loadActor(approverId)?.actor
            ?: return Failure(
                FailureKind.RULE_VIOLATION, "APPROVER_NOT_FOUND",
                "Approver $approverId does not exist.",
                mapOf("approverId" to approverId.toString()),
            )
        val decision = access.authorize(
            actor = approver,
            permission = "inventory.approve_adjustment",
            origin = origin,
            resource = ResourceContext(warehouseId = warehouseId),
            entity = AuditEntity("user", approverId.toString(), approver.username),
        )
        return if (decision is Either.Left) {
            Failure(
                FailureKind.FORBIDDEN, "APPROVER_NOT_AUTHORIZED",
                "Approver ${approver.username} does not have permission to approve adjustments in this warehouse.",
                mapOf(
                    "approverId" to approverId.toString(),
                    "approver" to approver.username,
                    "permission" to "inventory.approve_adjustment",
                ),
            )
        } else {
            null
        }
    }
}
