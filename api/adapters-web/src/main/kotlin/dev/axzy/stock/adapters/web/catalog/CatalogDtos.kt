package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.application.catalog.CatalogImportResult
import dev.axzy.stock.application.catalog.LocationView
import dev.axzy.stock.application.catalog.ProductView
import dev.axzy.stock.application.shared.Page
import dev.axzy.stock.domain.catalog.Warehouse
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal

/**
 * El contrato HTTP del catálogo (docs/api/catalogo.md).
 *
 * Las cantidades viajan como **número JSON con 3 decimales** (`5193.000`) y no
 * como cadena, que es la excepción deliberada del catálogo: el resto de la API
 * manda las cantidades como texto para que el cliente no las sume en coma
 * flotante. Aquí el cliente las muestra, y el `BigDecimal` conserva la escala.
 */

data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
)

fun <T, R> Page<T>.toDto(transform: (T) -> R): PageDto<R> =
    PageDto(items.map(transform), page, size, totalItems, totalPages)

/** `5193.000`: número JSON, con la escala fija de la base. */
internal fun Quantity.toJson(): BigDecimal = BigDecimal.valueOf(value, Quantity.SCALE)

data class ProductDto(
    val id: Long,
    val productCode: String,
    val name: String,
    val description: String?,
    val unitMeasure: String,
    val externalId: Long?,
    val active: Boolean,
    val stock: BigDecimal,
)

fun ProductView.toDto(): ProductDto = ProductDto(
    id = product.id,
    productCode = product.code.value,
    name = product.name,
    description = product.description,
    unitMeasure = product.unitMeasure,
    externalId = product.externalId,
    active = product.active,
    stock = stock.toJson(),
)

data class LocationDto(
    val id: Long,
    val warehouseId: Long,
    val warehouseCode: String,
    val hall: String,
    val code: String,
    val position: String,
    val levels: Int,
    val kind: String,
    val active: Boolean,
    /** **Derivado**: lo compone el servidor. El cliente nunca lo arma. */
    val displayName: String,
)

fun LocationView.toDto(): LocationDto = LocationDto(
    id = location.id,
    warehouseId = location.warehouseId,
    warehouseCode = warehouseCode,
    hall = location.hall,
    code = location.code,
    position = location.position,
    levels = location.levels,
    kind = location.kind.name,
    active = location.active,
    displayName = location.displayName,
)

data class WarehouseDto(
    val id: Long,
    val code: String,
    val name: String,
    val address: String?,
    val isDefault: Boolean,
    val active: Boolean,
)

fun Warehouse.toDto(): WarehouseDto = WarehouseDto(id, code, name, address, isDefault, active)

data class RejectionDto(val rowNumber: Int, val reason: String)

data class ImportReportDto(
    val dryRun: Boolean,
    val totalRows: Int,
    val accepted: Int,
    val rejected: Int,
    val totalStock: BigDecimal,
    val zeroStockCount: Int,
    val unitMeasures: List<String>,
    val rejections: List<RejectionDto>,
    /** Productos nuevos. La segunda importación del mismo archivo dice `0`. */
    val created: Int,
    /** Productos que ya existían **con cambios**. */
    val updated: Int,
    /** Productos que ya existían igual: es lo que hace visible que no se hizo nada. */
    val skipped: Int,
    /** Filas escritas de verdad: `0` en una simulación. */
    val persisted: Int,
    /** Siempre `false` en esta fase: el archivo no dice en qué estantería está cada pieza. */
    val stockPersisted: Boolean,
)

fun CatalogImportResult.toDto(): ImportReportDto = ImportReportDto(
    dryRun = dryRun,
    totalRows = totalRows,
    accepted = accepted,
    rejected = rejected,
    totalStock = totalStock.toJson(),
    zeroStockCount = zeroStockCount,
    unitMeasures = unitMeasures,
    rejections = rejections.map { RejectionDto(it.rowNumber, it.reason) },
    created = created,
    updated = updated,
    skipped = skipped,
    persisted = persisted,
    stockPersisted = stockPersisted,
)

/**
 * Las filas rechazadas también viajan en el cuerpo del error, indexadas por número
 * de fila: sin ellas, un archivo de 1.398 filas con un error es irresoluble.
 */
fun DomainError.ImportInvalidRows.toDetails(): Map<String, String> =
    rows.take(MAX_DETAILS).associate { "fila_${it.rowNumber}" to it.reason }

private const val MAX_DETAILS = 50

// ── Cuerpos de las peticiones ───────────────────────────────────────────────

/** Los campos son opcionales para que el caso de uso diga CUÁL falta (`FIELD_REQUIRED`). */
data class ProductRequest(
    val productCode: String? = null,
    val name: String? = null,
    val description: String? = null,
    val unitMeasure: String? = null,
)

data class LocationRequest(
    val warehouseId: Long? = null,
    val hall: String? = null,
    val code: String? = null,
    val position: String? = null,
    val levels: Int? = null,
    val kind: String? = null,
    val active: Boolean? = null,
)

data class WarehouseRequest(
    val code: String? = null,
    val name: String? = null,
    val address: String? = null,
    val isDefault: Boolean? = null,
)

/** El motivo de una baja lógica: en el cuerpo o en la query (ver el contrato). */
data class ReasonRequest(val reason: String? = null)
