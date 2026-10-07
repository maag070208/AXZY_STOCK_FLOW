package dev.axzy.stock.network

import dev.axzy.stock.network.client.ClientChannel
import dev.axzy.stock.network.client.EpochClock
import dev.axzy.stock.network.client.InMemoryTokenStore
import dev.axzy.stock.network.client.StoredSession
import dev.axzy.stock.network.client.StockApiConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.content.TextContent

/**
 * Utilidades comunes de los tests del módulo.
 *
 * El reloj y la base URL se fijan a propósito: un test que depende del reloj
 * real falla una vez cada mil ejecuciones, y un test que falla solo a veces se
 * acaba ignorando.
 */
internal const val FIXED_NOW = 1_700_000_000L
internal const val TEST_BASE_URL = "https://api.test/v1"

internal fun testConfig(clock: EpochClock = EpochClock { FIXED_NOW }): StockApiConfig = StockApiConfig(
    baseUrl = TEST_BASE_URL,
    channel = ClientChannel.MOBILE,
    timezone = "America/Mexico_City",
    clock = clock,
)

/** Sesión de prueba lista para el almacén en memoria. */
internal fun testSession(
    accessToken: String = "access-viejo",
    refreshToken: String = "refresh-viejo",
    expiresAt: Long = FIXED_NOW + 900,
): StoredSession = StoredSession(accessToken, refreshToken, expiresAt)

/** El almacén de tokens con una sesión ya dentro: así las peticiones salen autenticadas. */
internal fun tokenStoreWithSession(session: StoredSession = testSession()): InMemoryTokenStore =
    InMemoryTokenStore(session)

/**
 * Acumula las peticiones que recibió el motor simulado.
 *
 * Es la única forma de comprobar lo que de verdad importa de un cliente HTTP:
 * **qué se envió** (cabeceras, cuerpo, consulta) y **cuántas veces**.
 */
internal class MotorGrabado(private val requests: MutableList<HttpRequestData>) {
    val todas: List<HttpRequestData> get() = requests

    fun vecesRuta(path: String): Int =
        requests.count { it.url.encodedPath == path }

    fun ultimaPara(path: String): HttpRequestData? =
        requests.lastOrNull { it.url.encodedPath == path }
}

/**
 * Crea un motor simulado y su registro de peticiones.
 *
 * El manejador recibe el `MockRequestHandleScope` como receptor para poder usar
 * `respond(...)` directamente en los tests, que es como lo documenta Ktor.
 */
internal fun motorSimulado(
    handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
): Pair<MockEngine, MotorGrabado> {
    val requests = mutableListOf<HttpRequestData>()
    val engine = MockEngine { request ->
        requests += request
        handler(request)
    }
    return engine to MotorGrabado(requests)
}

/** El cuerpo de una petición como texto, cuando es texto. */
internal suspend fun HttpRequestData.cuerpoTexto(): String = when (val content = body) {
    is TextContent -> content.text
    else -> ""
}
