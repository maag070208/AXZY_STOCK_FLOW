package dev.axzy.stock.app

import dev.axzy.stock.app.config.SchemaGuard
import dev.axzy.stock.app.support.ApiTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/** The cross-cutting HTTP contract: health, request identifier and error body (docs/api/). */
class HttpContractTest : ApiTest() {

    @Test
    fun `health is public and states the schema version`() {
        val response = get("/v1/health")

        assertThat(response.status).isEqualTo(200)
        assertThat(response.at<String>("status")).isEqualTo("UP")
        assertThat(response.at<String>("schemaVersion")).isEqualTo(SchemaGuard.REQUIRED_SCHEMA_VERSION.toString())
        assertThat(response.at<String>("database", "status")).isEqualTo("UP")
        assertThat(response.at<Int>("database", "latencyMs")).isGreaterThanOrEqualTo(0)
        assertThat(response.at<String>("version")).isEqualTo("dev")
    }

    @Test
    fun `every response carries a request identifier - the client's if it is a UUID`() {
        val provided = UUID.randomUUID().toString()

        assertThat(get("/v1/health", headers = mapOf("X-Request-Id" to provided)).header("X-Request-Id")).isEqualTo(provided)
        // Free text is not accepted: it would end up in the log and the audit log.
        val replaced = get("/v1/health", headers = mapOf("X-Request-Id" to "'; DROP TABLE users; --")).header("X-Request-Id")
        assertThat(UUID.fromString(replaced)).isNotNull()
        // The security chain's rejections carry it too.
        assertThat(get("/v1/auth/me").header("X-Request-Id")).isNotBlank()
    }

    @Test
    fun `a route that does not exist answers 401 without identity and 404 with it`() {
        val anonymous = get("/v1/no-existe")
        assertThat(anonymous.status).isEqualTo(401)
        assertThat(anonymous.code).isEqualTo("UNAUTHENTICATED")

        val authenticated = get("/v1/no-existe", accessTokenOf())
        assertThat(authenticated.status).isEqualTo(404)
        assertThat(authenticated.code).isEqualTo("NOT_FOUND")
    }

    @Test
    fun `a body that is not JSON answers with the error contract, not with a trace`() {
        val response = post("/v1/auth/login", "{ esto no es json")

        assertThat(response.status).isEqualTo(400)
        assertThat(response.code).isEqualTo("MALFORMED_REQUEST")
        assertThat(response.body.keys).containsExactlyInAnyOrder("code", "message")
        assertThat(response.raw).doesNotContain("Exception").doesNotContain("tools.jackson").doesNotContain("at dev.axzy")
    }

    @Test
    fun `a method not allowed answers 405 with its code`() {
        val response = send("PUT", "/v1/auth/me", mapOf("x" to 1), accessTokenOf(), emptyMap())

        assertThat(response.status).isEqualTo(405)
        assertThat(response.code).isEqualTo("METHOD_NOT_ALLOWED")
    }

    @Test
    fun `an unknown client channel is rejected, and a service channel cannot be declared`() {
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
    fun `the API opens no server session and sends no cookies`() {
        val response = login()

        assertThat(response.headers.allValues("Set-Cookie")).isEmpty()
    }
}
