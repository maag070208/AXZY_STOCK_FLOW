package dev.axzy.stock.backend.catalog.product

import dev.axzy.stock.backend.catalog.JsonQuantity
import dev.axzy.stock.backend.catalog.ProductResponse
import dev.axzy.stock.backend.catalog.location.LocationRecord
import dev.axzy.stock.backend.catalog.productCode
import dev.axzy.stock.backend.catalog.warehouse.WarehouseRecord
/**
 * El mapeo de `ProductRecord` a su respuesta.
 *
 * **Vive con su recurso y no en los DTO compartidos.** Era el motivo por el que
 * `CatalogDtos.kt` necesitaba conocer los tipos de los TRES recursos: el archivo
 * compartido importaba `ProductRecord`, `LocationRecord` y `WarehouseRecord` solo
 * para escribir tres extensiones. Con el mapeo junto a su tipo, la capa compartida
 * deja de depender de las hojas y va en la dirección correcta: cada recurso usa
 * lo común, no al revés.
 */
fun ProductRecord.aDto() = ProductResponse(
    id = id,
    productCode = productCode,
    name = name,
    description = description,
    unitMeasure = unitMeasure,
    externalId = externalId,
    active = active,
    stock = JsonQuantity.fromDecimal(stock),
)
