package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.error.BadRequestException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LECTOR DE XLSX (mínimo, sin dependencias)
 *
 * El archivo real del cliente es un `.xlsx` de 7 columnas con cabeceras del ERP
 * (`CIDPRODUCTO`, `CCODIGOPRODUCTO`, `CNOMBREPRODUCTO`, `CUNIDADMEDIDA`,
 * `CEXISTENCIAS`…). Para leerlo se necesitan **dos** cosas: descomprimir el ZIP
 * y leer dos XML. Añadir Apache POI para eso traería ~15 MB de dependencias
 * (POI + XmlBeans) a un backend que solo tiene que leer una hoja de cálculo
 * plana; se implementa aquí con `java.util.zip` y el parser XML del JDK.
 *
 * **No se confía en el archivo.** Es una entrada que sube un usuario, así que:
 *  - se desactiva la declaración de tipo de documento y las entidades externas
 *    (un XLSX con un DOCTYPE puede intentar leer un archivo local del servidor:
 *    XXE);
 *  - se limita el tamaño descomprimido, porque un ZIP puede expandirse hasta
 *    llenar el disco (zip bomb).
 *
 * Las celdas se devuelven como texto, indexadas por columna, junto con el
 * **número de fila físico** del archivo: es el número que el usuario ve en
 * Excel y el que necesita un rechazo para poder corregirse.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Una fila de la hoja, con el número de fila tal como se ve en Excel. */
data class CellRow(val rowNumber: Int, val values: Map<Int, String>)

object XlsxReader {

    /**
     * Tope de bytes descomprimidos. 64 MB es holgadísimo para una hoja de
     * catálogo (la real ocupa unos pocos cientos de KB) y corta una zip bomb.
     */
    private const val LIMIT_DECOMPRESSED = 64L * 1024 * 1024

    /** Ruta por defecto de la primera hoja, si el libro no la resuelve. */
    private const val DEFAULT_SHEET = "xl/worksheets/sheet1.xml"

    private const val STRING_SHARED = "xl/sharedStrings.xml"
    private const val Book = "xl/workbook.xml"
    private const val LEDGER_RELATIONS = "xl/_rels/workbook.xml.rels"

    /** ¿El contenido es un ZIP? Los XLSX empiezan por `PK`. */
    fun isXlsx(content: ByteArray): Boolean =
        content.size >= 2 && content[0] == 'P'.code.toByte() && content[1] == 'K'.code.toByte()

    fun read(content: ByteArray): List<CellRow> {
        val entries = decompress(content)

        val strings = entries[STRING_SHARED]?.let(::readSharedStrings) ?: emptyList()
        val sheet = entries[firstSheetPath(entries)]
            ?: throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "El archivo no contiene ninguna hoja de cálculo legible.",
            )

        return readRows(sheet, strings)
    }

    // ── ZIP ─────────────────────────────────────────────────────────────────

    private fun decompress(content: ByteArray): Map<String, ByteArray> {
        val entries = mutableMapOf<String, ByteArray>()
        var total = 0L

        try {
            ZipInputStream(ByteArrayInputStream(content)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) continue

                    // Solo interesan las partes del libro. Leer las demás sería
                    // gastar memoria en imágenes y estilos que no se usan.
                    val interesting = entry.name == STRING_SHARED ||
                        entry.name == Book ||
                        entry.name == LEDGER_RELATIONS ||
                        entry.name.startsWith("xl/worksheets/")

                    if (!interesting) continue

                    val destination = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read <= 0) break
                        total += read
                        if (total > LIMIT_DECOMPRESSED) {
                            throw BadRequestException(
                                code = "FILE_TOO_LARGE",
                                message = "El archivo descomprimido supera " +
                                    "${LIMIT_DECOMPRESSED / (1024 * 1024)} MB. " +
                                    "Un catálogo de productos no debería llegar a ese tamaño.",
                            )
                        }
                        destination.write(buffer, 0, read)
                    }
                    entries[entry.name] = destination.toByteArray()
                }
            }
        } catch (e: BadRequestException) {
            throw e
        } catch (e: Exception) {
            throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "No se pudo leer el archivo como XLSX: ${e.message}",
            )
        }

        return entries
    }

    /**
     * Resuelve la ruta de la PRIMERA hoja del libro.
     *
     * `workbook.xml` da el `r:id` de cada hoja y `workbook.xml.rels` lo traduce
     * a la ruta del XML. Suponer siempre `sheet1.xml` funciona en la mayoría de
     * los archivos y falla justo en los que se guardaron con Excel en otro
     * idioma o con hojas renombradas, que es cuando más molesta.
     */
    private fun firstSheetPath(entries: Map<String, ByteArray>): String {
        val book = entries[Book]?.let { safeParse(it) } ?: return DEFAULT_SHEET
        val firstSheet = book.getElementsByTagName("sheet").item(0) as? Element
            ?: return DEFAULT_SHEET

        val idRelation = firstSheet.getAttribute("r:id").ifBlank {
            firstSheet.getAttribute("id")
        }
        val relations = entries[LEDGER_RELATIONS]?.let { safeParse(it) }
            ?: return DEFAULT_SHEET

        val relation = relations.getElementsByTagName("Relationship")
        for (i in 0 until relation.length) {
            val node = relation.item(i) as? Element ?: continue
            if (node.getAttribute("Id") != idRelation) continue
            val destination = node.getAttribute("Target")
            if (destination.isBlank()) continue
            return if (destination.startsWith("/")) destination.removePrefix("/") else "xl/$destination"
        }

        return DEFAULT_SHEET
    }

    // ── XML ─────────────────────────────────────────────────────────────────

    /**
     * Cadenas compartidas del libro.
     *
     * Casi todo el texto de un XLSX vive aquí: la hoja solo guarda el índice.
     * Un `<si>` puede tener varios `<t>` (texto enriquecido), así que se
     * concatenan en lugar de tomar solo el primero: un nombre con una parte en
     * negrita se partiría en dos.
     */
    private fun readSharedStrings(xml: ByteArray): List<String> {
        val document = safeParse(xml)
        val items = document.getElementsByTagName("si")

        return (0 until items.length).map { index ->
            val item = items.item(index) as? Element ?: return@map ""
            // `getElementsByTagName` baja por todo el subárbol, así que también
            // recoge los `<t>` que van dentro de un `<r>` (texto enriquecido).
            val texts = item.getElementsByTagName("t")
            (0 until texts.length).joinToString("") { texts.item(it).textContent }
        }
    }

    private fun readRows(xml: ByteArray, strings: List<String>): List<CellRow> {
        val document = safeParse(xml)
        val rows = document.getElementsByTagName("row")
        val result = mutableListOf<CellRow>()

        for (i in 0 until rows.length) {
            val row = rows.item(i) as? Element ?: continue
            // El número de fila físico del archivo, que es el que se ve en
            // Excel. Si el atributo no está, se usa la posición (1-based).
            val rowNumber = row.getAttribute("r").toIntOrNull() ?: (i + 1)

            val cells = mutableMapOf<Int, String>()
            val children = row.childNodes
            for (j in 0 until children.length) {
                val cell = children.item(j) as? Element ?: continue
                if (cell.tagName != "c") continue

                val column = columnOf(cell.getAttribute("r")) ?: continue
                val value = valueOf(cell, strings) ?: continue
                if (value.isNotEmpty()) cells[column] = value
            }

            result += CellRow(rowNumber, cells)
        }

        return result
    }

    /** Traduce `BC12` a la columna 54 (0-based: A = 0). */
    private fun columnOf(reference: String): Int? {
        val letters = reference.takeWhile { it.isLetter() }
        if (letters.isEmpty()) return null

        var column = 0
        letters.uppercase().forEach { letter ->
            column = column * 26 + (letter - 'A' + 1)
        }
        return column - 1
    }

    private fun valueOf(cell: Element, strings: List<String>): String? {
        when (cell.getAttribute("t")) {
            // Cadena compartida: el valor es el índice en `sharedStrings`.
            "s" -> {
                val index = textOf(cell, "v")?.toIntOrNull() ?: return null
                return strings.getOrNull(index)
            }
            // Cadena en línea: el texto va dentro de `<is>`.
            "inlineStr" -> {
                val nodes = cell.getElementsByTagName("t")
                return (0 until nodes.length).joinToString("") { nodes.item(it).textContent }
            }
            // `str` es el resultado de una fórmula y viene en `<v>` como texto.
            else -> return textOf(cell, "v")?.trim()
        }
    }

    private fun textOf(cell: Element, label: String): String? {
        val nodes = cell.getElementsByTagName(label)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent
    }

    /**
     * Parser XML endurecido.
     *
     * Sin estas banderas, un archivo subido podría incluir un DOCTYPE con una
     * entidad que apunte a `file:///etc/passwd` y el servidor la resolvería. Es
     * el XXE clásico, y aquí el archivo lo elige quien sube el catálogo.
     */
    private fun safeParse(xml: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (characteristic, value) ->
            runCatching { factory.setFeature(characteristic, value) }
        }
        factory.isXIncludeAware = false
        factory.isExpandEntityReferences = false

        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }
}
