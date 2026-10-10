package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * El catálogo (M01) de punta a punta por HTTP real: Tomcat, la cadena de
 * seguridad, el cableado y PostgreSQL.
 *
 * Los nombres K1…K6 son los de la sección «Pruebas de aceptación» de
 * docs/flujos/catalogo.md. Aquí también se cierran I5 e I8, que necesitaban una
 * ruta protegida por permiso y llegan con este módulo.
 */
class CatalogFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    /** Un usuario con todos los permisos y sin el cambio de contraseña pendiente. */
    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun productCount(): Int =
        db.sql("SELECT count(*) FROM products").query(Int::class.java).single()

    private fun csv(vararg rows: String): String =
        (listOf("externalId,productCode,name,unitMeasure,stock") + rows).joinToString("\n")

    private val validFile = csv(
        "1,ABRAA1B,ABRAZADERA DE ARRANQUE,PZA,10",
        "2,ABRAA1C,ABRAZADERA DE ARRANQUE 1-5/8,PZA,5",
    )

    // ── K1: una fila inválida rechaza el archivo entero ─────────────────────

    @Test
    fun `K1 - una fila invalida rechaza el archivo entero y no escribe nada`() {
        val token = manager()
        val file = csv(
            "1,ABRAA1B,ABRAZADERA DE ARRANQUE,PZA,10",
            "2,ABRAA1C,OTRA,PZA,5",
            "3,,SIN CODIGO,PZA,7",
        )

        val response = postFile("/v1/products/import", file, token = token, query = "?dryRun=false")

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("IMPORT_INVALID_ROWS")
        assertThat(response.at<Map<String, String>>("details")).containsKey("fila_4")
        assertThat(productCount()).isZero()
    }

    @Test
    fun `una simulacion con filas invalidas tampoco produce informe`() {
        val token = manager()
        val file = csv(
            "1,ABRAA1B,ABRAZADERA,PZA,10",
            "2,abraa1c,EN MINUSCULAS,PZA,5",
        )

        // El código en minúsculas se rechaza, no se normaliza: indica que algo cambió
        // en el origen del dato.
        val response = postFile("/v1/products/import", file, token = token)

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("IMPORT_INVALID_ROWS")
        assertThat(productCount()).isZero()
    }

    // ── K2: la importación es idempotente ───────────────────────────────────

    @Test
    fun `K2 - importar dos veces no duplica productos ni vuelve a crearlos`() {
        val token = manager()

        val first = postFile("/v1/products/import", validFile, token = token, query = "?dryRun=false")
        val second = postFile("/v1/products/import", validFile, token = token, query = "?dryRun=false")

        assertThat(first.status).isEqualTo(200)
        assertThat(first.at<Int>("created")).isEqualTo(2)
        assertThat(first.at<Int>("persisted")).isEqualTo(2)
        assertThat(second.at<Int>("created")).isZero()
        assertThat(second.at<Int>("skipped")).isEqualTo(2)
        assertThat(second.at<Int>("persisted")).isZero()
        assertThat(productCount()).isEqualTo(2)
    }

    @Test
    fun `la simulacion no escribe nada y dice lo que se crearia`() {
        val token = manager()

        // `dryRun` omitido: el valor por defecto es simular, porque el olvido inverso
        // (escribir 1.398 productos) no tiene vuelta atrás.
        val response = postFile("/v1/products/import", validFile, token = token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<Boolean>("dryRun")).isTrue()
        assertThat(response.at<Int>("accepted")).isEqualTo(2)
        assertThat(response.at<Int>("persisted")).isZero()
        assertThat(response.at<Boolean>("stockPersisted")).isFalse()
        assertThat(productCount()).isZero()
    }

    @Test
    fun `el Excel todavia no se lee, y se dice por que`() {
        val token = manager()

        val response = postFile("/v1/products/import", validFile, filename = "catalogo.xlsx", token = token)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("IMPORT_FILE_FORMAT")
    }

    @Test
    fun `un archivo vacio responde EMPTY_FILE`() {
        val token = manager()

        val response = postFile("/v1/products/import", "externalId,productCode,name,unitMeasure,stock", token = token)

        assertThat(response.code).isEqualTo("EMPTY_FILE")
    }

    // ── K3: el código de producto no se cambia ──────────────────────────────

    @Test
    fun `K3 - cambiar el codigo de un producto responde PRODUCT_CODE_IMMUTABLE`() {
        val token = manager()
        val created = post(
            "/v1/products",
            mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA", "unitMeasure" to "PZA"),
            token,
        )
        assertThat(created.status).isEqualTo(201)

        val response = send(
            "PUT", "/v1/products/${created.at<Int>("id")}",
            mapOf("productCode" to "ABRAA1C", "name" to "ABRAZADERA", "unitMeasure" to "PZA"),
            token, emptyMap(),
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("PRODUCT_CODE_IMMUTABLE")
    }

    @Test
    fun `crear un producto escribe en la bitacora dentro de la misma transaccion`() {
        val token = manager()

        post("/v1/products", mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA", "unitMeasure" to "PZA"), token)

        val registros = db.sql("SELECT count(*) FROM audit_log WHERE action = 'PRODUCT_CREATED'")
            .query(Int::class.java).single()
        assertThat(registros).isEqualTo(1)
    }

    @Test
    fun `un codigo de producto repetido responde DUPLICATE_PRODUCT_CODE`() {
        val token = manager()
        val body = mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA", "unitMeasure" to "PZA")
        post("/v1/products", body, token)

        val response = post("/v1/products", body, token)

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("DUPLICATE_PRODUCT_CODE")
    }

    @Test
    fun `una unidad que no esta en el catalogo responde UNKNOWN_UNIT_MEASURE`() {
        val token = manager()

        val response = post(
            "/v1/products",
            mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA", "unitMeasure" to "KG"),
            token,
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("UNKNOWN_UNIT_MEASURE")
    }

    // ── K4: la recepción no se desactiva ────────────────────────────────────

    @Test
    fun `K4 - desactivar la ubicacion de recepcion responde RECEIVING_LOCATION_PROTECTED`() {
        val token = manager()
        val list = get("/v1/locations?kind=RECEIVING", token)
        val id = list.at<List<Map<String, Any?>>>("items").single()["id"]

        val response = send("DELETE", "/v1/locations/$id", null, token, emptyMap())

        assertThat(response.status).isEqualTo(409)
        assertThat(response.code).isEqualTo("RECEIVING_LOCATION_PROTECTED")
    }

    @Test
    fun `una ubicacion nueva se lista con su nombre visible compuesto por el servidor`() {
        val token = manager()

        val created = post(
            "/v1/locations",
            mapOf("warehouseId" to 1, "hall" to "04", "code" to "b", "position" to "c", "levels" to 3),
            token,
        )

        assertThat(created.status).isEqualTo(201)
        // El cliente manda minúsculas y el servidor las escribe como están en el estante.
        assertThat(created.at<String>("displayName")).isEqualTo("04-B-C")
        assertThat(created.at<String>("warehouseCode")).isEqualTo("PRINCIPAL")
        assertThat(created.at<String>("kind")).isEqualTo("SHELF")
    }

    @Test
    fun `mover una ubicacion de bodega responde WAREHOUSE_IMMUTABLE`() {
        val token = manager()
        val created = post(
            "/v1/locations",
            mapOf("warehouseId" to 1, "hall" to "04", "code" to "B", "position" to "C"),
            token,
        )
        val otra = post("/v1/warehouses", mapOf("code" to "NORTE", "name" to "Bodega norte"), token)

        val response = send(
            "PUT", "/v1/locations/${created.at<Int>("id")}",
            mapOf("warehouseId" to otra.at<Int>("id"), "hall" to "04", "code" to "B", "position" to "C"),
            token, emptyMap(),
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("WAREHOUSE_IMMUTABLE")
    }

    // ── K5: un producto inactivo no recibe movimientos ──────────────────────

    @Test
    fun `K5 - un producto inactivo deja de encontrarse`() {
        val token = manager()
        val created = post(
            "/v1/products",
            mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA", "unitMeasure" to "PZA"),
            token,
        )
        val id = created.at<Int>("id")

        val baja = send("DELETE", "/v1/products/$id?reason=descontinuado", null, token, emptyMap())
        assertThat(baja.status).isEqualTo(200)
        assertThat(baja.at<Boolean>("active")).isFalse()

        // El producto activo es el que se busca: el inactivo ya no está.
        assertThat(get("/v1/products/$id", token).code).isEqualTo("PRODUCT_NOT_FOUND")
        assertThat(get("/v1/products?active=true", token).at<List<Map<String, Any?>>>("items")).isEmpty()
        // Y desaparecer no es borrar: la fila sigue, y sin filtro se puede seguir viendo.
        assertThat(get("/v1/products?active=false", token).at<List<Map<String, Any?>>>("items")).hasSize(1)
        assertThat(productCount()).isEqualTo(1)
    }

    @Test
    fun `desactivar un producto exige motivo`() {
        val token = manager()
        val created = post(
            "/v1/products",
            mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA", "unitMeasure" to "PZA"),
            token,
        )

        val response = send("DELETE", "/v1/products/${created.at<Int>("id")}", null, token, emptyMap())

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("REASON_REQUIRED")
    }

    // ── K6: solo una bodega predeterminada ──────────────────────────────────

    @Test
    fun `K6 - marcar otra bodega como predeterminada deja a la anterior sin la marca`() {
        val token = manager()

        val created = post(
            "/v1/warehouses",
            mapOf("code" to "NORTE", "name" to "Bodega norte", "isDefault" to true),
            token,
        )

        assertThat(created.status).isEqualTo(201)
        assertThat(created.at<Boolean>("isDefault")).isTrue()

        val list = get("/v1/warehouses", token).at<List<Map<String, Any?>>>("items")
        assertThat(list.count { it["isDefault"] == true }).isEqualTo(1)
        assertThat(list.single { it["isDefault"] == true }["code"]).isEqualTo("NORTE")
        assertThat(db.sql("SELECT count(*) FROM warehouses WHERE is_default").query(Int::class.java).single())
            .isEqualTo(1)
    }

    // ── Permisos y autorización ─────────────────────────────────────────────

    @Test
    fun `I5 - una ruta protegida deniega por defecto a quien no tiene el permiso`() {
        createUser("auditor", USER_PASSWORD, BaseRole.AUDITOR.key.value)
        val token = accessTokenOf("auditor", USER_PASSWORD)

        val response = post(
            "/v1/products",
            mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA", "unitMeasure" to "PZA"),
            token,
        )

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("PERMISSION_DENIED")
        assertThat(productCount()).isZero()
    }

    @Test
    fun `I5 - sin token, el catalogo responde 401 y no 403`() {
        val response = get("/v1/products")

        assertThat(response.status).isEqualTo(401)
        assertThat(response.code).isEqualTo("UNAUTHENTICATED")
    }

    @Test
    fun `I8 - una cuenta con el cambio de contrasena pendiente no usa el catalogo`() {
        createPendingUser("nueva", USER_PASSWORD, jefa)
        val token = accessTokenOf("nueva", USER_PASSWORD)

        val response = get("/v1/products", token)

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("PASSWORD_CHANGE_REQUIRED")
    }

    @Test
    fun `I8 - la cuenta con el cambio pendiente si puede ver quien es`() {
        createPendingUser("nueva", USER_PASSWORD, jefa)
        val token = accessTokenOf("nueva", USER_PASSWORD)

        assertThat(get("/v1/auth/me", token).status).isEqualTo(200)
    }

    // ── Paginación y listados ───────────────────────────────────────────────

    @Test
    fun `un size mayor de 200 se rechaza en lugar de recortarse`() {
        val token = manager()

        val response = get("/v1/products?size=201", token)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PAGINATION")
    }

    @Test
    fun `un orden que no se admite responde INVALID_SORT`() {
        val token = manager()

        val response = get("/v1/products?sort=password,asc", token)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_SORT")
    }

    @Test
    fun `el sobre de paginacion trae el total contado y no el tamano de la pagina`() {
        val token = manager()
        repeat(3) { index ->
            post(
                "/v1/products",
                mapOf("productCode" to "COD00$index", "name" to "Producto $index", "unitMeasure" to "PZA"),
                token,
            )
        }

        val response = get("/v1/products?size=1&sort=name,asc", token)

        assertThat(response.at<Int>("totalItems")).isEqualTo(3)
        assertThat(response.at<Int>("totalPages")).isEqualTo(3)
        assertThat(response.at<List<Map<String, Any?>>>("items")).hasSize(1)
    }

    @Test
    fun `el listado de bodegas no se pagina y trae la predeterminada`() {
        val token = manager()

        val items = get("/v1/warehouses", token).at<List<Map<String, Any?>>>("items")

        assertThat(items).hasSize(1)
        assertThat(items.single()["isDefault"]).isEqualTo(true)
    }

    // ── M10: la carga inicial de existencias ────────────────────────────────

    private fun totalStock(productCode: String): String =
        db.sql(
            """
            SELECT COALESCE(ps.quantity, 0) FROM products p
            LEFT JOIN product_stock ps ON ps.product_id = p.id
            WHERE p.product_code = :code
            """.trimIndent(),
        ).param("code", productCode).query(java.math.BigDecimal::class.java).single().setScale(3).toPlainString()

    private fun kardexInitialRows(): List<String> =
        db.sql(
            """
            SELECT l.hall FROM kardex k JOIN locations l ON l.id = k.to_location_id
            WHERE k.move_type = 'INITIAL' ORDER BY k.id
            """.trimIndent(),
        ).query(String::class.java).list().filterNotNull()

    @Test
    fun `M10 - el stock del archivo se carga en SIN-UBICAR con un movimiento INITIAL`() {
        val token = manager()

        val response = postFile("/v1/products/import", validFile, token = token, query = "?dryRun=false")

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<Boolean>("stockPersisted")).isTrue()
        assertThat(response.at<Int>("stockLoaded")).isEqualTo(2)
        assertThat(response.at<Int>("stockSkipped")).isZero()
        // El total heredado queda en el saldo sin ubicar: el archivo no dice en qué
        // estantería está cada pieza (ADR-0012).
        assertThat(totalStock("ABRAA1B")).isEqualTo("10.000")
        assertThat(totalStock("ABRAA1C")).isEqualTo("5.000")
        // La columna de la ubicación sin ubicar se llama SINUBICAR; su nombre visible es SIN-UBICAR.
        assertThat(kardexInitialRows()).containsOnly("SINUBICAR")
    }

    @Test
    fun `M10 - reimportar no duplica el stock`() {
        val token = manager()
        postFile("/v1/products/import", validFile, token = token, query = "?dryRun=false")

        val segunda = postFile("/v1/products/import", validFile, token = token, query = "?dryRun=false")

        assertThat(segunda.at<Int>("created")).isZero()
        assertThat(segunda.at<Int>("skipped")).isEqualTo(2)
        // Lo que ya tiene existencias no se vuelve a cargar: importar dos veces el mismo
        // archivo no puede duplicar el inventario.
        assertThat(segunda.at<Int>("stockLoaded")).isZero()
        assertThat(segunda.at<Int>("stockSkipped")).isEqualTo(2)
        assertThat(totalStock("ABRAA1B")).isEqualTo("10.000")
        assertThat(kardexInitialRows()).hasSize(2)
    }

    @Test
    fun `M10 - la simulacion no carga existencias`() {
        val token = manager()

        val response = postFile("/v1/products/import", validFile, token = token)

        assertThat(response.at<Int>("stockLoaded")).isZero()
        assertThat(response.at<Boolean>("stockPersisted")).isFalse()
        // En simulación no se crea ni el producto, así que no hay existencias que mirar.
        assertThat(response.at<Int>("created")).isZero()
        assertThat(db.sql("SELECT count(*) FROM product_stock").query(Int::class.java).single()).isZero()
        assertThat(kardexInitialRows()).isEmpty()
    }
}
