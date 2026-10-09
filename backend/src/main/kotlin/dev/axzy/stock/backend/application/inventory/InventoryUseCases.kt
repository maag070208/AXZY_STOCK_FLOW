package dev.axzy.stock.backend.application.inventory

import dev.axzy.stock.backend.application.audit.AuditChannel
import dev.axzy.stock.backend.application.audit.AuditEntry
import dev.axzy.stock.backend.application.shared.unitsText
import dev.axzy.stock.backend.error.NotFoundException
import dev.axzy.stock.backend.error.UnprocessableException
import dev.axzy.stock.domain.inventory.AdjustmentDirection
import dev.axzy.stock.domain.inventory.AdjustmentPolicy
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.AdjustmentRequest
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Casos de uso de inventario (contrato §4 y §9). **Es la única puerta por la que se ajusta o se
 * mueve inventario**: recepción y surtido delegan aquí en lugar de repetir la regla.
 *
 * Cada caso de uso hace todo su trabajo en una transacción ([InventoryRepository.inTransaction]):
 * el movimiento y su registro de bitácora se confirman juntos o no se confirma ninguno. Si el
 * movimiento se aplicara y su registro no, el inventario habría cambiado sin dejar rastro de
 * quién lo cambió, y eso se descubre un año después.
 */
class InventoryUseCases(private val repository: InventoryRepository) {

    /**
     * Aplica un ajuste de inventario. El orden es el que impone la regla del dominio:
     *  1. Se resuelven producto y ubicación. Sin ellos no hay nada que ajustar.
     *  2. Se lee el **saldo disponible**, para que el rechazo diga cuánto hay.
     *  3. Se pide la **decisión** a [AdjustmentPolicy].
     *  4. Solo si la decisión es aplicar, se escribe el movimiento.
     *
     * @param approverId quién aprueba, si el ajuste lo requiere.
     */
    fun applyAdjustment(request: AdjustmentRequest, approverId: Long? = null, ip: String? = null): AdjustmentResult =
        repository.inTransaction {
            val productId = activeProductId(request.productCode)
                ?: throw productNotFound(request.productCode)
            val location = activeLocation(request.locationCode)
                ?: throw locationNotFound(request.locationCode)

            val available = balance(productId, location.id)

            when (val decision = AdjustmentPolicy.decide(request = request, approverId = approverId, available = available)) {
                is AdjustmentPolicy.Decision.Reject ->
                    throw UnprocessableException(decision.error.code, decision.error.message)

                // **No se escribe nada.** Un movimiento que espera aprobación y ya movió el
                // inventario sería un cambio de stock sin autorizar, que es justo lo que la
                // aprobación pretende evitar.
                is AdjustmentPolicy.Decision.PendingApproval -> AdjustmentResult(
                    movements = emptyList(),
                    total = available,
                    status = AdjustmentStatus.PENDING_APPROVAL,
                    reason = decision.reason,
                )

                AdjustmentPolicy.Decision.Apply -> {
                    val increases = request.direction == AdjustmentDirection.INCREASE

                    val kardexId = move(
                        StockMovement(
                            productId = productId,
                            warehouseId = location.warehouseId,
                            type = request.movementType,
                            units = request.quantity,
                            // Un aumento ENTRA a la ubicación; una merma SALE de ella.
                            fromLocationId = location.id.takeUnless { increases },
                            toLocationId = location.id.takeIf { increases },
                            reason = request.reason,
                            origin = request.origin,
                            userId = request.requestedBy,
                            approverId = approverId,
                            idempotencyKey = request.idempotencyKey,
                            note = request.note,
                        ),
                    )

                    // El total anterior se deriva del delta en lugar de leerlo antes del
                    // movimiento: es aritmética exacta, y ahorra una consulta por ajuste.
                    val newTotal = total(productId)
                    val previousTotal = Quantity.ofScaled(
                        if (increases) newTotal.value - request.quantity.value else newTotal.value + request.quantity.value,
                    )

                    audit(adjustmentEntry(request, productId, previousTotal, newTotal, ip))

                    AdjustmentResult(movements = listOf(kardexId), total = newTotal, status = AdjustmentStatus.APPLIED)
                }
            }
        }

    /**
     * Mueve mercancía de una ubicación a otra: ubicar lo recibido o reorganizar estantes.
     *
     * **El total NO cambia.** Es un traslado, no una salida. En el legacy el total bajaba al
     * ubicar porque estaba anclado a un movimiento histórico mutado; aquí es una suma derivada
     * de las ubicaciones, así que mover no puede alterarlo.
     *
     * @return el id de la fila del kardex.
     */
    fun locate(
        productCode: ProductCode,
        from: LocationCode,
        until: LocationCode,
        quantity: Quantity,
        userId: Long,
        key: String? = null,
    ): Long = repository.inTransaction {
        val productId = activeProductId(productCode) ?: throw productNotFound(productCode)
        val source = activeLocation(from) ?: throw locationNotFound(from)
        val destination = activeLocation(until) ?: throw locationNotFound(until)

        if (source.id == destination.id) {
            throw UnprocessableException(
                "SAME_SOURCE_AND_DESTINATION",
                "El origen y el destino son la misma ubicación: no hay traslado.",
            )
        }

        val kardexId = move(
            StockMovement(
                productId = productId,
                warehouseId = destination.warehouseId,
                type = MovementType.LOCATE,
                units = quantity,
                fromLocationId = source.id,
                toLocationId = destination.id,
                userId = userId,
                idempotencyKey = key,
            ),
        )

        // El kardex ya guarda el movimiento, pero el kardex es del producto y la bitácora es de
        // la PERSONA: quién lo hizo y con qué permiso. Lleva origen y destino porque «de dónde
        // salió» es la pregunta que se hace cuando un estante aparece vacío.
        audit(
            AuditEntry(
                actorUserId = userId,
                channel = AuditChannel.WEB,
                entityType = "product",
                entityId = productId.toString(),
                entityLabel = productCode.value,
                action = "inventory.locate",
                previousState = """{"location":"${from.value}"}""",
                newState = """{"location":"${until.value}","units":"${quantity.unitsText()}"}""",
                permissionKey = "invoice.locate",
                idempotencyKey = key,
                businessDate = today(),
            ),
        )

        kardexId
    }

    // ── Consultas ─────────────────────────────────────────────────────────────

    fun stockByLocation(filter: StockFilter, offset: Int, limit: Int): List<StockByLocationDto> =
        repository.stockByLocation(filter, offset, limit)

    /** El total se cuenta con los mismos filtros que el listado: un total sin filtrar miente. */
    fun countStock(filter: StockFilter): Long = repository.countStock(filter)

    fun kardex(filter: KardexFilter, offset: Int, limit: Int): List<KardexDto> =
        repository.kardex(filter, offset, limit)

    fun countKardex(filter: KardexFilter): Long = repository.countKardex(filter)

    /**
     * El reporte de reconciliación. **El legacy no tenía esto**: el descuadre solo se descubría
     * con un conteo físico. Aquí se puede preguntar en cualquier momento y sin contar nada.
     */
    fun divergences(): List<DivergenceDto> = repository.divergences()

    // ── Piezas ────────────────────────────────────────────────────────────────

    /**
     * El registro de bitácora de un ajuste aplicado. Lleva los DOS totales: un registro que dice
     * «quedó en 5188» sin decir de dónde venía obliga a reconstruir la historia leyendo los
     * movimientos anteriores.
     *
     * El nivel es `RELEVANT` y debería ser `CRITICAL`: la tabla exige que un registro crítico
     * lleve su traza de decisión de acceso (`audit_critical_has_trace`) y `audit_log_record` no
     * la acepta todavía. Es una limitación pendiente de migración, no una decisión.
     */
    private fun adjustmentEntry(
        request: AdjustmentRequest,
        productId: Long,
        previousTotal: Quantity,
        newTotal: Quantity,
        ip: String?,
    ) = AuditEntry(
        actorUserId = request.requestedBy,
        channel = AuditChannel.WEB,
        sourceIp = ip,
        entityType = "product",
        entityId = productId.toString(),
        entityLabel = request.productCode.value,
        action = "inventory.adjustment",
        previousState = """{"total":"${previousTotal.unitsText()}"}""",
        newState = """{"total":"${newTotal.unitsText()}","units":"${request.quantity.unitsText()}",""" +
            """"direction":"${request.direction}"}""",
        reason = request.reason.name,
        note = request.note,
        permissionKey = adjustmentPermission(request.direction, request.reason),
        idempotencyKey = request.idempotencyKey,
        businessDate = today(),
    )

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun productNotFound(code: ProductCode) =
        NotFoundException("El producto '${code.value}' no existe.", "PRODUCT_NOT_FOUND")

    private fun locationNotFound(code: LocationCode) =
        NotFoundException("La ubicación '${code.value}' no existe.", "LOCATION_NOT_FOUND")
}

/** El resultado de pedir un ajuste. */
data class AdjustmentResult(
    /** Los ids del kardex que se escribieron. Vacío si el ajuste quedó pendiente. */
    val movements: List<Long>,
    val total: Quantity,
    val status: AdjustmentStatus,
    /** Por qué quedó pendiente, si quedó. */
    val reason: String? = null,
)

enum class AdjustmentStatus { APPLIED, PENDING_APPROVAL }

/**
 * El permiso que exige un ajuste, según su dirección y su motivo.
 *
 * **No hay un permiso genérico de «ajustar», y es deliberado.** Aumentar el inventario no es lo
 * mismo que mermarlo, y un robo no es lo mismo que un error de conteo: un permiso único daría a
 * cualquiera la capacidad de tapar un robo con un ajuste que él mismo firma.
 *
 * Es una sola función para la ruta, que autoriza con ella, y para la bitácora, que deja escrito
 * qué permiso se exigió. Dos copias de esta regla acabarían diciendo cosas distintas.
 */
fun adjustmentPermission(direction: AdjustmentDirection, reason: AdjustmentReason): String = when {
    direction == AdjustmentDirection.INCREASE -> "inventory.adjust_increase"
    reason == AdjustmentReason.THEFT -> "inventory.adjust_theft"
    reason == AdjustmentReason.DAMAGE -> "inventory.adjust_damage"
    reason == AdjustmentReason.SHORTAGE -> "inventory.adjust_shortage"
    else -> "inventory.adjust_count_error"
}
