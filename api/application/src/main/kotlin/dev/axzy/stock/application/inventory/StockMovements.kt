package dev.axzy.stock.application.inventory

import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.access.authorizeOr
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.application.shared.toFailure
import dev.axzy.stock.domain.access.ResourceContext
import dev.axzy.stock.domain.audit.AuditEntity
import dev.axzy.stock.domain.audit.AuditLevel
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.common.flatMap
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.util.UUID

/** El cuerpo de ubicar y mover: el mismo, porque la diferencia es el permiso y la regla. */
data class MovementInput(
    val productCode: String?,
    val fromLocationCode: String?,
    val toLocationCode: String?,
    val quantity: Quantity?,
    /**
     * La línea de factura de la que sale la mercancía, si viene de una recepción.
     *
     * Opcional a propósito: ubicar mercancía que no viene de una factura —una
     * regularización, una devolución— sigue siendo posible y no tiene línea.
     */
    val invoiceLineId: Long? = null,
)

/**
 * Las dos operaciones que **mueven** existencias sin cambiar el total.
 *
 * Se distinguen a propósito, y no solo por el nombre: ubicar saca de la recepción
 * y mover reorganiza estantes. Un operador tiene la primera y no necesariamente la
 * segunda, y por eso son permisos distintos (`invoice.locate` frente a
 * `inventory.move`). Si fueran la misma operación, quien puede ubicar podría
 * reorganizar el almacén entero.
 */
enum class MovementKind(val permission: String, val moveType: MovementType) {
    LOCATE("invoice.locate", MovementType.LOCATE),
    MOVE("inventory.move", MovementType.MOVE_WITHIN_WAREHOUSE),
    ;

    /** La acción de bitácora, para que el reporte de auditoría se lea sin traducir. */
    val auditAction: String get() = if (this == LOCATE) "INVENTORY_LOCATED" else "INVENTORY_MOVED"
}

/**
 * Aplica un movimiento de traslado interno (docs/flujos/existencias-y-ajustes.md §4).
 *
 * **El total no cambia**: sale de una ubicación y entra en otra. Lo que sí cambia es
 * el contenido de cada ubicación, y por eso se bloquea el saldo del origen antes de
 * restar (Q9) y se escribe un movimiento `LOCATE` o `MOVE_WITHIN_WAREHOUSE` en el
 * kardex, que es lo que permite explicar después de dónde salió cada pieza.
 */
open class ApplyStockMovement(
    private val kind: MovementKind,
    private val catalog: InventoryCatalog,
    private val ledger: StockLedger,
    private val locatingLines: LocatingLines,
    private val audit: AuditRecorder,
    private val auditEvents: AuditEvents,
    private val access: AccessDecider,
    private val tx: TransactionRunner,
    private val clock: java.time.Clock,
) {

    fun execute(
        actor: Actor,
        input: MovementInput,
        idempotencyKey: UUID?,
        origin: RequestOrigin,
    ): Outcome<MovementResult> =
        parse(input).flatMap { parsed ->
            access.authorizeOr(actor, kind.permission, origin) { grant ->
                val warehouseId = actor.user.warehouseIds.singleOrNull() ?: catalog.defaultWarehouseId()
                val from = catalog.activeLocation(warehouseId, parsed.from)
                    ?: return@authorizeOr DomainError.LocationNotFound(parsed.from.displayName).toFailure().asLeft()
                val to = catalog.activeLocation(warehouseId, parsed.to)
                    ?: return@authorizeOr DomainError.LocationNotFound(parsed.to.displayName).toFailure().asLeft()

                // Origen y destino iguales no es un traslado: no mueve nada y ensucia
                // el kardex con un movimiento sin efecto (A7).
                if (from.id == to.id) {
                    return@authorizeOr DomainError.SameSourceAndDestination(from.displayName).toFailure().asLeft()
                }

                // Un movimiento no cambia de bodega: para eso está el traslado (M05),
                // que es el único que sabe poner mercancía en tránsito.
                if (from.warehouseId != to.warehouseId) {
                    return@authorizeOr failure(
                        FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                        "The movement cannot change warehouse (${from.warehouseId} → ${to.warehouseId}): " +
                            "to move goods between warehouses, use a transfer.",
                        mapOf("rule" to "misma_bodega"),
                    )
                }

                val ruleError = checkRule(from, to)
                if (ruleError != null) return@authorizeOr ruleError

                // Solo ubicar conoce líneas de factura: mover es reorganizar estantes.
                if (kind != MovementKind.LOCATE && parsed.invoiceLineId != null) {
                    return@authorizeOr failure(
                        FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                        "Only locating can refer to an invoice line.",
                        mapOf("field" to "invoiceLineId"),
                    )
                }

                access.authorizeOr(
                    actor, kind.permission, origin,
                    resource = ResourceContext(warehouseId = from.warehouseId, locationKind = from.kind.name),
                    entityType = "location",
                    entityId = from.id.toString(),
                ) { scopedGrant ->
                    val product = catalog.activeProduct(parsed.code)
                        ?: return@authorizeOr DomainError.ProductNotFound(parsed.code.value).toFailure().asLeft()

                    /*
                     * La regla B1 se aplica **antes** de mover nada: si la línea tiene
                     * una diferencia sin aprobar, no se mueve el saldo y la línea no
                     * cambia. Es lo que exige R1 —«responde 409 y el inventario no
                     * cambia»— y por eso la validación del dominio va primero.
                     */
                    val line = parsed.invoiceLineId?.let { lineId ->
                        locatingLines.find(lineId) ?: return@authorizeOr failure(
                            FailureKind.NOT_FOUND, "LINE_NOT_FOUND",
                            "Line $lineId does not exist.",
                            mapOf("lineId" to lineId.toString()),
                        )
                    }
                    if (line != null && line.productCode != parsed.code) {
                        // Mover el saldo de un producto mientras se anota la línea de
                        // otro dejaría la factura diciendo una cosa y el almacén otra.
                        return@authorizeOr failure(
                            FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                            "Line ${line.id} belongs to ${line.productCode.value}, not ${parsed.code.value}.",
                            mapOf(
                                "invoiceLineId" to line.id.toString(),
                                "productCode" to parsed.code.value,
                                "lineProductCode" to line.productCode.value,
                            ),
                        )
                    }
                    val located = line?.let { target ->
                        when (val result = target.locate(parsed.quantity)) {
                            is Either.Left -> return@authorizeOr result.error.toFailure().asLeft()
                            is Either.Right -> result.value
                        }
                    }
                    apply(actor, product, from, to, parsed, located, idempotencyKey, origin, scopedGrant)
                }
            }
        }

    private fun apply(
        actor: Actor,
        product: dev.axzy.stock.domain.catalog.Product,
        from: Location,
        to: Location,
        parsed: Parsed,
        locatedLine: dev.axzy.stock.domain.receiving.InvoiceLine?,
        idempotencyKey: UUID?,
        origin: RequestOrigin,
        grant: dev.axzy.stock.application.access.AccessGrant,
    ): Outcome<MovementResult> = tx.inTransaction {
        // Idempotencia: repetir la clave devuelve el mismo movimiento y no vuelve a mover.
        val alreadyApplied = idempotencyKey?.let { ledger.movementIdOf(it) }
        if (alreadyApplied != null) {
            return@inTransaction MovementResult(
                kardexIds = listOf(alreadyApplied),
                status = MovementStatus.APPLIED,
                total = null,
                message = "This movement was already applied: the idempotency key was repeated.",
            ).asRight()
        }

        // El saldo del origen se lee CON BLOQUEO: el número que se comprueba tiene
        // que seguir siendo verdad al restar (Q9).
        val available = ledger.balanceAt(product.id, from.id, forUpdate = true)
        if (parsed.quantity > available) {
            return@inTransaction DomainError.InsufficientStock(
                available = available.units,
                requested = parsed.quantity.units,
                location = from.displayName,
            ).toFailure().asLeft()
        }

        val kardexId = ledger.apply(
            MovementCommand(
                productId = product.id,
                warehouseId = from.warehouseId,
                moveType = kind.moveType,
                units = parsed.quantity,
                fromLocationId = from.id,
                toLocationId = to.id,
                userId = actor.id,
                clientUuid = idempotencyKey,
            ),
        )

        /*
         * La línea se anota **en la misma transacción** que el movimiento: o quedan
         * los dos o no queda ninguno. Si la anotación fallara después de mover, el
         * saldo habría cambiado y la factura no lo sabría.
         */
        locatedLine?.let { line -> locatingLines.markLocated(line.id, line, to.id, clock.instant()) }

        audit.record(
            auditEvents.byActor(
                actor = actor,
                origin = origin,
                action = kind.auditAction,
                level = AuditLevel.CRITICAL,
                entity = AuditEntity("product", product.id.toString(), parsed.code.value),
                grant = grant,
                previousState = mapOf("from" to from.displayName, "available" to available.toString()),
                newState = mapOf("to" to to.displayName, "kardexId" to kardexId),
                idempotencyKey = idempotencyKey?.toString(),
            ),
        )

        // El total NO cambia al mover: devolverlo sería invitar a creer que sí.
        MovementResult(listOf(kardexId), MovementStatus.APPLIED, total = null).asRight()
    }

    /**
     * La forma de cada operación, que es lo que las hace permisos distintos.
     *
     * Ubicar **sale de la recepción** y entra en un estante; mover va de estante a
     * estante. Sin esta comprobación, quien solo puede ubicar podría reorganizar el
     * almacén llamando a `locate` con dos estantes.
     */
    private fun checkRule(from: Location, to: Location): Outcome<Nothing>? = when (kind) {
        MovementKind.LOCATE -> when {
            from.kind != LocationKind.RECEIVING -> failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "Locating takes goods out of the receiving location, and the source is ${from.displayName}.",
                mapOf("fromLocationCode" to from.displayName, "expectedKind" to "RECEIVING"),
            )
            to.kind != LocationKind.SHELF -> failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "Locating puts the goods on a shelf, and the destination is ${to.displayName}.",
                mapOf("toLocationCode" to to.displayName, "expectedKind" to "SHELF"),
            )
            else -> null
        }

        MovementKind.MOVE -> when {
            from.kind != LocationKind.SHELF || to.kind != LocationKind.SHELF -> failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "Moving is between shelves: ${from.displayName} → ${to.displayName} is not one. " +
                    "There is no operation to move goods to receiving or to the unassigned balance.",
                mapOf("fromLocationCode" to from.displayName, "toLocationCode" to to.displayName),
            )
            else -> null
        }
    }

    private data class Parsed(
        val code: ProductCode,
        val from: LocationRef,
        val to: LocationRef,
        val quantity: Quantity,
        val invoiceLineId: Long?,
    )

    private fun parse(input: MovementInput): Outcome<Parsed> =
        InventoryFilters.productCode(input.productCode).flatMap { code: ProductCode? ->
            if (code == null) {
                failure(FailureKind.RULE_VIOLATION, "FIELD_REQUIRED", "The product code is required.")
            } else {
                InventoryFilters.locationRef(input.fromLocationCode).flatMap { from: LocationRef? ->
                    InventoryFilters.locationRef(input.toLocationCode).flatMap { to: LocationRef? ->
                        when {
                            from == null -> failure(
                                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                                "The source location code is required.",
                            )
                            to == null -> failure(
                                FailureKind.RULE_VIOLATION, "FIELD_REQUIRED",
                                "The destination location code is required.",
                            )
                            input.quantity == null -> failure(
                                FailureKind.RULE_VIOLATION, "INVALID_QUANTITY",
                                "The quantity is required.",
                            )
                            else -> Parsed(code, from, to, input.quantity, input.invoiceLineId).asRight()
                        }
                    }
                }
            }
        }
}

/**
 * Ubicar: saca de la recepción y deja la mercancía en un estante (`invoice.locate`).
 *
 * Los dos movimientos son la misma clase con distinta regla y distinto permiso. Se
 * declaran como dos tipos —y no como dos instancias del mismo— porque el cableado
 * construye **dos objetos** y, con el mismo tipo, cuál es cuál dependería del nombre
 * del parámetro. Un tipo por operación lo hace imposible de confundir.
 */
class LocateStock(
    catalog: InventoryCatalog,
    ledger: StockLedger,
    locatingLines: LocatingLines,
    audit: AuditRecorder,
    auditEvents: AuditEvents,
    access: AccessDecider,
    tx: TransactionRunner,
    clock: java.time.Clock,
) : ApplyStockMovement(
    MovementKind.LOCATE, catalog, ledger, locatingLines, audit, auditEvents, access, tx, clock,
)

/** Mover: reorganiza estantes de la misma bodega (`inventory.move`). */
class MoveStock(
    catalog: InventoryCatalog,
    ledger: StockLedger,
    locatingLines: LocatingLines,
    audit: AuditRecorder,
    auditEvents: AuditEvents,
    access: AccessDecider,
    tx: TransactionRunner,
    clock: java.time.Clock,
) : ApplyStockMovement(
    MovementKind.MOVE, catalog, ledger, locatingLines, audit, auditEvents, access, tx, clock,
)
