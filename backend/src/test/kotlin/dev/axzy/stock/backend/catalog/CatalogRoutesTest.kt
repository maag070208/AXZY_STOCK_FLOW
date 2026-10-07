package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.module
import dev.axzy.stock.backend.routes.catalogRoutes
import dev.axzy.stock.backend.security.AllowAllPermissionChecker
import dev.axzy.stock.backend.security.PermissionChecker
import dev.axzy.stock.backend.security.PermissionDecision
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RUTAS DEL CATÁLOGO CONTRA POSTGRESQL REAL
 *
 * Se prueba el comportamiento por HTTP y contra la base de verdad: los códigos
 * de estado, los códigos de error del contrato §1.3, la paginación resuelta en
 * la base y el borrado lógico con su motivo. Un mock de la base no habría
 * detectado ni el `CHECK` de mayúsculas de las ubicaciones ni la violación de
 * unicidad que el código debe traducir a un 409.
 *
 * Si no hay base disponible, los tests se **saltan** (y lo dicen).
 * ═══════════════════════════════════════════════════════════════════════════
 */
class CatalogRoutesTest : DescribeSpec({

    val nombreBase = "stock_catalog_test"
    val problemaDeConexion = CatalogoTestDb.problemaDeConexion()
    val hayBase = problemaDeConexion == null

    if (!hayBase) {
        println(
            """
            |
            |  ⚠️  TESTS DE CATÁLOGO SALTADOS: no se pudo consultar PostgreSQL
            |      Motivo: $problemaDeConexion
            |      Levántalo con:  cd infra && docker compose up -d
            |
            """.trimMargin()
        )
    }

    beforeSpec {
        if (hayBase) CatalogoTestDb.recrear(nombreBase)
    }

    // Cada test parte de un catálogo vacío pero migrado: los datos de un test no
    // pueden cambiar el resultado de otro, sobre todo los recuentos.
    beforeTest {
        if (hayBase) CatalogoTestDb.conPool(nombreBase) { pool -> CatalogoTestDb.limpiar(pool) }
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    /** Monta la aplicación con el módulo real y las rutas del catálogo. */
    fun ApplicationTestBuilder.montar(
        pool: DataSource,
        verificador: PermissionChecker = AllowAllPermissionChecker,
    ) {
        application {
            module(pool)
            routing { catalogRoutes(pool, verificador) }
        }
    }

    /** Ejecuta un bloque HTTP contra una base real y limpia. */
    fun conApi(
        verificador: PermissionChecker = AllowAllPermissionChecker,
        bloque: suspend ApplicationTestBuilder.(DataSource) -> Unit,
    ) {
        if (!hayBase) return
        CatalogoTestDb.conPool(nombreBase) { pool ->
            testApplication {
                montar(pool, verificador)
                bloque(pool)
            }
        }
    }

    suspend fun ApplicationTestBuilder.crearProducto(
        codigo: String,
        nombre: String,
        descripcion: String? = null,
        unidad: String = "PZA",
    ): HttpResponse {
        val descripcionJson = descripcion?.let { "\"$it\"" } ?: "null"
        return client.post("/v1/products") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"productCode":"$codigo","name":"$nombre",
                 "description":$descripcionJson,"unitMeasure":"$unidad"}
                """.trimIndent()
            )
        }
    }

    suspend fun HttpResponse.cuerpo(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    suspend fun HttpResponse.codigoDeError(): String =
        cuerpo()["code"]!!.jsonPrimitive.content

    // ═════════════════════════════════════════════════════════════════════════
    describe("paginación y orden en la base (§1.4)") {

        it("devuelve el sobre del contrato con el total contado en la base") {
            conApi { _ ->
                crearProducto("AAA0001", "ABRAZADERA UNO")
                crearProducto("AAA0002", "ABRAZADERA DOS")
                crearProducto("AAA0003", "ABRAZADERA TRES")

                val primera = client.get("/v1/products?page=0&size=2")
                primera.status shouldBe HttpStatusCode.OK
                val sobre = primera.cuerpo()

                sobre["page"]!!.jsonPrimitive.int shouldBe 0
                sobre["size"]!!.jsonPrimitive.int shouldBe 2
                withClue("totalItems es el total que cumple el filtro, no el tamaño de la página") {
                    sobre["totalItems"]!!.jsonPrimitive.long shouldBe 3L
                }
                sobre["totalPages"]!!.jsonPrimitive.int shouldBe 2
                sobre["items"]!!.jsonArray.size shouldBe 2

                val segunda = client.get("/v1/products?page=1&size=2")
                segunda.cuerpo()["items"]!!.jsonArray.size shouldBe 1
            }
        }

        it("ordena por nombre descendente") {
            conApi { _ ->
                crearProducto("BBB0001", "B INTERMEDIO")
                crearProducto("BBB0002", "A PRIMERO")
                crearProducto("BBB0003", "C ULTIMO")

                val items = client.get("/v1/products?sort=name,desc")
                    .cuerpo()["items"]!!.jsonArray

                items.map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldBe
                    listOf("C ULTIMO", "B INTERMEDIO", "A PRIMERO")
            }
        }

        it("busca por código y por nombre con q") {
            conApi { _ ->
                crearProducto("CCC0001", "TORNILLO")
                crearProducto("DDD0002", "ABRAZADERA")

                val porNombre = client.get("/v1/products?q=abraz").cuerpo()["items"]!!.jsonArray
                porNombre.size shouldBe 1
                porNombre.single().jsonObject["productCode"]!!.jsonPrimitive.content shouldBe "DDD0002"

                val porCodigo = client.get("/v1/products?q=ccc").cuerpo()["items"]!!.jsonArray
                porCodigo.size shouldBe 1
            }
        }

        it("rechaza size fuera de rango con 400 en lugar de recortarlo") {
            conApi { _ ->
                val respuesta = client.get("/v1/products?size=500")

                respuesta.status shouldBe HttpStatusCode.BadRequest
                respuesta.codigoDeError() shouldBe "INVALID_PAGINATION"
            }
        }

        it("rechaza un campo de orden no admitido con 400") {
            conApi { _ ->
                val respuesta = client.get("/v1/products?sort=stock,asc")

                respuesta.status shouldBe HttpStatusCode.BadRequest
                respuesta.codigoDeError() shouldBe "INVALID_SORT"
            }
        }

        it("rechaza un identificador de ruta que no es un número") {
            conApi { _ ->
                client.get("/v1/products/abc").status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("productos") {

        it("crea un producto con 201, su id y stock cero") {
            conApi { _ ->
                val respuesta = crearProducto("PPP0001", "PRODUCTO NUEVO", "descripción")

                respuesta.status shouldBe HttpStatusCode.Created
                val creado = respuesta.cuerpo()
                creado["productCode"]!!.jsonPrimitive.content shouldBe "PPP0001"
                creado["unitMeasure"]!!.jsonPrimitive.content shouldBe "PZA"
                creado["active"]!!.jsonPrimitive.content.toBoolean() shouldBe true
                withClue("El stock de un producto recién creado es 0.000, como número") {
                    respuesta.bodyAsText() shouldContain "\"stock\":0.000"
                }
            }
        }

        it("rechaza el código duplicado con 409 DUPLICATE_PRODUCT_CODE") {
            conApi { _ ->
                crearProducto("DUP0001", "PRIMERO").status shouldBe HttpStatusCode.Created

                val repetido = crearProducto("DUP0001", "SEGUNDO")

                repetido.status shouldBe HttpStatusCode.Conflict
                repetido.codigoDeError() shouldBe "DUPLICATE_PRODUCT_CODE"
            }
        }

        it("rechaza un código que no cumple el alfabeto con 422 y el campo señalado") {
            conApi { _ ->
                val respuesta = crearProducto("mal-codigo", "PRODUCTO")

                respuesta.status shouldBe HttpStatusCode.UnprocessableEntity
                respuesta.codigoDeError() shouldBe "INVALID_PRODUCT_CODE"
                respuesta.cuerpo()["details"]!!.jsonObject.containsKey("productCode") shouldBe true
            }
        }

        it("rechaza una unidad de medida que no está en el catálogo") {
            conApi { _ ->
                val respuesta = crearProducto("UNI0001", "PRODUCTO", unidad = "KGS")

                respuesta.status shouldBe HttpStatusCode.UnprocessableEntity
                respuesta.codigoDeError() shouldBe "UNKNOWN_UNIT_MEASURE"
            }
        }

        it("devuelve 404 cuando el producto no existe") {
            conApi { _ ->
                val respuesta = client.get("/v1/products/999999")

                respuesta.status shouldBe HttpStatusCode.NotFound
                respuesta.codigoDeError() shouldBe "PRODUCT_NOT_FOUND"
            }
        }

        it("actualiza nombre, descripción y unidad con PUT") {
            conApi { _ ->
                val id = crearProducto("ACT0001", "NOMBRE VIEJO").cuerpo()["id"]!!.jsonPrimitive.long

                val respuesta = client.put("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"NOMBRE NUEVO","description":"ahora sí","unitMeasure":"PZA"}""")
                }

                respuesta.status shouldBe HttpStatusCode.OK
                respuesta.cuerpo()["name"]!!.jsonPrimitive.content shouldBe "NOMBRE NUEVO"
                respuesta.cuerpo()["description"]!!.jsonPrimitive.content shouldBe "ahora sí"
            }
        }

        it("tolera el ida y vuelta del objeto y rechaza cambiar el código") {
            conApi { _ ->
                val creado = crearProducto("INM0001", "PRODUCTO").cuerpo()
                val id = creado["id"]!!.jsonPrimitive.long

                // Un cliente que hace GET, cambia el nombre y devuelve el objeto
                // entero manda el mismo `productCode`: eso no es un cambio.
                val conMismoCodigo = client.put("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"productCode":"INM0001","name":"OTRO NOMBRE","unitMeasure":"PZA"}""")
                }
                conMismoCodigo.status shouldBe HttpStatusCode.OK

                val conOtroCodigo = client.put("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"productCode":"INM0002","name":"OTRO NOMBRE","unitMeasure":"PZA"}""")
                }
                conOtroCodigo.status shouldBe HttpStatusCode.UnprocessableEntity
                conOtroCodigo.codigoDeError() shouldBe "PRODUCT_CODE_IMMUTABLE"
            }
        }

        it("el borrado es lógico, exige motivo y deja el producto inactivo") {
            conApi { _ ->
                val id = crearProducto("DEL0001", "SE VA").cuerpo()["id"]!!.jsonPrimitive.long

                val sinMotivo = client.delete("/v1/products/$id")
                sinMotivo.status shouldBe HttpStatusCode.UnprocessableEntity
                sinMotivo.codigoDeError() shouldBe "REASON_REQUIRED"

                val conMotivo = client.delete("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"producto descontinuado por el proveedor"}""")
                }
                conMotivo.status shouldBe HttpStatusCode.NoContent

                val despues = client.get("/v1/products/$id")
                despues.status shouldBe HttpStatusCode.OK
                despues.cuerpo()["active"]!!.jsonPrimitive.content.toBoolean() shouldBe false
                withClue("El producto no se borra de la base: se desactiva") {
                    despues.cuerpo()["productCode"]!!.jsonPrimitive.content shouldBe "DEL0001"
                }
            }
        }

        it("acepta el motivo en la query, para clientes que no mandan cuerpo en DELETE") {
            conApi { _ ->
                val id = crearProducto("DEL0002", "SE VA").cuerpo()["id"]!!.jsonPrimitive.long

                val respuesta = client.delete("/v1/products/$id?reason=duplicado")

                respuesta.status shouldBe HttpStatusCode.NoContent
            }
        }

        it("no desactiva dos veces: el segundo borrado pisaría el motivo del primero") {
            conApi { _ ->
                val id = crearProducto("DEL0003", "SE VA").cuerpo()["id"]!!.jsonPrimitive.long
                client.delete("/v1/products/$id?reason=primero").status shouldBe HttpStatusCode.NoContent

                val segunda = client.delete("/v1/products/$id?reason=segundo")

                segunda.status shouldBe HttpStatusCode.Conflict
                segunda.codigoDeError() shouldBe "PRODUCT_ALREADY_INACTIVE"
            }
        }

        it("un producto con existencias no se desactiva: 409 PRODUCT_HAS_STOCK con details.stock") {
            conApi { pool ->
                val id = crearProducto("STK0001", "CON STOCK").cuerpo()["id"]!!.jsonPrimitive.long
                val (ubicacionId, bodegaId) = CatalogoTestDb.ubicacionDeRecepcion(pool)
                CatalogoTestDb.insertarStock(pool, id, ubicacionId, bodegaId, "12.500")

                val respuesta = client.delete("/v1/products/$id?reason=ya no se usa")

                respuesta.status shouldBe HttpStatusCode.Conflict
                respuesta.codigoDeError() shouldBe "PRODUCT_HAS_STOCK"
                withClue("El detalle dice cuánto stock lo bloquea, en número con 3 decimales") {
                    respuesta.bodyAsText() shouldContain "12.500"
                }
            }
        }

        it("el stock es la suma de las ubicaciones y de la mercancía en tránsito") {
            conApi { pool ->
                val id = crearProducto("STK0002", "REPARTIDO").cuerpo()["id"]!!.jsonPrimitive.long
                val (ubicacionId, bodegaId) = CatalogoTestDb.ubicacionDeRecepcion(pool)
                CatalogoTestDb.insertarStock(pool, id, ubicacionId, bodegaId, "100.000")
                CatalogoTestDb.ejecutar(
                    pool,
                    "INSERT INTO warehouses (code, name) VALUES ('SUCURSAL', 'Bodega sucursal')",
                )
                val sucursal = CatalogoTestDb.consultarUnLong(
                    pool,
                    "SELECT id FROM warehouses WHERE code = 'SUCURSAL'",
                )
                CatalogoTestDb.insertarTransito(pool, id, bodegaId, sucursal, "50.000")

                val detalle = client.get("/v1/products/$id")

                withClue("La mercancía en el camión SIGUE contando en el total") {
                    detalle.bodyAsText() shouldContain "\"stock\":150.000"
                }
            }
        }

        it("el listado incluye los productos inactivos, marcados como tales") {
            conApi { _ ->
                val id = crearProducto("INA0001", "DESACTIVADO").cuerpo()["id"]!!.jsonPrimitive.long
                client.delete("/v1/products/$id?reason=obsoleto").status shouldBe HttpStatusCode.NoContent

                val todos = client.get("/v1/products").cuerpo()["items"]!!.jsonArray
                todos.size shouldBe 1

                val soloActivos = client.get("/v1/products?active=true").cuerpo()["items"]!!.jsonArray
                soloActivos.size shouldBe 0
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("ubicaciones") {

        it("crea una ubicación y compone su nombre para mostrar") {
            conApi { pool ->
                val bodegaId = CatalogoTestDb.bodegaPrincipal(pool)

                val respuesta = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$bodegaId,"hall":"04","code":"b","position":"c","levels":3}""")
                }

                respuesta.status shouldBe HttpStatusCode.Created
                val creada = respuesta.cuerpo()
                withClue("Las partes se normalizan a mayúsculas: el esquema lo exige") {
                    creada["hall"]!!.jsonPrimitive.content shouldBe "04"
                    creada["code"]!!.jsonPrimitive.content shouldBe "B"
                }
                creada["displayName"]!!.jsonPrimitive.content shouldBe "04-B-C"
                creada["kind"]!!.jsonPrimitive.content shouldBe "SHELF"
                creada["warehouseCode"]!!.jsonPrimitive.content shouldBe "PRINCIPAL"
            }
        }

        it("la ubicación de recepción se muestra como RECEPCION, sin repetir la terna") {
            conApi { _ ->
                val items = client.get("/v1/locations?kind=RECEIVING").cuerpo()["items"]!!.jsonArray

                items.size shouldBe 1
                items.single().jsonObject["displayName"]!!.jsonPrimitive.content shouldBe "RECEPCION"
            }
        }

        it("rechaza la ubicación duplicada con 409 DUPLICATE_LOCATION") {
            conApi { pool ->
                val bodegaId = CatalogoTestDb.bodegaPrincipal(pool)
                val cuerpo = """{"warehouseId":$bodegaId,"hall":"05","code":"A","position":"A"}"""

                client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo)
                }.status shouldBe HttpStatusCode.Created

                val repetida = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody(cuerpo)
                }

                repetida.status shouldBe HttpStatusCode.Conflict
                repetida.codigoDeError() shouldBe "DUPLICATE_LOCATION"
            }
        }

        it("rechaza un tipo de ubicación que no existe") {
            conApi { pool ->
                val bodegaId = CatalogoTestDb.bodegaPrincipal(pool)

                val respuesta = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$bodegaId,"hall":"06","code":"A","position":"A","kind":"PASILLO"}""")
                }

                respuesta.status shouldBe HttpStatusCode.UnprocessableEntity
                respuesta.codigoDeError() shouldBe "INVALID_LOCATION_KIND"
            }
        }

        it("rechaza una bodega que no existe") {
            conApi { _ ->
                val respuesta = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":987654,"hall":"07","code":"A","position":"A"}""")
                }

                respuesta.status shouldBe HttpStatusCode.UnprocessableEntity
                respuesta.codigoDeError() shouldBe "UNKNOWN_WAREHOUSE"
            }
        }

        it("actualiza los niveles y vuelve a activar con PUT") {
            conApi { pool ->
                val bodegaId = CatalogoTestDb.bodegaPrincipal(pool)
                val id = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$bodegaId,"hall":"08","code":"A","position":"A"}""")
                }.cuerpo()["id"]!!.jsonPrimitive.long

                val respuesta = client.put("/v1/locations/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"levels":7,"kind":"RECEIVING","active":false}""")
                }

                respuesta.status shouldBe HttpStatusCode.OK
                respuesta.cuerpo()["levels"]!!.jsonPrimitive.int shouldBe 7
                respuesta.cuerpo()["kind"]!!.jsonPrimitive.content shouldBe "RECEIVING"
                respuesta.cuerpo()["active"]!!.jsonPrimitive.content.toBoolean() shouldBe false
            }
        }

        it("no permite mover una ubicación de bodega") {
            conApi { pool ->
                val bodegaId = CatalogoTestDb.bodegaPrincipal(pool)
                val id = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$bodegaId,"hall":"09","code":"A","position":"A"}""")
                }.cuerpo()["id"]!!.jsonPrimitive.long

                val respuesta = client.put("/v1/locations/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":${bodegaId + 1},"levels":2}""")
                }

                respuesta.status shouldBe HttpStatusCode.UnprocessableEntity
                respuesta.codigoDeError() shouldBe "WAREHOUSE_IMMUTABLE"
            }
        }

        it("desactiva una ubicación vacía") {
            conApi { pool ->
                val bodegaId = CatalogoTestDb.bodegaPrincipal(pool)
                val id = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$bodegaId,"hall":"10","code":"A","position":"A"}""")
                }.cuerpo()["id"]!!.jsonPrimitive.long

                client.delete("/v1/locations/$id?reason=pasillo reubicado")
                    .status shouldBe HttpStatusCode.NoContent

                client.get("/v1/locations/$id").cuerpo()["active"]!!.jsonPrimitive.content
                    .toBoolean() shouldBe false
            }
        }

        it("una ubicación con existencias no se desactiva: 409 LOCATION_HAS_STOCK") {
            conApi { pool ->
                val bodegaId = CatalogoTestDb.bodegaPrincipal(pool)
                val id = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$bodegaId,"hall":"11","code":"A","position":"A"}""")
                }.cuerpo()["id"]!!.jsonPrimitive.long

                CatalogoTestDb.insertarProducto(pool, "UBI0001", "PRODUCTO EN EL ESTANTE")
                val productoId = CatalogoTestDb.idDeProducto(pool, "UBI0001")
                CatalogoTestDb.insertarStock(pool, productoId, id, bodegaId, "3.000")

                val respuesta = client.delete("/v1/locations/$id?reason=vacia")

                respuesta.status shouldBe HttpStatusCode.Conflict
                respuesta.codigoDeError() shouldBe "LOCATION_HAS_STOCK"
            }
        }

        it("la ubicación de recepción está protegida: sin ella la recepción no tiene destino") {
            conApi { pool ->
                val (recepcionId, _) = CatalogoTestDb.ubicacionDeRecepcion(pool)

                val respuesta = client.delete("/v1/locations/$recepcionId?reason=estorbaba")

                respuesta.status shouldBe HttpStatusCode.Conflict
                respuesta.codigoDeError() shouldBe "RECEIVING_LOCATION_PROTECTED"
            }
        }

        it("filtra las ubicaciones por bodega") {
            conApi { pool ->
                val bodegaId = CatalogoTestDb.bodegaPrincipal(pool)
                client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$bodegaId,"hall":"12","code":"A","position":"A"}""")
                }.status shouldBe HttpStatusCode.Created

                val deLaPrincipal = client.get("/v1/locations?warehouseId=$bodegaId")
                    .cuerpo()["totalItems"]!!.jsonPrimitive.long

                withClue("La recepción sembrada más la creada") { deLaPrincipal shouldBe 2L }

                val deOtra = client.get("/v1/locations?warehouseId=987654")
                    .cuerpo()["totalItems"]!!.jsonPrimitive.long
                deOtra shouldBe 0L
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("bodegas") {

        it("lista la bodega sembrada por la migración") {
            conApi { _ ->
                val respuesta = client.get("/v1/warehouses")

                respuesta.status shouldBe HttpStatusCode.OK
                val items = respuesta.cuerpo()["items"]!!.jsonArray
                items.size shouldBe 1
                items.single().jsonObject["code"]!!.jsonPrimitive.content shouldBe "PRINCIPAL"
                items.single().jsonObject["isDefault"]!!.jsonPrimitive.content.toBoolean() shouldBe true
            }
        }

        it("devuelve TODAS las bodegas y sin sobre de paginación (§1.4)") {
            conApi { pool ->
                CatalogoTestDb.ejecutar(
                    pool,
                    "INSERT INTO warehouses (code, name) VALUES ('SUCURSAL', 'Bodega sucursal')",
                )

                val respuesta = client.get("/v1/warehouses?page=0&size=1")
                val cuerpo = respuesta.cuerpo()
                val items = cuerpo["items"]!!.jsonArray

                withClue(
                    "Es un catálogo cerrado y diminuto que la interfaz necesita COMPLETO " +
                        "para poblar un desplegable: paginarlo obligaría a pedir todas las " +
                        "páginas antes de dibujar el select"
                ) {
                    items.size shouldBe 2
                }
                withClue("El sobre de paginación se omite a propósito, no se rellena") {
                    cuerpo.containsKey("page") shouldBe false
                    cuerpo.containsKey("totalItems") shouldBe false
                    cuerpo.containsKey("totalPages") shouldBe false
                }
                withClue("El orden es estable por código, aunque no se pida sort") {
                    items.map { it.jsonObject["code"]!!.jsonPrimitive.content } shouldBe
                        listOf("PRINCIPAL", "SUCURSAL")
                }
            }
        }

        it("filtra por nombre y por estado con parámetros opcionales") {
            conApi { pool ->
                CatalogoTestDb.ejecutar(
                    pool,
                    "INSERT INTO warehouses (code, name, active) VALUES ('VIEJA', 'Bodega vieja', false)",
                )

                client.get("/v1/warehouses?q=vieja").cuerpo()["items"]!!.jsonArray.size shouldBe 1
                client.get("/v1/warehouses?active=true").cuerpo()["items"]!!.jsonArray.size shouldBe 1
                client.get("/v1/warehouses?q=inexistente").cuerpo()["items"]!!.jsonArray.size shouldBe 0
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("control de acceso (§11)") {

        it("la implementación por defecto deniega todo con 401, no con 403") {
            if (!hayBase) return@it
            CatalogoTestDb.conPool(nombreBase) { pool ->
                testApplication {
                    // Sin verificador explícito: el de por defecto.
                    montar(pool, dev.axzy.stock.backend.security.DenyAllPermissionChecker)

                    val respuesta = client.get("/v1/products")

                    withClue(
                        "Sin identidad conectada la verdad es que no hay identidad, y el " +
                            "cliente debe iniciar sesión; un 403 haría que pidiera un permiso " +
                            "que no le falta"
                    ) {
                        respuesta.status shouldBe HttpStatusCode.Unauthorized
                        respuesta.codigoDeError() shouldBe "UNAUTHENTICATED"
                    }
                }
            }
        }

        it("un permiso que falta se responde con 403 y el código del motivo") {
            val sinPermiso = object : PermissionChecker {
                override suspend fun comprobar(
                    call: io.ktor.server.application.ApplicationCall,
                    permiso: String,
                ): PermissionDecision = PermissionDecision.Denegado(
                    code = "PERMISSION_DENIED",
                    message = "Falta el permiso '$permiso'.",
                )
            }

            conApi(sinPermiso) { _ ->
                val respuesta = client.get("/v1/warehouses")

                respuesta.status shouldBe HttpStatusCode.Forbidden
                withClue("El código distingue «no tienes el permiso» de «no alcanzas el recurso»") {
                    respuesta.codigoDeError() shouldBe "PERMISSION_DENIED"
                }
            }
        }

        it("una denegación por alcance conserva su código propio") {
            val sinAlcance = object : PermissionChecker {
                override suspend fun comprobar(
                    call: io.ktor.server.application.ApplicationCall,
                    permiso: String,
                ): PermissionDecision = PermissionDecision.Denegado(
                    code = "SCOPE_INSUFFICIENT",
                    message = "El permiso '$permiso' no alcanza esta bodega.",
                )
            }

            conApi(sinAlcance) { _ ->
                val respuesta = client.get("/v1/products")

                respuesta.status shouldBe HttpStatusCode.Forbidden
                respuesta.codigoDeError() shouldBe "SCOPE_INSUFFICIENT"
            }
        }

        it("para crear exige product.create y para borrar product.delete") {
            val permisosPedidos = mutableListOf<String>()
            val registrador = object : PermissionChecker {
                override suspend fun comprobar(
                    call: io.ktor.server.application.ApplicationCall,
                    permiso: String,
                ): PermissionDecision {
                    permisosPedidos += permiso
                    return PermissionDecision.Permitido
                }
            }

            conApi(registrador) { _ ->
                val id = crearProducto("PRM0001", "PRODUCTO").cuerpo()["id"]!!.jsonPrimitive.long
                client.put("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"OTRO","unitMeasure":"PZA"}""")
                }
                client.delete("/v1/products/$id?reason=prueba")
                client.get("/v1/locations")
                client.get("/v1/warehouses")
            }

            permisosPedidos shouldBe listOf(
                "product.create",
                "product.edit",
                "product.delete",
                "location.view",
                "location.view",
            )
        }
    }
})
