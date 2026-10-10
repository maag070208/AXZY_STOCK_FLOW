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
 * Converts the uploaded file into catalog rows.
 *
 * **CSV, TSV and XLSX.** The delimiter is decided by the header content, not by the
 * extension, because renaming a file does not change its format.
 *
 * **The XLSX is converted to CSV and parsed with the same reader as the others.** Reading it
 * separately would mean two validation paths for the same data, and the day one
 * changed the Excel file and the text one would give different results without anyone
 * noticing. The conversion only handles what belongs to the format: cells, quotes and line breaks.
 */
class CatalogUpload(private val importer: CatalogImporter) {

    fun read(file: MultipartFile?): Outcome<List<CatalogRow>> {
        if (file == null) {
            return failure(FailureKind.INVALID_REQUEST, "FILE_REQUIRED", "The catalog file is missing.")
        }
        // A zero-byte file is not "the file is missing": it arrived, and it is empty. The
        // client does something different with each code (attach it again, or look at the file).
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
     * The first sheet of the workbook, as CSV.
     *
     * `DataFormatter` is what makes a number come out the way it looks in the sheet (`10914130`)
     * and not the way a `double` prints it (`1.091413E7`), which would break the stock parsing. The
     * language is fixed to English so that the decimal separator does not depend on the machine
     * the API runs on.
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
        // A corrupt Excel is a file error, not a server error: a `500` would send the
        // user to look at the API logs for something they can fix themselves.
        failure(
            FailureKind.INVALID_REQUEST, "IMPORT_FILE_FORMAT",
            "The Excel file could not be read: ${error.message ?: "the file is not a valid workbook"}.",
            mapOf("file" to file.originalFilename.orEmpty()),
        )
    }

    /** A field with a comma, quote or line break goes in quotes, and the quote is doubled. */
    private fun csvField(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    /** With tabs, TSV wins; otherwise comma. Only the header is checked. */
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
