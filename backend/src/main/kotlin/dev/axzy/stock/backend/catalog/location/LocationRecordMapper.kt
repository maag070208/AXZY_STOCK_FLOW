package dev.axzy.stock.backend.catalog.location

import dev.axzy.stock.backend.catalog.LocationResponse
import dev.axzy.stock.backend.catalog.product.ProductRecord
import dev.axzy.stock.backend.catalog.warehouse.WarehouseRecord
/**
 * El mapeo de `LocationRecord` a su respuesta.
 *
 * **Vive con su recurso y no en los DTO compartidos.** Era el motivo por el que
 * `CatalogDtos.kt` necesitaba conocer los tipos de los TRES recursos: el archivo
 * compartido importaba `ProductRecord`, `LocationRecord` y `WarehouseRecord` solo
 * para escribir tres extensiones. Con el mapeo junto a su tipo, la capa compartida
 * deja de depender de las hojas y va en la dirección correcta: cada recurso usa
 * lo común, no al revés.
 */
fun LocationRecord.aDto() = LocationResponse(
    id = id,
    warehouseId = warehouseId,
    warehouseCode = warehouseCode,
    hall = hall,
    code = code,
    position = position,
    levels = levels,
    kind = kind,
    active = active,
    displayName = displayName,
)
