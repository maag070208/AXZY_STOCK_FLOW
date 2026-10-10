package dev.axzy.stock.application.inventory

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.Product
import dev.axzy.stock.domain.inventory.AdjustmentOrigin
import dev.axzy.stock.domain.inventory.AdjustmentReason
import dev.axzy.stock.domain.inventory.MovementType
import dev.axzy.stock.domain.receiving.InvoiceLine
import java.time.Instant
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import java.util.UUID

/**
 * The stock output ports (M02, docs/flujos/existencias-y-ajustes.md).
 *
 * **Every write goes through the SQL engine** ([ADR-0015]): the API does not touch
 * `stock_by_location` nor `product_stock` directly. That is why there is no
 * `saveStock` or anything similar here: there is [StockLedger.apply], which is
 * `record_movement`.
 */

/**
 * A location, as the contract identifies it: **by its visible name**
 * (`04-B-C`, `RECEIVING`, `UNASSIGNED`).
 *
 * It is translated into parts in the application layer and not in SQL, because the composition rule
 * lives in the domain ([Location.displayName]) and having it twice would
 * guarantee that one day they would say different things. A round-trip test
 * (`LocationRefTest`) prevents them from separating.
 */
sealed interface LocationRef {
    /** A shelf: its three parts. */
    data class Shelf(val hall: String, val code: String, val position: String) : LocationRef

    /** The receiving area: where what was received and not located lands. */
    data object Receiving : LocationRef

    /** The inherited balance of the previous system, of which it is known that it exists but not where. */
    data object Unassigned : LocationRef

    /** The canonical visible name of this reference, to return it in a response. */
    val displayName: String
        get() = when (this) {
            is Shelf -> "$hall-$code-$position"
            Receiving -> "RECEIVING"
            Unassigned -> "UNASSIGNED"
        }

    companion object {
        const val RECEIVING_CODE = "RECEIVING"
        const val UNASSIGNED_CODE = "UNASSIGNED"

        /**
         * Rebuilds the reference from the visible name.
         *
         * `null` when it has no location form: each part's alphabet
         * excludes the hyphen, so a string with another number of parts cannot
         * corresponder a ninguna fila.
         */
        fun parse(raw: String): LocationRef? {
            val value = raw.trim().uppercase()
            if (value.isEmpty()) return null
            return when {
                value == UNASSIGNED_CODE -> Unassigned
                value == RECEIVING_CODE -> Receiving
                else -> {
                    val parts = value.split('-')
                    if (parts.size != 3 || parts.any { it.isEmpty() }) return null
                    Shelf(parts[0], parts[1], parts[2])
                }
            }
        }
    }
}

/** Where the real product and location identifiers come from. */
interface InventoryCatalog {
    /** The **active** product with that code: an inactive product receives no movements. */
    fun activeProduct(code: ProductCode): Product?

    /** The **active** location of that warehouse, or `null` if it does not exist or is inactive. */
    fun activeLocation(warehouseId: Long, ref: LocationRef): Location?

    /** The default warehouse: the one used when the request does not say another. */
    fun defaultWarehouseId(): Long
}

/**
 * A movement, as the SQL engine needs it.
 *
 * The types are the domain's and not strings: a `MovementType` that does not exist does not
 * compile, and the adapter is the only one that translates to PostgreSQL's `enum`s.
 */
data class MovementCommand(
    val productId: Long,
    val warehouseId: Long,
    val moveType: MovementType,
    val units: Quantity,
    val fromLocationId: Long? = null,
    val toLocationId: Long? = null,
    val container: String? = null,
    val orderNumber: String? = null,
    val adjustmentReason: AdjustmentReason? = null,
    val adjustmentOrigin: AdjustmentOrigin? = null,
    val userId: Long? = null,
    /** Idempotency key: repeating it does not duplicate the movement (A8). */
    val clientUuid: UUID? = null,
    val note: String? = null,
)

/** The inventory ledger. It is the only write gate. */
interface StockLedger {
    /**
     * Balance of a location.
     *
     * With `forUpdate` it locks the row until the end of the transaction. That is what
     * allows saying "there are 100" **and** that it is still true when writing: without the
     * lock, the check and the deduction are two different truths.
     * (requisito Q9).
     */
    fun balanceAt(productId: Long, locationId: Long, forUpdate: Boolean): Quantity

    /**
     * Applies the movement through the engine and returns the ledger identifier.
     *
     * If the balance is not enough, the database rejects it; the use case checks it beforehand
     * to be able to say **how much there is**.
     */
    fun apply(command: MovementCommand): Long

    /** The movement of that idempotency key, if it was already applied. */
    fun movementIdOf(clientUuid: UUID): Long?

    /** Product total, read from the projection and not from the history (R-P3). */
    fun productTotal(productId: Long): Quantity
}

/** A stock row: product, location and how much there is. */
data class StockLine(
    val productId: Long,
    val productCode: String,
    val productName: String,
    val locationId: Long,
    val locationDisplayName: String,
    val warehouseId: Long,
    val warehouseCode: String,
    val quantity: Quantity,
)

/** A ledger row, with the visible names already composed. */
data class KardexLine(
    val id: Long,
    val movedAt: java.time.Instant,
    val productCode: String,
    val productName: String,
    val moveType: String,
    val units: Quantity,
    val quantityAfter: Quantity,
    val fromLocationDisplayName: String?,
    val toLocationDisplayName: String?,
    val container: String?,
    val orderNumber: String?,
    val adjustmentReason: String?,
    val adjustmentOrigin: String?,
    val userId: Long?,
    val userName: String?,
    val note: String?,
)

/** Stock listing filters. `null` means "unfiltered", never "false". */
data class StockFilter(
    val productCode: ProductCode?,
    val location: LocationRef?,
    /** Defaults to `true`: nobody reads a listing of thousands of zero-rows. */
    val onlyWithStock: Boolean,
)

/** Ledger filters. */
data class KardexFilter(
    val productCode: ProductCode?,
    val moveType: MovementType?,
    val from: java.time.LocalDate?,
    val to: java.time.LocalDate?,
)

interface StockQueries {
    fun page(filter: StockFilter, request: PageRequest): Page<StockLine>
}

interface KardexQueries {
    fun page(filter: KardexFilter, request: PageRequest): Page<KardexLine>
}

/**
 * The hook into the **receiving invoice line** (M03).
 *
 * Locating goods that come from an invoice is not just moving balance: it is necessary to know which
 * line they came from so that the invoice can be closed, and rule B1 has to be respected
 * —a line with an unapproved difference cannot be located—. The port lives here, in
 * the inventory module, because it is the one that consumes it; the invoices adapter
 * implements it, since it is the one that has the lines.
 */
interface LocatingLines {

    fun find(lineId: Long): InvoiceLine?

    /**
     * Records what was located on the line: the accumulated quantity, its status and **the location
     * of destination**, which in the table is a foreign key.
     */
    fun markLocated(lineId: Long, line: InvoiceLine, locationId: Long, at: Instant): Unit
}
