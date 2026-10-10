package dev.axzy.stock.adapters.web.reports

import dev.axzy.stock.application.inventory.KardexLine
import dev.axzy.stock.application.reports.ExportFormat
import dev.axzy.stock.application.reports.KardexExport
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.streaming.SXSSFWorkbook
import java.io.OutputStream
import java.math.BigDecimal

/**
 * The ledger export, in the two formats of the contract.
 *
 * **It is written in chunks.** The rows arrive as a `Sequence` and are written as they come: the
 * ledger of a year is not loaded whole into memory. For XLSX `SXSSFWorkbook` is used
 * —the streaming variant of POI— for the same reason.
 */
internal object KardexExportWriter {

    private val HEADERS = listOf(
        "id", "fecha", "codigo", "producto", "movimiento", "unidades", "saldo",
        "origen", "destino", "contenedor", "orden", "motivo", "source of the adjustment",
        "usuario", "nota",
    )

    private fun row(line: KardexLine): List<String?> = listOf(
        line.id.toString(),
        line.movedAt.toString(),
        line.productCode,
        line.productName,
        line.moveType,
        line.units.toPlainString(),
        line.quantityAfter.toPlainString(),
        line.fromLocationDisplayName,
        line.toLocationDisplayName,
        line.container,
        line.orderNumber,
        line.adjustmentReason,
        line.adjustmentOrigin,
        line.userName,
        line.note,
    )

    fun write(export: KardexExport, out: OutputStream) {
        when (export.format) {
            ExportFormat.CSV -> writeCsv(export.chunks, out)
            ExportFormat.XLSX -> writeXlsx(export.chunks, out)
        }
    }

    // ── CSV ─────────────────────────────────────────────────────────────────

    private fun writeCsv(chunks: Sequence<List<KardexLine>>, out: OutputStream) {
        /*
         * With a byte order mark (BOM), and not without one: Excel opens a UTF-8 CSV
         * without a BOM as if it were in the system encoding, and the accents of the
         * product names come out as `ABRAZADERA DE ARRANQUE 1-5/8Â"`. The mark
         * is the way to have the file read as it was written.
         */
        out.write(0xEF)
        out.write(0xBB)
        out.write(0xBF)
        val writer = out.bufferedWriter(Charsets.UTF_8)
        writer.appendLine(HEADERS.joinToString(",") { escape(it) })
        chunks.forEach { chunk ->
            chunk.forEach { line ->
                writer.appendLine(row(line).joinToString(",") { escape(it) })
            }
        }
        writer.flush()
    }

    /** A field with a comma, quote or line break goes in quotes, and the quote is doubled. */
    private fun escape(value: String?): String {
        if (value == null) return ""
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    // ── XLSX ────────────────────────────────────────────────────────────────

    private fun writeXlsx(chunks: Sequence<List<KardexLine>>, out: OutputStream) {
        // 100 rows in memory and the rest in a temporary file: that is what allows
        // exporting a long ledger without the process growing with it.
        val workbook = SXSSFWorkbook(100)
        try {
            val sheet = workbook.createSheet("Kardex")
            val headerStyle = headerStyle(workbook)
            val quantityStyle = quantityStyle(workbook)
            val integerStyle = workbook.createCellStyle()

            val header = sheet.createRow(0)
            HEADERS.forEachIndexed { index, title ->
                header.createCell(index).apply {
                    setCellValue(title)
                    cellStyle = headerStyle
                }
            }

            var rowIndex = 1
            chunks.forEach { chunk ->
                chunk.forEach { line ->
                    val values = row(line)
                    val row = sheet.createRow(rowIndex++)
                    values.forEachIndexed { index, value ->
                        val cell = row.createCell(index)
                        // The two quantity columns and the id go as NUMBERS: a
                        // text column is not summed and Excel would count it as zero.
                        when (index) {
                            // The id is an integer; the two quantities carry the
                            // three decimals of the contract.
                            0 -> {
                                cell.setCellValue(line.id.toDouble())
                                cell.cellStyle = integerStyle
                            }
                            in QUANTITY_COLUMNS -> {
                                val quantity = if (index == 5) line.units else line.quantityAfter
                                cell.setCellValue(quantity.toBigDecimal().toDouble())
                                cell.cellStyle = quantityStyle
                            }
                            else -> cell.setCellValue(value ?: "")
                        }
                    }
                }
            }

            // Fixed widths and not `autoSizeColumn`: with a long file, auto-sizing
            // each column walks all the rows in memory and defeats the stream.
            listOf(8, 22, 14, 40, 20, 12, 12, 14, 14, 14, 14, 16, 16, 18, 30).forEachIndexed { index, width ->
                sheet.setColumnWidth(index, width * 256)
            }
            sheet.createFreezePane(0, 1)

            workbook.write(out)
            out.flush()
        } finally {
            workbook.close()
            // The stream's temporary files are deleted explicitly: `close()`
            // does not always clean them up and they would end up filling the container's disk.
            workbook.dispose()
        }
    }

    /** The quantity columns: units and balance. */
    private val QUANTITY_COLUMNS = 5..6

    private fun headerStyle(workbook: Workbook) = workbook.createCellStyle().apply {
        fillForegroundColor = IndexedColors.GREY_25_PERCENT.index
        fillPattern = FillPatternType.SOLID_FOREGROUND
        borderBottom = BorderStyle.THIN
    }

    private fun quantityStyle(workbook: Workbook) = workbook.createCellStyle().apply {
        dataFormat = workbook.createDataFormat().getFormat("#,##0.000")
    }
}

/** The domain quantity as an exact decimal (thousandths), without going through floating point. */
internal fun dev.axzy.stock.domain.value.Quantity.toBigDecimal(): BigDecimal =
    BigDecimal.valueOf(value, dev.axzy.stock.domain.value.Quantity.SCALE)

/** The quantity with the contract's three decimals, without scientific notation. */
internal fun dev.axzy.stock.domain.value.Quantity.toPlainString(): String = toBigDecimal().toPlainString()
