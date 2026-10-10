package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import dev.axzy.stock.domain.access.PermissionCatalog
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Los flujos de docs/flujos/autenticacion-y-permisos.md, de punta a punta por HTTP.
 * Los nombres I1…I8 son los de la sección «Pruebas de aceptación» de ese documento.
 */
class AuthFlowTest : ApiTest() {

    private val auditor = BaseRole.AUDITOR.key.value

    private fun auditRows(action: String): Int =
        db.sql("SELECT count(*) FROM audit_log WHERE action = :action").param("action", action).query(Int::class.java).single()

    // ── Inicio de sesión ─────────────────────────────────────────────────────

    @Test
    fun `signing in returns the tokens and the user with their permissions`() {
        val response = login()

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("accessToken")).isNotBlank()
        assertThat(response.at<String>("refreshToken")).hasSizeGreaterThanOrEqualTo(43)
        assertThat(response.at<Int>("expiresIn")).isEqualTo(900)
        assertThat(response.at<String>("user", "username")).isEqualTo("admin")
        assertThat(response.at<List<String>>("user", "roles")).containsExactly("ADMIN")
        assertThat(response.at<List<String>>("user", "permissions")).hasSize(PermissionCatalog.all.size)
        assertThat(response.at<Map<String, String>>("user", "scopes")).containsEntry("product.view", "ALL")
        // El primer administrador nace obligado a cambiar la contraseña que vino por configuración.
        assertThat(response.at<Boolean>("user", "mustChangePassword")).isTrue()
    }

    @Test
    fun `the access token carries no permissions, and the refresh token is stored hashed`() {
        val response = login()

        val payload = String(java.util.Base64.getUrlDecoder().decode(response.at<String>("accessToken").split(".")[1]))
        assertThat(payload).doesNotContain("permissions").doesNotContain("roles").doesNotContain("product.view")

        val stored = db.sql("SELECT trim(token_hash) FROM refresh_tokens").query(String::class.java).single()
        assertThat(stored).hasSize(64).isNotEqualTo(response.at<String>("refreshToken"))
    }

    @Test
    fun `without username or password answers FIELD_REQUIRED`() {
        for (body in listOf(mapOf("username" to "admin"), mapOf("password" to "x"), mapOf("username" to "  ", "password" to "x"), emptyMap())) {
            val response = post("/v1/auth/login", body)
            assertThat(response.status).isEqualTo(400)
            assertThat(response.code).isEqualTo("FIELD_REQUIRED")
        }
    }

    @Test
    fun `I1 - unknown user and wrong password answer exactly the same`() {
        val unknownUser = login(username = "no-existe", password = "cualquiera-123")
        val wrongPassword = login(username = "admin", password = "cualquiera-123")

        assertThat(unknownUser.status).isEqualTo(401)
        assertThat(unknownUser.code).isEqualTo("INVALID_CREDENTIALS")
        assertThat(wrongPassword.status).isEqualTo(unknownUser.status)
        assertThat(wrongPassword.body).isEqualTo(unknownUser.body)
    }

    @Test
    fun `I2 - five failures lock the username, and the lock is temporary`() {
        repeat(5) { assertThat(login(password = "incorrecta-000").code).isEqualTo("INVALID_CREDENTIALS") }

        // Bloqueado: ni con la contraseña correcta.
        val locked = login()
        assertThat(locked.status).isEqualTo(401)
        assertThat(locked.code).isEqualTo("ACCOUNT_LOCKED")

        // Insistir durante el bloqueo no lo prolonga.
        clock.advanceSeconds(10 * 60)
        assertThat(login().code).isEqualTo("ACCOUNT_LOCKED")

        clock.advanceSeconds(5 * 60 + 1)
        assertThat(login().status).isEqualTo(200)
    }

    @Test
    fun `I2 - a username that does not exist is locked the same way, so the lock does not reveal which accounts are real`() {
        repeat(5) { assertThat(login(username = "fantasma", password = "incorrecta-000").code).isEqualTo("INVALID_CREDENTIALS") }

        assertThat(login(username = "fantasma", password = "incorrecta-000").code).isEqualTo("ACCOUNT_LOCKED")
    }

    @Test
    fun `a deactivated account only tells that to whoever guesses the password`() {
        val userId = createUser("baja", roles = arrayOf(auditor))
        db.sql("UPDATE users SET disabled = TRUE WHERE id = :id").param("id", userId).update()

        assertThat(login("baja", "incorrecta-000").code).isEqualTo("INVALID_CREDENTIALS")
        val response = login("baja", USER_PASSWORD)
        assertThat(response.status).isEqualTo(401)
        assertThat(response.code).isEqualTo("ACCOUNT_DISABLED")
    }

    // ── El usuario actual ────────────────────────────────────────────────────

    @Test
    fun `returns the token's user, re-read from the database`() {
        createUser("ana", roles = arrayOf(auditor))
        val token = accessTokenOf("ana", USER_PASSWORD)

        val response = get("/v1/auth/me", token)

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("username")).isEqualTo("ana")
        assertThat(response.at<List<String>>("roles")).containsExactly(auditor)
        assertThat(response.at<List<String>>("permissions")).contains("audit.view").doesNotContain("product.create")
        assertThat(response.at<Boolean>("mustChangePassword")).isFalse()
    }

    @Test
    fun `removing a role takes effect on the next request, without waiting for the token to expire`() {
        val userId = createUser("ana", roles = arrayOf(auditor))
        val token = accessTokenOf("ana", USER_PASSWORD)
        assertThat(get("/v1/auth/me", token).at<List<String>>("permissions")).isNotEmpty()

        db.sql("DELETE FROM user_roles WHERE user_id = :id").param("id", userId).update()

        assertThat(get("/v1/auth/me", token).at<List<String>>("permissions")).isEmpty()
    }

    @Test
    fun `deactivating an account invalidates its token on the next request`() {
        val userId = createUser("ana", roles = arrayOf(auditor))
        val token = accessTokenOf("ana", USER_PASSWORD)

        db.sql("UPDATE users SET disabled = TRUE WHERE id = :id").param("id", userId).update()

        val response = get("/v1/auth/me", token)
        assertThat(response.status).isEqualTo(401)
        assertThat(response.code).isEqualTo("ACCOUNT_DISABLED")
    }

    @Test
    fun `without a token, with an unreadable token or with an expired one answers 401 UNAUTHENTICATED`() {
        val token = accessTokenOf()

        val missing = get("/v1/auth/me")
        assertThat(missing.status).isEqualTo(401)
        assertThat(missing.code).isEqualTo("UNAUTHENTICATED")
        assertThat(missing.header("WWW-Authenticate")).isEqualTo("Bearer")

        assertThat(get("/v1/auth/me", "esto-no-es-un-token").code).isEqualTo("UNAUTHENTICATED")
        assertThat(get("/v1/auth/me", token.dropLast(3) + "abc").code).isEqualTo("UNAUTHENTICATED")

        clock.advanceSeconds(15 * 60 + 1)
        assertThat(get("/v1/auth/me", token).code).isEqualTo("UNAUTHENTICATED")
    }

    // ── Renovación ───────────────────────────────────────────────────────────

    @Test
    fun `renewing gives new tokens and marks the previous one as rotated`() {
        val session = login()

        val renewed = post("/v1/auth/refresh", mapOf("refreshToken" to session.at<String>("refreshToken")))

        assertThat(renewed.status).isEqualTo(200)
        assertThat(renewed.at<String>("refreshToken")).isNotEqualTo(session.at<String>("refreshToken"))
        assertThat(get("/v1/auth/me", renewed.at("accessToken")).status).isEqualTo(200)
        val generations = db.sql("SELECT generation, rotated_at IS NOT NULL AS rotated FROM refresh_tokens ORDER BY generation")
            .query { rs, _ -> rs.getInt("generation") to rs.getBoolean("rotated") }.list()
        assertThat(generations).containsExactly(1 to true, 2 to false)
    }

    @Test
    fun `an immediate retry with the same token does not close the session - grace window`() {
        val refreshToken = login().at<String>("refreshToken")
        post("/v1/auth/refresh", mapOf("refreshToken" to refreshToken))

        clock.advanceSeconds(10)
        val retry = post("/v1/auth/refresh", mapOf("refreshToken" to refreshToken))

        assertThat(retry.status).isEqualTo(200)
        assertThat(auditRows("TOKEN_REUSE_DETECTED")).isZero()
    }

    @Test
    fun `I3 - reusing an already rotated refresh token revokes the whole family and is recorded in the audit log`() {
        val first = login().at<String>("refreshToken")
        val second = post("/v1/auth/refresh", mapOf("refreshToken" to first)).at<String>("refreshToken")

        clock.advanceSeconds(31) // fuera de la ventana de gracia: ya no es un reintento de red
        val reuse = post("/v1/auth/refresh", mapOf("refreshToken" to first))

        assertThat(reuse.status).isEqualTo(401)
        assertThat(reuse.code).isEqualTo("TOKEN_REUSED")
        // El token vigente más reciente también deja de servir.
        val legitimate = post("/v1/auth/refresh", mapOf("refreshToken" to second))
        assertThat(legitimate.status).isEqualTo(401)
        assertThat(legitimate.code).isEqualTo("INVALID_CREDENTIALS")
        assertThat(auditRows("TOKEN_REUSE_DETECTED")).isEqualTo(1)
    }

    @Test
    fun `an unknown, malformed or expired refresh answers INVALID_CREDENTIALS`() {
        val refreshToken = login().at<String>("refreshToken")

        assertThat(post("/v1/auth/refresh", mapOf("refreshToken" to "x".repeat(43))).code).isEqualTo("INVALID_CREDENTIALS")
        assertThat(post("/v1/auth/refresh", mapOf("refreshToken" to "corto")).code).isEqualTo("INVALID_CREDENTIALS")
        assertThat(post("/v1/auth/refresh", emptyMap<String, String>()).code).isEqualTo("INVALID_CREDENTIALS")

        clock.advanceSeconds(30L * 24 * 3600 + 1)
        assertThat(post("/v1/auth/refresh", mapOf("refreshToken" to refreshToken)).code).isEqualTo("INVALID_CREDENTIALS")
    }

    // ── Cierre de sesión ─────────────────────────────────────────────────────

    @Test
    fun `signing out revokes the refresh token and never answers with an error`() {
        val session = login()
        val refreshToken = session.at<String>("refreshToken")

        assertThat(post("/v1/auth/logout", mapOf("refreshToken" to refreshToken)).status).isEqualTo(204)

        assertThat(post("/v1/auth/refresh", mapOf("refreshToken" to refreshToken)).code).isEqualTo("INVALID_CREDENTIALS")
        // Repetirlo, no mandar cuerpo, mandar basura o traer un token de acceso inválido: siempre 204.
        assertThat(post("/v1/auth/logout", mapOf("refreshToken" to refreshToken)).status).isEqualTo(204)
        assertThat(post("/v1/auth/logout").status).isEqualTo(204)
        assertThat(post("/v1/auth/logout", mapOf("refreshToken" to "basura")).status).isEqualTo(204)
        assertThat(post("/v1/auth/logout", mapOf("refreshToken" to refreshToken), token = "token-de-acceso-caducado").status).isEqualTo(204)
    }

    // ── Cambio de contraseña ─────────────────────────────────────────────────

    @Test
    fun `I4 - changing the password closes all sessions, including the current one`() {
        createUser("ana", roles = arrayOf(auditor))
        val other = login("ana", USER_PASSWORD)
        val current = login("ana", USER_PASSWORD)

        val change = post(
            "/v1/auth/change-password",
            mapOf("currentPassword" to USER_PASSWORD, "newPassword" to "Una-Clave-Nueva-2027"),
            token = current.at("accessToken"),
        )

        assertThat(change.status).isEqualTo(204)
        // Sin que pase el tiempo: el token de acceso de AMBAS sesiones deja de servir de inmediato.
        assertThat(get("/v1/auth/me", current.at("accessToken")).status).isEqualTo(401)
        assertThat(get("/v1/auth/me", other.at("accessToken")).status).isEqualTo(401)
        assertThat(post("/v1/auth/refresh", mapOf("refreshToken" to other.at<String>("refreshToken"))).code).isEqualTo("INVALID_CREDENTIALS")
        assertThat(login("ana", USER_PASSWORD).code).isEqualTo("INVALID_CREDENTIALS")
        assertThat(login("ana", "Una-Clave-Nueva-2027").status).isEqualTo(200)
        assertThat(auditRows("PASSWORD_CHANGED")).isEqualTo(1)
    }

    @Test
    fun `the initial administrator is no longer required to change it once changed`() {
        val token = accessTokenOf()

        post("/v1/auth/change-password", mapOf("currentPassword" to ADMIN_PASSWORD, "newPassword" to "Una-Clave-Nueva-2027"), token)

        assertThat(login(password = "Una-Clave-Nueva-2027").at<Boolean>("user", "mustChangePassword")).isFalse()
    }

    @Test
    fun `a wrong current password, a weak new one or the same one change nothing`() {
        createUser("ana", roles = arrayOf(auditor))
        val token = accessTokenOf("ana", USER_PASSWORD)

        val wrongCurrent = post("/v1/auth/change-password", mapOf("currentPassword" to "no-es-esta", "newPassword" to "Una-Clave-Nueva-2027"), token)
        assertThat(wrongCurrent.status).isEqualTo(401)
        assertThat(wrongCurrent.code).isEqualTo("INVALID_CREDENTIALS")

        val weak = post("/v1/auth/change-password", mapOf("currentPassword" to USER_PASSWORD, "newPassword" to "corta"), token)
        assertThat(weak.status).isEqualTo(422)
        assertThat(weak.code).isEqualTo("WEAK_PASSWORD")

        val same = post("/v1/auth/change-password", mapOf("currentPassword" to USER_PASSWORD, "newPassword" to USER_PASSWORD), token)
        assertThat(same.status).isEqualTo(422)
        assertThat(same.code).isEqualTo("SAME_PASSWORD")

        // La sesión sigue viva y la contraseña es la de antes.
        assertThat(get("/v1/auth/me", token).status).isEqualTo(200)
        assertThat(login("ana", USER_PASSWORD).status).isEqualTo(200)
        assertThat(auditRows("PASSWORD_CHANGED")).isZero()
    }

    @Test
    fun `changing the password requires identity`() {
        val response = post("/v1/auth/change-password", mapOf("currentPassword" to "a", "newPassword" to "b"))

        assertThat(response.status).isEqualTo(401)
        assertThat(response.code).isEqualTo("UNAUTHENTICATED")
    }
}
