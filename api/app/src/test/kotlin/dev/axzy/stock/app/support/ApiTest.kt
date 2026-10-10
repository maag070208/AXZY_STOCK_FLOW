package dev.axzy.stock.app.support

import dev.axzy.stock.application.identity.AccessCatalogStore
import dev.axzy.stock.application.identity.BootstrapIdentity
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.PasswordHasher
import dev.axzy.stock.testing.MutableClock
import dev.axzy.stock.testing.TestDatabase
import org.junit.jupiter.api.BeforeEach
import dev.axzy.stock.app.config.StockProperties
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import java.time.LocalDate

/** An API response, with the body already read as a map. */
data class ApiResponse(val status: Int, val body: Map<String, Any?>, val headers: java.net.http.HttpHeaders, val raw: String) {
    val code: String? get() = body["code"] as String?
    fun header(name: String): String? = headers.firstValue(name).orElse(null)

    @Suppress("UNCHECKED_CAST")
    fun <T> at(vararg path: String): T {
        var current: Any? = body
        for (key in path) current = (current as Map<String, Any?>)[key]
        return current as T
    }
}

/**
 * Base of the flow tests: **the whole API, over real HTTP**. It starts Spring Boot with
 * Tomcat on a free port, the security chain, the real wiring and real PostgreSQL.
 *
 * The only thing replaced is the clock, so that time can be advanced (expiry, grace window,
 * lockout). Each test starts with the freshly seeded database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ApiTest.TestClock::class)
abstract class ApiTest {

    @TestConfiguration(proxyBeanMethods = false)
    class TestClock {
        @Bean
        @Primary
        fun testClock() = MutableClock(Instant.now())
    }

    @LocalServerPort
    protected var port: Int = 0

    @Autowired protected lateinit var clock: MutableClock

    /**
     * The operating day, **in the business zone and not in the machine's**.
     *
     * The API dates movements with `stock.business-zone`, so a test that asks for "today"
     * with `LocalDate.now()` gets the wrong date on the day when the machine and the business zone are
     * on different days (on the US west coast, from 23:00 to midnight). The rule is the
     * same for both, so the test takes it from the same configuration.
     */
    protected fun businessToday(): LocalDate = LocalDate.now(properties.businessZone)
    @Autowired protected lateinit var json: JsonMapper
    @Autowired protected lateinit var properties: StockProperties
    @Autowired private lateinit var bootstrap: BootstrapIdentity
    @Autowired private lateinit var tx: TransactionRunner
    @Autowired private lateinit var catalog: AccessCatalogStore
    @Autowired private lateinit var hasher: PasswordHasher

    /** To check the state of the database without going through the API. */
    protected val db: JdbcClient = JdbcClient.create(TestDatabase.owner)
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun freshState() {
        TestDatabase.reset()
        clock.set(Instant.now())
        tx.inTransaction { bootstrap.execute().asRight() }
    }

    // ── Datos ────────────────────────────────────────────────────────────────

    /** Creates an account with no password change pending. */
    protected fun createUser(username: String, password: String = USER_PASSWORD, vararg roles: String): Long =
        tx.inTransaction {
            catalog.createUser(username, "Usuario $username", hasher.hash(password), mustChangePassword = false, roleKeys = roles.toList()).asRight()
        }.getOrNull()!!

    /** Creates an account **with the password change pending**: it is the starting point of I8. */
    protected fun createPendingUser(username: String, password: String = USER_PASSWORD, vararg roles: String): Long =
        tx.inTransaction {
            catalog.createUser(username, "Usuario $username", hasher.hash(password), mustChangePassword = true, roleKeys = roles.toList()).asRight()
        }.getOrNull()!!

    protected fun login(username: String = "admin", password: String = ADMIN_PASSWORD): ApiResponse =
        post("/v1/auth/login", mapOf("username" to username, "password" to password))

    protected fun accessTokenOf(username: String = "admin", password: String = ADMIN_PASSWORD): String =
        login(username, password).at("accessToken")

    // ── HTTP ─────────────────────────────────────────────────────────────────

    protected fun get(path: String, token: String? = null, headers: Map<String, String> = emptyMap()): ApiResponse =
        send("GET", path, null, token, headers)

    protected fun post(path: String, body: Any? = null, token: String? = null, headers: Map<String, String> = emptyMap()): ApiResponse =
        send("POST", path, body, token, headers)

    /** `PUT`: the full edit of a resource. Labels are the first to use it. */
    protected fun put(path: String, body: Any? = null, token: String? = null, headers: Map<String, String> = emptyMap()): ApiResponse =
        send("PUT", path, body, token, headers)

    /** `PATCH`: the partial update of a resource. The printer's confirmation uses it. */
    protected fun patch(
        path: String,
        body: Any? = null,
        token: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): ApiResponse = send("PATCH", path, body, token, headers)

    protected fun send(method: String, path: String, body: Any?, token: String?, headers: Map<String, String>): ApiResponse {
        val payload = when (body) {
            null -> HttpRequest.BodyPublishers.noBody()
            is String -> HttpRequest.BodyPublishers.ofString(body)
            else -> HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))
        }
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path")).method(method, payload)
        if (body != null) request.header("Content-Type", "application/json")
        if (token != null) request.header("Authorization", "Bearer $token")
        headers.forEach { (name, value) -> request.header(name, value) }
        return execute(request.build())
    }

    /**
     * Uploads a file as `multipart/form-data`, which is what a browser form
     * really sends. A `application/json` body with the text inside
     * would not test the same route.
     */
    protected fun postFile(
        path: String,
        content: String,
        filename: String = "catalogo.csv",
        token: String? = null,
        query: String = "",
    ): ApiResponse {
        val boundary = "----stocktest${java.util.UUID.randomUUID()}"
        val body = buildString {
            append("--$boundary\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
            append("Content-Type: text/csv\r\n\r\n")
            append(content)
            append("\r\n--$boundary--\r\n")
        }
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path$query"))
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (token != null) request.header("Authorization", "Bearer $token")
        return execute(request.build())
    }

    private fun execute(request: HttpRequest): ApiResponse {
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        @Suppress("UNCHECKED_CAST")
        val parsed = response.body().takeIf { it.isNotBlank() }
            ?.let { runCatching { json.readValue(it, Map::class.java) as Map<String, Any?> }.getOrNull() }
            ?: emptyMap()
        return ApiResponse(response.statusCode(), parsed, response.headers(), response.body())
    }

    companion object {
        const val ADMIN_PASSWORD = "Almacen-Seguro-2026"
        const val USER_PASSWORD = "Otra-Clave-Larga-77"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { TestDatabase.jdbcUrl }
            registry.add("spring.datasource.username") { TestDatabase.apiUsername }
            registry.add("spring.datasource.password") { TestDatabase.apiPassword }
            registry.add("stock.jwt.secret") { "secreto-solo-para-las-pruebas-de-flujo-0123456789" }
            registry.add("stock.admin.bootstrap-password") { ADMIN_PASSWORD }
        }
    }
}
