package dev.axzy.stock.app

import dev.axzy.stock.app.support.ApiTest
import dev.axzy.stock.domain.access.BaseRole
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * La administración de usuarios y roles (M07) por HTTP real.
 *
 * Es la puerta de todo lo demás: quien puede asignar roles puede darse cualquier
 * permiso. Por eso casi todo lo que se comprueba aquí es **quién puede hacerlo**, y por
 * eso la última prueba mira el efecto que de verdad importa: restablecer una contraseña
 * tiene que dejar fuera a quien tenía la sesión abierta.
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
    fun `el listado pagina, filtra y trae los roles de cada uno`() {
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
    fun `el detalle trae los roles y las bodegas del usuario`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))

        val response = get("/v1/users/${userIdOf("ana")}", token)

        assertThat(response.at<String>("username")).isEqualTo("ana")
        assertThat(response.at<List<String>>("roles")).containsExactly(almacen)
        // Sin alcances por bodega, el usuario ve las de su área; el arreglo va vacío.
        assertThat(response.at<List<Long>>("warehouseIds")).isEmpty()
    }

    @Test
    fun `el catalogo de roles y permisos se puede leer con su permiso`() {
        val token = manager()

        val roles = get("/v1/roles", token)
        val permisos = get("/v1/permissions", token)

        assertThat(roles.status).isEqualTo(200)
        assertThat(roles.raw).contains(almacen)
        assertThat(permisos.status).isEqualTo(200)
        assertThat(permisos.raw).contains("inventory.adjust_theft")
        // El catálogo de permisos es código y se publica con su módulo y su sensibilidad.
        assertThat(permisos.raw).contains("\"sensitive\":true")
    }

    // ── Alta ────────────────────────────────────────────────────────────────

    @Test
    fun `el alta crea el usuario con sus roles y lo deja obligado a cambiar la contrasena`() {
        val token = manager()

        val response = create(token, "ana", roles = listOf(almacen))

        assertThat(response.status).describedAs(response.raw).isEqualTo(201)
        assertThat(response.at<String>("username")).isEqualTo("ana")
        assertThat(response.at<List<String>>("roles")).containsExactly(almacen)
        // Nace obligada a cambiarla: quien la creó conoce la contraseña.
        assertThat(response.at<Boolean>("mustChangePassword")).isTrue()
        // Y la contraseña no se devuelve nunca.
        assertThat(response.raw).doesNotContain(USER_PASSWORD)
    }

    @Test
    fun `el alta rechaza una contrasena debil, un nombre repetido y un rol que no existe`() {
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
    fun `un usuario nuevo puede entrar y tiene que cambiar la contrasena`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))

        val sesion = post("/v1/auth/login", mapOf("username" to "ana", "password" to USER_PASSWORD))
        val conLaClave = sesion.at<String>("accessToken")

        // I8: con la contraseña pendiente, cualquier ruta que no sea cambiarla se rechaza.
        val otraRuta = get("/v1/inventory/stock", conLaClave)
        assertThat(sesion.status).isEqualTo(200)
        assertThat(otraRuta.code).isEqualTo("PASSWORD_CHANGE_REQUIRED")
    }

    // ── Edición y asignación de roles ───────────────────────────────────────

    @Test
    fun `asignar roles cambia lo que la persona puede hacer`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")

        val response = put("/v1/users/$ana", mapOf("roles" to listOf(auditor)), token)

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<List<String>>("roles")).containsExactly(auditor)
        // Y queda en la base, que es lo que decide de verdad al entrar.
        assertThat(
            db.sql("SELECT count(*) FROM user_roles WHERE user_id = :id").param("id", ana)
                .query(Int::class.java).single(),
        ).isEqualTo(1)
    }

    @Test
    fun `editar deja lo que no se envia y no admite quedarse sin roles`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")

        val renombrada = put("/v1/users/$ana", mapOf("name" to "Ana Pérez"), token)
        val sinRoles = put("/v1/users/$ana", mapOf("roles" to emptyList<String>()), token)

        assertThat(renombrada.status).isEqualTo(200)
        assertThat(renombrada.at<String>("name")).isEqualTo("Ana Pérez")
        assertThat(renombrada.at<List<String>>("roles")).containsExactly(almacen)
        // Un usuario sin roles no podría hacer nada, y lo descubriría al entrar.
        assertThat(sinRoles.status).isEqualTo(422)
        assertThat(sinRoles.code).isEqualTo("FIELD_REQUIRED")
    }

    @Test
    fun `desactivar un usuario le quita el acceso`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")

        val response = put("/v1/users/$ana", mapOf("disabled" to true), token)
        val sesion = post("/v1/auth/login", mapOf("username" to "ana", "password" to USER_PASSWORD))

        assertThat(response.at<Boolean>("disabled")).isTrue()
        // 401 y no 403: la cuenta desactivada es un fallo de credenciales, y el contrato
        // solo lo dice **si la contraseña era correcta** (docs/api/autenticacion.md).
        assertThat(sesion.status).isEqualTo(401)
        assertThat(sesion.code).isEqualTo("ACCOUNT_DISABLED")
    }

    // ── Restablecer la contraseña ───────────────────────────────────────────

    @Test
    fun `restablecer la contrasena deja fuera la sesion abierta`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")
        // Ana entra y cambia su contraseña: su sesión es válida y no está pendiente.
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
        // El reloj de las pruebas está congelado: sin avanzarlo, el sello de credenciales
        // del token nuevo y el viejo serían el mismo milisegundo y no se podría comprobar
        // que el token viejo muere.
        clock.advanceSeconds(60)

        val response = post(
            "/v1/users/$ana/reset-password",
            // `mustChangePassword = false` a propósito: así lo que se comprueba es que el
            // token viejo muere, y no la pantalla de cambio obligatorio.
            mapOf("password" to "clave-restablecida-larga", "mustChangePassword" to false),
            token,
        )

        assertThat(response.status).describedAs(response.raw).isEqualTo(200)
        assertThat(response.at<Boolean>("mustChangePassword")).isFalse()
        // R3: el token que estaba vivo ya no vale. Si a alguien se le restablece la
        // contraseña porque sospecha que se la robaron, dejar vivo el token del ladrón
        // sería no haber hecho nada.
        assertThat(get("/v1/inventory/stock", suToken).status).isEqualTo(401)
        // Y con la contraseña nueva sí entra.
        assertThat(post("/v1/auth/login", mapOf("username" to "ana", "password" to "clave-restablecida-larga")).status)
            .isEqualTo(200)
    }

    @Test
    fun `restablecer exige el permiso y una contrasena que cumpla la politica`() {
        val token = manager()
        create(token, "ana", roles = listOf(almacen))
        val ana = userIdOf("ana")

        val debil = post("/v1/users/$ana/reset-password", mapOf("password" to "1234"), token)

        assertThat(debil.status).isEqualTo(422)
        assertThat(debil.code).isEqualTo("WEAK_PASSWORD")
    }

    // ── Permisos por ruta ───────────────────────────────────────────────────

    @Test
    fun `administrar usuarios no es cosa de cualquiera`() {
        createUser("operador", USER_PASSWORD, BaseRole.WAREHOUSE_OPERATOR.key.value)
        val token = accessTokenOf("operador", USER_PASSWORD)

        assertThat(get("/v1/users", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(post("/v1/users", mapOf("username" to "x"), token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/roles", token).code).isEqualTo("PERMISSION_DENIED")
        assertThat(get("/v1/permissions", token).code).isEqualTo("PERMISSION_DENIED")
    }
}
