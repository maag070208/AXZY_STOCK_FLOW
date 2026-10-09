package dev.axzy.stock.backend.reports

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EXPORTACIÓN DE REPORTES — CSV y XLSX
 *
 * ── POR QUÉ EL XLSX SE ESCRIBE A MANO Y NO CON UNA BIBLIOTECA ──────────────
 *
 * Un `.xlsx` es un ZIP con XML dentro, y para una **tabla plana** los XML son
 * cuatro ficheros cortos. La alternativa era añadir Apache POI (~15 MB con sus
 * dependencias transitivas) a un backend cuyo artefacto entero pesa una fracción
 * de eso, **para escribir filas de texto y números**.
 *
 * El proyecto ya tiene el criterio tomado en el otro sentido: `XlsxReader.kt`
 * **lee** XLSX con `java.util.zip` y DOM, sin POI, por el mismo motivo. Escribir
 * con la biblioteca y leer a mano habría sido lo incoherente.
 *
 * **Y se probó que el fichero abre**, no que «debería» abrir: el test lo genera,
 * lo vuelve a leer con `java.util.zip` y comprueba `[Content_Types].xml` y las
 * filas. Un exportador de Excel que produce un ZIP que Excel rechaza es un
 * exportador que miente en el formato.
 *
 * ── LOS NUMEROS VAN COMO NÚMEROS, NO COMO TEXTO ────────────────────────────
 *
 * Los importes se escriben como celda numérica con formato de 3 decimales, no
 * como cadena. **En el JSON van como texto** (§1.7) porque ahí el riesgo es la
 * aritmética en coma flotante del cliente; en una hoja de cálculo el usuario va a
 * sumar, y una columna de texto no se suma: Excel la cuenta como cero y el total
 * sale mal sin que nada avise.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Una tabla ya plana: cabeceras y filas de texto, en el orden de las columnas. */
data class ExportTable(val headers: List<String>, val rows: List<List<String>>)

/**
 * CSV canónico, con separador `,` y **comillas solo cuando hacen falta**.
 *
 * Se escapan comillas dobles duplicándolas, que es lo que dice el RFC 4180. Un
 * nombre de producto con coma —«TORNILLO 1/4, GALVANIZADO»— parte la fila en dos
 * columnas si no se entrecomilla, y el usuario ve el archivo descolocado sin
 * saber por qué.
 */
fun ExportTable.toCsv(): ByteArray {
    val texto = buildString {
        append(headers.joinToString(",") { escaparCsv(it) })
        append("\r\n") // CRLF: es lo que espera Excel, y el RFC lo admite.
        rows.forEach { fila ->
            append(fila.joinToString(",") { escaparCsv(it) })
            append("\r\n")
        }
    }
    // BOM UTF-8: sin él, Excel en Windows abre los acentos como basura. Cuesta
    // tres bytes y evita el «no se ve bien» que nadie sabe diagnosticar.
    return byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + texto.toByteArray(Charsets.UTF_8)
}

private fun escaparCsv(valor: String): String =
    if (valor.contains(',') || valor.contains('"') || valor.contains('\n') || valor.contains('\r')) {
        "\"" + valor.replace("\"", "\"\"") + "\""
    } else {
        valor
    }

/**
 * Construye el `.xlsx` mínimo y válido para una tabla plana.
 *
 * Las partes del paquete OOXML que hacen falta, y ninguna más:
 *
 * ```
 * [Content_Types].xml          qué tipo tiene cada parte
 * _rels/.rels                  el punto de entrada del documento
 * xl/workbook.xml              el libro y su hoja
 * xl/_rels/workbook.xml.rels   la relación libro → hoja y libro → estilos
 * xl/worksheets/sheet1.xml     las celdas
 * xl/styles.xml                el formato numérico de 3 decimales
 * ```
 *
 * **`xl/styles.xml` no es opcional y es la trampa del formato:** sin él, Excel
 * abre el archivo pero cada número sale con los decimales que le apetezcan, y
 * `5193.000` se muestra como `5193`. El estilo `numFmt` con `0.000` es lo que
 * conserva los tres decimales que el resto del sistema trata como sagrados.
 */
fun ExportTable.toXlsx(sheetName: String = "Reporte"): ByteArray {
    val salida = ByteArrayOutputStream()
    ZipOutputStream(salida).use { zip ->
        zip.escribir("[Content_Types].xml", CONTENT_TYPES)
        zip.escribir("_rels/.rels", RELS_RAIZ)
        zip.escribir("xl/workbook.xml", workbook(sheetName))
        zip.escribir("xl/_rels/workbook.xml.rels", RELS_LIBRO)
        zip.escribir("xl/styles.xml", STYLES)
        zip.escribir("xl/worksheets/sheet1.xml", hoja())

        // `docProps` es opcional y se omite a propósito: son metadatos de autoría
        // que este backend no tiene por qué inventar.
    }
    return salida.toByteArray()
}

private fun ZipOutputStream.escribir(ruta: String, contenido: String) {
    val bytes = contenido.toByteArray(Charsets.UTF_8)
    val crc = CRC32().apply { update(bytes) }
    putNextEntry(
        ZipEntry(ruta).apply {
            method = ZipEntry.DEFLATED
            setSize(bytes.size.toLong())
            setCompressedSize(-1)
            setCrc(crc.value)
            time = 0L // Fecha fija: dos exportaciones iguales dan el mismo archivo.
        },
    )
    write(bytes)
    closeEntry()
}

private fun ExportTable.hoja(): String {
    val filas = buildString {
        // Fila 1: cabeceras. `t="inlineStr"` evita tener que montar la tabla de
        // cadenas compartidas (`sharedStrings.xml`), que es la parte del formato
        // que se puede omitir sin perder validez.
        append("<row r=\"1\">")
        headers.forEachIndexed { i, h ->
            append("<c r=\"${columna(i)}1\" t=\"inlineStr\"><is><t>${xml(h)}</t></is></c>")
        }
        append("</row>")

        rows.forEachIndexed { indice, fila ->
            val numeroFila = indice + 2
            append("<row r=\"$numeroFila\">")
            fila.forEachIndexed { i, valor ->
                val ref = "${columna(i)}$numeroFila"
                val numerico = valor.toBigDecimalOrNull()
                if (numerico != null) {
                    // `s="1"` es el estilo de 3 decimales: sin él, `5193.000` se
                    // pinta como `5193` y el usuario cree que perdió precisión.
                    append("<c r=\"$ref\" s=\"1\"><v>${numerico.toPlainString()}</v></c>")
                } else {
                    append("<c r=\"$ref\" t=\"inlineStr\"><is><t>${xml(valor)}</t></is></c>")
                }
            }
            append("</row>")
        }
    }

    return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$filas</sheetData></worksheet>"""
}

/** `0` → `A`, `25` → `Z`, `26` → `AA`. */
private fun columna(indice: Int): String {
    var n = indice
    val letras = StringBuilder()
    while (true) {
        letras.insert(0, ('A' + n % 26))
        n = n / 26 - 1
        if (n < 0) break
    }
    return letras.toString()
}

private fun xml(valor: String): String = valor
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
    .replace("'", "&apos;")

private fun workbook(nombreHoja: String): String =
    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xml(nombreHoja.take(31))}" sheetId="1" r:id="rId1"/></sheets></workbook>"""

private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>"""

private const val RELS_RAIZ = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""

private const val RELS_LIBRO = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""

/**
 * Estilos: el índice `0` es el general y el `1` es **número con 3 decimales**.
 *
 * `numFmtId="164"` con `formatCode="0.000"` es un formato propio; los ids hasta
 * 163 están reservados por el formato. Reutilizar uno reservado con otro código
 * es lo que hace que Excel avise de que el archivo «tiene un problema».
 */
private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="0.000"/></numFmts><fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts><fills count="1"><fill><patternFill patternType="none"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/></cellXfs></styleSheet>"""
