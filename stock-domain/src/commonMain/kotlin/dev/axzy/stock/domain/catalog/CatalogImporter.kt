package dev.axzy.stock.domain.catalog

import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CATALOG IMPORTER
 *
 * Initial load of products from the real inventory file. Phase 0:
 * pure implementation, with no database, so that the rules are tested against
 * the **real file** (1,398 products, 10,914,130 units) instead of
 * contra datos inventados.
 *
 * **Strict rules by design.** The file has excellent quality —zero
 * nulls, zero negatives, zero decimals, unique codes— so the importer
 * can **reject a row** instead of trying to guess. A tolerant
 * importer hides capture errors that later reappear as miscounted stock.
 * contado.
 *
 * **Mandatory dry-run mode.** The whole import can be simulated and the report seen
 * **without writing anything**. Without that, the first run on real data
 * is an act of faith.
 *
 * Referencia: docs/historico/PLAN_ALMACEN_INTEGRAL.md §3.5.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** A raw row of the file, as it arrives. */
data class CatalogRow(
    val rowNumber: Int,
    val externalId: String,
    val productCode: String,
    val name: String,
    val unitMeasure: String,
    val stock: String,
)

/** Why a row was rejected. */
sealed interface RowRejection {
    val rowNumber: Int
    val reason: String

    data class BlankRow(override val rowNumber: Int) : RowRejection {
        override val reason = "Completely empty row"
    }

    data class InvalidProductCode(
        override val rowNumber: Int,
        val code: String,
        val detail: String,
    ) : RowRejection {
        override val reason = "Invalid product code ('$code'): $detail"
    }

    data class BlankName(override val rowNumber: Int, val code: String) : RowRejection {
        override val reason = "Product '$code' has no name"
    }

    data class UnknownUnitMeasure(
        override val rowNumber: Int,
        val code: String,
        val unit: String,
    ) : RowRejection {
        override val reason =
            "Unknown unit of measure '$unit' on '$code'. It must exist in the catalog."
    }

    data class InvalidStock(
        override val rowNumber: Int,
        val code: String,
        val raw: String,
        val detail: String,
    ) : RowRejection {
        override val reason = "Invalid stock in '$code' ('$raw'): $detail"
    }

    /**
     * The `external_id` is not numeric.
     *
     * The row is rejected instead of being stored as null: it is the cross-reference with
     * the ERP, and losing it in silence leaves products that cannot later be
     * reconciled with the system they came from.
     */
    data class InvalidExternalId(
        override val rowNumber: Int,
        val code: String,
        val raw: String,
    ) : RowRejection {
        override val reason =
            "Invalid external identifier in '$code' ('$raw'): it must be numeric or empty"
    }

    data class DuplicateCode(
        override val rowNumber: Int,
        val code: String,
        val firstSeenAtRow: Int,
    ) : RowRejection {
        override val reason =
            "Code '$code' is duplicated (it already appeared in row $firstSeenAtRow)"
    }
}

/** A product already validated, ready to persist. */
data class ValidatedProduct(
    val rowNumber: Int,
    val externalId: String?,
    val code: ProductCode,
    val name: String,
    val unitMeasure: String,
    val stock: Quantity,
)

/**
 * Import report.
 *
 * It is the deliverable of the dry-run: **what is presented before writing anything**.
 * It includes the reconciliation against the known figures of the file, because an import
 * that "worked" but does not reconcile is an import that failed silently.
 */
data class ImportReport(
    val totalRows: Int,
    val accepted: List<ValidatedProduct>,
    val rejected: List<RowRejection>,
    val isDryRun: Boolean,
) {
    val acceptedCount: Int get() = accepted.size
    val rejectedCount: Int get() = rejected.size

    /** Sum of the stock of the accepted rows. */
    val totalStock: Quantity
        get() = accepted.fold(Quantity.ZERO) { acc, p -> acc + p.stock }

    /** Accepted products with zero stock. */
    val zeroStockCount: Int get() = accepted.count { it.stock.isZero }

    /** Units of measure found, sorted. */
    val unitMeasures: List<String> get() = accepted.map { it.unitMeasure }.distinct().sorted()

    val hasRejections: Boolean get() = rejected.isNotEmpty()

    /** Readable report, to present to the business before importing. */
    fun render(): String = buildString {
        appendLine("Catalog import report${if (isDryRun) " (DRY RUN)" else ""}")
        appendLine("─".repeat(58))
        appendLine("Rows read:          $totalRows")
        appendLine("Accepted products:     $acceptedCount")
        appendLine("Rejected rows:          $rejectedCount")
        appendLine("Total stock:            $totalStock")
        appendLine("Products at zero:     $zeroStockCount")
        appendLine("Units of measure:    ${unitMeasures.joinToString(", ")}")
        if (rejected.isNotEmpty()) {
            appendLine()
            appendLine("Rejected rows (first 20):")
            rejected.take(20).forEach { appendLine("  · fila ${it.rowNumber}: ${it.reason}") }
            if (rejected.size > 20) appendLine("  … and ${rejected.size - 20} more")
        }
        if (isDryRun) {
            appendLine()
            appendLine("Nothing was written: it is a simulation.")
        }
    }

    /**
     * Checks that the import reconciles with the known figures of the file.
     *
     * Without this check, an import that discards rows in silence "passes" and
     * the discrepancy appears later, when there is no way to know what was
     * lost.
     */
    fun matchesExpectations(
        expectedProducts: Int,
        expectedTotalStock: Quantity,
        expectedRejections: Int = 0,
    ): List<String> = buildList {
        if (acceptedCount != expectedProducts) {
            add("Expected $expectedProducts products and $acceptedCount were accepted")
        }
        if (totalStock != expectedTotalStock) {
            add("Expected $expectedTotalStock units and $totalStock were obtained")
        }
        if (rejectedCount != expectedRejections) {
            add("Expected $expectedRejections rejected rows and there were $rejectedCount")
        }
    }
}

/**
 * The importer.
 *
 * @param knownUnitMeasures valid units. If a row brings another one, it is rejected:
 *   it is the defense against the classic `KG`/`Kg`/`KGS` as three different units.
 */
class CatalogImporter(
    private val knownUnitMeasures: Set<String> = setOf("PZA"),
) {

    /** Known figures of the real file, for the automatic reconciliation. */
    companion object {
        const val EXPECTED_PRODUCTS = 1398
        val EXPECTED_TOTAL_STOCK: Quantity = Quantity.of(10_914_130)
        const val EXPECTED_ZERO_STOCK_PRODUCTS = 334
    }

    /**
     * Processes the rows.
     *
     * @param dryRun if it is `true`, **nothing is persisted**. It is the conceptual
     *   default mode: the first run on real data must be
     *   a simulation.
     */
    fun import(rows: List<CatalogRow>, dryRun: Boolean = true): ImportReport {
        val accepted = mutableListOf<ValidatedProduct>()
        val rejected = mutableListOf<RowRejection>()
        val seenCodes = mutableMapOf<String, Int>()

        rows.forEach { row ->
            // ── Empty row ──────────────────────────────────────────────────
            if (row.productCode.isBlank() && row.name.isBlank() && row.stock.isBlank()) {
                rejected += RowRejection.BlankRow(row.rowNumber)
                return@forEach
            }

            // ── Product code ──────────────────────────────────────────────────
            // It is validated with the domain value object: the real alphabet is
            // 0-9 and A-Z, with no lowercase or spaces. If a row brings them, it is
            // REJECTED indicating the row number; it is not corrected in silence.
            val codeResult = ProductCode.validate(row.productCode)
            val code = codeResult.getOrNull()
            if (code == null) {
                rejected += RowRejection.InvalidProductCode(
                    rowNumber = row.rowNumber,
                    code = row.productCode,
                    detail = codeResult.exceptionOrNull()?.message ?: "invalid format",
                )
                return@forEach
            }

            // ── Duplicado ──────────────────────────────────────────────────
            // `product_code` is the identity key for the upsert.
            val alreadySeen = seenCodes[code.value]
            if (alreadySeen != null) {
                rejected += RowRejection.DuplicateCode(row.rowNumber, code.value, alreadySeen)
                return@forEach
            }
            seenCodes[code.value] = row.rowNumber

            // ── ERP identifier ──────────────────────────────────────────────────
            // Empty is null (the product is born in this API); with a value, it has to be
            // numeric. Storing it as null on an odd value would lose the cross-reference
            // without anyone finding out.
            val externalId = row.externalId.trim().ifBlank { null }
            if (externalId != null && externalId.toLongOrNull() == null) {
                rejected += RowRejection.InvalidExternalId(row.rowNumber, code.value, row.externalId)
                return@forEach
            }

            // ── Nombre ─────────────────────────────────────────────────────
            if (row.name.isBlank()) {
                rejected += RowRejection.BlankName(row.rowNumber, code.value)
                return@forEach
            }

            // ── Unit of measure ────────────────────────────────────────────────
            val unit = row.unitMeasure.trim().uppercase()
            if (unit !in knownUnitMeasures) {
                rejected += RowRejection.UnknownUnitMeasure(row.rowNumber, code.value, row.unitMeasure)
                return@forEach
            }

            // ── Existencias ────────────────────────────────────────────────
            val stockResult = Quantity.parse(row.stock)
            val stock = stockResult.getOrNull()
            if (stock == null) {
                rejected += RowRejection.InvalidStock(
                    rowNumber = row.rowNumber,
                    code = code.value,
                    raw = row.stock,
                    detail = stockResult.exceptionOrNull()?.message ?: "is not a number",
                )
                return@forEach
            }
            // A negative stock is impossible in a physical inventory.
            if (stock.isNegative) {
                rejected += RowRejection.InvalidStock(
                    rowNumber = row.rowNumber,
                    code = code.value,
                    raw = row.stock,
                    detail = "stock cannot be negative",
                )
                return@forEach
            }

            accepted += ValidatedProduct(
                rowNumber = row.rowNumber,
                externalId = row.externalId.trim().ifBlank { null },
                code = code,
                name = row.name,
                unitMeasure = unit,
                stock = stock,
            )
        }

        return ImportReport(
            totalRows = rows.size,
            accepted = accepted,
            rejected = rejected,
            isDryRun = dryRun,
        )
    }

    /**
     * Converts a CSV into rows, tolerating quotes and commas inside fields.
     *
     * It is a deliberately minimal parser: the real file uses double quotes
     * for names that contain quotes and commas (876 names carry
     * double quotes and 290 have `#`). It is implemented here instead of adding
     * a dependency to the domain, which must remain pure Kotlin.
     */
    fun parseCsv(content: String, delimiter: Char = ','): List<CatalogRow> {
        // Excel's BOM survives `lines()` and would break the first column of the
        // header. It is removed here, once, and not in every row.
        val lines = content.removePrefix("\uFEFF").lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()

        // The first line is the header.
        return lines.drop(1).mapIndexed { index, line ->
            val fields = splitCsvLine(line, delimiter)
            CatalogRow(
                rowNumber = index + 2,   // +2: una por la cabecera y otra porque las filas empiezan en 1
                externalId = fields.getOrElse(0) { "" },
                productCode = fields.getOrElse(1) { "" },
                name = fields.getOrElse(2) { "" },
                unitMeasure = fields.getOrElse(3) { "" },
                stock = fields.getOrElse(4) { "" },
            )
        }
    }

    /**
     * Splits a line respecting double quotes.
     *
     * The delimiter is a parameter because the migration's real file travels
     * as TSV: the same format, separated by tabs.
     */
    private fun splitCsvLine(line: String, delimiter: Char): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var betweenQuotes = false
        var i = 0

        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' -> {
                    // Escaped quote inside a quoted field.
                    if (betweenQuotes && i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        betweenQuotes = !betweenQuotes
                    }
                }
                c == delimiter && !betweenQuotes -> {
                    fields += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }
}
