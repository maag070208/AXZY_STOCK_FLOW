package dev.axzy.stock.adapters.web.reports

import dev.axzy.stock.application.inventory.KardexLine
import dev.axzy.stock.application.reports.ExportFormat
import dev.axzy.stock.application.reports.KardexExport
import dev.axzy.stock.domain.value.Quantity
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant

/**
 * La exportación del kardex.
 *
 * Lo que se comprueba aquí es lo que el contrato promete y no se ve en el código:
 * **en XLSX las cantidades son celdas numéricas**, no texto. Una columna de texto no
 * se suma y Excel la contaría como cero, así que un archivo que "se ve bien" puede
 * ser inútil.
 */
class KardexExportWriterTest {

    private fun line(
        id: Long = 1,
        productName: String = "ABRAZADERA DE ARRANQUE 1-5/8\"",
        container: String? = "CONT-01",
        note: String? = null,
    ) = KardexLine(
        id = id,
        movedAt = Instant.parse("2026-10-07T21:24:37.688Z"),
        productCode = "ABRAA1B",
        productName = productName,
        moveType = "RECEIPT_IN",
        units = Quantity.ofScaled(100_000),
        quantityAfter = Quantity.ofScaled(1_100_000),
        fromLocationDisplayName = null,
        toLocationDisplayName = "RECEIVING",
        container = container,
        orderNumber = null,
        adjustmentReason = null,
        adjustmentOrigin = null,
        userId = 7,
        userName = "Juan Pérez",
        note = note,
    )

    private fun export(format: ExportFormat, rows: List<KardexLine>, chunkSize: Int = 2) =
        KardexExport(format = format, chunks = rows.chunked(chunkSize).asSequence())

    private fun csvOf(rows: List<KardexLine>): String {
        val out = ByteArrayOutputStream()
        KardexExportWriter.write(export(ExportFormat.CSV, rows), out)
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    // ── CSV ─────────────────────────────────────────────────────────────────

    @Test
    fun `el CSV lleva cabecera y una fila por movimiento`() {
        val csv = csvOf(listOf(line(1), line(2)))

        val lines = csv.removePrefix("\uFEFF").trim().lines()
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).startsWith("id,fecha,codigo,producto")
        assertThat(lines[1]).contains("ABRAA1B").contains("100.000").contains("1100.000")
    }

    @Test
    fun `un campo con coma o comilla va entrecomillado y la comilla se dobla`() {
        val csv = csvOf(listOf(line(productName = "ABRAZADERA 1-5/8\", CON TUERCA", note = "Caja rota, pasillo 3")))

        // El nombre lleva comilla y coma: sin entrecomillar, el archivo tendría una
        // columna de más y ninguna fila cuadraría con la cabecera.
        assertThat(csv).contains("\"ABRAZADERA 1-5/8\"\", CON TUERCA\"")
        assertThat(csv).contains("\"Caja rota, pasillo 3\"")
    }

    @Test
    fun `el CSV empieza con marca de orden de bytes para que Excel lea los acentos`() {
        val bytes = ByteArrayOutputStream().also { KardexExportWriter.write(export(ExportFormat.CSV, listOf(line())), it) }
            .toByteArray()

        assertThat(bytes.take(3)).containsExactly(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }

    // ── XLSX ────────────────────────────────────────────────────────────────

    @Test
    fun `en XLSX las cantidades son celdas numericas con tres decimales`() {
        val out = ByteArrayOutputStream()
        KardexExportWriter.write(export(ExportFormat.XLSX, listOf(line())), out)

        val workbook = WorkbookFactory.create(ByteArrayInputStream(out.toByteArray()))
        try {
            val sheet = workbook.getSheetAt(0)
            val row = sheet.getRow(1)

            // El id es un entero y las dos cantidades, números: es lo que hace que una
            // columna de existencias se pueda sumar en Excel.
            assertThat(row.getCell(0).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(row.getCell(5).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(row.getCell(6).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(row.getCell(5).numericCellValue).isEqualTo(100.0)
            assertThat(row.getCell(6).numericCellValue).isEqualTo(1100.0)
            assertThat(row.getCell(5).cellStyle.dataFormat).isNotZero()

            // Y el texto sigue siendo texto.
            assertThat(row.getCell(2).stringCellValue).isEqualTo("ABRAA1B")
            assertThat(row.getCell(7).stringCellValue).isEqualTo("")   // origen nulo
            assertThat(sheet.getRow(0).getCell(1).stringCellValue).isEqualTo("fecha")
        } finally {
            workbook.close()
        }
    }

    @Test
    fun `el XLSX se escribe por trozos sin perder filas`() {
        val rows = (1..7).map { line(id = it.toLong()) }

        val out = ByteArrayOutputStream()
        // Trozos de 2 y 7 filas: el último trozo es incompleto a propósito.
        KardexExportWriter.write(export(ExportFormat.XLSX, rows, chunkSize = 2), out)

        val sheet = WorkbookFactory.create(ByteArrayInputStream(out.toByteArray())).use { it.getSheetAt(0) }
        assertThat(sheet.lastRowNum).isEqualTo(7)   // cabecera + 7
    }
}
