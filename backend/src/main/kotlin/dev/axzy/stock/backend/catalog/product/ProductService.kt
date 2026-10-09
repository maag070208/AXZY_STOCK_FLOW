package dev.axzy.stock.backend.catalog.product

import dev.axzy.stock.backend.application.shared.Page
import dev.axzy.stock.backend.application.shared.PageRequest
import dev.axzy.stock.backend.catalog.UnitOfMeasureRepository
import dev.axzy.stock.backend.catalog.JsonQuantity
import dev.axzy.stock.backend.catalog.productCode
import dev.axzy.stock.backend.catalog.productNotFound
import dev.axzy.stock.backend.catalog.textRequired
import dev.axzy.stock.backend.catalog.translatingDbErrors
import dev.axzy.stock.backend.catalog.unitUnknown
import dev.axzy.stock.backend.config.logger
import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.UnprocessableException

/**
 * Reglas del catálogo de productos.
 *
 * Las rutas traducen HTTP a estos métodos y nada más. Las reglas viven aquí y no en el
 * handler: así una regla que afecte a más de un recurso tiene sitio, y se prueba sin
 * levantar Ktor.
 */
class ProductService(
    private val products: ProductRepository,
    private val units: UnitOfMeasureRepository,
) {

    fun list(page: PageRequest, search: String?, active: Boolean?): Page<ProductRecord> =
        products.list(page, search, active)

    fun byId(id: Long): ProductRecord = products.byId(id) ?: throw productNotFound(id)

    fun create(rawCode: String, name: String, description: String?, unitMeasure: String): ProductRecord {
        val code = productCode(rawCode)
        val validName = textRequired(name, "name", 120)
        val unit = units.byCode(unitMeasure) ?: throw unitUnknown(unitMeasure)

        // El prechequeo da el 409 legible en el caso normal; la garantía la tiene el índice
        // único de la base, que es el que no tiene carrera.
        if (products.existsCode(code)) {
            throw ConflictException(
                code = "DUPLICATE_PRODUCT_CODE",
                message = "Ya existe un producto con el código '$code'.",
            )
        }

        return translatingDbErrors {
            products.create(
                data = NewProduct(
                    code = code,
                    name = validName,
                    description = description?.trim()?.takeIf { it.isNotEmpty() },
                    unitMeasureCode = unit.code,
                ),
                unitOfMeasureId = unit.id,
            )
        }
    }

    /**
     * Un PUT reemplaza el recurso. [sentCode] es opcional: tolerar el código igual permite el
     * ida y vuelta de un cliente que recibe el objeto del GET y lo devuelve modificado.
     * Cambiarlo se rechaza: está impreso en las etiquetas y en los estantes.
     */
    fun update(id: Long, sentCode: String?, name: String, description: String?, unitMeasure: String): ProductRecord {
        val current = products.byId(id) ?: throw productNotFound(id)

        sentCode?.let { sent ->
            if (sent != current.productCode) {
                throw UnprocessableException(
                    code = "PRODUCT_CODE_IMMUTABLE",
                    message = "El código de producto no se puede cambiar ('${current.productCode}' → " +
                        "'$sent'): es el código de barras impreso en las etiquetas y en los " +
                        "estantes, y cambiarlo obliga a reetiquetar el almacén. Existe una acción " +
                        "específica para reasignar códigos.",
                    details = mapOf("productCode" to sent),
                )
            }
        }

        val validName = textRequired(name, "name", 120)
        val unit = units.byCode(unitMeasure) ?: throw unitUnknown(unitMeasure)

        return products.update(
            id = id,
            changes = ProductChanges(
                name = validName,
                // En un PUT el cuerpo reemplaza al recurso: si no viene descripción, se guarda
                // sin descripción. Es la única forma de borrarla.
                description = description?.trim()?.takeIf { it.isNotEmpty() },
                unitOfMeasure = unit.code,
            ),
            unitOfMeasureId = unit.id,
        ) ?: throw productNotFound(id)
    }

    /**
     * Desactiva el producto. Lo que se valida aquí, y no en la ruta, es el estado del recurso:
     * ya inactivo, o con existencias.
     */
    fun disable(id: Long, reason: String) {
        val product = products.byId(id) ?: throw productNotFound(id)

        if (!product.active) {
            throw ConflictException(
                code = "PRODUCT_ALREADY_INACTIVE",
                message = "El producto '$id' ya estaba desactivado. Volver a desactivarlo " +
                    "sobrescribiría el motivo del primer borrado y perdería la traza.",
            )
        }

        // Un producto con existencias no se desactiva: el stock quedaría en un producto invisible
        // para el listado. Primero se ajusta a cero (con motivo) o se traslada.
        if (product.stock.signum() != 0) {
            throw ConflictException(
                code = "PRODUCT_HAS_STOCK",
                message = "El producto '${product.productCode}' tiene existencias y no se " +
                    "puede desactivar. Ajusta o traslada el stock primero.",
                details = mapOf("stock" to JsonQuantity.fromDecimal(product.stock).asText()),
            )
        }

        if (!products.disable(id)) {
            // Carrera: otra petición lo desactivó entre la lectura y el UPDATE.
            throw ConflictException(
                code = "PRODUCT_ALREADY_INACTIVE",
                message = "El producto '$id' ya estaba desactivado.",
            )
        }

        // El motivo no tiene columna en `products` y el esquema no se toca: se registra para que
        // quede rastro del porqué. La traza con autor y fecha corresponde a `audit_log`.
        logger.info("Producto {} ({}) desactivado. Motivo declarado: {}", id, product.productCode, reason)
    }
}
