package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.module
import dev.axzy.stock.backend.routes.catalogRoutes
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

    val nombreBase = "stock_import_test"
    val problemaDeConexion = CatalogoTestDb.problemaDeConexion()
    val hayBase = problemaDeConexion == null

    if (!hayBase) {
        println(
            """
            |
            |  ⚠️  TESTS DE IMPORTACIÓN SALTADOS: no se pudo consultar PostgreSQL
            |      Motivo: $problemaDeConexion
            |      Levántalo con:  cd infra && docker compose up -d
            |
            """.trimMargin()
        )
    }

    beforeSpec {
        if (hayBase) CatalogoTestDb.recrear(nombreBase)
    }

    beforeTest {
        if (hayBase) CatalogoTestDb.conPool(nombreBase) { pool -> CatalogoTestDb.limpiar(pool) }
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    fun ApplicationTestBuilder.montar(pool: DataSource) {
        application {
            module(dataSource = pool, permissionChecker = AllowAllPermissionChecker)
        }
    }

    fun conApi(bloque: suspend ApplicationTestBuilder.(DataSource) -> Unit) {
        if (!hayBase) return
        CatalogoTestDb.conPool(nombreBase) { pool ->
            testApplication {
                montar(pool)
                bloque(pool)
            }
        }
    }

    /**
     * Envía el archivo como `multipart/form-data`.
     *
     * `dryRun` se omite cuando es `null`, que es como se prueba que el valor por
     * defecto es simular.
     */
    suspend fun ApplicationTestBuilder.importar(
        contenido: ByteArray,
        nombreArchivo: String = "catalogo.csv",
        tipo: String = "text/csv",
        dryRun: Boolean? = null,
    ): HttpResponse = client.submitFormWithBinaryData(
        url = "/v1/products/import",
        formData = formData {
            dryRun?.let { append("dryRun", it.toString()) }
            append(
                "file",
                contenido,
                Headers.build {
                    append(HttpHeaders.ContentType, tipo)
                    append(HttpHeaders.ContentDisposition, "filename=\"$nombreArchivo\"")
                },
            )
        },
    )

    suspend fun HttpResponse.cuerpo() = Json.parseToJsonElement(bodyAsText()).jsonObject

    /** El CSV que produce el importador del dominio, el mismo del archivo real. */
    fun csvReal(): ByteArray {
        val archivo = CatalogoTestDb.archivoCsvReal()
            ?: error(
                "No se encontró 'catalogo_real.csv' (el extracto del Excel real). " +
                    "Es el insumo del test de cuadre."
            )
        return archivo.readBytes()
    }

    fun csv(vararg filas: String): ByteArray =
        (listOf("product_id,product_code,name,unit_measure,stock") + filas)
            .joinToString("\n")
            .toByteArray()

    // ═════════════════════════════════════════════════════════════════════════
    describe("dry-run con el archivo real: el cuadre y cero escrituras") {

        it("reproduce las cifras del Excel y NO escribe nada") {
            conApi { pool ->
                val respuesta = importar(csvReal(), dryRun = true)

                respuesta.status shouldBe HttpStatusCode.OK
                val informe = respuesta.cuerpo()

                informe["dryRun"]!!.jsonPrimitive.content.toBoolean() shouldBe true
                informe["totalRows"]!!.jsonPrimitive.int shouldBe 1398
                informe["accepted"]!!.jsonPrimitive.int shouldBe 1398
                informe["rejected"]!!.jsonPrimitive.int shouldBe 0
                informe["zeroStockCount"]!!.jsonPrimitive.int shouldBe 334
                informe["unitMeasures"]!!.jsonArray.single().jsonPrimitive.content shouldBe "PZA"
                withClue("La suma de existencias debe ser exactamente la del Excel") {
                    respuesta.bodyAsText() shouldContain "\"totalStock\":10914130.000"
                }
                withClue("Una simulación no persiste: `persisted` lo dice explícitamente") {
                    informe["persisted"]!!.jsonPrimitive.int shouldBe 0
                }
                withClue(
                    "El stock del archivo NO se escribe al inventario: el archivo no dice " +
                        "en qué estantería está cada pieza (§3.5.1)"
                ) {
                    informe["stockPersisted"]!!.jsonPrimitive.content.toBoolean() shouldBe false
                    CatalogoTestDb.consultarUnLong(pool, "SELECT count(*) FROM stock_by_location") shouldBe 0L
                }

                withClue("El dry-run no puede dejar ni un producto escrito") {
                    CatalogoTestDb.contarProductos(pool) shouldBe 0L
                }
            }
        }

        it("rechaza las filas malas indicando su número de fila") {
            conApi { _ ->
                val contenido = csv(
                    "1,GOOD0001,PRODUCTO BUENO,PZA,10",
                    "2,mal,PRODUCTO CON CÓDIGO MALO,PZA,20",
                    "3,GOOD0003,PRODUCTO BUENO 3,PZA,30",
                )

                val informe = importar(contenido, dryRun = true).cuerpo()

                informe["totalRows"]!!.jsonPrimitive.int shouldBe 3
                informe["accepted"]!!.jsonPrimitive.int shouldBe 2
                informe["rejected"]!!.jsonPrimitive.int shouldBe 1

                val rechazo = informe["rejections"]!!.jsonArray.single().jsonObject
                withClue("Sin el número de fila el usuario no puede corregir el archivo") {
                    rechazo["rowNumber"]!!.jsonPrimitive.int shouldBe 3
                }
                rechazo["productCode"]!!.jsonPrimitive.content shouldBe "mal"
                rechazo["reason"]!!.jsonPrimitive.content shouldContain "inválido"
            }
        }

        it("sin el campo dryRun simula: el olvido no puede escribir 1.398 productos") {
            conApi { pool ->
                val respuesta = importar(csv("1,SIN00001,PRODUCTO,PZA,5"))

                respuesta.cuerpo()["dryRun"]!!.jsonPrimitive.content.toBoolean() shouldBe true
                CatalogoTestDb.contarProductos(pool) shouldBe 0L
            }
        }

        it("rechaza un CSV con cabeceras del ERP en lugar de adivinar las columnas") {
            conApi { _ ->
                val contenido = (
                    "CIDPRODUCTO,CCODIGOPRODUCTO,CNOMBREPRODUCTO,CDESCRIPCIONPRODUCTO," +
                        "CUNIDADMEDIDA,CEXISTENCIAS\n" +
                        "1,ABRAA1B,ABRAZADERA,ABRAZADERA,PZA,10\n"
                    ).toByteArray()

                val respuesta = importar(contenido, dryRun = true)

                withClue("Mapear por posición un archivo ajeno pone datos en el campo equivocado") {
                    respuesta.status shouldBe HttpStatusCode.BadRequest
                    respuesta.cuerpo()["code"]!!.jsonPrimitive.content shouldBe "IMPORT_FILE_FORMAT"
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("import real: una sola transacción") {

        it("persiste las 1.398 filas del archivo real") {
            conApi { pool ->
                val respuesta = importar(csvReal(), dryRun = false)

                respuesta.status shouldBe HttpStatusCode.OK
                val informe = respuesta.cuerpo()

                informe["dryRun"]!!.jsonPrimitive.content.toBoolean() shouldBe false
                informe["persisted"]!!.jsonPrimitive.int shouldBe 1398
                informe["accepted"]!!.jsonPrimitive.int shouldBe 1398
                CatalogoTestDb.contarProductos(pool) shouldBe 1398L
                CatalogoTestDb.contarProductosActivos(pool) shouldBe 1398L
                withClue("El id del ERP se conserva para la referencia cruzada") {
                    CatalogoTestDb.consultarUnLong(
                        pool,
                        "SELECT count(*) FROM products WHERE external_id IS NOT NULL",
                    ) shouldBe 1398L
                }
                withClue("La carga inicial de stock por ubicación es otra decisión (§3.5.1)") {
                    CatalogoTestDb.consultarUnLong(pool, "SELECT count(*) FROM stock_by_location") shouldBe 0L
                }
            }
        }

        it("reimportar actualiza por código en lugar de duplicar") {
            conApi { pool ->
                importar(csvReal(), dryRun = false).status shouldBe HttpStatusCode.OK
                val segunda = importar(csvReal(), dryRun = false)

                segunda.status shouldBe HttpStatusCode.OK
                segunda.cuerpo()["persisted"]!!.jsonPrimitive.int shouldBe 1398
                withClue("product_code es la clave de identidad del upsert: no puede duplicar") {
                    CatalogoTestDb.contarProductos(pool) shouldBe 1398L
                }
            }
        }

        it("no reactiva un producto que se desactivó a propósito") {
            conApi { pool ->
                importar(csvReal(), dryRun = false).status shouldBe HttpStatusCode.OK

                val codigo = "ABRAA1B"
                val id = CatalogoTestDb.idDeProducto(pool, codigo)
                CatalogoTestDb.ejecutar(pool, "UPDATE products SET active = false WHERE id = ?", id)

                importar(csvReal(), dryRun = false).status shouldBe HttpStatusCode.OK

                withClue("Reimportar el catálogo no puede resucitar lo que alguien desactivó") {
                    CatalogoTestDb.contarProductosActivos(pool) shouldBe 1397L
                }
            }
        }

        it("UNA transacción fallida no deja productos a medias") {
            conApi { pool ->
                // Ya existe un producto con external_id 999999: la tercera fila
                // del archivo chocará contra su índice único DESPUÉS de que las
                // dos primeras se hayan insertado.
                CatalogoTestDb.insertarProducto(
                    pool = pool,
                    codigo = "PREEXIST",
                    nombre = "PRODUCTO QUE YA ESTABA",
                    externalId = 999999L,
                )

                val contenido = csv(
                    "900001,ZTEST001,PRIMERO,PZA,10",
                    "900002,ZTEST002,SEGUNDO,PZA,20",
                    "999999,ZTEST003,CHOCA CON EL ID EXTERNO,PZA,30",
                    "900004,ZTEST004,CUARTO,PZA,40",
                )

                val respuesta = importar(contenido, dryRun = false)

                respuesta.status shouldBe HttpStatusCode.Conflict
                respuesta.cuerpo()["code"]!!.jsonPrimitive.content shouldBe "DUPLICATE_EXTERNAL_ID"

                withClue(
                    "Un import a medias con 1.398 productos es peor que un import fallido: " +
                        "solo debe quedar el producto que ya existía. Productos: " +
                        CatalogoTestDb.contarProductos(pool)
                ) {
                    CatalogoTestDb.contarProductos(pool) shouldBe 1L
                    CatalogoTestDb.consultarUnLong(
                        pool,
                        "SELECT count(*) FROM products WHERE product_code LIKE 'ZTEST%'",
                    ) shouldBe 0L
                }
            }
        }

        it("con filas rechazadas no importa NINGUNA: 422 y cero escrituras") {
            conApi { pool ->
                val contenido = csv(
                    "1,MIX00001,PRODUCTO BUENO,PZA,10",
                    "2,mal,PRODUCTO MALO,PZA,20",
                )

                val respuesta = importar(contenido, dryRun = false)

                respuesta.status shouldBe HttpStatusCode.UnprocessableEntity
                respuesta.cuerpo()["code"]!!.jsonPrimitive.content shouldBe "IMPORT_HAS_REJECTIONS"
                withClue("Persistir «las buenas de momento» es el import a medias que se evita") {
                    CatalogoTestDb.contarProductos(pool) shouldBe 0L
                }
            }
        }

        it("rechaza un identificador de ERP que no cabe en la columna, en lugar de perderlo") {
            conApi { pool ->
                val contenido = csv("ABC,ZEXT0001,PRODUCTO CON ID NO NUMÉRICO,PZA,10")

                val respuesta = importar(contenido, dryRun = true)

                respuesta.status shouldBe HttpStatusCode.UnprocessableEntity
                respuesta.cuerpo()["code"]!!.jsonPrimitive.content shouldBe "IMPORT_INVALID_EXTERNAL_ID"
                CatalogoTestDb.contarProductos(pool) shouldBe 0L
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("XLSX del ERP: las columnas se leen por nombre, nunca por posición") {

        it("lee una hoja con cabeceras del ERP y conserva el número de fila") {
            conApi { _ ->
                val contenido = xlsxDeEjemplo(
                    listOf(
                        listOf("CIDPRODUCTO", "CCODIGOPRODUCTO", "CNOMBREPRODUCTO", "CUNIDADMEDIDA", "CEXISTENCIAS"),
                        listOf("1", "ABRAA1B", "ABRAZADERA 1-5/8\" & TUERCA", "PZA", "5193"),
                        listOf("2", "ABRAA1E", "PRODUCTO EN CERO", "PZA", "0"),
                        listOf("3", "mal", "PRODUCTO CON CÓDIGO MALO", "PZA", "10"),
                    )
                )

                val respuesta = importar(
                    contenido = contenido,
                    nombreArchivo = "inventario.xlsx",
                    tipo = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    dryRun = true,
                )

                respuesta.status shouldBe HttpStatusCode.OK
                val informe = respuesta.cuerpo()

                withClue("La cabecera está en la fila 1, así que los datos empiezan en la 2") {
                    informe["totalRows"]!!.jsonPrimitive.int shouldBe 3
                }
                informe["accepted"]!!.jsonPrimitive.int shouldBe 2
                informe["rejected"]!!.jsonPrimitive.int shouldBe 1
                informe["zeroStockCount"]!!.jsonPrimitive.int shouldBe 1
                withClue("5193 + 0, leído del valor numérico de la celda") {
                    respuesta.bodyAsText() shouldContain "\"totalStock\":5193.000"
                }
                withClue("El rechazo señala la fila FÍSICA del archivo (la 4)") {
                    informe["rejections"]!!.jsonArray.single().jsonObject["rowNumber"]!!
                        .jsonPrimitive.int shouldBe 4
                }
            }
        }

        it("rechaza un XLSX al que le faltan columnas obligatorias") {
            conApi { _ ->
                val contenido = xlsxDeEjemplo(
                    listOf(
                        listOf("CIDPRODUCTO", "CNOMBREPRODUCTO"),
                        listOf("1", "PRODUCTO SIN CÓDIGO NI STOCK"),
                    )
                )

                val respuesta = importar(
                    contenido = contenido,
                    nombreArchivo = "incompleto.xlsx",
                    tipo = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    dryRun = true,
                )

                respuesta.status shouldBe HttpStatusCode.BadRequest
                respuesta.cuerpo()["code"]!!.jsonPrimitive.content shouldBe "IMPORT_FILE_FORMAT"
            }
        }

        it("descarta las filas vacías del final, como el archivo real") {
            conApi { _ ->
                val contenido = xlsxDeEjemplo(
                    listOf(
                        listOf("CIDPRODUCTO", "CCODIGOPRODUCTO", "CNOMBREPRODUCTO", "CUNIDADMEDIDA", "CEXISTENCIAS"),
                        listOf("1", "ABRAA1B", "PRODUCTO", "PZA", "100"),
                        listOf("", "", "", "", ""),
                        listOf("", "", "", "", ""),
                    )
                )

                val informe = importar(
                    contenido,
                    nombreArchivo = "catalogo.xlsx",
                    tipo = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    dryRun = true,
                ).cuerpo()

                withClue("El perfil del archivo real habla de 1.398 filas útiles, no de 1.400") {
                    informe["totalRows"]!!.jsonPrimitive.int shouldBe 1
                    informe["rejected"]!!.jsonPrimitive.int shouldBe 0
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
private fun xlsxDeEjemplo(filas: List<List<String>>): ByteArray {
    val cadenas = mutableListOf<String>()
    fun indiceDe(texto: String): Int {
        val existente = cadenas.indexOf(texto)
        if (existente >= 0) return existente
        cadenas += texto
        return cadenas.lastIndex
    }

    val hoja = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        filas.forEachIndexed { indiceFila, celdas ->
            val numeroDeFila = indiceFila + 1
            append("""<row r="$numeroDeFila">""")
            celdas.forEachIndexed { indiceColumna, valor ->
                if (valor.isEmpty()) return@forEachIndexed
                val referencia = "${letraDeColumna(indiceColumna)}$numeroDeFila"
                if (NUMERO.matches(valor)) {
                    append("""<c r="$referencia"><v>$valor</v></c>""")
                } else {
                    append("""<c r="$referencia" t="s"><v>${indiceDe(valor)}</v></c>""")
                }
            }
            append("</row>")
        }
        append("</sheetData></worksheet>")
    }

    val compartidas = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${cadenas.size}" uniqueCount="${cadenas.size}">""")
        cadenas.forEach { append("<si><t>${escaparXml(it)}</t></si>") }
        append("</sst>")
    }

    val salida = ByteArrayOutputStream()
    ZipOutputStream(salida).use { zip ->
        fun agregar(nombre: String, contenido: String) {
            zip.putNextEntry(ZipEntry(nombre))
            zip.write(contenido.toByteArray())
            zip.closeEntry()
        }

        agregar(
            "[Content_Types].xml",
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""" +
                """<Default Extension="xml" ContentType="application/xml"/></Types>""",
        )
        agregar(
            "xl/workbook.xml",
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" """ +
                """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
                """<sheets><sheet name="Hoja1" sheetId="1" r:id="rId1"/></sheets></workbook>""",
        )
        agregar(
            "xl/_rels/workbook.xml.rels",
            """<?xml version="1.0" encoding="UTF-8"?>""" +
                """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                """<Relationship Id="rId1" """ +
                """Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" """ +
                """Target="worksheets/sheet1.xml"/></Relationships>""",
        )
        agregar("xl/sharedStrings.xml", compartidas)
        agregar("xl/worksheets/sheet1.xml", hoja)
    }

    return salida.toByteArray()
}

private val NUMERO = Regex("^-?\\d+(\\.\\d+)?$")

private fun letraDeColumna(indice: Int): String {
    var restante = indice
    val letras = StringBuilder()
    while (true) {
        letras.insert(0, ('A' + restante % 26))
        restante = restante / 26 - 1
        if (restante < 0) break
    }
    return letras.toString()
}

private fun escaparXml(texto: String): String = texto
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
