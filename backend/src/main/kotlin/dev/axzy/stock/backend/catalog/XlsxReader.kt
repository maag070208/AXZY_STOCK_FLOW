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
data class FilaDeCeldas(val numeroDeFila: Int, val valores: Map<Int, String>)

object XlsxReader {

    /**
     * Tope de bytes descomprimidos. 64 MB es holgadísimo para una hoja de
     * catálogo (la real ocupa unos pocos cientos de KB) y corta una zip bomb.
     */
    private const val LIMITE_DESCOMPRIMIDO = 64L * 1024 * 1024

    /** Ruta por defecto de la primera hoja, si el libro no la resuelve. */
    private const val HOJA_POR_DEFECTO = "xl/worksheets/sheet1.xml"

    private const val CADENA_COMPARTIDA = "xl/sharedStrings.xml"
    private const val LIBRO = "xl/workbook.xml"
    private const val RELACIONES_DEL_LIBRO = "xl/_rels/workbook.xml.rels"

    /** ¿El contenido es un ZIP? Los XLSX empiezan por `PK`. */
    fun esXlsx(contenido: ByteArray): Boolean =
        contenido.size >= 2 && contenido[0] == 'P'.code.toByte() && contenido[1] == 'K'.code.toByte()

    fun leer(contenido: ByteArray): List<FilaDeCeldas> {
        val entradas = descomprimir(contenido)

        val cadenas = entradas[CADENA_COMPARTIDA]?.let(::leerCadenasCompartidas) ?: emptyList()
        val hoja = entradas[rutaDeLaPrimeraHoja(entradas)]
            ?: throw BadRequestException(
                code = "IMPORT_FILE_FORMAT",
                message = "El archivo no contiene ninguna hoja de cálculo legible.",
            )

        return leerFilas(hoja, cadenas)
    }

    // ── ZIP ─────────────────────────────────────────────────────────────────

    private fun descomprimir(contenido: ByteArray): Map<String, ByteArray> {
        val entradas = mutableMapOf<String, ByteArray>()
        var total = 0L

        try {
            ZipInputStream(ByteArrayInputStream(contenido)).use { zip ->
                while (true) {
                    val entrada = zip.nextEntry ?: break
                    if (entrada.isDirectory) continue

                    // Solo interesan las partes del libro. Leer las demás sería
                    // gastar memoria en imágenes y estilos que no se usan.
                    val interesante = entrada.name == CADENA_COMPARTIDA ||
                        entrada.name == LIBRO ||
                        entrada.name == RELACIONES_DEL_LIBRO ||
                        entrada.name.startsWith("xl/worksheets/")

                    if (!interesante) continue

                    val destino = ByteArrayOutputStream()
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val leidos = zip.read(buffer)
                        if (leidos <= 0) break
                        total += leidos
                        if (total > LIMITE_DESCOMPRIMIDO) {
                            throw BadRequestException(
                                code = "FILE_TOO_LARGE",
                                message = "El archivo descomprimido supera " +
                                    "${LIMITE_DESCOMPRIMIDO / (1024 * 1024)} MB. " +
                                    "Un catálogo de productos no debería llegar a ese tamaño.",
                            )
                        }
                        destino.write(buffer, 0, leidos)
                    }
                    entradas[entrada.name] = destino.toByteArray()
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

        return entradas
    }

    /**
     * Resuelve la ruta de la PRIMERA hoja del libro.
     *
     * `workbook.xml` da el `r:id` de cada hoja y `workbook.xml.rels` lo traduce
     * a la ruta del XML. Suponer siempre `sheet1.xml` funciona en la mayoría de
     * los archivos y falla justo en los que se guardaron con Excel en otro
     * idioma o con hojas renombradas, que es cuando más molesta.
     */
    private fun rutaDeLaPrimeraHoja(entradas: Map<String, ByteArray>): String {
        val libro = entradas[LIBRO]?.let { parsearSeguro(it) } ?: return HOJA_POR_DEFECTO
        val primeraHoja = libro.getElementsByTagName("sheet").item(0) as? Element
            ?: return HOJA_POR_DEFECTO

        val idRelacion = primeraHoja.getAttribute("r:id").ifBlank {
            primeraHoja.getAttribute("id")
        }
        val relaciones = entradas[RELACIONES_DEL_LIBRO]?.let { parsearSeguro(it) }
            ?: return HOJA_POR_DEFECTO

        val relacion = relaciones.getElementsByTagName("Relationship")
        for (i in 0 until relacion.length) {
            val nodo = relacion.item(i) as? Element ?: continue
            if (nodo.getAttribute("Id") != idRelacion) continue
            val destino = nodo.getAttribute("Target")
            if (destino.isBlank()) continue
            return if (destino.startsWith("/")) destino.removePrefix("/") else "xl/$destino"
        }

        return HOJA_POR_DEFECTO
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
    private fun leerCadenasCompartidas(xml: ByteArray): List<String> {
        val documento = parsearSeguro(xml)
        val items = documento.getElementsByTagName("si")

        return (0 until items.length).map { indice ->
            val item = items.item(indice) as? Element ?: return@map ""
            // `getElementsByTagName` baja por todo el subárbol, así que también
            // recoge los `<t>` que van dentro de un `<r>` (texto enriquecido).
            val textos = item.getElementsByTagName("t")
            (0 until textos.length).joinToString("") { textos.item(it).textContent }
        }
    }

    private fun leerFilas(xml: ByteArray, cadenas: List<String>): List<FilaDeCeldas> {
        val documento = parsearSeguro(xml)
        val filas = documento.getElementsByTagName("row")
        val resultado = mutableListOf<FilaDeCeldas>()

        for (i in 0 until filas.length) {
            val fila = filas.item(i) as? Element ?: continue
            // El número de fila físico del archivo, que es el que se ve en
            // Excel. Si el atributo no está, se usa la posición (1-based).
            val numeroDeFila = fila.getAttribute("r").toIntOrNull() ?: (i + 1)

            val celdas = mutableMapOf<Int, String>()
            val hijos = fila.childNodes
            for (j in 0 until hijos.length) {
                val celda = hijos.item(j) as? Element ?: continue
                if (celda.tagName != "c") continue

                val columna = columnaDe(celda.getAttribute("r")) ?: continue
                val valor = valorDe(celda, cadenas) ?: continue
                if (valor.isNotEmpty()) celdas[columna] = valor
            }

            resultado += FilaDeCeldas(numeroDeFila, celdas)
        }

        return resultado
    }

    /** Traduce `BC12` a la columna 54 (0-based: A = 0). */
    private fun columnaDe(referencia: String): Int? {
        val letras = referencia.takeWhile { it.isLetter() }
        if (letras.isEmpty()) return null

        var columna = 0
        letras.uppercase().forEach { letra ->
            columna = columna * 26 + (letra - 'A' + 1)
        }
        return columna - 1
    }

    private fun valorDe(celda: Element, cadenas: List<String>): String? {
        when (celda.getAttribute("t")) {
            // Cadena compartida: el valor es el índice en `sharedStrings`.
            "s" -> {
                val indice = textoDe(celda, "v")?.toIntOrNull() ?: return null
                return cadenas.getOrNull(indice)
            }
            // Cadena en línea: el texto va dentro de `<is>`.
            "inlineStr" -> {
                val nodos = celda.getElementsByTagName("t")
                return (0 until nodos.length).joinToString("") { nodos.item(it).textContent }
            }
            // `str` es el resultado de una fórmula y viene en `<v>` como texto.
            else -> return textoDe(celda, "v")?.trim()
        }
    }

    private fun textoDe(celda: Element, etiqueta: String): String? {
        val nodos = celda.getElementsByTagName(etiqueta)
        if (nodos.length == 0) return null
        return nodos.item(0).textContent
    }

    /**
     * Parser XML endurecido.
     *
     * Sin estas banderas, un archivo subido podría incluir un DOCTYPE con una
     * entidad que apunte a `file:///etc/passwd` y el servidor la resolvería. Es
     * el XXE clásico, y aquí el archivo lo elige quien sube el catálogo.
     */
    private fun parsearSeguro(xml: ByteArray): org.w3c.dom.Document {
        val fabrica = DocumentBuilderFactory.newInstance()
        listOf(
            "http://apache.org/xml/features/disallow-doctype-decl" to true,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
        ).forEach { (caracteristica, valor) ->
            runCatching { fabrica.setFeature(caracteristica, valor) }
        }
        fabrica.isXIncludeAware = false
        fabrica.isExpandEntityReferences = false

        return fabrica.newDocumentBuilder().parse(ByteArrayInputStream(xml))
    }
}
