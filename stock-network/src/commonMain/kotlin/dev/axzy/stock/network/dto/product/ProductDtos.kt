package dev.axzy.stock.network.dto.product

import kotlinx.serialization.Serializable

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CATÁLOGO — DTOs de `/v1/products`
 *
 * Contrato: CONTRATO_API.md §3.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Un producto del catálogo.
 *
 * `stock` viene **incluido en el listado** aunque sea un dato derivado: es lo
 * que más se consulta y evitar una petición por producto importa con 1.398
 * referencias. Es la suma de `stock_by_location` + `stock_in_transit`, y el
 * servidor es quien la calcula; el cliente no la recalcula nunca.
 *
 * `stock` es un número decimal con 3 decimales (nunca cadena): el legacy
 * mezclaba `Decimal` de Prisma con `number` de JSON y de ahí venían los
 * descuadres de redondeo.
 */
@Serializable
data class ProductDto(
    val id: Long,
    val productCode: String,
    val name: String,
    val description: String? = null,
    val unitMeasure: String = "PZA",
    /** Identificador en el ERP de origen. Puede no existir. */
    val externalId: Long? = null,
    val active: Boolean = true,
    val stock: Double = 0.0,
)

/** Cuerpo de `POST /v1/products`. `409 DUPLICATE_PRODUCT_CODE` si ya existe. */
@Serializable
data class CreateProductRequest(
    val productCode: String,
    val name: String,
    val description: String? = null,
    val unitMeasure: String,
)

/**
 * Cuerpo de `PUT /v1/products/{id}`.
 *
 * **El contrato no fija la forma del `PUT`** (§3 solo documenta `GET|PUT|DELETE`
 * sin cuerpo). Se interpreta como «los mismos campos del alta, más `active`»,
 * porque el `DELETE` es lógico y la reactivación tiene que pasar por aquí. Si el
 * backend acaba usando otro conjunto de campos, este es el DTO a corregir: está
 * marcado con `AMBIGUO` a propósito.
 */
@Serializable
data class UpdateProductRequest(
    val productCode: String,
    val name: String,
    val description: String? = null,
    val unitMeasure: String,
    val active: Boolean = true,
)

/**
 * Cuerpo del `DELETE /v1/products/{id}`.
 *
 * El borrado es **lógico** (`active = false`) y exige `reason`. Un producto con
 * stock no se desactiva sin forzarlo: si no, se desactivaría una referencia que
 * sigue teniendo existencias y el inventario quedaría con mercancía huérfana.
 *
 * AMBIGUO: el contrato dice «exige `reason` en el cuerpo» y menciona forzar
 * explícitamente, pero no nombra el campo del forzado. Se usa `force`.
 */
@Serializable
data class DeactivateProductRequest(
    val reason: String,
    val force: Boolean = false,
)

/**
 * Informe de `POST /v1/products/import`.
 *
 * Es el entregable del `dryRun`: **el informe sin escribir nada**. Se modela
 * aquí, en transporte, y se traduce al informe del dominio
 * (`ImportReport`) con un mapper explícito.
 */
@Serializable
data class ImportReportDto(
    val dryRun: Boolean = false,
    val totalRows: Int = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val totalStock: Double = 0.0,
    val zeroStockCount: Int = 0,
    val unitMeasures: List<String> = emptyList(),
    val rejections: List<ImportRejectionDto> = emptyList(),
)

/**
 * Una fila rechazada del import.
 *
 * `rowNumber` es **obligatorio**: sin el número de fila el usuario no puede
 * corregir el archivo, y el import se convierte en «algo falló, busca tú dónde».
 */
@Serializable
data class ImportRejectionDto(
    val rowNumber: Int,
    val productCode: String? = null,
    val reason: String,
)
