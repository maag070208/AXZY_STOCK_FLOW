package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * La consulta de la bitácora (M08) por HTTP real: **U5** de docs/flujos/auditoria.md §5.
 *
 * La bitácora se escribe en cada escritura desde la fase 3 —U1 a U4 están probadas en su
 * sitio—, así que lo que se comprueba aquí es que se puede **leer**: filtrar por entidad
 * y rango de fechas, paginar con el total calculado con el mismo filtro, y que la propia
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
     * La fecha de negocio **en UTC**, que es como la escribe la bitácora.
     *
     * No es un detalle: a las 22:00 de Ciudad de México ya es el día siguiente en UTC, y
     * una prueba que use la fecha local falla todas las noches y pasa todas las mañanas.
     */
    private fun today(): String = LocalDate.now(java.time.ZoneOffset.UTC).toString()

    private fun log(token: String, query: String = ""): dev.axzy.stock.app.support.ApiResponse =
        get("/v1/audit/log?from=${businessToday().minusDays(1)}&to=${businessToday().plusDays(1)}$query", token)

    // ── U5: la consulta filtra y pagina ─────────────────────────────────────

    @Test
    fun `U5 - la consulta filtra por entidad y el total usa el mismo filtro`() {
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
    fun `U5 - la pagina es la pedida y el total no es el de la pagina`() {
        val token = manager()
        (1..3).forEach { product(token, "AAA00$it") }

        val primera = log(token, "&entityType=product&size=1&page=0")
        val segunda = log(token, "&entityType=product&size=1&page=1")

        assertThat(primera.at<List<Map<String, Any?>>>("items")).hasSize(1)
        // El total se calcula con el mismo filtro, no con lo que cabe en la página.
        assertThat(primera.at<Int>("totalItems")).isEqualTo(3)
        assertThat(primera.at<Int>("totalPages")).isEqualTo(3)
        val primerId = (primera.at<List<Map<String, Any?>>>("items").single()["id"] as Number).toLong()
        val segundoId = (segunda.at<List<Map<String, Any?>>>("items").single()["id"] as Number).toLong()
        assertThat(segundoId).isNotEqualTo(primerId)
    }

    @Test
    fun `la consulta filtra por accion, por actor y por nivel`() {
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
        // La acción de cambiar la contraseña la escribió el caso de uso, no esta prueba.
        assertThat(porAccion.at<Int>("totalItems")).isZero()
    }

    @Test
    fun `un registro lleva quien, que, cuando y por que`() {
        val token = manager()
        product(token, "AAA001")

        val entrada = log(token, "&entityType=product").at<List<Map<String, Any?>>>("items").single()

        assertThat(entrada["actorUsername"]).isEqualTo("jefa")
        assertThat(entrada["actorName"]).isEqualTo("Usuario jefa")
        assertThat(entrada["channel"]).isEqualTo("WEB")
        assertThat(entrada["level"]).isEqualTo("RELEVANT")
        assertThat(entrada["result"]).isEqualTo("SUCCESS")
        // El estado nuevo es un documento, y va como JSON en texto.
        assertThat(entrada["newState"] as String).contains("AAA001")
        assertThat(entrada["businessDate"]).isEqualTo(today())
    }

    @Test
    fun `un registro critico lleva su permiso y su traza`() {
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
        // CRITICAL exige permiso y traza de decisión: la base lo impone, y aquí se ve.
        // Un aumento se autoriza con el permiso de aumentar, no con el del motivo: el
        // permiso depende de la dirección y del motivo (M02).
        assertThat(ajuste["permissionKey"]).isEqualTo("inventory.adjust_increase")
        assertThat(ajuste["decision"]).isNotNull()
        assertThat((ajuste["actorUserId"] as Number).toLong()).isEqualTo(quien)
    }

    // ── La propia consulta queda registrada ─────────────────────────────────

    @Test
    fun `consultar la bitacora deja su propio registro de acceso`() {
        val token = manager()
        product(token, "AAA001")

        log(token, "&entityType=product")

        val consultas = log(token, "&action=AUDIT_QUERIED")
        assertThat(consultas.at<Int>("totalItems")).isPositive()
        val entrada = consultas.at<List<Map<String, Any?>>>("items").single()
        assertThat(entrada["level"]).isEqualTo("ACCESS")
        assertThat(entrada["entityType"]).isEqualTo("audit_log")
        // Y se sabe qué se consultó: los filtros van en el estado nuevo.
        assertThat(entrada["newState"] as String).contains("entityType=product")
    }

    // ── Errores y permisos ──────────────────────────────────────────────────

    @Test
    fun `un rango invertido y una fecha mal formada se rechazan`() {
        val token = manager()

        val invertido = get("/v1/audit/log?from=2026-10-31&to=2026-10-01", token)
        val malFecha = get("/v1/audit/log?from=31-10-2026", token)

        assertThat(invertido.status).isEqualTo(400)
        assertThat(invertido.code).isEqualTo("INVALID_FILTER_RANGE")
        assertThat(malFecha.status).isEqualTo(400)
        assertThat(malFecha.code).isEqualTo("INVALID_FILTER_DATE")
    }

    @Test
    fun `sin el permiso de auditoria la bitacora no se lee`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        val response = get("/v1/audit/log", token)

        assertThat(response.status).isEqualTo(403)
        assertThat(response.code).isEqualTo("PERMISSION_DENIED")
    }

    @Test
    fun `la bitacora no se puede modificar`() {
        val token = manager()
        product(token, "AAA001")
        val antes = db.sql("SELECT count(*) FROM audit_log").query(Int::class.java).single()

        // No hay ruta de escritura…
        assertThat(put("/v1/audit/log", emptyMap<String, Any>(), token).status).isIn(403, 404, 405)

        // …y la tabla lo impide por disparador, que es lo que hace que la bitácora sirva
        // para algo: **una auditoría que se puede editar no es auditoría** (U2).
        assertThatThrownBy { db.sql("UPDATE audit_log SET note = 'cambiado'").update() }
            .hasMessageContaining("append-only")
        assertThatThrownBy { db.sql("DELETE FROM audit_log").update() }
            .hasMessageContaining("append-only")
        assertThat(db.sql("SELECT count(*) FROM audit_log").query(Int::class.java).single()).isEqualTo(antes)
    }
}
