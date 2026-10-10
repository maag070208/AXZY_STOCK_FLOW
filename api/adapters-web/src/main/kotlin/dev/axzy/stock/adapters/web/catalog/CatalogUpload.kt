package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.Outcome
import dev.axzy.stock.application.shared.failure
import dev.axzy.stock.domain.catalog.CatalogImporter
import dev.axzy.stock.domain.catalog.CatalogRow
import dev.axzy.stock.domain.common.asRight
import org.springframework.web.multipart.MultipartFile

/**
 * Convierte el archivo subido en filas del catálogo.
 *
 * **CSV y TSV.** El archivo real de la migración viaja como TSV
 * (`scripts/catalogo-existencias.tsv`), y el del análisis como CSV: se decide por
 * el contenido de la cabecera y no por la extensión, porque renombrar un archivo
 * no cambia su formato.
 *
 * **XLSX todavía no.** La lectura del Excel nativo llega con la migración de datos
 * (M10), que es donde vive el corte; hasta entonces se responde
 * `IMPORT_FILE_FORMAT` diciendo exactamente eso, en lugar de intentar leerlo y
 * fallar de una forma que parezca un error del usuario.
 */
class CatalogUpload(private val importer: CatalogImporter) {

    fun read(file: MultipartFile?): Outcome<List<CatalogRow>> {
        if (file == null) {
            return failure(FailureKind.INVALID_REQUEST, "FILE_REQUIRED", "Falta el archivo del catálogo.")
        }
        // Un archivo de cero bytes no es «falta el archivo»: llegó, y está vacío. El
        // cliente hace algo distinto con cada código (volver a adjuntar, o mirar el archivo).
        if (file.isEmpty) {
            return failure(FailureKind.INVALID_REQUEST, "EMPTY_FILE", "El archivo no tiene filas de datos.")
        }
        val name = file.originalFilename.orEmpty().lowercase()
        if (EXCEL.any { name.endsWith(it) }) {
            return failure(
                FailureKind.INVALID_REQUEST, "IMPORT_FILE_FORMAT",
                "Por ahora la importación acepta CSV o TSV. La lectura del Excel (XLSX) llega con la " +
                    "migración de datos; exporta la hoja a CSV para importarla hoy.",
            )
        }
        if (name.isNotEmpty() && SUPPORTED.none { name.endsWith(it) }) {
            return failure(
                FailureKind.INVALID_REQUEST, "IMPORT_FILE_FORMAT",
                "Formato no admitido. Se acepta CSV o TSV.",
                mapOf("file" to name),
            )
        }

        val text = String(file.bytes, Charsets.UTF_8)
        val rows = importer.parseCsv(text, delimiterOf(text))
        if (rows.isEmpty()) {
            return failure(FailureKind.INVALID_REQUEST, "EMPTY_FILE", "El archivo no tiene filas de datos.")
        }
        return rows.asRight()
    }

    /** Con tabuladores gana el TSV; si no, coma. Se mira solo la cabecera. */
    private fun delimiterOf(text: String): Char {
        val header = text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val tabs = header.count { it == '\t' }
        val commas = header.count { it == ',' }
        return if (tabs > commas) '\t' else ','
    }

    private companion object {
        val SUPPORTED = listOf(".csv", ".tsv", ".txt")
        val EXCEL = listOf(".xlsx", ".xls")
    }
}
