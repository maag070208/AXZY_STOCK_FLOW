package dev.axzy.stock.domain.catalog

import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * IMPORTADOR DEL CATÁLOGO
 *
 * Carga inicial de productos desde el archivo de inventario real. Fase 0:
 * implementación pura, sin base de datos, para que las reglas se prueben contra
 * el **archivo de verdad** (1.398 productos, 10.914.130 unidades) en lugar de
 * contra datos inventados.
 *
 * **Reglas estrictas por diseño.** El archivo tiene calidad excelente —cero
 * nulos, cero negativos, cero decimales, códigos únicos— así que el importador
 * puede **rechazar una fila** en lugar de intentar adivinar. Un importador
 * tolerante esconde errores de captura que después reaparecen como stock mal
 * contado.
 *
 * **Modo dry-run obligatorio.** Se puede simular el import completo y ver el
 * informe **sin escribir nada**. Sin eso, la primera ejecución sobre datos
 * reales es un acto de fe.
 *
 * Referencia: PLAN_ALMACEN_INTEGRAL.md §3.5.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Una fila cruda del archivo, tal como llega. */
data class CatalogRow(
    val rowNumber: Int,
    val externalId: String,
    val productCode: String,
    val name: String,
    val unitMeasure: String,
    val stock: String,
)

/** Por qué se rechazó una fila. */
sealed interface RowRejection {
    val rowNumber: Int
    val reason: String

    data class BlankRow(override val rowNumber: Int) : RowRejection {
        override val reason = "Fila completamente vacía"
    }

    data class InvalidProductCode(
        override val rowNumber: Int,
        val code: String,
        val detail: String,
    ) : RowRejection {
        override val reason = "Código de producto inválido ('$code'): $detail"
    }

    data class BlankName(override val rowNumber: Int, val code: String) : RowRejection {
        override val reason = "El producto '$code' no tiene nombre"
    }

    data class UnknownUnitMeasure(
        override val rowNumber: Int,
        val code: String,
        val unit: String,
    ) : RowRejection {
        override val reason =
            "Unidad de medida desconocida '$unit' en '$code'. Debe existir en el catálogo."
    }

    data class InvalidStock(
        override val rowNumber: Int,
        val code: String,
        val raw: String,
        val detail: String,
    ) : RowRejection {
        override val reason = "Existencias inválidas en '$code' ('$raw'): $detail"
    }

    data class DuplicateCode(
        override val rowNumber: Int,
        val code: String,
        val firstSeenAtRow: Int,
    ) : RowRejection {
        override val reason =
            "Código '$code' duplicado (ya apareció en la fila $firstSeenAtRow)"
    }
}

/** Un producto ya validado, listo para persistir. */
data class ValidatedProduct(
    val rowNumber: Int,
    val externalId: String?,
    val code: ProductCode,
    val name: String,
    val unitMeasure: String,
    val stock: Quantity,
)

/**
 * Informe del import.
 *
 * Es el entregable del dry-run: **lo que se presenta antes de escribir nada**.
 * Incluye el cuadre contra las cifras conocidas del archivo, porque un import
 * que "funcionó" pero no cuadra es un import que falló en silencio.
 */
data class ImportReport(
    val totalRows: Int,
    val accepted: List<ValidatedProduct>,
    val rejected: List<RowRejection>,
    val isDryRun: Boolean,
) {
    val acceptedCount: Int get() = accepted.size
    val rejectedCount: Int get() = rejected.size

    /** Suma de existencias de las filas aceptadas. */
    val totalStock: Quantity
        get() = accepted.fold(Quantity.ZERO) { acc, p -> acc + p.stock }

    /** Productos aceptados con stock cero. */
    val zeroStockCount: Int get() = accepted.count { it.stock.isZero }

    /** Unidades de medida encontradas, ordenadas. */
    val unitMeasures: List<String> get() = accepted.map { it.unitMeasure }.distinct().sorted()

    val hasRejections: Boolean get() = rejected.isNotEmpty()

    /** Informe legible, para presentarlo al negocio antes de importar. */
    fun render(): String = buildString {
        appendLine("Informe de importación del catálogo${if (isDryRun) " (SIMULACIÓN)" else ""}")
        appendLine("─".repeat(58))
        appendLine("Filas leídas:          $totalRows")
        appendLine("Productos aceptados:   $acceptedCount")
        appendLine("Filas rechazadas:      $rejectedCount")
        appendLine("Existencias totales:   $totalStock")
        appendLine("Productos en cero:     $zeroStockCount")
        appendLine("Unidades de medida:    ${unitMeasures.joinToString(", ")}")
        if (rejected.isNotEmpty()) {
            appendLine()
            appendLine("Filas rechazadas (las primeras 20):")
            rejected.take(20).forEach { appendLine("  · fila ${it.rowNumber}: ${it.reason}") }
            if (rejected.size > 20) appendLine("  … y ${rejected.size - 20} más")
        }
        if (isDryRun) {
            appendLine()
            appendLine("No se escribió nada: es una simulación.")
        }
    }

    /**
     * Comprueba que el import cuadre con las cifras conocidas del archivo.
     *
     * Sin esta verificación, un import que descarta filas en silencio "pasa" y
     * el descuadre aparece después, cuando ya no hay forma de saber qué se
     * perdió.
     */
    fun matchesExpectations(
        expectedProducts: Int,
        expectedTotalStock: Quantity,
        expectedRejections: Int = 0,
    ): List<String> = buildList {
        if (acceptedCount != expectedProducts) {
            add("Se esperaban $expectedProducts productos y se aceptaron $acceptedCount")
        }
        if (totalStock != expectedTotalStock) {
            add("Se esperaban $expectedTotalStock unidades y se obtuvieron $totalStock")
        }
        if (rejectedCount != expectedRejections) {
            add("Se esperaban $expectedRejections filas rechazadas y hubo $rejectedCount")
        }
    }
}

/**
 * El importador.
 *
 * @param knownUnitMeasures unidades válidas. Si una fila trae otra, se rechaza:
 *   es la defensa contra el clásico `KG`/`Kg`/`KGS` como tres unidades distintas.
 */
class CatalogImporter(
    private val knownUnitMeasures: Set<String> = setOf("PZA"),
) {

    /** Cifras conocidas del archivo real, para el cuadre automático. */
    companion object {
        const val EXPECTED_PRODUCTS = 1398
        val EXPECTED_TOTAL_STOCK: Quantity = Quantity.of(10_914_130)
        const val EXPECTED_ZERO_STOCK_PRODUCTS = 334
    }

    /**
     * Procesa las filas.
     *
     * @param dryRun si es `true`, **no se persiste nada**. Es el modo por
     *   defecto conceptual: la primera ejecución sobre datos reales debe ser
     *   una simulación.
     */
    fun import(rows: List<CatalogRow>, dryRun: Boolean = true): ImportReport {
        val accepted = mutableListOf<ValidatedProduct>()
        val rejected = mutableListOf<RowRejection>()
        val seenCodes = mutableMapOf<String, Int>()

        rows.forEach { row ->
            // ── Fila vacía ──────────────────────────────────────────────────
            if (row.productCode.isBlank() && row.name.isBlank() && row.stock.isBlank()) {
                rejected += RowRejection.BlankRow(row.rowNumber)
                return@forEach
            }

            // ── Código de producto ──────────────────────────────────────────
            // Se valida con el objeto de valor del dominio: el alfabeto real es
            // 0-9 y A-Z, sin minúsculas ni espacios. Si una fila los trae, se
            // RECHAZA indicando el número de fila; no se corrige en silencio.
            val codeResult = ProductCode.validate(row.productCode)
            val code = codeResult.getOrNull()
            if (code == null) {
                rejected += RowRejection.InvalidProductCode(
                    rowNumber = row.rowNumber,
                    code = row.productCode,
                    detail = codeResult.exceptionOrNull()?.message ?: "formato inválido",
                )
                return@forEach
            }

            // ── Duplicado ──────────────────────────────────────────────────
            // `product_code` es la clave de identidad para el upsert.
            val yaVisto = seenCodes[code.value]
            if (yaVisto != null) {
                rejected += RowRejection.DuplicateCode(row.rowNumber, code.value, yaVisto)
                return@forEach
            }
            seenCodes[code.value] = row.rowNumber

            // ── Nombre ─────────────────────────────────────────────────────
            if (row.name.isBlank()) {
                rejected += RowRejection.BlankName(row.rowNumber, code.value)
                return@forEach
            }

            // ── Unidad de medida ───────────────────────────────────────────
            val unidad = row.unitMeasure.trim().uppercase()
            if (unidad !in knownUnitMeasures) {
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
                    detail = stockResult.exceptionOrNull()?.message ?: "no es un número",
                )
                return@forEach
            }
            // Un stock negativo es imposible en un inventario físico.
            if (stock.isNegative) {
                rejected += RowRejection.InvalidStock(
                    rowNumber = row.rowNumber,
                    code = code.value,
                    raw = row.stock,
                    detail = "las existencias no pueden ser negativas",
                )
                return@forEach
            }

            accepted += ValidatedProduct(
                rowNumber = row.rowNumber,
                externalId = row.externalId.trim().ifBlank { null },
                code = code,
                name = row.name,
                unitMeasure = unidad,
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
     * Convierte un CSV en filas, tolerando comillas y comas dentro de campos.
     *
     * Es un parser deliberadamente mínimo: el archivo real usa comillas dobles
     * para los nombres que contienen comillas y comas (876 nombres llevan
     * comillas dobles y 290 llevan `#`). Se implementa aquí en lugar de añadir
     * una dependencia al dominio, que debe seguir siendo Kotlin puro.
     */
    fun parseCsv(content: String): List<CatalogRow> {
        val lineas = content.lines().filter { it.isNotBlank() }
        if (lineas.isEmpty()) return emptyList()

        // La primera línea es la cabecera.
        return lineas.drop(1).mapIndexed { indice, linea ->
            val campos = splitCsvLine(linea)
            CatalogRow(
                rowNumber = indice + 2,   // +2: una por la cabecera y otra porque las filas empiezan en 1
                externalId = campos.getOrElse(0) { "" },
                productCode = campos.getOrElse(1) { "" },
                name = campos.getOrElse(2) { "" },
                unitMeasure = campos.getOrElse(3) { "" },
                stock = campos.getOrElse(4) { "" },
            )
        }
    }

    /** Divide una línea CSV respetando las comillas dobles. */
    private fun splitCsvLine(linea: String): List<String> {
        val campos = mutableListOf<String>()
        val actual = StringBuilder()
        var entreComillas = false
        var i = 0

        while (i < linea.length) {
            val c = linea[i]
            when {
                c == '"' -> {
                    // Comilla escapada dentro de un campo entrecomillado.
                    if (entreComillas && i + 1 < linea.length && linea[i + 1] == '"') {
                        actual.append('"')
                        i++
                    } else {
                        entreComillas = !entreComillas
                    }
                }
                c == ',' && !entreComillas -> {
                    campos += actual.toString()
                    actual.clear()
                }
                else -> actual.append(c)
            }
            i++
        }
        campos += actual.toString()
        return campos
    }
}
