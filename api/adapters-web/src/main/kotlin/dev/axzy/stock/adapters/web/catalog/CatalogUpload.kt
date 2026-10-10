package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.catalog.CatalogImporter
import dev.axzy.stock.domain.catalog.CatalogRow
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.springframework.web.multipart.MultipartFile
import java.util.Locale

/**
 * Convierte el archivo subido en filas del catálogo.
 *
 * **CSV, TSV y XLSX.** El delimitador se decide por el contenido de la cabecera y no por la
 * extensión, porque renombrar un archivo no cambia su formato.
 *
 * **El XLSX se convierte a CSV y se parsea con el mismo lector que los demás.** Leerlo por
 * separado sería tener dos caminos de validación para el mismo dato, y el día que uno
 * cambiara el archivo de Excel y el de texto darían resultados distintos sin que nadie lo
 * notara. La conversión solo se ocupa de lo que es del formato: celdas, comillas y saltos.
 */
class CatalogUpload(private val importer: CatalogImporter) {

    fun read(file: MultipartFile?): Outcome<List<CatalogRow>> {
        if (file == null) {
            return failure(FailureKind.INVALID_REQUEST, "FILE_REQUIRED", "The catalog file is missing.")
        }
        // Un archivo de cero bytes no es «falta el archivo»: llegó, y está vacío. El
        // cliente hace algo distinto con cada código (volver a adjuntar, o mirar el archivo).
        if (file.isEmpty) {
            return failure(FailureKind.INVALID_REQUEST, "EMPTY_FILE", "The file has no data rows.")
        }
        val name = file.originalFilename.orEmpty().lowercase()
        if (name.isNotEmpty() && SUPPORTED.none { name.endsWith(it) }) {
            return failure(
                FailureKind.INVALID_REQUEST, "IMPORT_FILE_FORMAT",
                "Unsupported format. CSV, TSV or XLSX is accepted.",
                mapOf("file" to name),
            )
        }

        val text = if (EXCEL.any { name.endsWith(it) }) {
            when (val sheet = toCsv(file)) {
                is Either.Left -> return sheet.error.asLeft()
                is Either.Right -> sheet.value
            }
        } else {
            String(file.bytes, Charsets.UTF_8)
        }

        val rows = importer.parseCsv(text, delimiterOf(text))
        if (rows.isEmpty()) {
            return failure(FailureKind.INVALID_REQUEST, "EMPTY_FILE", "The file has no data rows.")
        }
        return rows.asRight()
    }

    /**
     * La primera hoja del libro, como CSV.
     *
     * `DataFormatter` es lo que hace que un número salga como se ve en la hoja (`10914130`)
     * y no como lo imprime un `double` (`1.091413E7`), que rompería el parseo del stock. Se
     * fija el idioma a inglés para que el separador decimal no dependa de la máquina donde
     * corra la API.
     */
    private fun toCsv(file: MultipartFile): Outcome<String> = try {
        WorkbookFactory.create(file.inputStream).use { workbook ->
            val sheet = workbook.getSheetAt(0)
            if (sheet == null) {
                failure(FailureKind.INVALID_REQUEST, "EMPTY_FILE", "The workbook has no sheets.")
            } else {
                val formatter = DataFormatter(Locale.US)
                val text = buildString {
                    sheet.forEach { row ->
                        val line = row.map { cell -> formatter.formatCellValue(cell) }
                        appendLine(line.joinToString(",") { field -> csvField(field) })
                    }
                }
                text.asRight()
            }
        }
    } catch (error: Exception) {
        // Un Excel corrupto es un error del archivo, no del servidor: `500` mandaría al
        // usuario a mirar los registros de la API por algo que puede arreglar él.
        failure(
            FailureKind.INVALID_REQUEST, "IMPORT_FILE_FORMAT",
            "The Excel file could not be read: ${error.message ?: "the file is not a valid workbook"}.",
            mapOf("file" to file.originalFilename.orEmpty()),
        )
    }

    /** Un campo con coma, comilla o salto de línea va entre comillas, y la comilla se dobla. */
    private fun csvField(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    /** Con tabuladores gana el TSV; si no, coma. Se mira solo la cabecera. */
    private fun delimiterOf(text: String): Char {
        val header = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val tabs = header.count { it == '\t' }
        val commas = header.count { it == ',' }
        return if (tabs > commas) '\t' else ','
    }

    private companion object {
        val SUPPORTED = listOf(".csv", ".tsv", ".txt", ".xlsx", ".xls")
        val EXCEL = listOf(".xlsx", ".xls")
    }
}
