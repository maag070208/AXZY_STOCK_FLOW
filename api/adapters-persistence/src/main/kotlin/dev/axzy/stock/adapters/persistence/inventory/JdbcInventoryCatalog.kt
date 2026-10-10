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
 * Resuelve producto y ubicación por su clave de negocio, **reutilizando los puertos
 * del catálogo** en lugar de volver a escribir las consultas.
 *
 * La traducción de nombre visible a columnas vive aquí y es la única del sistema:
 * `04-B-C` son tres columnas, `RECEPCION` y `SIN-UBICAR` son las dos ubicaciones
 * especiales que siembra la migración. Los nombres de esas dos columnas
 * (`RECEPCION`, `SINUBICAR`) están en la migración `V6` y **no** son el nombre
 * visible: el de `UNASSIGNED` lo compone el dominio (`SIN-UBICAR`).
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
            ?: error("No hay bodega predeterminada. El esquema siembra una (V3); sin ella ningún movimiento tiene bodega.")

    private fun columnsOf(ref: LocationRef): Triple<String, String, String> = when (ref) {
        is LocationRef.Shelf -> Triple(ref.hall, ref.code, ref.position)
        LocationRef.Receiving -> Triple(RECEIVING_COLUMNS, RECEIVING_COLUMNS, RECEIVING_COLUMNS)
        LocationRef.Unassigned -> Triple(UNASSIGNED_COLUMNS, UNASSIGNED_COLUMNS, UNASSIGNED_COLUMNS)
    }

    private companion object {
        /** Cómo se llama la recepción en las columnas (`V3`). */
        const val RECEIVING_COLUMNS = "RECEPCION"

        /** Cómo se llama el saldo sin ubicar en las columnas (`V6`); su nombre visible es `SIN-UBICAR`. */
        const val UNASSIGNED_COLUMNS = "SINUBICAR"
    }
}
