package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.application.catalog.CatalogImportResult
import dev.axzy.stock.application.catalog.LocationView
import dev.axzy.stock.application.catalog.ProductView
import dev.axzy.stock.domain.catalog.Warehouse
import dev.axzy.stock.domain.error.DomainError
import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal

/**
 * The HTTP contract of the catalog (docs/api/catalogo.md).
 *
 * Quantities travel as a **JSON number with 3 decimals** (`5193.000`) and not
 * as a string, which is the deliberate exception in the catalog: the rest of the API
 * sends quantities as text so that the client does not add them in floating point.
 * floating point. Here the client displays them, and the `BigDecimal` keeps the scale.
 */

/** `5193.000`: JSON number, with the database's fixed scale. */
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
    /** **Derived**: the server composes it. The client never builds it. */
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
    /** New products. The second import of the same file says `0`. */
    val created: Int,
    /** Products that already existed **with changes**. */
    val updated: Int,
    /** Products that already existed unchanged: it is what makes visible that nothing was done. */
    val skipped: Int,
    /** Rows actually written: `0` in a simulation. */
    val persisted: Int,
    /** Always `false` in this phase: the file does not say which shelf each piece is on. */
    val stockPersisted: Boolean,
    val stockLoaded: Int,
    val stockSkipped: Int,
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
    stockLoaded = stockLoaded,
    stockSkipped = stockSkipped,
)

/**
 * The rejected rows also travel in the error body, indexed by row number:
 * without them, a file of 1,398 rows with one error is unresolvable.
 */
fun DomainError.ImportInvalidRows.toDetails(): Map<String, String> =
    rows.take(MAX_DETAILS).associate { "row_${it.rowNumber}" to it.reason }

private const val MAX_DETAILS = 50

// ── Request bodies ───────────────────────────────────────────────

/** The fields are optional so that the use case says WHICH one is missing (`FIELD_REQUIRED`). */
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

/** The reason for a soft delete: in the body or in the query (see the contract). */
data class ReasonRequest(val reason: String? = null)
