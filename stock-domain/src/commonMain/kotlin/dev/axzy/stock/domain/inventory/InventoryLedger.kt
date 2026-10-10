package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.common.DomainResult
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.domain.value.Quantity.Companion.ofScaled

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE INVENTORY LEDGER — in-memory, pure and deterministic model
 *
 * It is the piece that allows verifying the **invariants I1–I10** with property-based
 * testing: random sequences of valid operations are generated, and it is asserted
 * that the properties hold. This covers combinations that nobody would
 * think to write by hand.
 *
 * **Design decisions, and why:**
 *
 *  1. **The ledger is append-only.** Entries are never modified or
 *     deleted. It is the opposite of what the legacy did in
 *     `removeUnitsFromKardex`, which did `kardex.update` on a historical row
 *     and thereby destroyed traceability (finding R-02).
 *
 *  2. **Stock by location is the source of truth and the total is its sum.**
 *     In the legacy it was the other way around: a single total number (`Store.stock`) from which
 *     the location was attempted to be deduced, compensating with subtractions. That is where
 *     the double-deduction bug on locating came from (finding R-01).
 *
 *  3. **No operation subtracts from the total.** The total only changes
 *     by `RECEIPT` (adds) and by `ADJUSTMENT_OUT` (subtracts, and requires a reason).
 *     Everything else is a transfer between locations or toward transit.
 *
 *  4. **Transit is explicit.** Goods traveling between warehouses still
 *     count in the total inventory. If it were modeled as two instant movements,
 *     the total would drop during the journey and the reports would
 *     lie.
 *
 * **Scope of Phase 0:** this model is the executable specification of the
 * domain. The PostgreSQL implementation with Exposed comes in Phase 1 and
 * must reproduce exactly these transitions.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** A ledger entry. Immutable: once written, it is not touched. */
data class LedgerEntry(
    /** Sequence number. It is the total order of the ledger. */
    val sequence: Long,
    val productCode: String,
    val type: MovementType,
    val quantity: Quantity,
    val from: LocationCode?,
    val to: LocationCode?,
    /** Total quantity of the product **after** applying this entry (I4). */
    val quantityAfter: Quantity,
    /** Reason, mandatory for adjustments (I6). */
    val reason: String? = null,
    /** Client identifier, for the mobile app's idempotency (I8). */
    val clientUuid: String? = null,
)

/** Balance of a product at a specific location. */
data class LocationBalance(
    val productCode: String,
    val location: LocationCode,
    val quantity: Quantity,
)

/** Goods that left a warehouse and have not yet entered the other. */
data class InTransitBalance(
    val productCode: String,
    val fromLocation: LocationCode,
    val toWarehouseId: Long,
    val quantity: Quantity,
)

/**
 * The inventory ledger.
 *
 * It is mutable **only through its operations**, which validate before
 * writing. It exposes no setters: the state changes only through movements, just as
 * in the database, where the ledger is append-only.
 */
class InventoryLedger {

    private val _entries = mutableListOf<LedgerEntry>()
    private val balances = mutableMapOf<LocationCode, MutableMap<String, Quantity>>()
    private val _inTransit = mutableListOf<InTransitBalance>()
    private var nextSequence = 1L

    /** The whole ledger, in order. Read-only from outside. */
    val entries: List<LedgerEntry> get() = _entries.toList()

    /** Goods in transit between warehouses. */
    val inTransit: List<InTransitBalance> get() = _inTransit.toList()

    // ═════════════════════════════════════════════════════════════════════════
    // CONSULTAS
    // ═════════════════════════════════════════════════════════════════════════

    /** Quantity of a product at a specific location. */
    fun quantityAt(productCode: String, location: LocationCode): Quantity =
        balances[location]?.get(productCode) ?: Quantity.ZERO

    /**
     * **Product total = sum of locations + transit.**
     *
     * It is the definition that makes the legacy's bug impossible. It is not a stored number to keep
     * in sync: it is derived.
     */
    fun totalOf(productCode: String): Quantity {
        val atLocations = balances.values.sumOf { byProduct ->
            (byProduct[productCode] ?: Quantity.ZERO).value
        }
        val inTransit = _inTransit
            .filter { it.productCode == productCode }
            .sumOf { it.quantity.value }
        return ofScaled(atLocations + inTransit)
    }

    /** Product total summing **only** the locations, without transit. */
    fun totalAtLocationsOf(productCode: String): Quantity =
        ofScaled(
            balances.values.sumOf { (it[productCode] ?: Quantity.ZERO).value }
        )

    /** Product total in transit. */
    fun totalInTransitOf(productCode: String): Quantity =
        ofScaled(
            _inTransit.filter { it.productCode == productCode }.sumOf { it.quantity.value }
        )

    /**
     * Algebraic sum of all the ledger's entries for a product (I1).
     * Each entry declares its effect on the total: only `RECEIPT` adds and
     * `ADJUSTMENT_OUT` resta.
     */
    fun ledgerNetOf(productCode: String): Quantity =
        ofScaled(
            _entries.filter { it.productCode == productCode }
                .sumOf { entry ->
                    when (entry.type) {
                        MovementType.RECEIPT_IN -> entry.quantity.value
                        MovementType.ADJUSTMENT_OUT -> -entry.quantity.value
                        MovementType.ADJUSTMENT_IN -> entry.quantity.value
                        MovementType.INITIAL -> entry.quantity.value
                        else -> 0L
                    }
                }
        )

    /** All the balances, for recalculations (I5). */
    fun allBalances(): List<LocationBalance> =
        balances.flatMap { (location, byProduct) ->
            byProduct.map { (productCode, quantity) ->
                LocationBalance(productCode, location, quantity)
            }
        }

    // ═════════════════════════════════════════════════════════════════════════
    // OPERACIONES
    // ═════════════════════════════════════════════════════════════════════════

    /** Initial catalog load. Goods enter for the first time. */
    fun initialLoad(
        productCode: String,
        location: LocationCode,
        quantity: Quantity,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity(
                "The initial load must be greater than zero (got $quantity)"
            ).asLeft()
        }
        val result = record(productCode, MovementType.INITIAL, quantity, from = null, to = location)
        if (result is Either.Left<*>) return result

        addToLocation(productCode, location, quantity)
        return result
    }

    /** Receipt of an invoice: new goods enter. It is one of the two that add. */
    fun receipt(
        productCode: String,
        receivingLocation: LocationCode,
        quantity: Quantity,
        clientUuid: String? = null,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("The receipt must be greater than zero").asLeft()
        }

        // ── Invariante I8: idempotencia ─────────────────────────────────────
        // Repeating the same key does not duplicate the movement. It is what allows the
        // mobile app to retry a synchronization without duplicating stock.
        if (clientUuid != null && _entries.any { it.clientUuid == clientUuid }) {
            return DomainError.Duplicate(
                entity = "movimiento",
                value = clientUuid,
                message = "Este movimiento ya fue registrado (idempotencia)",
            ).asLeft()
        }

        val result = record(
            productCode, MovementType.RECEIPT_IN, quantity,
            from = null, to = receivingLocation, clientUuid = clientUuid,
        )
        if (result is Either.Left<*>) return result
        addToLocation(productCode, receivingLocation, quantity)
        return result
    }

    /**
     * **Locating** goods received onto their shelf.
     *
     * It is the movement of the legacy bug: here it was subtracted from the total, twice
     * in fact. In this model it is a **transfer**: it leaves the receiving location and
     * enters the shelf, and **the total does not change** (decision R-65).
     */
    fun locate(
        productCode: String,
        fromReceiving: LocationCode,
        toShelf: LocationCode,
        quantity: Quantity,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("The quantity to locate must be greater than zero").asLeft()
        }
        if (fromReceiving == toShelf) {
            return DomainError.SameSourceAndDestination(fromReceiving.value).asLeft()
        }

        val available = quantityAt(productCode, fromReceiving)
        if (quantity > available) {
            return DomainError.InsufficientStock(
                available = available.value,
                requested = quantity.value,
                location = fromReceiving.value,
            ).asLeft()
        }

        val result = record(
            productCode, MovementType.LOCATE, quantity,
            from = fromReceiving, to = toShelf,
        )
        if (result is Either.Left<*>) return result
        addToLocation(productCode, fromReceiving, ofScaled(-quantity.value))
        addToLocation(productCode, toShelf, quantity)
        return result
    }

    /** Transfer between shelves of the same warehouse. Invariant total. */
    fun moveWithinWarehouse(
        productCode: String,
        from: LocationCode,
        to: LocationCode,
        quantity: Quantity,
        clientUuid: String? = null,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("The quantity to move must be greater than zero").asLeft()
        }
        if (from == to) {
            return DomainError.SameSourceAndDestination(from.value).asLeft()
        }
        if (clientUuid != null && _entries.any { it.clientUuid == clientUuid }) {
            return DomainError.Duplicate("movimiento", clientUuid, "Movimiento ya registrado").asLeft()
        }

        val available = quantityAt(productCode, from)
        if (quantity > available) {
            return DomainError.InsufficientStock(available.value, quantity.value, from.value).asLeft()
        }

        val result = record(
            productCode, MovementType.MOVE_WITHIN_WAREHOUSE, quantity,
            from = from, to = to, clientUuid = clientUuid,
        )
        if (result is Either.Left<*>) return result
        addToLocation(productCode, from, ofScaled(-quantity.value))
        addToLocation(productCode, to, quantity)
        return result
    }

    /** Outflow from one warehouse to another: the goods enter transit. */
    fun transferOut(
        productCode: String,
        fromLocation: LocationCode,
        toWarehouseId: Long,
        quantity: Quantity,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("The quantity to transfer must be greater than zero").asLeft()
        }

        val available = quantityAt(productCode, fromLocation)
        if (quantity > available) {
            return DomainError.InsufficientStock(
                available.value, quantity.value, fromLocation.value,
            ).asLeft()
        }

        val result = record(
            productCode, MovementType.TRANSFER_OUT, quantity,
            from = fromLocation, to = null,
        )
        if (result is Either.Left<*>) return result
        addToLocation(productCode, fromLocation, ofScaled(-quantity.value))
        // Enters transit: it still counts in the total.
        _inTransit.add(
            InTransitBalance(productCode, fromLocation, toWarehouseId, quantity)
        )
        return result
    }

    /** Arrival from transit: enters the destination shelf and leaves transit. */
    fun transferIn(
        productCode: String,
        toLocation: LocationCode,
        fromWarehouseId: Long,
        quantity: Quantity,
    ): DomainResult<LedgerEntry> {
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("The quantity to receive must be greater than zero").asLeft()
        }

        val index = _inTransit.indexOfFirst {
            it.productCode == productCode &&
                it.toWarehouseId == toLocation.warehouseHint &&
                it.quantity >= quantity
        }
        if (index < 0) {
            return DomainError.InsufficientStock(
                available = 0L,
                requested = quantity.value,
                location = "transit",
                message = "There are no goods of $productCode in transit to receive",
            ).asLeft()
        }

        val result = record(
            productCode, MovementType.TRANSFER_IN, quantity,
            from = null, to = toLocation,
        )
        if (result is Either.Left<*>) return result
        val current = _inTransit[index]
        _inTransit[index] = current.copy(quantity = current.quantity - quantity)
        if (_inTransit[index].quantity.isZero) _inTransit.removeAt(index)
        addToLocation(productCode, toLocation, quantity)
        return result
    }

    /** Ajuste positivo autorizado. Requiere motivo (I6). */
    fun adjustmentIn(
        productCode: String,
        location: LocationCode,
        quantity: Quantity,
        reason: String,
    ): DomainResult<LedgerEntry> {
        if (reason.isBlank()) {
            return DomainError.ReasonRequired("an incoming adjustment").asLeft()
        }
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("The adjustment must be greater than zero").asLeft()
        }
        val result = record(
            productCode, MovementType.ADJUSTMENT_IN, quantity,
            from = null, to = location, reason = reason,
        )
        if (result is Either.Left<*>) return result
        addToLocation(productCode, location, quantity)
        return result
    }

    /** Shrinkage: goods leave definitively. It is the other one that subtracts. */
    fun adjustmentOut(
        productCode: String,
        location: LocationCode,
        quantity: Quantity,
        reason: String,
    ): DomainResult<LedgerEntry> {
        if (reason.isBlank()) {
            return DomainError.ReasonRequired("a shrinkage").asLeft()
        }
        if (!quantity.isPositive) {
            return DomainError.InvalidQuantity("The shrinkage must be greater than zero").asLeft()
        }

        val available = quantityAt(productCode, location)
        if (quantity > available) {
            return DomainError.InsufficientStock(
                available.value, quantity.value, location.value,
            ).asLeft()
        }

        val result = record(
            productCode, MovementType.ADJUSTMENT_OUT, quantity,
            from = location, to = null, reason = reason,
        )
        if (result is Either.Left<*>) return result
        addToLocation(productCode, location, ofScaled(-quantity.value))
        return result
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNO
    // ═════════════════════════════════════════════════════════════════════════

    private fun record(
        productCode: String,
        type: MovementType,
        quantity: Quantity,
        from: LocationCode?,
        to: LocationCode?,
        reason: String? = null,
        clientUuid: String? = null,
    ): DomainResult<LedgerEntry> {
        // `quantityAfter` is computed with the sum of locations plus transit
        // BEFORE applying the effect, so that the ledger is self-auditing (I4).
        val before = totalOf(productCode)
        val after = when (type) {
            MovementType.RECEIPT_IN, MovementType.ADJUSTMENT_IN, MovementType.INITIAL -> before + quantity
            MovementType.ADJUSTMENT_OUT -> before - quantity
            else -> before
        }

        val entry = LedgerEntry(
            sequence = nextSequence++,
            productCode = productCode,
            type = type,
            quantity = quantity,
            from = from,
            to = to,
            quantityAfter = after,
            reason = reason,
            clientUuid = clientUuid,
        )
        _entries.add(entry)
        return entry.asRight()
    }

    private fun addToLocation(productCode: String, location: LocationCode, delta: Quantity) {
        val byProduct = balances.getOrPut(location) { mutableMapOf() }
        val current = byProduct[productCode] ?: Quantity.ZERO
        byProduct[productCode] = current + delta
    }
}

/**
 * Warehouse hint derived from the location code.
 *
 * In the real model the location has an FK to the warehouse. Here, for the
 * property test, it is derived from the code's prefix: `NORTE-01` → warehouse 1,
 * `SUR-01` → warehouse 2. It keeps the test model without duplicating the schema.
 */
val LocationCode.warehouseHint: Long
    get() = when {
        value.startsWith("NORTE") -> 1L
        value.startsWith("SUR") -> 2L
        else -> 0L
    }
