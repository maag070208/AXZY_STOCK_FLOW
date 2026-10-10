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
 * The ledger export.
 *
 * What is checked here is what the contract promises and is not visible in the code:
 * **in XLSX the quantities are numeric cells**, not text. A text column is not
 * summed and Excel would count it as zero, so a file that "looks right" can be
 * useless.
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
    fun `the CSV has a header and one row per movement`() {
        val csv = csvOf(listOf(line(1), line(2)))

        val lines = csv.removePrefix("\uFEFF").trim().lines()
        assertThat(lines).hasSize(3)
        assertThat(lines[0]).startsWith("id,fecha,codigo,producto")
        assertThat(lines[1]).contains("ABRAA1B").contains("100.000").contains("1100.000")
    }

    @Test
    fun `a field with a comma or quote is quoted and the quote is doubled`() {
        val csv = csvOf(listOf(line(productName = "ABRAZADERA 1-5/8\", CON TUERCA", note = "Caja rota, pasillo 3")))

        // The name has a quote and a comma: without quoting, the file would have one
        // extra column and no row would match the header.
        assertThat(csv).contains("\"ABRAZADERA 1-5/8\"\", CON TUERCA\"")
        assertThat(csv).contains("\"Caja rota, pasillo 3\"")
    }

    @Test
    fun `the CSV starts with a byte order mark so Excel reads the accents`() {
        val bytes = ByteArrayOutputStream().also { KardexExportWriter.write(export(ExportFormat.CSV, listOf(line())), it) }
            .toByteArray()

        assertThat(bytes.take(3)).containsExactly(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }

    // ── XLSX ────────────────────────────────────────────────────────────────

    @Test
    fun `in XLSX the quantities are numeric cells with three decimals`() {
        val out = ByteArrayOutputStream()
        KardexExportWriter.write(export(ExportFormat.XLSX, listOf(line())), out)

        val workbook = WorkbookFactory.create(ByteArrayInputStream(out.toByteArray()))
        try {
            val sheet = workbook.getSheetAt(0)
            val row = sheet.getRow(1)

            // The id is an integer and the two quantities are numbers: that is what makes a
            // stock column summable in Excel.
            assertThat(row.getCell(0).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(row.getCell(5).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(row.getCell(6).cellType).isEqualTo(CellType.NUMERIC)
            assertThat(row.getCell(5).numericCellValue).isEqualTo(100.0)
            assertThat(row.getCell(6).numericCellValue).isEqualTo(1100.0)
            assertThat(row.getCell(5).cellStyle.dataFormat).isNotZero()

            // And the text stays text.
            assertThat(row.getCell(2).stringCellValue).isEqualTo("ABRAA1B")
            assertThat(row.getCell(7).stringCellValue).isEqualTo("")   // origen nulo
            assertThat(sheet.getRow(0).getCell(1).stringCellValue).isEqualTo("fecha")
        } finally {
            workbook.close()
        }
    }

    @Test
    fun `the XLSX is written in chunks without losing rows`() {
        val rows = (1..7).map { line(id = it.toLong()) }

        val out = ByteArrayOutputStream()
        // Chunks of 2 and 7 rows: the last chunk is incomplete on purpose.
        KardexExportWriter.write(export(ExportFormat.XLSX, rows, chunkSize = 2), out)

        val sheet = WorkbookFactory.create(ByteArrayInputStream(out.toByteArray())).use { it.getSheetAt(0) }
        assertThat(sheet.lastRowNum).isEqualTo(7)   // cabecera + 7
    }
}
