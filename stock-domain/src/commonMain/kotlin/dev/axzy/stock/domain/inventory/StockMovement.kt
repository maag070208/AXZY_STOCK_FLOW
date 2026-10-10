package dev.axzy.stock.domain.inventory

import dev.axzy.stock.domain.value.LocationCode
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE MOVEMENT → STOCK EFFECT MATRIX
 *
 * This file is the one that fixes the most serious defect of the legacy. The problem
 * was not a badly written `if`: it was that **the effect of each movement was not
 * declared**. It lived in an `if/else` (`updateStoreStock`) with a rule by
 * default —"any other type subtracts"— that made `LOCATION` subtract from the
 * total without anyone having decided it.
 *
 * Here each movement **declares its effect explicitly**. Consequences:
 *
 *   1. An exhaustive `when` over [StockMovement] **does not compile** if a new movement appears
 *      without declaring its effect. The compiler prevents the oversight.
 *   2. **No movement subtracts from the total.** The total is a derived sum of
 *      the locations. The double-deduction bug on locating **stops being
 *      expressible**, which is better than "fixed".
 *   3. Transit between warehouses is explicit ([StockEffect.EnterTransit] and
 *      [StockEffect.LeaveTransit]) instead of a side effect.
 *
 * Reference: docs/historico/PLAN_ALMACEN_INTEGRAL.md §2.7 (the verified bug) and §3.2-ter.
 * Decision R-65: locating does NOT lower the total.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Effect of a movement on the inventory.
 *
 * The total (`product_stock`) is **always a derived sum** of
 * `Σ stock_by_location + Σ stock_in_transit`. No effect modifies it
 * directly: that is the structural guarantee against the discrepancy.
 */
sealed interface StockEffect {

    /**
     * The movement increases the physical inventory because **new goods enter**.
     * It is the only effect that increases the total, and it happens only through:
     * an initial load, a captured receipt or an authorized positive adjustment.
     */
    data class AddToLocation(val location: LocationCode) : StockEffect

    /**
     * The movement takes goods out of the warehouse definitively.
     * It is the only effect that decreases the total, and it **always requires a reason**.
     */
    data class RemoveFromLocation(val location: LocationCode) : StockEffect

    /**
     * Goods that change shelf **within the same warehouse**.
     * The total is invariant: it leaves one location and enters another.
     *
     * This is the effect that **locating** uses during receiving: it leaves the
     * receiving location and enters the shelf (decision R-65).
     */
    data class TransferWithinWarehouse(
        val from: LocationCode,
        val to: LocationCode,
    ) : StockEffect

    /**
     * Goods that leave one warehouse for another: they enter **transit**.
     * They still count in the total inventory, but they are no longer on a shelf.
     * The legacy had this concept half-way and without knowing it: `PENDING-TO-OUT`
     * "did not affect the stock" by accident.
     */
    data class EnterTransit(
        val from: LocationCode,
        val toWarehouseId: Long,
    ) : StockEffect

    /**
     * Goods that arrive from transit: they leave transit and enter the
     * shelf of the destination warehouse. The total is still invariant.
     */
    data class LeaveTransit(
        val to: LocationCode,
        val fromWarehouseId: Long,
    ) : StockEffect

    /**
     * Movement **with no effect on the inventory**.
     *
     * It is not an "empty effect through carelessness": it is the explicit declaration that
     * this movement is an **intention**, not a fact. Example: reserving
     * goods for an order before physically taking them out. The legacy had it
     * as a hidden special case (`if moveType == 'PENDING-TO-OUT' return`).
     */
    data object NoInventoryChange : StockEffect
}

/**
 * Ledger movement type. **Closed enum**: the database uses the
 * same set, so an invalid value is impossible.
 *
 * The names are the legacy's (`INITIAL`, `IN`, `OUT`, `LOCATION`…) plus the
 * new ones the model needs, so that the migration is traceable.
 */
enum class MovementType {
    /** Initial catalog load. Goods enter for the first time. */
    INITIAL,

    /** Entry by invoice receipt: new goods enter the warehouse. */
    RECEIPT_IN,

    /** Entry by authorized positive adjustment (count, supplier return). */
    ADJUSTMENT_IN,

    /** Definitive outflow by fulfillment of an order. */
    FULFILLMENT_OUT,

    /** Outflow for shrinkage: requires a mandatory reason. */
    ADJUSTMENT_OUT,

    /**
     * Location of received goods: they leave the receiving location and
     * enter the shelf. **Does NOT change the total** (decision R-65).
     * In the legacy this subtracted from the total through a default effect: it was the bug.
     */
    LOCATE,

    /** Transfer between shelves of the same warehouse. It does not change the total. */
    MOVE_WITHIN_WAREHOUSE,

    /** Outflow from one warehouse to another: goods enter transit. */
    TRANSFER_OUT,

    /** Arrival from transit: enters the destination shelf. */
    TRANSFER_IN,

    /** Outflow intention (reservation for an order). It does not change the inventory. */
    PENDING_OUT,
}

/**
 * A ledger movement, with its **declared** effect.
 *
 * Modeled as a `sealed interface` so that the `when` that consumes it is
 * exhaustive and the compiler detects a new movement not handled.
 */
sealed interface StockMovement {

    val type: MovementType
    val quantity: Quantity

    /** The location the goods leave from, if applicable. */
    val from: LocationCode?

    /** The location the goods enter, if applicable. */
    val to: LocationCode?

    /**
     * **The declared effect.** It is the central piece: the consumer does not infer the
     * effect, it reads it. Adding a new movement forces declaring it here, and the
     * `when` below stops compiling until it is done.
     */
    val effect: StockEffect
}

// ─────────────────────────────────────────────────────────────────────────────
// Implementations. Each one declares its effect in the constructor.
// ─────────────────────────────────────────────────────────────────────────────

/** Initial catalog load: goods enter for the first time. */
data class InitialLoad(
    override val quantity: Quantity,
    val location: LocationCode,
    override val to: LocationCode = location,
) : StockMovement {
    override val type = MovementType.INITIAL
    override val from: LocationCode? = null
    override val effect: StockEffect = StockEffect.AddToLocation(location)
}

/** Captured invoice receipt: new goods enter the warehouse. */
data class ReceiptIn(
    override val quantity: Quantity,
    val receivingLocation: LocationCode,
) : StockMovement {
    override val type = MovementType.RECEIPT_IN
    override val from: LocationCode? = null
    override val to: LocationCode = receivingLocation
    override val effect: StockEffect = StockEffect.AddToLocation(receivingLocation)
}

/** Ajuste positivo autorizado: requiere motivo (invariante I6). */
data class AdjustmentIn(
    override val quantity: Quantity,
    val location: LocationCode,
    val reason: String,
) : StockMovement {
    init {
        require(reason.isNotBlank()) {
            "Every incoming adjustment requires a reason. An adjustment without a reason is an audit gap."
        }
    }

    override val type = MovementType.ADJUSTMENT_IN
    override val from: LocationCode? = null
    override val to: LocationCode = location
    override val effect: StockEffect = StockEffect.AddToLocation(location)
}

/** Fulfillment of an order: definitive outflow from the warehouse. */
data class FulfillmentOut(
    override val quantity: Quantity,
    val location: LocationCode,
    val orderNumber: String,
) : StockMovement {
    init {
        require(orderNumber.isNotBlank()) { "A picking exit requires the order number" }
    }

    override val type = MovementType.FULFILLMENT_OUT
    override val from: LocationCode? = location
    override val to: LocationCode? = null
    override val effect: StockEffect = StockEffect.RemoveFromLocation(location)
}

/** Shrinkage: outflow with a **mandatory reason** (decision B2 / invariant I6). */
data class AdjustmentOut(
    override val quantity: Quantity,
    val location: LocationCode,
    val reason: String,
) : StockMovement {
    init {
        require(reason.isNotBlank()) {
            "Every shrinkage requires a reason. Inventory is never deleted: the movement is recorded."
        }
    }

    override val type = MovementType.ADJUSTMENT_OUT
    override val from: LocationCode? = location
    override val to: LocationCode? = null
    override val effect: StockEffect = StockEffect.RemoveFromLocation(location)
}

/**
 * Locating received goods: **the legacy's bug movement**.
 *
 * It leaves the receiving location and enters the shelf. The total **does not change**.
 * In the legacy this subtracted from the total (twice, in fact), and a box of 100
 * left the inventory 100 units below.
 */
data class Locate(
    override val quantity: Quantity,
    val fromReceiving: LocationCode,
    val toShelf: LocationCode,
) : StockMovement {
    init {
        require(fromReceiving != toShelf) {
            "Locating requires the destination location to differ from the receiving location"
        }
    }

    override val type = MovementType.LOCATE
    override val from: LocationCode? = fromReceiving
    override val to: LocationCode = toShelf
    override val effect: StockEffect =
        StockEffect.TransferWithinWarehouse(from = fromReceiving, to = toShelf)
}

/** Transfer between shelves of the same warehouse. Invariant total. */
data class MoveWithinWarehouse(
    override val quantity: Quantity,
    override val from: LocationCode,
    override val to: LocationCode,
) : StockMovement {
    init {
        require(from != to) { "A transfer requires different origin and destination" }
    }

    override val type = MovementType.MOVE_WITHIN_WAREHOUSE
    override val effect: StockEffect = StockEffect.TransferWithinWarehouse(from = from, to = to)
}

/** Outflow from one warehouse to another: the goods enter transit. */
data class TransferOut(
    override val quantity: Quantity,
    val fromLocation: LocationCode,
    val toWarehouseId: Long,
) : StockMovement {
    override val type = MovementType.TRANSFER_OUT
    override val from: LocationCode? = fromLocation
    override val to: LocationCode? = null
    override val effect: StockEffect = StockEffect.EnterTransit(fromLocation, toWarehouseId)
}

/** Arrival from transit: enters the destination warehouse's shelf. */
data class TransferIn(
    override val quantity: Quantity,
    val toLocation: LocationCode,
    val fromWarehouseId: Long,
) : StockMovement {
    override val type = MovementType.TRANSFER_IN
    override val from: LocationCode? = null
    override val to: LocationCode = toLocation
    override val effect: StockEffect = StockEffect.LeaveTransit(toLocation, fromWarehouseId)
}

/**
 * Outflow intention: reserves goods without taking them out.
 * **It does not change the inventory.** Declaring it as an explicit effect avoids the
 * hidden special case the legacy had.
 */
data class PendingOut(
    override val quantity: Quantity,
    val location: LocationCode,
    val orderNumber: String,
) : StockMovement {
    override val type = MovementType.PENDING_OUT
    override val from: LocationCode? = location
    override val to: LocationCode? = null
    override val effect: StockEffect = StockEffect.NoInventoryChange
}

// ─────────────────────────────────────────────────────────────────────────────
// Operations derived from the effect
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Indicates whether the movement **changes the total inventory**.
 *
 * It is the query the legacy could not make: there was no way to know whether a
 * movement affected the total without reading a 15-line `if/else`.
 */
val StockEffect.changesTotal: Boolean
    get() = when (this) {
        is StockEffect.AddToLocation -> true
        is StockEffect.RemoveFromLocation -> true
        is StockEffect.TransferWithinWarehouse -> false  // invariante
        is StockEffect.EnterTransit -> false             // sigue en el inventario
        is StockEffect.LeaveTransit -> false             // sale del tránsito, entra al estante
        StockEffect.NoInventoryChange -> false
    }

/**
 * Delta of the inventory total that this movement produces.
 *
 * **Note that only two effects can return something other than zero**, and one
 * is positive and the other negative. There is no combination that subtracts
 * twice. That is the guarantee against the legacy's bug.
 */
fun StockEffect.totalDelta(quantity: Quantity): Long = when (this) {
    is StockEffect.AddToLocation -> quantity.value
    is StockEffect.RemoveFromLocation -> -quantity.value
    is StockEffect.TransferWithinWarehouse -> 0L
    is StockEffect.EnterTransit -> 0L
    is StockEffect.LeaveTransit -> 0L
    StockEffect.NoInventoryChange -> 0L
}

/**
 * Affected locations and their delta. It is what the persistence layer applies
 * sobre `stock_by_location`.
 *
 * It returns a map because a single movement can touch two locations
 * (transfer) or a location and the transit.
 */
fun StockEffect.locationDeltas(quantity: Quantity): Map<LocationCode, Long> = when (this) {
    is StockEffect.AddToLocation -> mapOf(location to quantity.value)
    is StockEffect.RemoveFromLocation -> mapOf(location to -quantity.value)
    is StockEffect.TransferWithinWarehouse ->
        mapOf(from to -quantity.value, to to quantity.value)
    is StockEffect.EnterTransit -> mapOf(from to -quantity.value)
    is StockEffect.LeaveTransit -> mapOf(to to quantity.value)
    StockEffect.NoInventoryChange -> emptyMap()
}
