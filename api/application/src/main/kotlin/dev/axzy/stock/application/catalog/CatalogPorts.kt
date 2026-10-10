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
 * Los puertos de salida del catálogo (M01).
 *
 * Un puerto **no expone tipos de la base**: ni `ResultSet` ni filas. Devuelve
 * entidades del dominio, y traducir la unicidad de un índice a un error de
 * negocio es trabajo del adaptador.
 */

/**
 * Un producto con su existencia total.
 *
 * El saldo es una **proyección de lectura** (`product_stock`), no un atributo del
 * catálogo: el producto no «tiene» stock, y por eso no vive en la entidad del
 * dominio ni se puede escribir desde aquí.
 */
data class ProductView(val product: Product, val stock: Quantity)

/** Una ubicación con el código de su bodega, que viene del `join` y no de la ubicación. */
data class LocationView(val location: Location, val warehouseCode: String)

/** Filtros del listado de productos. `null` es «sin filtrar», nunca «falso». */
data class ProductFilter(val query: String?, val active: Boolean?)

/** Filtros del listado de ubicaciones. */
data class LocationFilter(
    val query: String?,
    val warehouseId: Long?,
    val kind: LocationKind?,
    val active: Boolean?,
)

/** Un producto a crear. Todavía sin `id`. */
data class NewProduct(
    val code: ProductCode,
    val name: String,
    val description: String?,
    val unitMeasureId: Long,
    val unitMeasure: String,
    val externalId: Long? = null,
)

/** Una ubicación a crear. Todavía sin `id`. */
data class NewLocation(
    val warehouseId: Long,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int,
    val kind: LocationKind,
)

/** Una bodega a crear. Todavía sin `id`. */
data class NewWarehouse(
    val code: String,
    val name: String,
    val address: String?,
    val isDefault: Boolean,
)

/**
 * Resumen de un `upsert` masivo.
 *
 * `skipped` son las filas que ya existían **sin cambios**: es lo que hace visible
 * que la segunda importación no hizo nada (K2) en lugar de decir «1.398
 * actualizados» cuando no cambió ninguno.
 */
data class UpsertSummary(val created: Int, val updated: Int, val skipped: Int) {
    val persisted: Int get() = created + updated
}

interface Products {
    fun findById(id: Long): ProductView?

    /** Busca por clave de negocio, **incluidos los inactivos**: la unicidad no distingue de estado. */
    fun findByCode(code: ProductCode): Product?

    /** El saldo de un producto, leído de la proyección y no del historial (R-P3). */
    fun stockOf(productId: Long): Quantity

    fun page(filter: ProductFilter, request: PageRequest): Page<ProductView>

    fun insert(newProduct: NewProduct): ProductView

    fun update(product: Product): ProductView

    /** Alta o actualización por `product_code`. Idempotente por diseño (K2). */
    fun upsertAll(products: List<ValidatedProduct>): UpsertSummary
}

interface Locations {
    fun findById(id: Long): LocationView?

    fun page(filter: LocationFilter, request: PageRequest): Page<LocationView>

    /** Busca por su identidad natural: bodega + pasillo + localidad + posición. */
    fun find(warehouseId: Long, hall: String, code: String, position: String): LocationView?

    /** Existencias de la ubicación, para no desactivar una que todavía guarda mercancía. */
    fun stockOf(locationId: Long): Quantity

    fun insert(newLocation: NewLocation): LocationView

    fun update(location: Location): LocationView

    /** La ubicación de recepción de una bodega. `null` si no existe: es un dato roto. */
    fun receivingOf(warehouseId: Long): LocationView?
}

interface Warehouses {
    fun list(query: String?, active: Boolean?): List<Warehouse>

    fun findById(id: Long): Warehouse?

    fun findByCode(code: String): Warehouse?

    fun insert(newWarehouse: NewWarehouse): Warehouse

    fun update(warehouse: Warehouse): Warehouse

    /**
     * Quita la marca de predeterminada a la que la tenga.
     *
     * Va **en la misma transacción** que la que la pone: entre las dos no puede
     * haber dos predeterminadas ni ninguna, porque el índice parcial de la base
     * lo prohibiría y la operación fallaría a mitad.
     */
    fun clearDefault()
}

interface UnitsOfMeasure {
    fun all(): List<UnitOfMeasure>
}
