package dev.axzy.stock.network.mapper

import dev.axzy.stock.domain.catalog.ValidatedProduct
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.network.dto.product.ProductDto

/**
 * Mappers del catálogo: `ProductDto` → objetos de valor y productos validados
 * del dominio.
 *
 * Lo que se gana: el `productCode` del transporte es una **cadena**, y en el
 * dominio es un [ProductCode], que ya no se puede construir mal (mayúsculas,
 * sin espacios, alfabeto real del catálogo). Validar en la frontera significa
 * que ni la caché local ni la UI pueden meter un código inválido en el sistema.
 */

/**
 * El código de producto del contrato, validado.
 *
 * Devuelve `null` si no cumple el formato **en lugar de normalizarlo**: el
 * importador del dominio rechaza la fila y dice por qué, y normalizar en
 * silencio esconde errores de captura que después aparecen como stock mal
 * contado.
 */
fun ProductDto.productCodeOrNull(): ProductCode? =
    ProductCode.validate(productCode).getOrNull()

/**
 * Convierte el producto del API en un producto validado del dominio.
 *
 * `rowNumber` existe en el modelo del dominio porque un producto validado es,
 * por origen, la fila de un archivo de importación. Al venir del API no hay
 * fila: se usa `0`, que el dominio interpreta como «no viene de un archivo» y
 * no como «fila cero» (nunca se usa para indexar nada).
 */
fun ProductDto.toValidatedProduct(rowNumber: Int = 0): ValidatedProduct? {
    val code = productCodeOrNull() ?: return null
    return ValidatedProduct(
        rowNumber = rowNumber,
        externalId = externalId?.toString(),
        code = code,
        name = name,
        unitMeasure = unitMeasure,
        stock = stock.toQuantity(),
    )
}

/** El nombre de la unidad de medida, tal como lo usa el importador de catálogo. */
val ProductDto.unitMeasureOrNull: String?
    get() = unitMeasure.takeIf { it.isNotBlank() }
