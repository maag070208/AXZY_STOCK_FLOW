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
 * El lector del archivo de importación y la traducción del archivo demasiado
 * grande, sin arrancar la aplicación: son reglas de la capa web.
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
    fun `un CSV se lee por columnas y la cabecera no se convierte en producto`() {
        val parsed = rows("externalId,productCode,name,unitMeasure,stock\n1,ABRAA1B,ABRAZADERA,PZA,10")

        assertThat(parsed).hasSize(1)
        assertThat(parsed.single().productCode).isEqualTo("ABRAA1B")
        assertThat(parsed.single().stock).isEqualTo("10")
        // El número de fila es el FÍSICO del archivo: la cabecera es la 1.
        assertThat(parsed.single().rowNumber).isEqualTo(2)
    }

    @Test
    fun `un TSV se lee por tabuladores, que es como viaja el archivo de la migracion`() {
        val parsed = rows("externalId\tproductCode\tname\tunitMeasure\tstock\n1\tABRAA1B\tABRAZADERA\tPZA\t10")

        assertThat(parsed.single().productCode).isEqualTo("ABRAA1B")
        assertThat(parsed.single().name).isEqualTo("ABRAZADERA")
    }

    @Test
    fun `el BOM de Excel no se cuela en la primera columna`() {
        val parsed = rows("\uFEFFexternalId,productCode,name,unitMeasure,stock\n1,ABRAA1B,ABRAZADERA,PZA,10")

        assertThat(parsed.single().externalId).isEqualTo("1")
    }

    @Test
    fun `un archivo vacio o solo con cabecera responde EMPTY_FILE`() {
        assertThat(errorCode("")).isEqualTo("EMPTY_FILE")
        assertThat(errorCode("externalId,productCode,name,unitMeasure,stock")).isEqualTo("EMPTY_FILE")
    }

    @Test
    fun `sin archivo responde FILE_REQUIRED`() {
        assertThat((upload.read(null) as Either.Left).error.code).isEqualTo("FILE_REQUIRED")
    }

    @Test
    fun `un Excel que no se puede leer responde IMPORT_FILE_FORMAT y no un 500`() {
        // Antes esta prueba comprobaba que el XLSX se **rechazaba** diciendo que llegaría
        // con la migración. Ya llega: ahora se lee, y lo que se comprueba es que un libro
        // corrupto es un error del archivo y no del servidor.
        assertThat(errorCode("contenido binario", name = "catalogo.xlsx")).isEqualTo("IMPORT_FILE_FORMAT")
    }

    @Test
    fun `un formato que no es de texto se rechaza antes de intentar leerlo`() {
        assertThat(errorCode("lo que sea", name = "catalogo.pdf")).isEqualTo("IMPORT_FILE_FORMAT")
    }

    @Test
    fun `un archivo demasiado grande responde FILE_TOO_LARGE y no un 500`() {
        val response = GlobalErrorHandler()
            .fileTooLarge()

        assertThat(response.statusCode.value()).isEqualTo(400)
        assertThat(response.body!!.toString()).contains("FILE_TOO_LARGE")
    }


    /** Un CSV bien formado: un campo con coma va entre comillas. */
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
                // Los números van como número, que es como los escribe Excel.
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

    /** El Excel viaja con su tipo real, como lo manda un navegador. */
    private fun readXlsx(bytes: ByteArray, name: String = "catalogo.xlsx") =
        upload.read(
            MockMultipartFile(
                "file",
                name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                bytes,
            ),
        )

    /** Las filas del Excel, para poder compararlas con las del CSV. */
    private fun rows(bytes: ByteArray): List<CatalogRow> = (upload.read(MockMultipartFile(
        "file", "catalogo.xlsx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes,
    )) as Either.Right).value

    private val headers = listOf("externalId", "productCode", "name", "unitMeasure", "stock")


    @Test
    fun `el Excel y el CSV producen las mismas filas`() {
        val datos = listOf(
            listOf("1", "ABRAA1B", "ABRAZADERA DE ARRANQUE", "PZA", "1500"),
            listOf("2", "TORNILLO^M6", "TORNILLO CON TUERCA, GRANDE", "PZA", "0"),
        )

        val delCsv = rows(csv(listOf(headers) + datos))
        val delExcel = rows(readXlsx(xlsx(listOf(headers) + datos)))

        assertThat(delExcel).isEqualTo(delCsv)
        // El nombre lleva una coma: si la conversión no la entrecomillara, el campo se
        // partiría en dos y el archivo entero se rechazaría por una fila de más.
        assertThat(delExcel[1].name).isEqualTo("TORNILLO CON TUERCA, GRANDE")
        assertThat(delExcel[0].stock).isEqualTo("1500")
    }

    @Test
    fun `un numero del Excel no sale en notacion cientifica`() {
        // Un `double` imprimiría `1.091413E7` y el parseo del stock fallaría.
        val bytes = xlsx(listOf(headers, listOf("1", "ABRAA1B", "ABRAZADERA", "PZA", "10914130")))

        val parsed = rows(readXlsx(bytes))

        assertThat(parsed.single().stock).isEqualTo("10914130")
    }

    @Test
    fun `el delimitador se decide por la cabecera y no por la extension`() {
        val tsv = (listOf(headers) + listOf(listOf("1", "ABRAA1B", "ABRAZADERA", "PZA", "10")))
            .joinToString("\n") { it.joinToString("\t") }

        // El nombre dice `.csv` y el contenido es TSV: manda el contenido.
        val parsed = rows(tsv, name = "catalogo.csv")

        assertThat(parsed.single().productCode).isEqualTo("ABRAA1B")
        assertThat(parsed.single().stock).isEqualTo("10")
    }

    @Test
    fun `un archivo de otro formato se rechaza diciendo cual se acepta`() {
        val mensaje = (upload.read(file("lo que sea", "catalogo.pdf")) as Either.Left).error.message

        assertThat(mensaje).contains("CSV, TSV o XLSX")
    }

}
