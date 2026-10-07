package dev.axzy.stock.network.client

import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONFIGURACIÓN DEL CLIENTE HTTP
 *
 * Contrato: CONTRATO_API.md §1.6.
 *
 * Lo que **no** entra aquí, a propósito: el token. La configuración es inmutable
 * y compartible; el token cambia en cada refresh. Mezclarlos obligaría a
 * reconstruir el cliente en cada rotación de tokens.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Canal del cliente, que viaja en `X-Client-Channel`.
 *
 * **No es decorativo**: la auditoría registra el canal, y `MOBILE_OFFLINE`
 * dispara el camino de sincronización diferida con sus dos horas de gracia. Un
 * cliente que manda siempre `MOBILE` oculta que la operación se capturó sin
 * señal, y eso es justo lo que hay que poder auditar.
 */
enum class ClientChannel(val headerValue: String) {
    WEB("WEB"),
    MOBILE("MOBILE"),
    MOBILE_OFFLINE("MOBILE_OFFLINE"),
}

/**
 * Reloj en segundos desde la época.
 *
 * Es una interfaz inyectable —y no `Clock.System.now()` sembrado por el
 * código— porque la caducidad del access token se calcula con él: en un test se
 * controla el paso del tiempo sin esperar quince minutos.
 */
fun interface EpochClock {
    fun nowEpochSeconds(): Long
}

/** El reloj del sistema. Es el único sitio del módulo que lee la hora real. */
object SystemEpochClock : EpochClock {
    override fun nowEpochSeconds(): Long = Clock.System.now().epochSeconds
}

/**
 * La configuración del cliente.
 *
 * @param baseUrl URL del API **incluyendo el prefijo de versión**, p. ej.
 *   `https://almacen.interno/api/v1`. El prefijo es obligatorio en el contrato:
 *   el legacy no versionaba sus rutas y no se podía cambiar el contrato sin
 *   romper los clientes desplegados.
 * @param channel canal que se declara en cada petición (auditoría).
 * @param timezone zona IANA que viaja en `X-Client-Timezone`. Por defecto, la
 *   del dispositivo: el servidor la necesita para resolver la fecha de negocio
 *   del turno de noche, que **no** es la fecha del reloj.
 * @param logRequests activa el plugin `Logging` de Ktor. Apagado por defecto:
 *   un log de peticiones en un cliente con tokens es un log con tokens.
 * @param requestTimeoutMillis tope de una petición. Treinta segundos: en el piso
 *   del almacén la cobertura es intermitente, y sin tope una petición se queda
 *   colgada hasta que el sistema operativo la mata, sin poder reintentar.
 */
data class StockApiConfig(
    val baseUrl: String,
    val channel: ClientChannel = ClientChannel.MOBILE,
    val timezone: String = TimeZone.currentSystemDefault().id,
    val logRequests: Boolean = false,
    val requestTimeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    val clock: EpochClock = SystemEpochClock,
) {
    init {
        require(baseUrl.isNotBlank()) { "La URL base no puede estar vacía" }
        require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) {
            "La URL base debe empezar por http:// o https:// (se recibió '$baseUrl'). " +
                "El legacy guardaba una IP pelada y el cliente tenía que adivinar el esquema y el puerto."
        }
        require(timezone.isNotBlank()) { "La zona horaria no puede estar vacía" }
    }

    /** La URL normalizada, sin barra final. */
    val normalizedBaseUrl: String = baseUrl.trim().trimEnd('/')

    /**
     * Une la URL base con la ruta del endpoint.
     *
     * Las rutas se declaran **empezando por barra** (`/auth/login`) para que se
     * lean igual que en el contrato, y aquí se normaliza la unión.
     */
    fun resolve(path: String): String =
        normalizedBaseUrl + if (path.startsWith("/")) path else "/$path"

    companion object {
        /** Tope por defecto de una petición, en milisegundos. */
        const val DEFAULT_TIMEOUT_MILLIS = 30_000L
    }
}
