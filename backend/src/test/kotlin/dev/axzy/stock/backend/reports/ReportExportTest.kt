package dev.axzy.stock.backend.reports

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL EXPORTADOR DE REPORTES — CSV y XLSX
 *
 * **Se prueba releyendo el archivo, no comprobando que la función devuelve algo.**
 * Un exportador puede producir bytes sin fallar y que el archivo no abra: el ZIP
 * con un `CRC` mal puesto, una parte que falta del paquete OOXML o un XML sin
 * escapar dan un archivo que **solo se descubre roto cuando el usuario intenta
 * abrirlo en Excel**, que es el peor sitio para enterarse.
 *
 * Por eso cada prueba descomprime y lee lo que se ha escrito. Es la misma
 * disciplina que la del lector de XLSX: el formato se verifica con el formato.
 *
 * **Son pruebas puras**: no tocan la base ni el servidor, así que corren en
 * milisegundos y no se saltan nunca.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ReportExportTest : DescribeSpec({

    val tabla = ExportTable(
        headers = listOf("Producto", "Nombre", "Unidades"),
        rows = listOf(
            listOf("ABRAA1B", "ABRAZADERA DE ARRANQUE", "5193.000"),
            listOf("TORNILLO", "TORNILLO 1/4, GALVANIZADO", "0.500"),
        ),
    )

    /** Descomprime el XLSX y devuelve sus partes como texto. */
    fun partesXlsx(bytes: ByteArray): Map<String, String> {
        val partes = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entrada = zip.nextEntry
            while (entrada != null) {
                partes[entrada.name] = zip.readBytes().toString(Charsets.UTF_8)
                entrada = zip.nextEntry
            }
        }
        return partes
    }

    describe("CSV") {

        it("empieza por el BOM UTF-8, o Excel rompe los acentos en Windows") {
            val bytes = tabla.toCsv()
            // Los tres primeros bytes, uno a uno: un BOM «parecido» no sirve.
            bytes[0] shouldBe 0xEF.toByte()
            bytes[1] shouldBe 0xBB.toByte()
            bytes[2] shouldBe 0xBF.toByte()
        }

        it("entrecomilla el campo que lleva coma, y solo ese") {
            // **Este es el caso que rompe el archivo entero.** «TORNILLO 1/4,
            // GALVANIZADO» sin comillas parte la fila en dos columnas, y el
            // usuario ve el archivo descolocado a partir de esa línea.
            val texto = tabla.toCsv().toString(Charsets.UTF_8)

            texto shouldContain "\"TORNILLO 1/4, GALVANIZADO\""
            // El que no lleva coma NO se entrecomilla: entrecomillar todo es
            // válido pero hace ilegible el archivo al abrirlo en un editor.
            texto shouldContain "ABRAA1B,ABRAZADERA DE ARRANQUE,5193.000"
        }

        it("escapa las comillas dobles duplicándolas, como dice el RFC 4180") {
            val comillas = ExportTable(
                headers = listOf("A"),
                rows = listOf(listOf("""dice "hola"""")),
            ).toCsv().toString(Charsets.UTF_8)

            comillas shouldContain """"dice ""hola""""""
        }

        it("usa CRLF, que es lo que espera Excel") {
            tabla.toCsv().toString(Charsets.UTF_8) shouldContain "\r\n"
        }
    }

    describe("XLSX") {

        it("es un ZIP con TODAS las partes que el formato exige") {
            val partes = partesXlsx(tabla.toXlsx("Kardex"))

            // Si falta cualquiera de estas, Excel abre el archivo con un aviso de
            // «contenido ilegible» o directamente no lo abre.
            listOf(
                "[Content_Types].xml",
                "_rels/.rels",
                "xl/workbook.xml",
                "xl/_rels/workbook.xml.rels",
                "xl/worksheets/sheet1.xml",
                "xl/styles.xml",
            ).forEach { parte ->
                (parte in partes) shouldBe true
            }
        }

        it("los CRC son válidos: el ZIP se puede descomprimir entero") {
            // `ZipInputStream` valida el CRC al leer cada entrada y lanza si no
            // cuadra. Que `partesXlsx` no reviente es la comprobación.
            partesXlsx(tabla.toXlsx()).keys.size shouldBe 6
        }

        it("las cabeceras van en la primera fila y las filas después") {
            val hoja = partesXlsx(tabla.toXlsx())["xl/worksheets/sheet1.xml"]!!

            hoja shouldContain "<row r=\"1\">"
            hoja shouldContain "<row r=\"2\">"
            hoja shouldContain "<row r=\"3\">"
            hoja shouldContain "Producto"
            hoja shouldContain "ABRAA1B"
        }

        it("las cantidades van como NÚMERO con estilo de 3 decimales, no como texto") {
            // **La decisión que importa de todo el formato.** En JSON las
            // cantidades van como texto (§1.7) para que el cliente no las sume en
            // coma flotante; en una hoja de cálculo el usuario SÍ va a sumar, y
            // una columna de texto **Excel la cuenta como cero**: el total sale
            // mal sin que nada avise.
            val hoja = partesXlsx(tabla.toXlsx())["xl/worksheets/sheet1.xml"]!!

            hoja shouldContain "<v>5193.000</v>"
            // Y NO va envuelto en `inlineStr`, que es lo que lo convertiría en texto.
            hoja shouldNotContain "<t>5193.000</t>"
        }

        it("el estilo de 3 decimales existe y está referenciado por las celdas numéricas") {
            val partes = partesXlsx(tabla.toXlsx())

            partes["xl/styles.xml"]!! shouldContain "formatCode=\"0.000\""
            // `s="1"` es el índice del estilo en `cellXfs`. Sin él, Excel mostraría
            // `5193` y el usuario creería que se perdió la precisión.
            partes["xl/worksheets/sheet1.xml"]!! shouldContain "s=\"1\""
        }

        it("escapa el XML: un `&` o un `<` en un nombre no rompe el archivo") {
            // Un nombre de producto con `&` —«TUERCA & ARANDELA»— sin escapar
            // produce un XML inválido, y Excel no abre el archivo. El navegador no
            // lo detecta porque el archivo se genera en el servidor.
            val conSimbolos = ExportTable(
                headers = listOf("Nombre"),
                rows = listOf(listOf("""TUERCA & ARANDELA <grande> "x"""")),
            )

            val hoja = partesXlsx(conSimbolos.toXlsx())["xl/worksheets/sheet1.xml"]!!

            hoja shouldContain "TUERCA &amp; ARANDELA &lt;grande&gt; &quot;x&quot;"
            hoja shouldNotContain "& ARANDELA"
        }

        it("la columna 26 se llama AA, no se queda en Z") {
            // El bug clásico de la conversión a letras: `indice % 26` sin el `-1`
            // hace que la columna 26 se llame `BA` y **desplaza todas las celdas**
            // a partir de ahí, sin fallar. Con 27 columnas se ve.
            val ancha = ExportTable(
                headers = (1..28).map { "C$it" },
                rows = listOf((1..28).map { "v$it" }),
            )

            val hoja = partesXlsx(ancha.toXlsx())["xl/worksheets/sheet1.xml"]!!

            hoja shouldContain "r=\"AA1\""
            hoja shouldContain "r=\"AB1\""
            // La 27ª columna es `AA` (índice 26), no `BA`.
            hoja shouldNotContain "r=\"BA1\""
        }

        it("un valor que NO es número se escribe como cadena, no como número vacío") {
            val hoja = partesXlsx(tabla.toXlsx())["xl/worksheets/sheet1.xml"]!!

            hoja shouldContain "<c r=\"B2\" t=\"inlineStr\">"
        }

        it("dos exportaciones de la misma tabla dan bytes IDÉNTICOS") {
            // Se fija la fecha de las entradas del ZIP a propósito. Sin eso, dos
            // exportaciones del mismo reporte dan archivos distintos y cualquier
            // comparación —un test, un `diff`, una caché— deja de servir.
            tabla.toXlsx().toList() shouldBe tabla.toXlsx().toList()
        }
    }
})
