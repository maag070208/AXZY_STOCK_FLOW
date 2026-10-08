package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.module
import dev.axzy.stock.backend.catalog.catalogRoutes
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

    val nameBase = "stock_catalog_test"
    val connectionProblem = CatalogTestDb.connectionProblem()
    val thereIsBase = connectionProblem == null

    if (!thereIsBase) {
        println(
            """
            |
            |  ⚠️  TESTS DE CATÁLOGO SALTADOS: no se pudo consultar PostgreSQL
            |      Motivo: $connectionProblem
            |      Levántalo con:  cd infra && docker compose up -d
            |
            """.trimMargin()
        )
    }

    beforeSpec {
        if (thereIsBase) CatalogTestDb.recreate(nameBase)
    }

    // Cada test parte de un catálogo vacío pero migrado: los datos de un test no
    // pueden cambiar el resultado de otro, sobre todo los recuentos.
    beforeTest {
        if (thereIsBase) CatalogTestDb.withPool(nameBase) { pool -> CatalogTestDb.clean(pool) }
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    /** Monta la aplicación con el módulo real y las rutas del catálogo. */
    fun ApplicationTestBuilder.build(
        pool: DataSource,
        verifier: PermissionChecker = AllowAllPermissionChecker,
    ) {
        application {
            // El verificador se pasa a `module()`, que registra las rutas
            // UNA sola vez. Antes se montaba `module(pool)` y ADEMAS un
            // `routing { catalogRoutes(...) }`, asi que las rutas quedaban
            // duplicadas y Ktor resolvia la del verificador que deniega todo.
            module(dataSource = pool, permissionChecker = verifier)
        }
    }

    /** Ejecuta un bloque HTTP contra una base real y limpia. */
    fun withApi(
        verifier: PermissionChecker = AllowAllPermissionChecker,
        block: suspend ApplicationTestBuilder.(DataSource) -> Unit,
    ) {
        if (!thereIsBase) return
        CatalogTestDb.withPool(nameBase) { pool ->
            testApplication {
                build(pool, verifier)
                block(pool)
            }
        }
    }

    suspend fun ApplicationTestBuilder.createProduct(
        code: String,
        name: String,
        description: String? = null,
        unit: String = "PZA",
    ): HttpResponse {
        val descriptionJson = description?.let { "\"$it\"" } ?: "null"
        return client.post("/v1/products") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"productCode":"$code","name":"$name",
                 "description":$descriptionJson,"unitMeasure":"$unit"}
                """.trimIndent()
            )
        }
    }

    suspend fun HttpResponse.body(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject

    suspend fun HttpResponse.errorCode(): String =
        body()["code"]!!.jsonPrimitive.content

    // ═════════════════════════════════════════════════════════════════════════
    describe("paginación y orden en la base (§1.4)") {

        it("devuelve el sobre del contrato con el total contado en la base") {
            withApi { _ ->
                createProduct("AAA0001", "ABRAZADERA UNO")
                createProduct("AAA0002", "ABRAZADERA DOS")
                createProduct("AAA0003", "ABRAZADERA TRES")

                val first = client.get("/v1/products?page=0&size=2")
                first.status shouldBe HttpStatusCode.OK
                val over = first.body()

                over["page"]!!.jsonPrimitive.int shouldBe 0
                over["size"]!!.jsonPrimitive.int shouldBe 2
                withClue("totalItems es el total que cumple el filtro, no el tamaño de la página") {
                    over["totalItems"]!!.jsonPrimitive.long shouldBe 3L
                }
                over["totalPages"]!!.jsonPrimitive.int shouldBe 2
                over["items"]!!.jsonArray.size shouldBe 2

                val second = client.get("/v1/products?page=1&size=2")
                second.body()["items"]!!.jsonArray.size shouldBe 1
            }
        }

        it("ordena por nombre descendente") {
            withApi { _ ->
                createProduct("BBB0001", "B INTERMEDIO")
                createProduct("BBB0002", "A PRIMERO")
                createProduct("BBB0003", "C ULTIMO")

                val items = client.get("/v1/products?sort=name,desc")
                    .body()["items"]!!.jsonArray

                items.map { it.jsonObject["name"]!!.jsonPrimitive.content } shouldBe
                    listOf("C ULTIMO", "B INTERMEDIO", "A PRIMERO")
            }
        }

        it("busca por código y por nombre con q") {
            withApi { _ ->
                createProduct("CCC0001", "TORNILLO")
                createProduct("DDD0002", "ABRAZADERA")

                val byName = client.get("/v1/products?q=abraz").body()["items"]!!.jsonArray
                byName.size shouldBe 1
                byName.single().jsonObject["productCode"]!!.jsonPrimitive.content shouldBe "DDD0002"

                val byCode = client.get("/v1/products?q=ccc").body()["items"]!!.jsonArray
                byCode.size shouldBe 1
            }
        }

        it("rechaza size fuera de rango con 400 en lugar de recortarlo") {
            withApi { _ ->
                val response = client.get("/v1/products?size=500")

                response.status shouldBe HttpStatusCode.BadRequest
                response.errorCode() shouldBe "INVALID_PAGINATION"
            }
        }

        it("rechaza un campo de orden no admitido con 400") {
            withApi { _ ->
                val response = client.get("/v1/products?sort=stock,asc")

                response.status shouldBe HttpStatusCode.BadRequest
                response.errorCode() shouldBe "INVALID_SORT"
            }
        }

        it("rechaza un identificador de ruta que no es un número") {
            withApi { _ ->
                client.get("/v1/products/abc").status shouldBe HttpStatusCode.BadRequest
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("productos") {

        it("crea un producto con 201, su id y stock cero") {
            withApi { _ ->
                val response = createProduct("PPP0001", "PRODUCTO NUEVO", "descripción")

                response.status shouldBe HttpStatusCode.Created
                val created = response.body()
                created["productCode"]!!.jsonPrimitive.content shouldBe "PPP0001"
                created["unitMeasure"]!!.jsonPrimitive.content shouldBe "PZA"
                created["active"]!!.jsonPrimitive.content.toBoolean() shouldBe true
                withClue("El stock de un producto recién creado es 0.000, como número") {
                    response.bodyAsText() shouldContain "\"stock\":0.000"
                }
            }
        }

        it("rechaza el código duplicado con 409 DUPLICATE_PRODUCT_CODE") {
            withApi { _ ->
                createProduct("DUP0001", "PRIMERO").status shouldBe HttpStatusCode.Created

                val repeated = createProduct("DUP0001", "SEGUNDO")

                repeated.status shouldBe HttpStatusCode.Conflict
                repeated.errorCode() shouldBe "DUPLICATE_PRODUCT_CODE"
            }
        }

        it("rechaza un código que no cumple el alfabeto con 422 y el campo señalado") {
            withApi { _ ->
                val response = createProduct("mal-codigo", "PRODUCTO")

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.errorCode() shouldBe "INVALID_PRODUCT_CODE"
                response.body()["details"]!!.jsonObject.containsKey("productCode") shouldBe true
            }
        }

        it("rechaza una unidad de medida que no está en el catálogo") {
            withApi { _ ->
                val response = createProduct("UNI0001", "PRODUCTO", unit = "KGS")

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.errorCode() shouldBe "UNKNOWN_UNIT_MEASURE"
            }
        }

        it("devuelve 404 cuando el producto no existe") {
            withApi { _ ->
                val response = client.get("/v1/products/999999")

                response.status shouldBe HttpStatusCode.NotFound
                response.errorCode() shouldBe "PRODUCT_NOT_FOUND"
            }
        }

        it("actualiza nombre, descripción y unidad con PUT") {
            withApi { _ ->
                val id = createProduct("ACT0001", "NOMBRE VIEJO").body()["id"]!!.jsonPrimitive.long

                val response = client.put("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"NOMBRE NUEVO","description":"ahora sí","unitMeasure":"PZA"}""")
                }

                response.status shouldBe HttpStatusCode.OK
                response.body()["name"]!!.jsonPrimitive.content shouldBe "NOMBRE NUEVO"
                response.body()["description"]!!.jsonPrimitive.content shouldBe "ahora sí"
            }
        }

        it("tolera el ida y vuelta del objeto y rechaza cambiar el código") {
            withApi { _ ->
                val created = createProduct("INM0001", "PRODUCTO").body()
                val id = created["id"]!!.jsonPrimitive.long

                // Un cliente que hace GET, cambia el nombre y devuelve el objeto
                // entero manda el mismo `productCode`: eso no es un cambio.
                val withSameCode = client.put("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"productCode":"INM0001","name":"OTRO NOMBRE","unitMeasure":"PZA"}""")
                }
                withSameCode.status shouldBe HttpStatusCode.OK

                val withOtherCode = client.put("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"productCode":"INM0002","name":"OTRO NOMBRE","unitMeasure":"PZA"}""")
                }
                withOtherCode.status shouldBe HttpStatusCode.UnprocessableEntity
                withOtherCode.errorCode() shouldBe "PRODUCT_CODE_IMMUTABLE"
            }
        }

        it("el borrado es lógico, exige motivo y deja el producto inactivo") {
            withApi { _ ->
                val id = createProduct("DEL0001", "SE VA").body()["id"]!!.jsonPrimitive.long

                val withoutReason = client.delete("/v1/products/$id")
                withoutReason.status shouldBe HttpStatusCode.UnprocessableEntity
                withoutReason.errorCode() shouldBe "REASON_REQUIRED"

                val withReason = client.delete("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"reason":"producto descontinuado por el proveedor"}""")
                }
                withReason.status shouldBe HttpStatusCode.NoContent

                val after = client.get("/v1/products/$id")
                after.status shouldBe HttpStatusCode.OK
                after.body()["active"]!!.jsonPrimitive.content.toBoolean() shouldBe false
                withClue("El producto no se borra de la base: se desactiva") {
                    after.body()["productCode"]!!.jsonPrimitive.content shouldBe "DEL0001"
                }
            }
        }

        it("acepta el motivo en la query, para clientes que no mandan cuerpo en DELETE") {
            withApi { _ ->
                val id = createProduct("DEL0002", "SE VA").body()["id"]!!.jsonPrimitive.long

                val response = client.delete("/v1/products/$id?reason=duplicado")

                response.status shouldBe HttpStatusCode.NoContent
            }
        }

        it("no desactiva dos veces: el segundo borrado pisaría el motivo del primero") {
            withApi { _ ->
                val id = createProduct("DEL0003", "SE VA").body()["id"]!!.jsonPrimitive.long
                client.delete("/v1/products/$id?reason=primero").status shouldBe HttpStatusCode.NoContent

                val second = client.delete("/v1/products/$id?reason=segundo")

                second.status shouldBe HttpStatusCode.Conflict
                second.errorCode() shouldBe "PRODUCT_ALREADY_INACTIVE"
            }
        }

        it("un producto con existencias no se desactiva: 409 PRODUCT_HAS_STOCK con details.stock") {
            withApi { pool ->
                val id = createProduct("STK0001", "CON STOCK").body()["id"]!!.jsonPrimitive.long
                val (locationId, warehouseId) = CatalogTestDb.receivingLocation(pool)
                CatalogTestDb.insertStock(pool, id, locationId, warehouseId, "12.500")

                val response = client.delete("/v1/products/$id?reason=ya no se usa")

                response.status shouldBe HttpStatusCode.Conflict
                response.errorCode() shouldBe "PRODUCT_HAS_STOCK"
                withClue("El detalle dice cuánto stock lo bloquea, en número con 3 decimales") {
                    response.bodyAsText() shouldContain "12.500"
                }
            }
        }

        it("el stock es la suma de las ubicaciones y de la mercancía en tránsito") {
            withApi { pool ->
                val id = createProduct("STK0002", "REPARTIDO").body()["id"]!!.jsonPrimitive.long
                val (locationId, warehouseId) = CatalogTestDb.receivingLocation(pool)
                CatalogTestDb.insertStock(pool, id, locationId, warehouseId, "100.000")
                CatalogTestDb.execute(
                    pool,
                    "INSERT INTO warehouses (code, name) VALUES ('SUCURSAL', 'Bodega sucursal')",
                )
                val branch = CatalogTestDb.querySingleLong(
                    pool,
                    "SELECT id FROM warehouses WHERE code = 'SUCURSAL'",
                )
                CatalogTestDb.insertTransit(pool, id, warehouseId, branch, "50.000")

                val detail = client.get("/v1/products/$id")

                withClue("La mercancía en el camión SIGUE contando en el total") {
                    detail.bodyAsText() shouldContain "\"stock\":150.000"
                }
            }
        }

        it("el listado incluye los productos inactivos, marcados como tales") {
            withApi { _ ->
                val id = createProduct("INA0001", "DESACTIVADO").body()["id"]!!.jsonPrimitive.long
                client.delete("/v1/products/$id?reason=obsoleto").status shouldBe HttpStatusCode.NoContent

                val all = client.get("/v1/products").body()["items"]!!.jsonArray
                all.size shouldBe 1

                val onlyActive = client.get("/v1/products?active=true").body()["items"]!!.jsonArray
                onlyActive.size shouldBe 0
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("ubicaciones") {

        it("crea una ubicación y compone su nombre para mostrar") {
            withApi { pool ->
                val warehouseId = CatalogTestDb.warehouseMain(pool)

                val response = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$warehouseId,"hall":"04","code":"b","position":"c","levels":3}""")
                }

                response.status shouldBe HttpStatusCode.Created
                val created = response.body()
                withClue("Las partes se normalizan a mayúsculas: el esquema lo exige") {
                    created["hall"]!!.jsonPrimitive.content shouldBe "04"
                    created["code"]!!.jsonPrimitive.content shouldBe "B"
                }
                created["displayName"]!!.jsonPrimitive.content shouldBe "04-B-C"
                created["kind"]!!.jsonPrimitive.content shouldBe "SHELF"
                created["warehouseCode"]!!.jsonPrimitive.content shouldBe "PRINCIPAL"
            }
        }

        it("la ubicación de recepción se muestra como RECEPCION, sin repetir la terna") {
            withApi { _ ->
                val items = client.get("/v1/locations?kind=RECEIVING").body()["items"]!!.jsonArray

                items.size shouldBe 1
                items.single().jsonObject["displayName"]!!.jsonPrimitive.content shouldBe "RECEPCION"
            }
        }

        it("rechaza la ubicación duplicada con 409 DUPLICATE_LOCATION") {
            withApi { pool ->
                val warehouseId = CatalogTestDb.warehouseMain(pool)
                val body = """{"warehouseId":$warehouseId,"hall":"05","code":"A","position":"A"}"""

                client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status shouldBe HttpStatusCode.Created

                val repeated = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

                repeated.status shouldBe HttpStatusCode.Conflict
                repeated.errorCode() shouldBe "DUPLICATE_LOCATION"
            }
        }

        it("rechaza un tipo de ubicación que no existe") {
            withApi { pool ->
                val warehouseId = CatalogTestDb.warehouseMain(pool)

                val response = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$warehouseId,"hall":"06","code":"A","position":"A","kind":"PASILLO"}""")
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.errorCode() shouldBe "INVALID_LOCATION_KIND"
            }
        }

        it("rechaza una bodega que no existe") {
            withApi { _ ->
                val response = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":987654,"hall":"07","code":"A","position":"A"}""")
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.errorCode() shouldBe "UNKNOWN_WAREHOUSE"
            }
        }

        it("actualiza los niveles y vuelve a activar con PUT") {
            withApi { pool ->
                val warehouseId = CatalogTestDb.warehouseMain(pool)
                val id = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$warehouseId,"hall":"08","code":"A","position":"A"}""")
                }.body()["id"]!!.jsonPrimitive.long

                val response = client.put("/v1/locations/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"levels":7,"kind":"RECEIVING","active":false}""")
                }

                response.status shouldBe HttpStatusCode.OK
                response.body()["levels"]!!.jsonPrimitive.int shouldBe 7
                response.body()["kind"]!!.jsonPrimitive.content shouldBe "RECEIVING"
                response.body()["active"]!!.jsonPrimitive.content.toBoolean() shouldBe false
            }
        }

        it("no permite mover una ubicación de bodega") {
            withApi { pool ->
                val warehouseId = CatalogTestDb.warehouseMain(pool)
                val id = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$warehouseId,"hall":"09","code":"A","position":"A"}""")
                }.body()["id"]!!.jsonPrimitive.long

                val response = client.put("/v1/locations/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":${warehouseId + 1},"levels":2}""")
                }

                response.status shouldBe HttpStatusCode.UnprocessableEntity
                response.errorCode() shouldBe "WAREHOUSE_IMMUTABLE"
            }
        }

        it("desactiva una ubicación vacía") {
            withApi { pool ->
                val warehouseId = CatalogTestDb.warehouseMain(pool)
                val id = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$warehouseId,"hall":"10","code":"A","position":"A"}""")
                }.body()["id"]!!.jsonPrimitive.long

                client.delete("/v1/locations/$id?reason=pasillo reubicado")
                    .status shouldBe HttpStatusCode.NoContent

                client.get("/v1/locations/$id").body()["active"]!!.jsonPrimitive.content
                    .toBoolean() shouldBe false
            }
        }

        it("una ubicación con existencias no se desactiva: 409 LOCATION_HAS_STOCK") {
            withApi { pool ->
                val warehouseId = CatalogTestDb.warehouseMain(pool)
                val id = client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$warehouseId,"hall":"11","code":"A","position":"A"}""")
                }.body()["id"]!!.jsonPrimitive.long

                CatalogTestDb.insertProduct(pool, "UBI0001", "PRODUCTO EN EL ESTANTE")
                val productId = CatalogTestDb.productId(pool, "UBI0001")
                CatalogTestDb.insertStock(pool, productId, id, warehouseId, "3.000")

                val response = client.delete("/v1/locations/$id?reason=vacia")

                response.status shouldBe HttpStatusCode.Conflict
                response.errorCode() shouldBe "LOCATION_HAS_STOCK"
            }
        }

        it("la ubicación de recepción está protegida: sin ella la recepción no tiene destino") {
            withApi { pool ->
                val (receivingId, _) = CatalogTestDb.receivingLocation(pool)

                val response = client.delete("/v1/locations/$receivingId?reason=estorbaba")

                response.status shouldBe HttpStatusCode.Conflict
                response.errorCode() shouldBe "RECEIVING_LOCATION_PROTECTED"
            }
        }

        it("filtra las ubicaciones por bodega") {
            withApi { pool ->
                val warehouseId = CatalogTestDb.warehouseMain(pool)
                client.post("/v1/locations") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"warehouseId":$warehouseId,"hall":"12","code":"A","position":"A"}""")
                }.status shouldBe HttpStatusCode.Created

                val fromMain = client.get("/v1/locations?warehouseId=$warehouseId")
                    .body()["totalItems"]!!.jsonPrimitive.long

                withClue("La recepción sembrada más la creada") { fromMain shouldBe 2L }

                val fromOther = client.get("/v1/locations?warehouseId=987654")
                    .body()["totalItems"]!!.jsonPrimitive.long
                fromOther shouldBe 0L
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("bodegas") {

        it("lista la bodega sembrada por la migración") {
            withApi { _ ->
                val response = client.get("/v1/warehouses")

                response.status shouldBe HttpStatusCode.OK
                val items = response.body()["items"]!!.jsonArray
                items.size shouldBe 1
                items.single().jsonObject["code"]!!.jsonPrimitive.content shouldBe "PRINCIPAL"
                items.single().jsonObject["isDefault"]!!.jsonPrimitive.content.toBoolean() shouldBe true
            }
        }

        it("devuelve TODAS las bodegas y sin sobre de paginación (§1.4)") {
            withApi { pool ->
                CatalogTestDb.execute(
                    pool,
                    "INSERT INTO warehouses (code, name) VALUES ('SUCURSAL', 'Bodega sucursal')",
                )

                val response = client.get("/v1/warehouses?page=0&size=1")
                val body = response.body()
                val items = body["items"]!!.jsonArray

                withClue(
                    "Es un catálogo cerrado y diminuto que la interfaz necesita COMPLETO " +
                        "para poblar un desplegable: paginarlo obligaría a pedir todas las " +
                        "páginas antes de dibujar el select"
                ) {
                    items.size shouldBe 2
                }
                withClue("El sobre de paginación se omite a propósito, no se rellena") {
                    body.containsKey("page") shouldBe false
                    body.containsKey("totalItems") shouldBe false
                    body.containsKey("totalPages") shouldBe false
                }
                withClue("El orden es estable por código, aunque no se pida sort") {
                    items.map { it.jsonObject["code"]!!.jsonPrimitive.content } shouldBe
                        listOf("PRINCIPAL", "SUCURSAL")
                }
            }
        }

        it("filtra por nombre y por estado con parámetros opcionales") {
            withApi { pool ->
                CatalogTestDb.execute(
                    pool,
                    "INSERT INTO warehouses (code, name, active) VALUES ('VIEJA', 'Bodega vieja', false)",
                )

                client.get("/v1/warehouses?q=vieja").body()["items"]!!.jsonArray.size shouldBe 1
                client.get("/v1/warehouses?active=true").body()["items"]!!.jsonArray.size shouldBe 1
                client.get("/v1/warehouses?q=inexistente").body()["items"]!!.jsonArray.size shouldBe 0
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("control de acceso (§11)") {

        it("la implementación por defecto deniega todo con 401, no con 403") {
            if (!thereIsBase) return@it
            CatalogTestDb.withPool(nameBase) { pool ->
                testApplication {
                    // Sin verificador explícito: el de por defecto.
                    build(pool, dev.axzy.stock.backend.security.DenyAllPermissionChecker)

                    val response = client.get("/v1/products")

                    withClue(
                        "Sin identidad conectada la verdad es que no hay identidad, y el " +
                            "cliente debe iniciar sesión; un 403 haría que pidiera un permiso " +
                            "que no le falta"
                    ) {
                        response.status shouldBe HttpStatusCode.Unauthorized
                        response.errorCode() shouldBe "UNAUTHENTICATED"
                    }
                }
            }
        }

        it("un permiso que falta se responde con 403 y el código del motivo") {
            val withoutPermission = object : PermissionChecker {
                override suspend fun check(
                    call: io.ktor.server.application.ApplicationCall,
                    permission: String,
                ): PermissionDecision = PermissionDecision.Denied(
                    code = "PERMISSION_DENIED",
                    message = "Falta el permiso '$permission'.",
                )
            }

            withApi(withoutPermission) { _ ->
                val response = client.get("/v1/warehouses")

                response.status shouldBe HttpStatusCode.Forbidden
                withClue("El código distingue «no tienes el permiso» de «no alcanzas el recurso»") {
                    response.errorCode() shouldBe "PERMISSION_DENIED"
                }
            }
        }

        it("una denegación por alcance conserva su código propio") {
            val withoutScope = object : PermissionChecker {
                override suspend fun check(
                    call: io.ktor.server.application.ApplicationCall,
                    permission: String,
                ): PermissionDecision = PermissionDecision.Denied(
                    code = "SCOPE_INSUFFICIENT",
                    message = "El permiso '$permission' no alcanza esta bodega.",
                )
            }

            withApi(withoutScope) { _ ->
                val response = client.get("/v1/products")

                response.status shouldBe HttpStatusCode.Forbidden
                response.errorCode() shouldBe "SCOPE_INSUFFICIENT"
            }
        }

        it("para crear exige product.create y para borrar product.delete") {
            val permissionsRequests = mutableListOf<String>()
            val logger = object : PermissionChecker {
                override suspend fun check(
                    call: io.ktor.server.application.ApplicationCall,
                    permission: String,
                ): PermissionDecision {
                    permissionsRequests += permission
                    return PermissionDecision.Permitted
                }
            }

            withApi(logger) { _ ->
                val id = createProduct("PRM0001", "PRODUCTO").body()["id"]!!.jsonPrimitive.long
                client.put("/v1/products/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"OTRO","unitMeasure":"PZA"}""")
                }
                client.delete("/v1/products/$id?reason=prueba")
                client.get("/v1/locations")
                client.get("/v1/warehouses")
            }

            permissionsRequests shouldBe listOf(
                "product.create",
                "product.edit",
                "product.delete",
                "location.view",
                "location.view",
            )
        }
    }
})
