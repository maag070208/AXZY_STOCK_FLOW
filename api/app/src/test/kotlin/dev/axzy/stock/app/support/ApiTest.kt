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

/** Una respuesta de la API, con el cuerpo ya leído como mapa. */
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
 * Base de las pruebas de flujo: **la API entera, por HTTP de verdad**. Arranca Spring Boot con
 * Tomcat en un puerto libre, la cadena de seguridad, el cableado real y PostgreSQL real.
 *
 * Lo único sustituido es el reloj, para poder adelantar el tiempo (caducidad, ventana de gracia,
 * bloqueo). Cada prueba empieza con la base recién sembrada.
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
     * El día operativo, **en la zona de negocio y no en la de la máquina**.
     *
     * La API fecha los movimientos con `stock.business-zone`, así que un test que pregunte por
     * «hoy» con `LocalDate.now()` falla la hora al día en que la máquina y la zona de negocio
     * están en días distintos (en la costa oeste de EE. UU., de 23:00 a 00:00). La regla es la
     * misma para los dos, así que el test la pide de la misma configuración.
     */
    protected fun businessToday(): LocalDate = LocalDate.now(properties.businessZone)
    @Autowired protected lateinit var json: JsonMapper
    @Autowired protected lateinit var properties: StockProperties
    @Autowired private lateinit var bootstrap: BootstrapIdentity
    @Autowired private lateinit var tx: TransactionRunner
    @Autowired private lateinit var catalog: AccessCatalogStore
    @Autowired private lateinit var hasher: PasswordHasher

    /** Para comprobar el estado de la base sin pasar por la API. */
    protected val db: JdbcClient = JdbcClient.create(TestDatabase.owner)
    private val http: HttpClient = HttpClient.newHttpClient()

    @BeforeEach
    fun freshState() {
        TestDatabase.reset()
        clock.set(Instant.now())
        tx.inTransaction { bootstrap.execute().asRight() }
    }

    // ── Datos ────────────────────────────────────────────────────────────────

    /** Crea una cuenta que no tiene pendiente el cambio de contraseña. */
    protected fun createUser(username: String, password: String = USER_PASSWORD, vararg roles: String): Long =
        tx.inTransaction {
            catalog.createUser(username, "Usuario $username", hasher.hash(password), mustChangePassword = false, roleKeys = roles.toList()).asRight()
        }.getOrNull()!!

    /** Crea una cuenta **con el cambio de contraseña pendiente**: es el punto de partida de I8. */
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

    /** `PUT`: la edición completa de un recurso. Las etiquetas son las primeras en usarlo. */
    protected fun put(path: String, body: Any? = null, token: String? = null, headers: Map<String, String> = emptyMap()): ApiResponse =
        send("PUT", path, body, token, headers)

    /** `PATCH`: la actualización parcial de un recurso. La confirmación de la impresora la usa. */
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
     * Sube un archivo por `multipart/form-data`, que es lo que un formulario de
     * navegador envía de verdad. Un cuerpo `application/json` con el texto dentro
     * no probaría la misma ruta.
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
