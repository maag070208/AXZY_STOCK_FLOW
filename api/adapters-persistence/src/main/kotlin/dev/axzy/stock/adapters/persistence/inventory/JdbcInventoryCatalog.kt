package dev.axzy.stock.adapters.persistence.inventory

import dev.axzy.stock.application.catalog.Locations
import dev.axzy.stock.application.catalog.Products
import dev.axzy.stock.application.catalog.Warehouses
import dev.axzy.stock.application.inventory.InventoryCatalog
import dev.axzy.stock.application.inventory.LocationRef
import dev.axzy.stock.domain.catalog.Location
import dev.axzy.stock.domain.catalog.Product
import dev.axzy.stock.domain.value.ProductCode

/**
 * Resolves product and location by their business key, **reusing the ports
 * of the catalog** instead of rewriting the queries.
 *
 * The translation of the visible name to columns lives here and is the only one in the system:
 * `04-B-C` are three columns, `RECEIVING` and `UNASSIGNED` are the two special
 * special locations that the migration seeds. The names of those two columns
 * (`RECEIVING`, `UNASSIGNED`) are in migration `V6` and are **not** the visible name:
 * the visible name of `UNASSIGNED` is composed by the domain (`UNASSIGNED`).
 */
class JdbcInventoryCatalog(
    private val products: Products,
    private val locations: Locations,
    private val warehouses: Warehouses,
) : InventoryCatalog {

    override fun activeProduct(code: ProductCode): Product? =
        products.findByCode(code)?.takeIf { it.active }

    override fun activeLocation(warehouseId: Long, ref: LocationRef): Location? {
        val (hall, code, position) = columnsOf(ref)
        return locations.find(warehouseId, hall, code, position)
            ?.location
            ?.takeIf { it.active }
    }

    override fun defaultWarehouseId(): Long =
        warehouses.defaultWarehouse()?.id
            ?: error("There is no default warehouse. The schema seeds one (V3); without it no movement has a warehouse.")

    private fun columnsOf(ref: LocationRef): Triple<String, String, String> = when (ref) {
        is LocationRef.Shelf -> Triple(ref.hall, ref.code, ref.position)
        LocationRef.Receiving -> Triple(RECEIVING_COLUMNS, RECEIVING_COLUMNS, RECEIVING_COLUMNS)
        LocationRef.Unassigned -> Triple(UNASSIGNED_COLUMNS, UNASSIGNED_COLUMNS, UNASSIGNED_COLUMNS)
    }

    private companion object {
        /** What the receiving location is called in the columns (`V3`). */
        const val RECEIVING_COLUMNS = "RECEIVING"

        /** What the unassigned balance is called in the columns (`V6`); its visible name is `UNASSIGNED`. */
        const val UNASSIGNED_COLUMNS = "UNASSIGNED"
    }
}
