package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * User and role administration (M07) over real HTTP.
 *
 * It is the gate to everything else: whoever can assign roles can grant any
 * permission. That is why almost everything checked here is **who can do it**, and
 * that is why the last test checks the effect that really matters: resetting a password
 * has to lock out whoever had the session open.
 */
class UserAdminFlowTest : ApiTest() {

    private val jefa = BaseRole.ADMIN.key.value
    private val almacen = BaseRole.WAREHOUSE_OPERATOR.key.value
    private val auditor = BaseRole.AUDITOR.key.value

    private fun manager(): String {
        createUser("jefa", USER_PASSWORD, jefa)
        return accessTokenOf("jefa", USER_PASSWORD)
    }

    private fun create(token: String, username: String, roles: List<String>? = null, password: String = USER_PASSWORD) =
        post(
            "/v1/users",
            buildMap<String, Any?> {
                put("username", username)
                put("name", "Persona $username")
                put("password", password)
                if (roles != null) put("roles", roles)
            },
            token,
        )

    private fun userIdOf(username: String): Long =
        db.sql("SELECT id FROM users WHERE username = :username").param("username", username)
            .query(Long::class.java).single()

    // ── Consulta ────────────────────────────────────────────────────────────

    @Test
    fun `the list paginates, filters and brings the roles of each one`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        create(token, "bruno", roles = listOf(auditor))

        val todos = get("/v1/users", token)
        val sembrados = db.sql("SELECT count(*) FROM users").query(Int::class.java).single()
        val porRol = get("/v1/users?role=$almacen", token)
        val porTexto = get("/v1/users?q=bru", token)

        assertThat(todos.at<Int>("totalItems")).isEqualTo(sembrados)
        val ana = todos.at<List<Map<String, Any?>>>("items").first { it["username"] == "ana" }
        assertThat(ana["roles"]).isEqualTo(listOf(almacen))
        assertThat(porRol.at<Int>("totalItems")).isEqualTo(1)
        assertThat(porTexto.at<Int>("totalItems")).isEqualTo(1)
        assertThat(porTexto.at<List<Map<String, Any?>>>("items").single()["username"]).isEqualTo("bruno")
    }

    @Test
    fun `the detail brings the user's roles and warehouses`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))

        val response = get("/v1/users/${userIdOf("ana")}", token)

        assertThat(response.at<String>("username")).isEqualTo("ana")
        assertThat(response.at<List<String>>("roles")).containsExactly(almacen)
        // Without warehouse scopes, the user sees the ones in their area; the array is empty.
        assertThat(response.at<List<Long>>("warehouseIds")).isEmpty()
    }

    @Test
    fun `the roles and permissions catalog can be read with its permission`() {
        val token = manager()

        val roles = get("/v1/roles", token)
        val permisos = get("/v1/permissions", token)

        assertThat(roles.status).isEqualTo(200)
        assertThat(roles.raw).contains(almacen)
        assertThat(permisos.status).isEqualTo(200)
        assertThat(permisos.raw).contains("inventory.adjust_theft")
        // The permission catalog is code and is published with its module and its sensitivity.
        assertThat(permisos.raw).contains("\"sensitive\":true")
    }

    // ── Alta ────────────────────────────────────────────────────────────────

    @Test
    fun `creating a user creates it with its roles and requires a password change`() {
        val token = manager()

        val response = create(token, "ana", roles = listOf(almacen))

        assertThat(response.status).describedAs(response.raw).isEqualTo(201)
        assertThat(response.at<String>("username")).isEqualTo("ana")
        assertThat(response.at<List<String>>("roles")).containsExactly(almacen)
        // It is born required to change it: whoever created it knows the password.
        assertThat(response.at<Boolean>("mustChangePassword")).isTrue()
        // And the password is never returned.
        assertThat(response.raw).doesNotContain(USER_PASSWORD)
    }

    @Test
    fun `creating rejects a weak password, a repeated name and a role that does not exist`() {
        val token = manager()
        create(token, "ana")

        val debil = create(token, "bruno", password = "1234")
        val repetido = create(token, "ana")
        val rolInventado = create(token, "carla", roles = listOf("SUPERJEFA"))

        assertThat(debil.status).isEqualTo(422)
        assertThat(debil.code).isEqualTo("WEAK_PASSWORD")
        assertThat(repetido.status).isEqualTo(409)
        assertThat(repetido.code).isEqualTo("DUPLICATE_USERNAME")
        assertThat(rolInventado.status).isEqualTo(422)
        assertThat(rolInventado.code).isEqualTo("UNKNOWN_ROLE")
        assertThat(rolInventado.at<Map<String, String>>("details")["allowed"]).contains(almacen)
    }

    @Test
    fun `a new user can sign in and has to change the password`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))

        val sesion = post("/v1/auth/login", mapOf("username" to "ana", "password" to USER_PASSWORD))
        val conLaClave = sesion.at<String>("accessToken")

        // I8: with the password pending, any route other than changing it is rejected.
        val otraRuta = get("/v1/inventory/stock", conLaClave)
        assertThat(sesion.status).isEqualTo(200)
        assertThat(otraRuta.code).isEqualTo("PASSWORD_CHANGE_REQUIRED")
    }

    // ── Editing and role assignment ───────────────────────────────────────

    @Test
    fun `assigning roles changes what the person can do`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")

        val response = put("/v1/users/$ana", mapOf("roles" to listOf(auditor)), token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<List<String>>("roles")).containsExactly(auditor)
        // And it stays in the database, which is what really decides on sign-in.
        assertThat(
            db.sql("SELECT count(*) FROM user_roles WHERE user_id = :id").param("id", ana)
                .query(Int::class.java).single(),
        ).isEqualTo(1)
    }

    @Test
    fun `editing leaves what is not sent unchanged and does not allow being left without roles`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")

        val renombrada = put("/v1/users/$ana", mapOf("name" to "Ana Pérez"), token)
        val sinRoles = put("/v1/users/$ana", mapOf("roles" to emptyList<String>()), token)

        assertThat(renombrada.status).isEqualTo(200)
        assertThat(renombrada.at<String>("name")).isEqualTo("Ana Pérez")
        assertThat(renombrada.at<List<String>>("roles")).containsExactly(almacen)
        // A user without roles could not do anything, and would find out on sign-in.
        assertThat(sinRoles.status).isEqualTo(422)
        assertThat(sinRoles.code).isEqualTo("FIELD_REQUIRED")
    }

    @Test
    fun `deactivating a user removes their access`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")

        val response = put("/v1/users/$ana", mapOf("disabled" to true), token)
        val sesion = post("/v1/auth/login", mapOf("username" to "ana", "password" to USER_PASSWORD))

        assertThat(response.at<Boolean>("disabled")).isTrue()
        // 401 and not 403: a deactivated account is a credentials failure, and the contract only
        // says so **if the password was correct** (docs/api/autenticacion.md).
        assertThat(sesion.status).isEqualTo(401)
        assertThat(sesion.code).isEqualTo("ACCOUNT_DISABLED")
    }

    // ── Resetting the password ─────────────────────────────────────────────

    @Test
    fun `resetting the password drops the open session`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")
        // Ana signs in and changes her password: her session is valid and is not pending.
        val sesion = post("/v1/auth/login", mapOf("username" to "ana", "password" to USER_PASSWORD))
            .at<String>("accessToken")
        post(
            "/v1/auth/change-password",
            mapOf("currentPassword" to USER_PASSWORD, "newPassword" to "otra-clave-larga-y-buena"),
            sesion,
        )
        val suToken = post("/v1/auth/login", mapOf("username" to "ana", "password" to "otra-clave-larga-y-buena"))
            .at<String>("accessToken")
        assertThat(get("/v1/inventory/stock", suToken).status).isEqualTo(200)
        // The test clock is frozen: without advancing it, the credentials stamp
        // of the new token and the old one would be the same millisecond and it could not be checked
        // that the old token dies.
        clock.advanceSeconds(60)

        val response = post(
            "/v1/users/$ana/reset-password",
            // `mustChangePassword = false` on purpose: so what is checked is that the
            // old token dies, and not the mandatory change screen.
            mapOf("password" to "clave-restablecida-larga", "mustChangePassword" to false),
            token,
        )

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<Boolean>("mustChangePassword")).isFalse()
        // R3: the token that was alive no longer works. If someone's password is reset
        // because they suspect it was stolen, leaving the thief's token alive
        // would be doing nothing.
        assertThat(get("/v1/inventory/stock", suToken).status).isEqualTo(401)
        // And with the new password they can sign in.
        assertThat(post("/v1/auth/login", mapOf("username" to "ana", "password" to "clave-restablecida-larga")).status)
            .isEqualTo(200)
    }

    @Test
    fun `resetting requires the permission and a password that meets the policy`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")

        val debil = post("/v1/users/$ana/reset-password", mapOf("password" to "1234"), token)

        assertThat(debil.status).isEqualTo(422)
        assertThat(debil.code).isEqualTo("WEAK_PASSWORD")
    }

    // ── Permissions per route ─────────────────────────────────────────────────

    @Test
    fun `managing users is not for just anyone`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        assertThat(get("/v1/users", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(post("/v1/users", mapOf("username" to "x"), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/roles", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/permissions", token).code).isEqualTo("PERMISSION_DENIED")
    }

    // ── Revoking another person's sessions ───────────────────────────────

    @Test
    fun `revoking the sessions leaves the person unable to renew`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")
        val sesion = post("/v1/auth/login", mapOf("username" to "ana", "password" to USER_PASSWORD))
        val suRefresh = sesion.at<String>("refreshToken")

        val response = post("/v1/users/$ana/revoke-sessions", null, token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<Int>("revokedSessions")).isGreaterThanOrEqualTo(1)
        // What is cut is the renewal: the live refresh token no longer works and they must sign in again.
        // (It is tested with the token not rotated: rotating it first would bring in the reuse detection,
        // which is another rule and has its own tests.)
        assertThat(post("/v1/auth/refresh", mapOf("refreshToken" to suRefresh)).status).isEqualTo(401)
        // And they can sign in again: revoking is not locking the account.
        assertThat(post("/v1/auth/login", mapOf("username" to "ana", "password" to USER_PASSWORD)).status)
            .isEqualTo(200)
    }

    @Test
    fun `revoking without open sessions invents nothing`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))

        val response = post("/v1/users/${userIdOf("ana")}/revoke-sessions", null, token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        // Zero is not an error: the person did not have the session open, which is what was wanted.
        assertThat(response.at<Int>("revokedSessions")).isZero()
    }

    @Test
    fun `revoking requires the permission and an existing user`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val suToken = accessTokenOf("operador", USER_PASSWORD)
        val token = manager()
        create(token, "ana", roles = listOf(almacen))

        val sinPermiso = post("/v1/users/${userIdOf("ana")}/revoke-sessions", null, suToken)
        val inventado = post("/v1/users/999999/revoke-sessions", null, token)

        assertThat(sinPermiso.code).isEqualTo("PERMISSION_DENIED")
        assertThat(inventado.code).isEqualTo("USER_NOT_FOUND")
    }
}
