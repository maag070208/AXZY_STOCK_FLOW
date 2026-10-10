package dev.axzy.stock.adapters.web.catalog

import dev.axzy.stock.adapters.web.http.GlobalErrorHandler
import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.catalog.CatalogImporter
import dev.axzy.stock.domain.catalog.CatalogRow
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
    fun `el Excel se rechaza diciendo que llega con la migracion`() {
        val code = errorCode("contenido binario", name = "catalogo.xlsx")

        assertThat(code).isEqualTo("IMPORT_FILE_FORMAT")
        assertThat((upload.read(file("x", "catalogo.xlsx")) as Either.Left).error.message)
            .contains("migración de datos")
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
}
