package dev.axzy.stock.app

import dev.axzy.stock.app.config.SchemaGuard
import dev.axzy.stock.app.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** El contrato HTTP transversal: salud, identificador de petición y cuerpo de error (docs/api/). */
class HttpContractTest : ApiTest() {

    @Test
    fun `la salud es publica y dice la version del esquema`() {
        val response = get("/v1/health")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("status")).isEqualTo("UP")
        assertThat(response.at<String>("schemaVersion")).isEqualTo(SchemaGuard.REQUIRED_SCHEMA_VERSION.toString())
        assertThat(response.at<String>("database", "status")).isEqualTo("UP")
        assertThat(response.at<Int>("database", "latencyMs")).isGreaterThanOrEqualTo(0)
        assertThat(response.at<String>("version")).isEqualTo("dev")
    }

    @Test
    fun `toda respuesta lleva un identificador de peticion - el del cliente si es un UUID`() {
        val provided = UUID.randomUUID().toString()

        assertThat(get("/v1/health", headers = mapOf("X-Request-Id" to provided)).header("X-Request-Id")).isEqualTo(provided)
        // Texto libre no se acepta: acabaría en el log y en la bitácora.
        val replaced = get("/v1/health", headers = mapOf("X-Request-Id" to "'; DROP TABLE users; --")).header("X-Request-Id")
        assertThat(UUID.fromString(replaced)).isNotNull()
        // También los rechazos de la cadena de seguridad lo llevan.
        assertThat(get("/v1/auth/me").header("X-Request-Id")).isNotBlank()
    }

    @Test
    fun `una ruta que no existe responde 401 sin identidad y 404 con ella`() {
        val anonymous = get("/v1/no-existe")
        assertThat(anonymous.status).isEqualTo(401)
        assertThat(anonymous.code).isEqualTo("UNAUTHENTICATED")

        val authenticated = get("/v1/no-existe", accessTokenOf())
        assertThat(authenticated.status).isEqualTo(404)
        assertThat(authenticated.code).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `un cuerpo que no es JSON responde con el contrato de error, no con una traza`() {
        val response = post("/v1/auth/login", "{ esto no es json")

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("MALFORMED_REQUEST")
        assertThat(response.body.keys).containsExactlyInAnyOrder("code", "message")
        assertThat(response.raw).doesNotContain("Exception").doesNotContain("tools.jackson").doesNotContain("at dev.axzy")
    }

    @Test
    fun `un metodo no permitido responde 405 con su codigo`() {
        val response = send("PUT", "/v1/auth/me", mapOf("x" to 1), accessTokenOf(), emptyMap())

        assertThat(response.status).isEqualTo(405)
        assertThat(response.code).isEqualTo("METHOD_NOT_ALLOWED")
    }

    @Test
    fun `un canal de cliente desconocido se rechaza, y no se puede declarar un canal de servicio`() {
        for (channel in listOf("FAX", "SERVICE_ERP", "SYSTEM")) {
            val response = post(
                "/v1/auth/login",
                mapOf("username" to "admin", "password" to ADMIN_PASSWORD),
                headers = mapOf("X-Client-Channel" to channel),
            )
            assertThat(response.status).isEqualTo(400)
            assertThat(response.code).isEqualTo("INVALID_PARAMETER")
        }
        assertThat(post("/v1/auth/login", mapOf("username" to "admin", "password" to ADMIN_PASSWORD), headers = mapOf("X-Client-Channel" to "mobile")).status)
            .isEqualTo(200)
    }

    @Test
    fun `la API no abre sesion de servidor ni manda cookies`() {
        val response = login()

        assertThat(response.headers.allValues("Set-Cookie")).isEmpty()
    }
}
