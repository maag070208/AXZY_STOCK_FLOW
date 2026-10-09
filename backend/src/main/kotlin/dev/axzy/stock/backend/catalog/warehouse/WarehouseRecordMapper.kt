package dev.axzy.stock.backend.catalog.warehouse

import dev.axzy.stock.backend.catalog.WarehouseResponse
import dev.axzy.stock.backend.catalog.location.LocationRecord
import dev.axzy.stock.backend.catalog.product.ProductRecord
/**
 * El mapeo de `WarehouseRecord` a su respuesta.
 *
 * **Vive con su recurso y no en los DTO compartidos.** Era el motivo por el que
 * `CatalogDtos.kt` necesitaba conocer los tipos de los TRES recursos: el archivo
 * compartido importaba `ProductRecord`, `LocationRecord` y `WarehouseRecord` solo
 * para escribir tres extensiones. Con el mapeo junto a su tipo, la capa compartida
 * deja de depender de las hojas y va en la dirección correcta: cada recurso usa
 * lo común, no al revés.
 */
fun WarehouseRecord.aDto() = WarehouseResponse(
    id = id,
    code = code,
    name = name,
    isDefault = isDefault,
    active = active,
)
