package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Crear y editar roles con su matriz (M07) por HTTP real.
 *
 * La prueba que de verdad importa es la primera: un rol nuevo **concede lo que su matriz
 * dice** y nada más. Una matriz que se guarda bien y no surte efecto sería una pantalla de
 * administración que miente, y el usuario lo descubriría intentando trabajar.
 */
class RoleAdminFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun role(
        token: String,
        key: String,
        permissions: List<Map<String, String>>? = null,
        name: String = "Rol $key",
    ) = post(
        "/v1/roles",
        buildMap<String, Any?> {
            put("key", key)
            put("name", name)
            if (permissions != null) put("permissions", permissions)
        },
        token,
    )

    private fun grant(permission: String, scope: String = "ALL") =
        mapOf("permissionKey" to permission, "scope" to scope)

    private fun tokenOf(username: String): String = accessTokenOf(username, USER_PASSWORD)

    // ── La matriz surte efecto ──────────────────────────────────────────────

    @Test
    fun `a new role grants what its matrix says and nothing else`() {
        val token = manager()
        role(token, "CONSULTA", permissions = listOf(grant("inventory.view"), grant("kardex.view")))
        createUser("ana", USER_PASSWORD, "CONSULTA")

        val suyo = tokenOf("ana")
        val stock = get("/v1/inventory/stock", suyo)
        val kardex = get("/v1/inventory/kardex", suyo)
        val ajuste = post(
            "/v1/inventory/adjustments",
            mapOf(
                "productCode" to "X", "locationCode" to "RECEIVING", "quantity" to 1,
                "direction" to "DECREASE", "reason" to "COUNT_ERROR", "origin" to "MANUAL_COUNT",
            ),
            suyo,
        )

        assertThat(stock.status).describedAs(stock.raw).isEqualTo(200)
        assertThat(kardex.status).isEqualTo(200)
        // No se concedió ajustar: la matriz manda, no el «ya está dentro».
        assertThat(ajuste.status).isEqualTo(403)
        assertThat(ajuste.code).isEqualTo("PERMISSION_DENIED")
    }

    @Test
    fun `removing a cell really removes the access`() {
        val token = manager()
        role(token, "CONSULTA", permissions = listOf(grant("inventory.view")))
        createUser("ana", USER_PASSWORD, "CONSULTA")
        assertThat(get("/v1/inventory/stock", tokenOf("ana")).status).isEqualTo(200)

        val response = put("/v1/roles/CONSULTA/permissions", mapOf("permissions" to emptyList<Any>()), token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<List<Any>>("permissions")).isEmpty()
        assertThat(get("/v1/inventory/stock", tokenOf("ana")).code).isEqualTo("PERMISSION_DENIED")
    }

    @Test
    fun `deactivating a role removes its permissions from whoever holds it`() {
        val token = manager()
        role(token, "CONSULTA", permissions = listOf(grant("inventory.view")))
        createUser("ana", USER_PASSWORD, "CONSULTA")

        val response = put("/v1/roles/CONSULTA", mapOf("active" to false), token)

        assertThat(response.at<Boolean>("active")).isFalse()
        assertThat(get("/v1/inventory/stock", tokenOf("ana")).code).isEqualTo("PERMISSION_DENIED")
    }

    @Test
    fun `the detail brings the matrix and counts how many people hold it`() {
        val token = manager()
        role(token, "CONSULTA", permissions = listOf(grant("inventory.view"), grant("kardex.view")))
        createUser("ana", USER_PASSWORD, "CONSULTA")

        val response = get("/v1/roles/CONSULTA", token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<Int>("users")).isEqualTo(1)
        val permisos = response.at<List<Map<String, Any?>>>("permissions")
        assertThat(permisos.map { it["permissionKey"] }).containsExactlyInAnyOrder("inventory.view", "kardex.view")
        assertThat(permisos.map { it["scope"] }).containsOnly("ALL")
    }

    // ── Validación de la matriz ─────────────────────────────────────────────

    @Test
    fun `a matrix cannot invent permissions or scopes`() {
        val token = manager()

        val inventado = role(token, "RARA", permissions = listOf(grant("inventory.volar")))
        val malAlcance = role(token, "RARA2", permissions = listOf(grant("user.create", "OWN")))
        val repetido = role(token, "RARA3", permissions = listOf(grant("inventory.view"), grant("inventory.view", "AREA")))
        val ninguno = role(token, "RARA4", permissions = listOf(grant("inventory.view", "NONE")))

        assertThat(inventado.status).isEqualTo(422)
        assertThat(inventado.code).isEqualTo("UNKNOWN_PERMISSION")
        // `user.create` solo admite ALL: conceder OWN sería una celda que el motor no sabe evaluar.
        assertThat(malAlcance.code).isEqualTo("SCOPE_NOT_ALLOWED")
        assertThat(malAlcance.at<Map<String, String>>("details")["allowed"]).isEqualTo("ALL")
        assertThat(repetido.status).isEqualTo(400)
        assertThat(repetido.code).isEqualTo("INVALID_PARAMETER")
        assertThat(ninguno.status).isEqualTo(400)
    }

    @Test
    fun `the role key has a shape and is not repeated`() {
        val token = manager()
        role(token, "CONSULTA")

        val minusculas = role(token, "consulta2")
        val conEspacios = role(token, "CON SULT A")
        val repetida = role(token, "CONSULTA")

        // La clave viaja en el token y en la bitácora: se normaliza a mayúsculas y no admite espacios.
        assertThat(minusculas.status).isEqualTo(201)
        assertThat(minusculas.at<String>("key")).isEqualTo("CONSULTA2")
        assertThat(conEspacios.status).isEqualTo(400)
        assertThat(repetida.status).isEqualTo(409)
        assertThat(repetida.code).isEqualTo("DUPLICATE_ROLE")
    }

    @Test
    fun `managing roles is not for just anyone`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = tokenOf("operador")

        assertThat(post("/v1/roles", mapOf("key" to "X", "name" to "X"), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(put("/v1/roles/ADMIN", mapOf("name" to "Otro"), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/roles/ADMIN", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(put("/v1/roles/ADMIN/permissions", mapOf("permissions" to emptyList<Any>()), token).code)
            .isEqualTo("PERMISSION_DENIED")
    }

    @Test
    fun `a role that does not exist answers ROLE_NOT_FOUND`() {
        val token = manager()

        assertThat(get("/v1/roles/NO_EXISTE", token).code).isEqualTo("ROLE_NOT_FOUND")
        assertThat(put("/v1/roles/NO_EXISTE", mapOf("name" to "X"), token).code).isEqualTo("ROLE_NOT_FOUND")
    }
}
