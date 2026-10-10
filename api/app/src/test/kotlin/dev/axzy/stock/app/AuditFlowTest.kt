package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * The audit log query (M08) over real HTTP: **U5** of docs/flujos/auditoria.md §5.
 *
 * The audit log is written on every write since phase 3 —U1 to U4 are tested in their
 * place—, so what is checked here is that it can be **read**: filtering by entity
 * and date range, paginating with the total computed with the same filter, and that the
 * consulta quede registrada.
 */
class AuditFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun product(token: String, code: String) =
        post("/v1/products", mapOf("productCode" to code, "name" to "PRODUCTO $code", "unitMeasure" to "PZA"), token)

    /**
     * The business date **in UTC**, which is how the audit log writes it.
     *
     * It is not a detail: at 22:00 in Mexico City it is already the next day in UTC, and
     * a test that uses the local date fails every night and passes every morning.
     */
    private fun today(): String = LocalDate.now(java.time.ZoneOffset.UTC).toString()

    private fun log(token: String, query: String = ""): dev.axzy.stock.app.support.ApiResponse =
        get("/v1/audit/log?from=${businessToday().minusDays(1)}&to=${businessToday().plusDays(1)}$query", token)

    // ── U5: the query filters and paginates ─────────────────────────────────

    @Test
    fun `U5 - the query filters by entity and the total uses the same filter`() {
        val token = manager()
        product(token, "AAA001")
        product(token, "AAA002")

        val productos = log(token, "&entityType=product")
        val inventario = log(token, "&entityType=stock_by_location")

        assertThat(productos.at<Int>("totalItems")).isEqualTo(2)
        assertThat(productos.at<List<Map<String, Any?>>>("items")).hasSize(2)
        assertThat(productos.at<List<Map<String, Any?>>>("items").map { it["action"] })
            .containsOnly("PRODUCT_CREATED")
        assertThat(inventario.at<Int>("totalItems")).isZero()
    }

    @Test
    fun `U5 - the page is the one requested and the total is not the page's`() {
        val token = manager()
        (1..3).forEach { product(token, "AAA00$it") }

        val primera = log(token, "&entityType=product&size=1&page=0")
        val segunda = log(token, "&entityType=product&size=1&page=1")

        assertThat(primera.at<List<Map<String, Any?>>>("items")).hasSize(1)
        // The total is computed with the same filter, not with what fits on the page.
        assertThat(primera.at<Int>("totalItems")).isEqualTo(3)
        assertThat(primera.at<Int>("totalPages")).isEqualTo(3)
        val primerId = (primera.at<List<Map<String, Any?>>>("items").single()["id"] as Number).toLong()
        val segundoId = (segunda.at<List<Map<String, Any?>>>("items").single()["id"] as Number).toLong()
        assertThat(segundoId).isNotEqualTo(primerId)
    }

    @Test
    fun `the query filters by action, by actor and by level`() {
        val token = manager()
        val quien = db.sql("SELECT id FROM users WHERE username = 'jefa'").query(Long::class.java).single()
        product(token, "AAA001")

        val porAccion = log(token, "&action=PASSWORD_CHANGED")
        val deUsuario = log(token, "&entityType=product&actorUserId=$quien")
        val relevantes = log(token, "&entityType=product&level=RELEVANT")
        val criticos = log(token, "&entityType=product&level=CRITICAL")

        assertThat(deUsuario.at<Int>("totalItems")).isEqualTo(1)
        assertThat(relevantes.at<Int>("totalItems")).isEqualTo(1)
        assertThat(criticos.at<Int>("totalItems")).isZero()
        // The password change action was written by the use case, not by this test.
        assertThat(porAccion.at<Int>("totalItems")).isZero()
    }

    @Test
    fun `a record holds who, what, when and why`() {
        val token = manager()
        product(token, "AAA001")

        val entrada = log(token, "&entityType=product").at<List<Map<String, Any?>>>("items").single()

        assertThat(entrada["actorUsername"]).isEqualTo("jefa")
        assertThat(entrada["actorName"]).isEqualTo("Usuario jefa")
        assertThat(entrada["channel"]).isEqualTo("WEB")
        assertThat(entrada["level"]).isEqualTo("RELEVANT")
        assertThat(entrada["result"]).isEqualTo("SUCCESS")
        // The new state is a document, and it goes as JSON text.
        assertThat(entrada["newState"] as String).contains("AAA001")
        assertThat(entrada["businessDate"]).isEqualTo(today())
    }

    @Test
    fun `a critical record carries its permission and its trace`() {
        val token = manager()
        val quien = db.sql("SELECT id FROM users WHERE username = 'jefa'").query(Long::class.java).single()
        createUser("supervisor", USER_PASSWORD, jefa)
        val aprobador = db.sql("SELECT id FROM users WHERE username = 'supervisor'").query(Long::class.java).single()
        product(token, "AAA001")
        post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "AAA001", "locationCode" to "RECEIVING", "quantity" to 100,
                "direction" to "INCREASE", "reason" to "COUNT_ERROR", "origin" to "MANUAL_COUNT",
                "approverId" to aprobador,
            ),
            token,
        )

        val entradas = log(token, "&entityType=product&level=CRITICAL").at<List<Map<String, Any?>>>("items")

        val ajuste = entradas.first { it["action"] == "INVENTORY_ADJUSTED" }
        // CRITICAL requires permission and a decision trace: the database enforces it, and it is visible here.
        // An increase is authorized with the permission to increase, not with the reason's: the
        // permission depends on the direction and on the reason (M02).
        assertThat(ajuste["permissionKey"]).isEqualTo("inventory.adjust_increase")
        assertThat(ajuste["decision"]).isNotNull()
        assertThat((ajuste["actorUserId"] as Number).toLong()).isEqualTo(quien)
    }

    // ── The query itself is recorded ─────────────────────────────────────────

    @Test
    fun `querying the audit log leaves its own access record`() {
        val token = manager()
        product(token, "AAA001")

        log(token, "&entityType=product")

        val consultas = log(token, "&action=AUDIT_QUERIED")
        assertThat(consultas.at<Int>("totalItems")).isPositive()
        val entrada = consultas.at<List<Map<String, Any?>>>("items").single()
        assertThat(entrada["level"]).isEqualTo("ACCESS")
        assertThat(entrada["entityType"]).isEqualTo("audit_log")
        // And it is known what was queried: the filters go in the new state.
        assertThat(entrada["newState"] as String).contains("entityType=product")
    }

    // ── Errors and permissions ────────────────────────────────────────────

    @Test
    fun `an inverted range and a badly formed date are rejected`() {
        val token = manager()

        val invertido = get("/v1/audit/log?from=2026-10-31&to=2026-10-01", token)
        val malFecha = get("/v1/audit/log?from=31-10-2026", token)

        assertThat(invertido.status).isEqualTo(400)
        assertThat(invertido.code).isEqualTo("INVALID_FILTER_RANGE")
        assertThat(malFecha.status).isEqualTo(400)
        assertThat(malFecha.code).isEqualTo("INVALID_FILTER_DATE")
    }

    @Test
    fun `without the audit permission the audit log is not read`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        val response = get("/v1/audit/log", token)

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("PERMISSION_DENIED")
    }

    @Test
    fun `the audit log cannot be modified`() {
        val token = manager()
        product(token, "AAA001")
        val antes = db.sql("SELECT count(*) FROM audit_log").query(Int::class.java).single()

        // There is no write route…
        assertThat(put("/v1/audit/log", emptyMap<String, Any>(), token).status).isIn(403, 404, 405)

        // …and the table prevents it with a trigger, which is what makes the audit log serve
        // something: **an audit log that can be edited is not an audit log** (U2).
        assertThatThrownBy { db.sql("UPDATE audit_log SET note = 'cambiado'").update() }
            .hasMessageContaining("append-only")
        assertThatThrownBy { db.sql("DELETE FROM audit_log").update() }
            .hasMessageContaining("append-only")
        assertThat(db.sql("SELECT count(*) FROM audit_log").query(Int::class.java).single()).isEqualTo(antes)
    }
}
