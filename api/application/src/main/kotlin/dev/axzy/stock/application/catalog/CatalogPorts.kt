package dev.axzy.stock.application.catalog

import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.catalog.Product
import dev.axzy.stock.domain.catalog.UnitOfMeasure
import dev.axzy.stock.domain.catalog.ValidatedProduct
import dev.axzy.stock.domain.catalog.Warehouse
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity

/**
 * The catalog's output ports (M01).
 *
 * A port **does not expose database types**: neither `ResultSet` nor rows. It returns
 * domain entities, and translating an index's uniqueness into a
 * business error is the adapter's job.
 */

/**
 * A product with its total stock.
 *
 * The balance is a **read projection** (`product_stock`), not an attribute of the
 * catalog: the product does not "have" stock, and that is why it does not live in the entity of the
 * domain nor can it be written from here.
 */
data class ProductView(val product: Product, val stock: Quantity)

/** A location with its warehouse code, which comes from the `join` and not from the location. */
data class LocationView(val location: Location, val warehouseCode: String)

/** Product listing filters. `null` means "unfiltered", never "false". */
data class ProductFilter(val query: String?, val active: Boolean?)

/** Location listing filters. */
data class LocationFilter(
    val query: String?,
    val warehouseId: Long?,
    val kind: LocationKind?,
    val active: Boolean?,
)

/** A product to create. Still without an `id`. */
data class NewProduct(
    val code: ProductCode,
    val name: String,
    val description: String?,
    val unitMeasureId: Long,
    val unitMeasure: String,
    val externalId: Long? = null,
)

/** A location to create. Still without an `id`. */
data class NewLocation(
    val warehouseId: Long,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int,
    val kind: LocationKind,
)

/** A warehouse to create. Still without an `id`. */
data class NewWarehouse(
    val code: String,
    val name: String,
    val address: String?,
    val isDefault: Boolean,
)

/**
 * Summary of a bulk `upsert`.
 *
 * `skipped` are the rows that already existed **unchanged**: it is what makes visible
 * that the second import did nothing (K2), instead of saying "1,398
 * updated" when none changed.
 */
data class UpsertSummary(val created: Int, val updated: Int, val skipped: Int) {
    val persisted: Int get() = created + updated
}

interface Products {
    fun findById(id: Long): ProductView?

    /** Looks up by business key, **including the inactive ones**: uniqueness does not distinguish by status. */
    fun findByCode(code: ProductCode): Product?

    /** A product's balance, read from the projection and not from the history (R-P3). */
    fun stockOf(productId: Long): Quantity

    fun page(filter: ProductFilter, request: PageRequest): Page<ProductView>

    fun insert(newProduct: NewProduct): ProductView

    fun update(product: Product): ProductView

    /** Create or update by `product_code`. Idempotent by design (K2). */
    fun upsertAll(products: List<ValidatedProduct>): UpsertSummary
}

interface Locations {
    fun findById(id: Long): LocationView?

    fun page(filter: LocationFilter, request: PageRequest): Page<LocationView>

    /** Looks up by its natural identity: warehouse + hall + code + position. */
    fun find(warehouseId: Long, hall: String, code: String, position: String): LocationView?

    /** The location's stock, so that a location still holding goods is not deactivated. */
    fun stockOf(locationId: Long): Quantity

    fun insert(newLocation: NewLocation): LocationView

    fun update(location: Location): LocationView

    /** A warehouse's receiving location. `null` if it does not exist: it is broken data. */
    fun receivingOf(warehouseId: Long): LocationView?
}

interface Warehouses {
    fun list(query: String?, active: Boolean?): List<Warehouse>

    fun findById(id: Long): Warehouse?

    fun findByCode(code: String): Warehouse?

    /**
     * The default warehouse.
     *
     * The partial index `warehouses_one_default_idx` guarantees there is **at
     * most** one; that there is at least one is guaranteed by the system startup, which
     * seeds the first one. Inventory movements use it, which do not carry a
     * warehouse in the body: moving between warehouses is a transfer (M05).
     */
    fun defaultWarehouse(): Warehouse?

    fun insert(newWarehouse: NewWarehouse): Warehouse

    fun update(warehouse: Warehouse): Warehouse

    /**
     * Removes the default flag from the one that has it.
     *
     * It goes **in the same transaction** as the one that sets it: between the two there cannot be
     * two defaults nor none, because the database's partial index
     * would forbid it and the operation would fail halfway.
     */
    fun clearDefault()
}

interface UnitsOfMeasure {
    fun all(): List<UnitOfMeasure>
}
