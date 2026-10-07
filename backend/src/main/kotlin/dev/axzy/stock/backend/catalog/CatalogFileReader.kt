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
private const val CAMPO_ID_EXTERNO = "externalId"
private const val CAMPO_CODIGO = "productCode"
private const val CAMPO_NOMBRE = "name"
private const val CAMPO_UNIDAD = "unitMeasure"
private const val CAMPO_STOCK = "stock"

object CatalogFileReader {

    /** Cabecera exacta del CSV que entiende el dominio, en orden. */
    private val CABECERA_CANONICA =
        listOf("product_id", "product_code", "name", "unit_measure", "stock")

    /**
     * Lee el archivo y devuelve las filas.
     *
     * @param importador el importador del dominio, que aporta el parser CSV.
     * @throws BadRequestException si el archivo no es legible o no tiene el
     *   formato esperado. **No se lanza por una fila mala**: eso es un rechazo
     *   del informe, con su número de fila.
     */
    fun leer(contenido: ByteArray, importador: CatalogImporter): List<CatalogRow> {
        if (contenido.isEmpty()) {
            throw BadRequestException(code = "EMPTY_FILE", message = "El archivo está vacío.")
        }

        if (XlsxReader.esXlsx(contenido)) {
            return leerXlsx(contenido)
        }

        val texto = String(contenido, StandardCharsets.UTF_8)
        if (texto.isBlank()) {
            throw BadRequestException(code = "EMPTY_FILE", message = "El archivo está vacío.")
        }

        val cabecera = cabeceraDe(texto)
        if (cabecera != CABECERA_CANONICA) {
            throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "La cabecera del CSV no es la esperada. Se esperaba " +
                    "${CABECERA_CANONICA.joinToString(",")} y se recibió " +
                    "${cabecera.joinToString(",")}. Si el archivo es el Excel del ERP, " +
                    "envíalo como .xlsx: ahí las columnas se leen por nombre.",
                details = mapOf("expectedHeader" to CABECERA_CANONICA.joinToString(",")),
            )
        }

        return importador.parseCsv(texto)
    }

    private fun cabeceraDe(texto: String): List<String> =
        texto.lineSequence()
            .firstOrNull { it.isNotBlank() }
            // El BOM que Excel escribe al guardar como CSV UTF-8 rompería la
            // comparación de `product_id` y el archivo se rechazaría sin motivo.
            ?.removePrefix("\uFEFF")
            ?.split(',')
            ?.map { it.trim().trim('"').lowercase() }
            ?: emptyList()

    // ── XLSX ────────────────────────────────────────────────────────────────

    private fun leerXlsx(contenido: ByteArray): List<CatalogRow> {
        val filas = XlsxReader.leer(contenido)

        // La cabecera es la primera fila con contenido: no se asume que sea la
        // fila 1, porque hay archivos con un título encima de la tabla.
        val indiceCabecera = filas.indexOfFirst { it.valores.isNotEmpty() }
        if (indiceCabecera < 0) {
            throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "La hoja del archivo está vacía.",
            )
        }

        val columnas = MapeoDeColumnas.desde(filas[indiceCabecera].valores)

        return filas.drop(indiceCabecera + 1).mapNotNull { fila ->
            val valores = MapeoDeColumnas.campos(columnas, fila.valores)

            // Las filas totalmente vacías se descartan en lugar de contarse: el
            // archivo real trae dos al final, y el perfil del archivo (§3.5)
            // habla de 1.398 filas útiles, no de 1.400. Si se contaran, el
            // cuadre automático del importador daría un descuadre falso.
            if (valores.values.all { it.isBlank() }) return@mapNotNull null

            CatalogRow(
                rowNumber = fila.numeroDeFila,
                externalId = valores.getValue(CAMPO_ID_EXTERNO),
                productCode = valores.getValue(CAMPO_CODIGO),
                name = valores.getValue(CAMPO_NOMBRE),
                unitMeasure = valores.getValue(CAMPO_UNIDAD),
                stock = normalizarEntero(valores.getValue(CAMPO_STOCK)),
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
    private fun normalizarEntero(valor: String): String =
        ENTERO_CON_CEROS.matchEntire(valor)?.groupValues?.get(1) ?: valor

    private val ENTERO_CON_CEROS = Regex("^(-?\\d+)\\.0+$")
}

/**
 * Traduce las cabeceras del archivo a los campos del importador.
 *
 * Los alias salen del archivo real (`CIDPRODUCTO`…) y del CSV extraído
 * (`product_id`…), normalizados a minúsculas y sin separadores, para que
 * `Product_Code`, `product code` y `PRODUCT_CODE` sean lo mismo.
 */
private object MapeoDeColumnas {

    private val ALIAS = mapOf(
        CAMPO_ID_EXTERNO to setOf("productid", "idproducto", "cidproducto", "externalid"),
        CAMPO_CODIGO to setOf("productcode", "codigoproducto", "ccodigoproducto", "codigo"),
        CAMPO_NOMBRE to setOf("name", "nombre", "nombreproducto", "cnombreproducto"),
        CAMPO_UNIDAD to setOf("unitmeasure", "unidadmedida", "cunidadmedida", "unidad"),
        CAMPO_STOCK to setOf("stock", "existencias", "cexistencias"),
    )

    /** Campos sin los que no se puede importar. El id externo es opcional. */
    private val OBLIGATORIOS = setOf(CAMPO_CODIGO, CAMPO_NOMBRE, CAMPO_UNIDAD, CAMPO_STOCK)

    /** `campo → índice de columna`. */
    fun desde(celdasDeCabecera: Map<Int, String>): Map<String, Int> {
        val normalizadas = celdasDeCabecera.mapValues { (_, valor) -> normalizar(valor) }
        val mapeo = mutableMapOf<String, Int>()

        ALIAS.forEach { (campo, alias) ->
            val columna = normalizadas.entries
                .firstOrNull { (_, titulo) -> titulo in alias }
                ?.key
            if (columna != null) mapeo[campo] = columna
        }

        val faltantes = OBLIGATORIOS - mapeo.keys
        if (faltantes.isNotEmpty()) {
            throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "Al archivo le faltan columnas obligatorias: " +
                    "${faltantes.sorted().joinToString(", ")}. Cabeceras encontradas: " +
                    "${celdasDeCabecera.values.joinToString(", ")}. " +
                    "El mapeo es por nombre de cabecera, nunca por posición.",
                details = mapOf("missingColumns" to faltantes.sorted().joinToString(",")),
            )
        }

        return mapeo
    }

    /** Los cinco campos del importador, en blanco si su columna no existe. */
    fun campos(mapeo: Map<String, Int>, valores: Map<Int, String>): Map<String, String> =
        listOf(CAMPO_ID_EXTERNO, CAMPO_CODIGO, CAMPO_NOMBRE, CAMPO_UNIDAD, CAMPO_STOCK)
            .associateWith { campo ->
                mapeo[campo]?.let { columna -> valores[columna]?.trim() }.orEmpty()
            }

    private fun normalizar(titulo: String): String = titulo.lowercase().filter { it.isLetterOrDigit() }
}
