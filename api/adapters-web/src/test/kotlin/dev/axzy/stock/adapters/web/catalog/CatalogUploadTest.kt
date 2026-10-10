package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.adapters.web.http.GlobalErrorHandler
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.catalog.CatalogImporter
import dev.axzy.stock.domain.catalog.CatalogRow
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockMultipartFile

/**
 * The reader of the import file and the translation of the file that is too
 * large, without starting the application: they are rules of the web layer.
 */
class CatalogUploadTest {

    private val upload = CatalogUpload(CatalogImporter())

    private fun file(content: String, name: String = "catalogo.csv") =
        MockMultipartFile("file", name, "text/csv", content.toByteArray(Charsets.UTF_8))

    private fun rows(content: String, name: String = "catalogo.csv"): List<CatalogRow> =
        (upload.read(file(content, name)) as Either.Right).value

    private fun errorCode(content: String, name: String = "catalogo.csv"): String? =
        (upload.read(file(content, name)) as? Either.Left)?.error?.code

    @Test
    fun `a CSV is read by columns and the header is not turned into a product`() {
        val parsed = rows("externalId,productCode,name,unitMeasure,stock\n1,ABRAA1B,ABRAZADERA,PZA,10")

        assertThat(parsed).hasSize(1)
        assertThat(parsed.single().productCode).isEqualTo("ABRAA1B")
        assertThat(parsed.single().stock).isEqualTo("10")
        // The row number is the file's PHYSICAL row: the header is row 1.
        assertThat(parsed.single().rowNumber).isEqualTo(2)
    }

    @Test
    fun `a TSV is read by tabs, which is how the migration file travels`() {
        val parsed = rows("externalId\tproductCode\tname\tunitMeasure\tstock\n1\tABRAA1B\tABRAZADERA\tPZA\t10")

        assertThat(parsed.single().productCode).isEqualTo("ABRAA1B")
        assertThat(parsed.single().name).isEqualTo("ABRAZADERA")
    }

    @Test
    fun `Excel's BOM does not leak into the first column`() {
        val parsed = rows("\uFEFFexternalId,productCode,name,unitMeasure,stock\n1,ABRAA1B,ABRAZADERA,PZA,10")

        assertThat(parsed.single().externalId).isEqualTo("1")
    }

    @Test
    fun `an empty file or one with only a header answers EMPTY_FILE`() {
        assertThat(errorCode("")).isEqualTo("EMPTY_FILE")
        assertThat(errorCode("externalId,productCode,name,unitMeasure,stock")).isEqualTo("EMPTY_FILE")
    }

    @Test
    fun `without a file answers FILE_REQUIRED`() {
        assertThat((upload.read(null) as Either.Left).error.code).isEqualTo("FILE_REQUIRED")
    }

    @Test
    fun `an Excel that cannot be read answers IMPORT_FILE_FORMAT and not a 500`() {
        // This test used to check that the XLSX was **rejected**, saying it would arrive
        // with the migration. It arrives now: it is read, and what is checked is that a
        // corrupt workbook is a file error and not a server error.
        assertThat(errorCode("contenido binario", name = "catalogo.xlsx")).isEqualTo("IMPORT_FILE_FORMAT")
    }

    @Test
    fun `a format that is not text is rejected before trying to read it`() {
        assertThat(errorCode("lo que sea", name = "catalogo.pdf")).isEqualTo("IMPORT_FILE_FORMAT")
    }

    @Test
    fun `a file that is too large answers FILE_TOO_LARGE and not a 500`() {
        val response = GlobalErrorHandler()
            .fileTooLarge()

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body!!.toString()).contains("FILE_TOO_LARGE")
    }


    /** A well-formed CSV: a field with a comma goes in quotes. */
    private fun csv(rows: List<List<String>>): String =
        rows.joinToString("\n") { row ->
            row.joinToString(",") { field ->
                if (field.any { it == ',' || it == '"' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
            }
        }

    private fun xlsx(rows: List<List<String>>): ByteArray {
        val workbook: Workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("Catálogo")
        rows.forEachIndexed { index, values ->
            val row = sheet.createRow(index)
            values.forEachIndexed { column, value ->
                // Numbers go as numbers, the way Excel writes them.
                if (column == 4 && value.toDoubleOrNull() != null) {
                    row.createCell(column).setCellValue(value.toDouble())
                } else {
                    row.createCell(column).setCellValue(value)
                }
            }
        }
        val out = ByteArrayOutputStream()
        workbook.write(out)
        workbook.close()
        return out.toByteArray()
    }

    /** The Excel travels with its real type, as a browser sends it. */
    private fun readXlsx(bytes: ByteArray, name: String = "catalogo.xlsx") =
        upload.read(
            MockMultipartFile(
                "file",
                name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes,
            ),
        )

    /** The Excel rows, so they can be compared with the CSV ones. */
    private fun rows(bytes: ByteArray): List<CatalogRow> = (upload.read(MockMultipartFile(
        "file", "catalogo.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes,
    )) as Either.Right).value

    private val headers = listOf("externalId", "productCode", "name", "unitMeasure", "stock")


    @Test
    fun `Excel and CSV produce the same rows`() {
        val datos = listOf(
            listOf("1", "ABRAA1B", "ABRAZADERA DE ARRANQUE", "PZA", "1500"),
            listOf("2", "TORNILLO^M6", "TORNILLO CON TUERCA, GRANDE", "PZA", "0"),
        )

        val delCsv = rows(csv(listOf(headers) + datos))
        val delExcel = rows(xlsx(listOf(headers) + datos))

        assertThat(delExcel).isEqualTo(delCsv)
        // The name has a comma: if the conversion did not quote it, the field would
        // split in two and the whole file would be rejected for one extra row.
        assertThat(delExcel[1].name).isEqualTo("TORNILLO CON TUERCA, GRANDE")
        assertThat(delExcel[0].stock).isEqualTo("1500")
    }

    @Test
    fun `a number in the Excel does not come out in scientific notation`() {
        // A `double` would print `1.091413E7` and the stock parsing would fail.
        val bytes = xlsx(listOf(headers, listOf("1", "ABRAA1B", "ABRAZADERA", "PZA", "10914130")))

        val parsed = rows(bytes)

        assertThat(parsed.single().stock).isEqualTo("10914130")
    }

    @Test
    fun `the delimiter is decided by the header and not by the extension`() {
        val tsv = (listOf(headers) + listOf(listOf("1", "ABRAA1B", "ABRAZADERA", "PZA", "10")))
            .joinToString("\n") { it.joinToString("\t") }

        // The name says `.csv` and the content is TSV: the content wins.
        val parsed = rows(tsv, name = "catalogo.csv")

        assertThat(parsed.single().productCode).isEqualTo("ABRAA1B")
        assertThat(parsed.single().stock).isEqualTo("10")
    }

    @Test
    fun `a file of another format is rejected saying which one is accepted`() {
        val mensaje = (upload.read(file("lo que sea", "catalogo.pdf")) as Either.Left).error.message

        assertThat(mensaje).contains("CSV, TSV or XLSX")
    }

}
