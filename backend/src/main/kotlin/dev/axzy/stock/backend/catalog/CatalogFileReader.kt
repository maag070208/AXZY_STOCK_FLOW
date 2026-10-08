package dev.axzy.stock.backend.catalog

import dev.axzy.stock.domain.catalog.CatalogImporter
import dev.axzy.stock.domain.catalog.CatalogRow
import dev.axzy.stock.backend.error.BadRequestException
import java.nio.charset.StandardCharsets

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LECTURA DEL ARCHIVO DE CATÁLOGO
 *
 * El import acepta dos formatos y **cada uno se lee como corresponde**:
 *
 *  - **CSV canónico** (`product_id,product_code,name,unit_measure,stock`): lo
 *    parsea `CatalogImporter.parseCsv` del dominio, que ya está probado contra
 *    el archivo real y maneja las comillas y las comas dentro de un campo.
 *  - **XLSX del ERP**: el archivo real tiene 7 columnas con nombres del ERP y
 *    la columna del stock no está en la posición 5. Se mapea **por nombre de
 *    cabecera**, nunca por posición: mapear por posición un archivo que no
 *    tiene el formato esperado pone datos equivocados en el campo equivocado, y
 *    eso es peor que rechazar el archivo.
 *
 * Un CSV con cabeceras del ERP se **rechaza** con un mensaje que dice qué
 * columnas se esperaban. Adivinar la posición de un CSV ajeno es exactamente la
 * clase de tolerancia que esconde un descuadre de inventario.
 * ═══════════════════════════════════════════════════════════════════════════
 */

// Nombres de campo en el mapeo por cabecera.
private const val FIELD_ID_EXTERNAL = "externalId"
private const val FIELD_CODE = "productCode"
private const val FIELD_NAME = "name"
private const val FIELD_UNIT = "unitMeasure"
private const val FIELD_STOCK = "stock"

object CatalogFileReader {

    /** Cabecera exacta del CSV que entiende el dominio, en orden. */
    private val HEADER_CANONICAL =
        listOf("product_id", "product_code", "name", "unit_measure", "stock")

    /**
     * Lee el archivo y devuelve las filas.
     *
     * @param importador el importador del dominio, que aporta el parser CSV.
     * @throws BadRequestException si el archivo no es legible o no tiene el
     *   formato esperado. **No se lanza por una fila mala**: eso es un rechazo
     *   del informe, con su número de fila.
     */
    fun read(content: ByteArray, importer: CatalogImporter): List<CatalogRow> {
        if (content.isEmpty()) {
            throw BadRequestException(code = "EMPTY_FILE", message = "El archivo está vacío.")
        }

        if (XlsxReader.isXlsx(content)) {
            return readXlsx(content)
        }

        val text = String(content, StandardCharsets.UTF_8)
        if (text.isBlank()) {
            throw BadRequestException(code = "EMPTY_FILE", message = "El archivo está vacío.")
        }

        val header = headerOf(text)
        if (header != HEADER_CANONICAL) {
            throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "La cabecera del CSV no es la esperada. Se esperaba " +
                    "${HEADER_CANONICAL.joinToString(",")} y se recibió " +
                    "${header.joinToString(",")}. Si el archivo es el Excel del ERP, " +
                    "envíalo como .xlsx: ahí las columnas se leen por nombre.",
                details = mapOf("expectedHeader" to HEADER_CANONICAL.joinToString(",")),
            )
        }

        return importer.parseCsv(text)
    }

    private fun headerOf(text: String): List<String> =
        text.lineSequence()
            .firstOrNull { it.isNotBlank() }
            // El BOM que Excel escribe al guardar como CSV UTF-8 rompería la
            // comparación de `product_id` y el archivo se rechazaría sin motivo.
            ?.removePrefix("\uFEFF")
            ?.split(',')
            ?.map { it.trim().trim('"').lowercase() }
            ?: emptyList()

    // ── XLSX ────────────────────────────────────────────────────────────────

    private fun readXlsx(content: ByteArray): List<CatalogRow> {
        val rows = XlsxReader.read(content)

        // La cabecera es la primera fila con contenido: no se asume que sea la
        // fila 1, porque hay archivos con un título encima de la tabla.
        val indexHeader = rows.indexOfFirst { it.values.isNotEmpty() }
        if (indexHeader < 0) {
            throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "La hoja del archivo está vacía.",
            )
        }

        val columns = ColumnMapping.from(rows[indexHeader].values)

        return rows.drop(indexHeader + 1).mapNotNull { row ->
            val values = ColumnMapping.fields(columns, row.values)

            // Las filas totalmente vacías se descartan en lugar de contarse: el
            // archivo real trae dos al final, y el perfil del archivo (§3.5)
            // habla de 1.398 filas útiles, no de 1.400. Si se contaran, el
            // cuadre automático del importador daría un descuadre falso.
            if (values.values.all { it.isBlank() }) return@mapNotNull null

            CatalogRow(
                rowNumber = row.rowNumber,
                externalId = values.getValue(FIELD_ID_EXTERNAL),
                productCode = values.getValue(FIELD_CODE),
                name = values.getValue(FIELD_NAME),
                unitMeasure = values.getValue(FIELD_UNIT),
                stock = normalizeInteger(values.getValue(FIELD_STOCK)),
            )
        }
    }

    /**
     * Excel guarda un entero como `5193` o `5193.0` según el formato de la
     * celda, y `Quantity.parse` acepta ambos. Pero un `5193.0000000001` sí lo
     * rechazaría, así que se quitan los ceros decimales sobrantes **solo cuando
     * el valor es exactamente un entero**; cualquier otra cosa se deja intacta
     * para que el importador la valide y, si procede, la rechace con su fila.
     */
    private fun normalizeInteger(value: String): String =
        INTEGER_WITH_TRAILING_ZEROS.matchEntire(value)?.groupValues?.get(1) ?: value

    private val INTEGER_WITH_TRAILING_ZEROS = Regex("^(-?\\d+)\\.0+$")
}

/**
 * Traduce las cabeceras del archivo a los campos del importador.
 *
 * Los alias salen del archivo real (`CIDPRODUCTO`…) y del CSV extraído
 * (`product_id`…), normalizados a minúsculas y sin separadores, para que
 * `Product_Code`, `product code` y `PRODUCT_CODE` sean lo mismo.
 */
private object ColumnMapping {

    private val ALIAS = mapOf(
        FIELD_ID_EXTERNAL to setOf("productid", "idproducto", "cidproducto", "externalid"),
        FIELD_CODE to setOf("productcode", "codigoproducto", "ccodigoproducto", "codigo"),
        FIELD_NAME to setOf("name", "nombre", "nombreproducto", "cnombreproducto"),
        FIELD_UNIT to setOf("unitmeasure", "unidadmedida", "cunidadmedida", "unidad"),
        FIELD_STOCK to setOf("stock", "existencias", "cexistencias"),
    )

    /** Campos sin los que no se puede importar. El id externo es opcional. */
    private val Required = setOf(FIELD_CODE, FIELD_NAME, FIELD_UNIT, FIELD_STOCK)

    /** `campo → índice de columna`. */
    fun from(headerCells: Map<Int, String>): Map<String, Int> {
        val normalized = headerCells.mapValues { (_, value) -> normalize(value) }
        val mapping = mutableMapOf<String, Int>()

        ALIAS.forEach { (field, alias) ->
            val column = normalized.entries
                .firstOrNull { (_, title) -> title in alias }
                ?.key
            if (column != null) mapping[field] = column
        }

        val missing = Required - mapping.keys
        if (missing.isNotEmpty()) {
            throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "Al archivo le faltan columnas obligatorias: " +
                    "${missing.sorted().joinToString(", ")}. Cabeceras encontradas: " +
                    "${headerCells.values.joinToString(", ")}. " +
                    "El mapeo es por nombre de cabecera, nunca por posición.",
                details = mapOf("missingColumns" to missing.sorted().joinToString(",")),
            )
        }

        return mapping
    }

    /** Los cinco campos del importador, en blanco si su columna no existe. */
    fun fields(mapping: Map<String, Int>, values: Map<Int, String>): Map<String, String> =
        listOf(FIELD_ID_EXTERNAL, FIELD_CODE, FIELD_NAME, FIELD_UNIT, FIELD_STOCK)
            .associateWith { field ->
                mapping[field]?.let { column -> values[column]?.trim() }.orEmpty()
            }

    private fun normalize(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }
}
