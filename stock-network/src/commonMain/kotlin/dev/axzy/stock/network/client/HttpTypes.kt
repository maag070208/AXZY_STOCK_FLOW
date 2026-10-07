package dev.axzy.stock.network.client

import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PIEZAS DE TRANSPORTE QUE NO SON DTOs
 *
 * Contrato: CONTRATO_API.md §1.5 y §1.6.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Cabeceras propias del contrato. Se nombran una sola vez para no escribirlas mal. */
object StockHeaders {
    /**
     * Deduplica reintentos en las operaciones que mueven inventario.
     *
     * Es **obligatorio** en: captura de línea, ubicación, ajustes, empaque,
     * confirmación de orden y exportación al ERP. El móvil sin señal reintenta;
     * sin esta cabecera, un reintento duplica un movimiento.
     */
    const val IDEMPOTENCY_KEY = "Idempotency-Key"

    /** Correlación con la auditoría. Viaja en las dos direcciones. */
    const val REQUEST_ID = "X-Request-Id"

    /** `WEB`, `MOBILE` o `MOBILE_OFFLINE`. La auditoría registra el canal. */
    const val CLIENT_CHANNEL = "X-Client-Channel"

    /** Zona IANA. La usa el servidor para resolver la fecha de negocio. */
    const val CLIENT_TIMEZONE = "X-Client-Timezone"
}

/**
 * Genera el `X-Request-Id` de cada petición.
 *
 * Es inyectable para que los tests sean deterministas: un identificador aleatorio
 * en una aserción convierte el test en un test que falla una vez cada mil.
 */
fun interface RequestIdGenerator {
    fun next(): String
}

/**
 * Generador por defecto: un UUID versión 4 con [Random].
 *
 * Se implementa a mano en lugar de usar `java.util.UUID` porque el módulo es
 * multiplataforma: en Kotlin/Native no existe, y una `expect/actual` para
 * generar un identificador de correlación no aporta nada. Los dos bits de
 * versión y variante se fijan igualmente, para que el valor sea un UUID válido
 * y las herramientas de trazas lo acepten.
 */
object RandomRequestIdGenerator : RequestIdGenerator {

    private val hex = "0123456789abcdef".toCharArray()

    override fun next(): String {
        val bytes = Random.nextBytes(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // versión 4
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // variante RFC 4122

        val out = CharArray(36)
        var index = 0
        for (i in 0 until 16) {
            if (i == 4 || i == 6 || i == 8 || i == 10) out[index++] = '-'
            val value = bytes[i].toInt() and 0xFF
            out[index++] = hex[value shr 4]
            out[index++] = hex[value and 0x0F]
        }
        return out.concatToString()
    }
}

/**
 * Una respuesta **ya leída**.
 *
 * El cuerpo se lee entero antes de decidir: el refresh ante `401` necesita el
 * cuerpo del error para distinguir `TOKEN_REUSED` de un `401` cualquiera, y una
 * respuesta de Ktor solo se puede consumir una vez.
 */
class HttpResponseBody internal constructor(
    val status: Int,
    val text: String,
    val requestId: String?,
)

/**
 * Un archivo descargado (exportaciones de reportes).
 *
 * `fileName` sale del `Content-Disposition`; es lo que permite guardar el
 * archivo con el nombre que el servidor decidió, en lugar de inventarlo.
 */
class DownloadedFile internal constructor(
    val bytes: ByteArray,
    val fileName: String?,
    val contentType: String?,
)
