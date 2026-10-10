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
 * La exportación del kardex, en los dos formatos del contrato.
 *
 * **Se escribe por trozos.** Las filas llegan en `Sequence` y se van escribiendo: el
 * kardex de un año no se carga entero en memoria. En XLSX se usa `SXSSFWorkbook`
 * —la variante de flujo de POI— por la misma razón.
 */
internal object KardexExportWriter {

    private val HEADERS = listOf(
        "id", "fecha", "codigo", "producto", "movimiento", "unidades", "saldo",
        "origen", "destino", "contenedor", "orden", "motivo", "origen del ajuste",
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
         * Con marca de orden de bytes (BOM) y no sin ella: Excel abre un CSV UTF-8
         * sin BOM como si fuera de la codificación del sistema, y los acentos de los
         * nombres de producto salen como `ABRAZADERA DE ARRANQUE 1-5/8Â"`. La marca
         * es la forma de que el archivo se lea como se escribió.
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

    /** Un campo con coma, comilla o salto de línea va entre comillas, y la comilla se dobla. */
    private fun escape(value: String?): String {
        if (value == null) return ""
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        val escaped = value.replace("\"", "\"\"")
        return if (needsQuotes) "\"$escaped\"" else escaped
    }

    // ── XLSX ────────────────────────────────────────────────────────────────

    private fun writeXlsx(chunks: Sequence<List<KardexLine>>, out: OutputStream) {
        // 100 filas en memoria y el resto a un archivo temporal: es lo que permite
        // exportar un kardex largo sin que el proceso crezca con él.
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
                        // Las dos columnas de cantidad y el id van como NÚMERO: una
                        // columna de texto no se suma y Excel la contaría como cero.
                        when (index) {
                            // El id es un entero; las dos cantidades llevan los tres
                            // decimales del contrato.
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

            // Anchos fijos y no `autoSizeColumn`: con un archivo largo, autoajustar
            // cada columna recorre todas las filas en memoria y anula el flujo.
            listOf(8, 22, 14, 40, 20, 12, 12, 14, 14, 14, 14, 16, 16, 18, 30).forEachIndexed { index, width ->
                sheet.setColumnWidth(index, width * 256)
            }
            sheet.createFreezePane(0, 1)

            workbook.write(out)
            out.flush()
        } finally {
            workbook.close()
            // Los archivos temporales del flujo se borran explícitamente: `close()`
            // no siempre los limpia y acabarían llenando el disco del contenedor.
            workbook.dispose()
        }
    }

    /** Las columnas de cantidad: unidades y saldo. */
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

/** La cantidad del dominio a decimal exacto (milésimas), sin pasar por coma flotante. */
internal fun dev.axzy.stock.domain.value.Quantity.toBigDecimal(): BigDecimal =
    BigDecimal.valueOf(value, dev.axzy.stock.domain.value.Quantity.SCALE)

/** La cantidad con los tres decimales del contrato, sin notación científica. */
internal fun dev.axzy.stock.domain.value.Quantity.toPlainString(): String = toBigDecimal().toPlainString()
