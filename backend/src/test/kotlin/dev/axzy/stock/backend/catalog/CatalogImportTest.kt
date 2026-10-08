package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.module
import dev.axzy.stock.backend.catalog.catalogRoutes
import dev.axzy.stock.backend.security.AllowAllPermissionChecker
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * IMPORTACIÓN DEL CATÁLOGO CONTRA POSTGRESQL REAL
 *
 * Los dos tests que de verdad importan:
 *
 *  1. **El cuadre**: el dry-run del archivo real (1.398 productos, 10.914.130
 *     unidades, 334 en cero) y **sin escribir nada**.
 *  2. **La atomicidad**: un import que falla a mitad **no deja productos a
 *     medias**. Se fuerza el fallo con un `external_id` que ya existe, de forma
 *     que las primeras filas sí se insertan antes de chocar: si la transacción
 *     no envolviera el lote completo, quedarían dentro.
 *
 * Si no hay base disponible, los tests se **saltan** (y lo dicen).
 * ═══════════════════════════════════════════════════════════════════════════
 */
class CatalogImportTest : DescribeSpec({

    val nameBase = "stock_import_test"
    val connectionProblem = CatalogTestDb.connectionProblem()
    val thereIsBase = connectionProblem == null

    if (!thereIsBase) {
        println(
            """
            |
            |  ⚠️  TESTS DE IMPORTACIÓN SALTADOS: no se pudo consultar PostgreSQL
            |      Motivo: $connectionProblem
            |      Levántalo con:  cd infra && docker compose up -d
            |
            """.trimMargin()
        )
    }

    beforeSpec {
        if (thereIsBase) CatalogTestDb.recreate(nameBase)
    }

    beforeTest {
        if (thereIsBase) CatalogTestDb.withPool(nameBase) { pool -> CatalogTestDb.clean(pool) }
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    fun ApplicationTestBuilder.build(pool: DataSource) {
        application {
            module(dataSource = pool, permissionChecker = AllowAllPermissionChecker)
        }
    }

    fun withApi(block: suspend ApplicationTestBuilder.(DataSource) -> Unit) {
        if (!thereIsBase) return
        CatalogTestDb.withPool(nameBase) { pool ->
            testApplication {
                build(pool)
                block(pool)
            }
        }
    }

    /**
     * Envía el archivo como `multipart/form-data`.
     *
     * `dryRun` se omite cuando es `null`, que es como se prueba que el valor por
     * defecto es simular.
     */
    suspend fun ApplicationTestBuilder.importCatalog(
        content: ByteArray,
        nameFile: String = "catalogo.csv",
        type: String = "text/csv",
        dryRun: Boolean? = null,
    ): HttpResponse = client.submitFormWithBinaryData(
        url = "/v1/products/import",
        formData = formData {
            dryRun?.let { append("dryRun", it.toString()) }
            append(
                "file",
                content,
                Headers.build {
                    append(HttpHeaders.ContentType, type)
                    append(HttpHeaders.ContentDisposition, "filename=\"$nameFile\"")
                },
            )
        },
    )

    suspend fun HttpResponse.body() = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** El CSV que produce el importador del dominio, el mismo del archivo real. */
    fun csvReal(): ByteArray {
        val file = CatalogTestDb.fileCsvReal()
            ?: error(
                "No se encontró 'catalogo_real.csv' (el extracto del Excel real). " +
                    "Es el insumo del test de cuadre."
            )
        return file.readBytes()
    }

    fun csv(vararg rows: String): ByteArray =
        (listOf("product_id,product_code,name,unit_measure,stock") + rows)
            .joinToString("\n")
            .toByteArray()

    // ═════════════════════════════════════════════════════════════════════════
    describe("dry-run con el archivo real: el cuadre y cero escrituras") {

        it("reproduce las cifras del Excel y NO escribe nada") {
            withApi { pool ->
                val response = importCatalog(csvReal(), dryRun = true)

                response.status shouldBe HttpStatusCode.OK
                val report = response.body()

                report["dryRun"]!!.jsonPrimitive.content.toBoolean() shouldBe true
                report["totalRows"]!!.jsonPrimitive.int shouldBe 1398
                report["accepted"]!!.jsonPrimitive.int shouldBe 1398
                report["rejected"]!!.jsonPrimitive.int shouldBe 0
                report["zeroStockCount"]!!.jsonPrimitive.int shouldBe 334
                report["unitMeasures"]!!.jsonArray.single().jsonPrimitive.content shouldBe "PZA"
                withClue("La suma de existencias debe ser exactamente la del Excel") {
                    response.bodyAsText() shouldContain "\"totalStock\":10914130.000"
                }
                withClue("Una simulación no persiste: `persisted` lo dice explícitamente") {
                    report["persisted"]!!.jsonPrimitive.int shouldBe 0
                }
                withClue(
                    "El stock del archivo NO se escribe al inventario: el archivo no dice " +
                        "en qué estantería está cada pieza (§3.5.1)"
                ) {
                    report["stockPersisted"]!!.jsonPrimitive.content.toBoolean() shouldBe false
                    CatalogTestDb.querySingleLong(pool, "SELECT count(*) FROM stock_by_location") shouldBe 0L
                }

                withClue("El dry-run no puede dejar ni un producto escrito") {
                    CatalogTestDb.countProducts(pool) shouldBe 0L
                }
            }
        }

        it("rechaza las filas malas indicando su número de fila") {
            withApi { _ ->
                val content = csv(
                    "1,GOOD0001,PRODUCTO BUENO,PZA,10",
                    "2,mal,PRODUCTO CON CÓDIGO MALO,PZA,20",
                    "3,GOOD0003,PRODUCTO BUENO 3,PZA,30",
                )

                val report = importCatalog(content, dryRun = true).body()

                report["totalRows"]!!.jsonPrimitive.int shouldBe 3
                report["accepted"]!!.jsonPrimitive.int shouldBe 2
                report["rejected"]!!.jsonPrimitive.int shouldBe 1

                val rejection = report["rejections"]!!.jsonArray.single().jsonObject
                withClue("Sin el número de fila el usuario no puede corregir el archivo") {
                    rejection["rowNumber"]!!.jsonPrimitive.int shouldBe 3
                }
                rejection["productCode"]!!.jsonPrimitive.content shouldBe "mal"
                rejection["reason"]!!.jsonPrimitive.content shouldContain "inválido"
            }
        }

        it("sin el campo dryRun simula: el olvido no puede escribir 1.398 productos") {
            withApi { pool ->
                val response = importCatalog(csv("1,SIN00001,PRODUCTO,PZA,5"))

                response.body()["dryRun"]!!.jsonPrimitive.content.toBoolean() shouldBe true
                CatalogTestDb.countProducts(pool) shouldBe 0L
            }
        }

        it("rechaza un CSV con cabeceras del ERP en lugar de adivinar las columnas") {
            withApi { _ ->
                val content = (
                    "CIDPRODUCTO,CCODIGOPRODUCTO,CNOMBREPRODUCTO,CDESCRIPCIONPRODUCTO," +
                        "CUNIDADMEDIDA,CEXISTENCIAS\n" +
                        "1,ABRAA1B,ABRAZADERA,ABRAZADERA,PZA,10\n"
                    ).toByteArray()

                val response = importCatalog(content, dryRun = true)

                withClue("Mapear por posición un archivo ajeno pone datos en el campo equivocado") {
                    response.status shouldBe HttpStatusCode.BadRequest
                    response.body()["code"]!!.jsonPrimitive.content shouldBe "IMPORT_FILE_FORMAT"
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("import real: una sola transacción") {

        it("persiste las 1.398 filas del archivo real") {
            withApi { pool ->
                val response = importCatalog(csvReal(), dryRun = false)

                response.status shouldBe HttpStatusCode.OK
                val report = response.body()

                report["dryRun"]!!.jsonPrimitive.content.toBoolean() shouldBe false
                report["persisted"]!!.jsonPrimitive.int shouldBe 1398
                report["accepted"]!!.jsonPrimitive.int shouldBe 1398
                CatalogTestDb.countProducts(pool) shouldBe 1398L
                CatalogTestDb.countActiveProducts(pool) shouldBe 1398L
                withClue("El id del ERP se conserva para la referencia cruzada") {
                    CatalogTestDb.querySingleLong(
                        pool,
                        "SELECT count(*) FROM products WHERE external_id IS NOT NULL",
                    ) shouldBe 1398L
                }
                withClue("La carga inicial de stock por ubicación es otra decisión (§3.5.1)") {
                    CatalogTestDb.querySingleLong(pool, "SELECT count(*) FROM stock_by_location") shouldBe 0L
                }
            }
        }

        it("reimportar actualiza por código en lugar de duplicar") {
            withApi { pool ->
                importCatalog(csvReal(), dryRun = false).status shouldBe HttpStatusCode.OK
                val second = importCatalog(csvReal(), dryRun = false)

                second.status shouldBe HttpStatusCode.OK
                second.body()["persisted"]!!.jsonPrimitive.int shouldBe 1398
                withClue("product_code es la clave de identidad del upsert: no puede duplicar") {
                    CatalogTestDb.countProducts(pool) shouldBe 1398L
                }
            }
        }

        it("no reactiva un producto que se desactivó a propósito") {
            withApi { pool ->
                importCatalog(csvReal(), dryRun = false).status shouldBe HttpStatusCode.OK

                val code = "ABRAA1B"
                val id = CatalogTestDb.productId(pool, code)
                CatalogTestDb.execute(pool, "UPDATE products SET active = false WHERE id = ?", id)

                importCatalog(csvReal(), dryRun = false).status shouldBe HttpStatusCode.OK

                withClue("Reimportar el catálogo no puede resucitar lo que alguien desactivó") {
                    CatalogTestDb.countActiveProducts(pool) shouldBe 1397L
                }
            }
        }

        it("UNA transacción fallida no deja productos a medias") {
            withApi { pool ->
                // Ya existe un producto con external_id 999999: la tercera fila
                // del archivo chocará contra su índice único DESPUÉS de que las
                // dos primeras se hayan insertado.
                CatalogTestDb.insertProduct(
                    pool = pool,
                    code = "PREEXIST",
                    name = "PRODUCTO QUE YA ESTABA",
                    externalId = 999999L,
                )

                val content = csv(
                    "900001,ZTEST001,PRIMERO,PZA,10",
                    "900002,ZTEST002,SEGUNDO,PZA,20",
                    "999999,ZTEST003,CHOCA CON EL ID EXTERNO,PZA,30",
                    "900004,ZTEST004,CUARTO,PZA,40",
                )

                val response = importCatalog(content, dryRun = false)

                response.status shouldBe HttpStatusCode.Conflict
                response.body()["code"]!!.jsonPrimitive.content shouldBe "DUPLICATE_EXTERNAL_ID"

                withClue(
                    "Un import a medias con 1.398 productos es peor que un import fallido: " +
                        "solo debe quedar el producto que ya existía. Productos: " +
                        CatalogTestDb.countProducts(pool)
                ) {
                    CatalogTestDb.countProducts(pool) shouldBe 1L
                    CatalogTestDb.querySingleLong(
                        pool,
                        "SELECT count(*) FROM products WHERE product_code LIKE 'ZTEST%'",
                    ) shouldBe 0L
                }
            }
        }

        it("con filas rechazadas no importa NINGUNA: 422 y cero escrituras") {
            withApi { pool ->
                val content = csv(
                    "1,MIX00001,PRODUCTO BUENO,PZA,10",
                    "2,mal,PRODUCTO MALO,PZA,20",
                )

                val response = importCatalog(content, dryRun = false)

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.body()["code"]!!.jsonPrimitive.content shouldBe "IMPORT_HAS_REJECTIONS"
                withClue("Persistir «las buenas de momento» es el import a medias que se evita") {
                    CatalogTestDb.countProducts(pool) shouldBe 0L
                }
            }
        }

        it("rechaza un identificador de ERP que no cabe en la columna, en lugar de perderlo") {
            withApi { pool ->
                val content = csv("ABC,ZEXT0001,PRODUCTO CON ID NO NUMÉRICO,PZA,10")

                val response = importCatalog(content, dryRun = true)

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.body()["code"]!!.jsonPrimitive.content shouldBe "IMPORT_INVALID_EXTERNAL_ID"
                CatalogTestDb.countProducts(pool) shouldBe 0L
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("XLSX del ERP: las columnas se leen por nombre, nunca por posición") {

        it("lee una hoja con cabeceras del ERP y conserva el número de fila") {
            withApi { _ ->
                val content = sampleXlsx(
                    listOf(
                        listOf("CIDPRODUCTO", "CCODIGOPRODUCTO", "CNOMBREPRODUCTO", "CUNIDADMEDIDA", "CEXISTENCIAS"),
                        listOf("1", "ABRAA1B", "ABRAZADERA 1-5/8\" & TUERCA", "PZA", "5193"),
                        listOf("2", "ABRAA1E", "PRODUCTO EN CERO", "PZA", "0"),
                        listOf("3", "mal", "PRODUCTO CON CÓDIGO MALO", "PZA", "10"),
                    )
                )

                val response = importCatalog(
                    content = content,
                    nameFile = "inventario.xlsx",
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    dryRun = true,
                )

                response.status shouldBe HttpStatusCode.OK
                val report = response.body()

                withClue("La cabecera está en la fila 1, así que los datos empiezan en la 2") {
                    report["totalRows"]!!.jsonPrimitive.int shouldBe 3
                }
                report["accepted"]!!.jsonPrimitive.int shouldBe 2
                report["rejected"]!!.jsonPrimitive.int shouldBe 1
                report["zeroStockCount"]!!.jsonPrimitive.int shouldBe 1
                withClue("5193 + 0, leído del valor numérico de la celda") {
                    response.bodyAsText() shouldContain "\"totalStock\":5193.000"
                }
                withClue("El rechazo señala la fila FÍSICA del archivo (la 4)") {
                    report["rejections"]!!.jsonArray.single().jsonObject["rowNumber"]!!
                        .jsonPrimitive.int shouldBe 4
                }
            }
        }

        it("rechaza un XLSX al que le faltan columnas obligatorias") {
            withApi { _ ->
                val content = sampleXlsx(
                    listOf(
                        listOf("CIDPRODUCTO", "CNOMBREPRODUCTO"),
                        listOf("1", "PRODUCTO SIN CÓDIGO NI STOCK"),
                    )
                )

                val response = importCatalog(
                    content = content,
                    nameFile = "incompleto.xlsx",
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    dryRun = true,
                )

                response.status shouldBe HttpStatusCode.BadRequest
                response.body()["code"]!!.jsonPrimitive.content shouldBe "IMPORT_FILE_FORMAT"
            }
        }

        it("descarta las filas vacías del final, como el archivo real") {
            withApi { _ ->
                val content = sampleXlsx(
                    listOf(
                        listOf("CIDPRODUCTO", "CCODIGOPRODUCTO", "CNOMBREPRODUCTO", "CUNIDADMEDIDA", "CEXISTENCIAS"),
                        listOf("1", "ABRAA1B", "PRODUCTO", "PZA", "100"),
                        listOf("", "", "", "", ""),
                        listOf("", "", "", "", ""),
                    )
                )

                val report = importCatalog(
                    content,
                    nameFile = "catalogo.xlsx",
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    dryRun = true,
                ).body()

                withClue("El perfil del archivo real habla de 1.398 filas útiles, no de 1.400") {
                    report["totalRows"]!!.jsonPrimitive.int shouldBe 1
                    report["rejected"]!!.jsonPrimitive.int shouldBe 0
                }
            }
        }
    }
})

/**
 * Construye un XLSX mínimo pero real: un ZIP con `xl/workbook.xml`, sus
 * relaciones, la tabla de cadenas compartidas y la primera hoja.
 *
 * Es el mismo formato que produce Excel, y sirve para probar el lector contra
 * el camino real (índices de cadena, valores numéricos, referencias de celda)
 * sin depender de un archivo binario en el repositorio.
 */
private fun sampleXlsx(rows: List<List<String>>): ByteArray {
    val strings = mutableListOf<String>()
    fun indexOf(text: String): Int {
        val existing = strings.indexOf(text)
        if (existing >= 0) return existing
        strings += text
        return strings.lastIndex
    }

    val sheet = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { indexRow, cells ->
            val rowNumber = indexRow + 1
            append("""<row r="$rowNumber">""")
            cells.forEachIndexed { indexColumn, value ->
                if (value.isEmpty()) return@forEachIndexed
                val reference = "${columnLetter(indexColumn)}$rowNumber"
                if (NUMBER.matches(value)) {
                    append("""<c r="$reference"><v>$value</v></c>""")
                } else {
                    append("""<c r="$reference" t="s"><v>${indexOf(value)}</v></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    val shared = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${strings.size}" uniqueCount="${strings.size}">""")
        strings.forEach { append("<si><t>${escapeXml(it)}</t></si>") }
        append("</sst>")
    }

    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        fun add(name: String, content: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(content.toByteArray())
            zip.closeEntry()
        }

        add(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="xml" ContentType="application/xml"/></Types>""",
        )
        add(
            "xl/workbook.xml",
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                """<sheets><sheet name="Hoja1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        )
        add(
            "xl/_rels/workbook.xml.rels",
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
                """Target="worksheets/sheet1.xml"/></Relationships>""",
        )
        add("xl/sharedStrings.xml", shared)
        add("xl/worksheets/sheet1.xml", sheet)
    }

    return output.toByteArray()
}

private val NUMBER = Regex("^-?\\d+(\\.\\d+)?$")

private fun columnLetter(index: Int): String {
    var remaining = index
    val letters = StringBuilder()
    while (true) {
        letters.insert(0, ('A' + remaining % 26))
        remaining = remaining / 26 - 1
        if (remaining < 0) break
    }
    return letters.toString()
}

private fun escapeXml(text: String): String = text
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
