package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiResponse
import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Existencias (M02) de punta a punta por HTTP real.
 *
 * Los nombres A1…A10 son los de docs/flujos/existencias-y-ajustes.md §7. La
 * concurrencia (A3, A4) y la inmutabilidad del kardex (A9) están en
 * `InventoryEngineConcurrencyTest`, contra la base y con dos transacciones de
 * verdad; repetirlas aquí por HTTP no añadiría nada.
 */
class InventoryFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    /** Un segundo usuario: todo aumento necesita que **otra persona** lo apruebe. */
    private fun approver(): Long = createUser("supervisor", USER_PASSWORD, jefa)

    private fun adjust(token: String, body: Map<String, Any?>, key: UUID? = null) =
        post(
            "/v1/inventory/adjustments",
            body,
            token,
            if (key == null) emptyMap() else mapOf("Idempotency-Key" to key.toString()),
        )

    /** Deja existencias en una ubicación: es el único camino, y pasa por el motor. */
    private fun stockIn(token: String, location: String, quantity: Int, who: Long): ApiResponse =
        adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to location,
                "quantity" to quantity,
                "direction" to "INCREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
                "approverId" to who,
            ),
        )

    private fun createProduct(token: String, code: String = "ABRAA1B") =
        post("/v1/products", mapOf("productCode" to code, "name" to "ABRAZADERA", "unitMeasure" to "PZA"), token)

    private fun createShelf(token: String, hall: String) =
        post(
            "/v1/locations",
            mapOf("warehouseId" to 1, "hall" to hall, "code" to "B", "position" to "C"),
            token,
        )

    private fun quantityAt(token: String, location: String): String? {
        val items = get("/v1/inventory/stock?onlyWithStock=false&locationCode=$location", token)
            .at<List<Map<String, Any?>>>("items")
        return items.firstOrNull()?.get("quantity") as String?
    }

    // ── A1: ubicar no cambia el total ───────────────────────────────────────

    @Test
    fun `A1 - ubicar mueve sin cambiar el total y deja un movimiento LOCATE`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        createShelf(token, "04")
        assertThat(stockIn(token, "RECEPCION", 1000, who).status).isEqualTo(201)

        val located = post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "ABRAA1B",
                "fromLocationCode" to "RECEPCION",
                "toLocationCode" to "04-B-C",
                "quantity" to 300,
            ),
            token,
        )

        assertThat(located.status).isEqualTo(201)
        assertThat(located.at<String>("status")).isEqualTo("APPLIED")
        // El total no viaja en la respuesta de un traslado: devolverlo sería invitar
        // a creer que cambió.
        assertThat(located.at<String?>("total")).isNull()

        assertThat(quantityAt(token, "04-B-C")).isEqualTo("300.000")
        assertThat(quantityAt(token, "RECEPCION")).isEqualTo("700.000")

        val kardex = get("/v1/inventory/kardex?moveType=LOCATE", token)
        assertThat(kardex.at<Int>("totalItems")).isEqualTo(1)
        assertThat(kardex.at<List<Map<String, Any?>>>("items").single()["toLocationDisplayName"]).isEqualTo("04-B-C")
    }

    // ── A2: no se saca más de lo que hay ────────────────────────────────────

    @Test
    fun `A2 - una merma mayor que el saldo responde INSUFFICIENT_STOCK con lo que hay`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        createShelf(token, "04")
        stockIn(token, "04-B-C", 100, who)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "04-B-C",
                "quantity" to 150,
                "direction" to "DECREASE",
                "reason" to "SHORTAGE",
                "origin" to "MANUAL_COUNT",
            ),
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("INSUFFICIENT_STOCK")
        // El detalle dice CUÁNTO hay: un «stock insuficiente» sin números obliga a
        // mirar la base para saber qué pasó.
        assertThat(response.at<Map<String, String>>("details")["available"]).isEqualTo("100")
        assertThat(quantityAt(token, "04-B-C")).isEqualTo("100.000")
    }

    // ── A5: una merma exige su motivo ───────────────────────────────────────

    @Test
    fun `A5 - un ajuste sin motivo responde REASON_REQUIRED y no escribe nada`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEPCION", 100, who)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEPCION",
                "quantity" to 10,
                "direction" to "DECREASE",
                "origin" to "MANUAL_COUNT",
            ),
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("REASON_REQUIRED")
        assertThat(quantityAt(token, "RECEPCION")).isEqualTo("100.000")
    }

    @Test
    fun `un motivo que no existe responde INVALID_PARAMETER con la lista admitida`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEPCION", 100, who)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEPCION",
                "quantity" to 10,
                "direction" to "DECREASE",
                "reason" to "PORQUE_SI",
                "origin" to "MANUAL_COUNT",
            ),
        )

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PARAMETER")
        assertThat(response.at<Map<String, String>>("details")["allowed"]).contains("THEFT")
    }

    // ── A6: el permiso depende del motivo ───────────────────────────────────

    @Test
    fun `A6 - el permiso de un ajuste depende de su motivo`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEPCION", 100, who)

        // Un operador que puede corregir un conteo pero no declarar un robo.
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        db.sql(
            """
            INSERT INTO role_permissions (role_id, permission_id, scope)
            SELECT r.id, p.id, 'ALL' FROM roles r, permissions p
            WHERE r.key = :role AND p.key IN ('inventory.view', 'inventory.adjust_count_error')
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("role", BaseRole.WAREHOUSE_OPERATOR.key.value).update()
        val operator = accessTokenOf("operador", USER_PASSWORD)

        val theft = adjust(
            operator,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEPCION",
                "quantity" to 1,
                "direction" to "DECREASE",
                "reason" to "THEFT",
                "origin" to "MANUAL_COUNT",
                "note" to "Faltó una caja del estante",
            ),
        )
        val countError = adjust(
            operator,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEPCION",
                "quantity" to 1,
                "direction" to "DECREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
            ),
        )

        assertThat(theft.status).isEqualTo(403)
        assertThat(theft.code).isEqualTo("PERMISSION_DENIED")
        assertThat(countError.status).isEqualTo(201)
    }

    // ── A7: origen y destino distintos ──────────────────────────────────────

    @Test
    fun `A7 - ubicar al mismo estante responde SAME_SOURCE_AND_DESTINATION`() {
        val token = manager()
        createProduct(token)

        val response = post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "ABRAA1B",
                "fromLocationCode" to "RECEPCION",
                "toLocationCode" to "RECEPCION",
                "quantity" to 10,
            ),
            token,
        )

        assertThat(response.status).isEqualTo(422)
        assertThat(response.code).isEqualTo("SAME_SOURCE_AND_DESTINATION")
    }

    @Test
    fun `ubicar desde un estante se rechaza - para eso esta mover`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        createShelf(token, "04")
        stockIn(token, "04-B-C", 50, who)
        createShelf(token, "05")

        val response = post(
            "/v1/inventory/locate",
            mapOf(
                "productCode" to "ABRAA1B",
                "fromLocationCode" to "04-B-C",
                "toLocationCode" to "05-B-C",
                "quantity" to 10,
            ),
            token,
        )

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_PARAMETER")
    }

    // ── A8: idempotencia ────────────────────────────────────────────────────

    @Test
    fun `A8 - reenviar con la misma clave no duplica el movimiento`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEPCION", 100, who)
        val key = UUID.randomUUID()

        val body = mapOf(
            "productCode" to "ABRAA1B",
            "locationCode" to "RECEPCION",
            "quantity" to 10,
            "direction" to "DECREASE",
            "reason" to "COUNT_ERROR",
            "origin" to "MANUAL_COUNT",
        )
        val first = adjust(token, body, key)
        val second = adjust(token, body, key)

        assertThat(first.status).isEqualTo(201)
        assertThat(second.status).isEqualTo(201)
        // La misma respuesta: el mismo movimiento.
        assertThat(second.at<List<Int>>("kardexIds")).isEqualTo(first.at<List<Int>>("kardexIds"))
        assertThat(quantityAt(token, "RECEPCION")).isEqualTo("90.000")
        assertThat(db.sql("SELECT count(*) FROM kardex").query(Int::class.java).single()).isEqualTo(2)
    }

    @Test
    fun `una clave de idempotencia que no es UUID responde INVALID_IDEMPOTENCY_KEY`() {
        val token = manager()
        createProduct(token)

        val response = post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEPCION",
                "quantity" to 1,
                "direction" to "DECREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
            ),
            token,
            mapOf("Idempotency-Key" to "no-es-un-uuid"),
        )

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_IDEMPOTENCY_KEY")
    }

    // ── A10: el conteo usa el mismo filtro que el listado ───────────────────

    @Test
    fun `A10 - un rango sin movimientos da items vacio y totalItems cero`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "RECEPCION", 10, who)

        val response = get("/v1/inventory/kardex?from=2020-01-01&to=2020-01-31", token)

        assertThat(response.at<List<Any>>("items")).isEmpty()
        assertThat(response.at<Int>("totalItems")).isZero()
    }

    @Test
    fun `un rango invertido se rechaza en lugar de devolver nada`() {
        val token = manager()

        val response = get("/v1/inventory/kardex?from=2026-10-31&to=2026-10-01", token)

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("INVALID_FILTER_RANGE")
    }

    // ── Aprobación y bitácora ───────────────────────────────────────────────

    @Test
    fun `un aumento sin aprobador queda pendiente y no toca el inventario`() {
        val token = manager()
        createProduct(token)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEPCION",
                "quantity" to 500,
                "direction" to "INCREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
            ),
        )

        // 202: aceptado, pendiente. No es un error y no es un movimiento.
        assertThat(response.status).isEqualTo(202)
        assertThat(response.at<String>("status")).isEqualTo("PENDING_APPROVAL")
        assertThat(quantityAt(token, "RECEPCION")).isNull()
        assertThat(db.sql("SELECT count(*) FROM kardex").query(Int::class.java).single()).isZero()
    }

    @Test
    fun `quien solicita un aumento no puede aprobarlo el mismo`() {
        val token = manager()
        val me = db.sql("SELECT id FROM users WHERE username = 'jefa'").query(Long::class.java).single()
        createProduct(token)

        val response = adjust(
            token,
            mapOf(
                "productCode" to "ABRAA1B",
                "locationCode" to "RECEPCION",
                "quantity" to 500,
                "direction" to "INCREASE",
                "reason" to "COUNT_ERROR",
                "origin" to "MANUAL_COUNT",
                "approverId" to me,
            ),
        )

        // 403 y no 422: `SEGREGATION_OF_DUTIES` pertenece a la familia de
        // autorización (docs/api/errores.md), y el cliente hace lo mismo que ante
        // cualquier denegación: que lo apruebe otra persona.
        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("SEGREGATION_OF_DUTIES")
    }

    @Test
    fun `cada movimiento escribe en la bitacora dentro de la misma transaccion`() {
        val token = manager()
        val who = approver()
        createProduct(token)

        stockIn(token, "RECEPCION", 100, who)

        val registros = db.sql("SELECT count(*) FROM audit_log WHERE action = 'INVENTORY_ADJUSTED'")
            .query(Int::class.java).single()
        assertThat(registros).isEqualTo(1)

        // Y el movimiento del que la bitácora habla existe: si la transacción hubiera
        // revertido, habría bitácora de algo que no pasó.
        assertThat(db.sql("SELECT count(*) FROM kardex").query(Int::class.java).single()).isEqualTo(1)
    }

    // ── El listado de existencias ───────────────────────────────────────────

    @Test
    fun `el listado de existencias filtra por producto y por ubicacion`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        createShelf(token, "04")
        stockIn(token, "RECEPCION", 100, who)
        stockIn(token, "04-B-C", 40, who)

        val all = get("/v1/inventory/stock?onlyWithStock=false", token)
        val byLocation = get("/v1/inventory/stock?locationCode=04-B-C", token)

        assertThat(all.at<Int>("totalItems")).isEqualTo(2)
        assertThat(byLocation.at<Int>("totalItems")).isEqualTo(1)
        assertThat(byLocation.at<List<Map<String, Any?>>>("items").single()["locationDisplayName"]).isEqualTo("04-B-C")
        // La cantidad viaja como cadena con tres decimales, que es la regla general.
        assertThat(byLocation.at<List<Map<String, Any?>>>("items").single()["quantity"]).isEqualTo("40.000")
    }

    @Test
    fun `el saldo sin ubicar se encuentra por su nombre visible`() {
        val token = manager()
        val who = approver()
        createProduct(token)
        stockIn(token, "SIN-UBICAR", 250, who)

        val response = get("/v1/inventory/stock?locationCode=SIN-UBICAR", token)

        assertThat(response.at<List<Map<String, Any?>>>("items").single()["locationDisplayName"]).isEqualTo("SIN-UBICAR")
        assertThat(response.at<List<Map<String, Any?>>>("items").single()["quantity"]).isEqualTo("250.000")
    }
}
