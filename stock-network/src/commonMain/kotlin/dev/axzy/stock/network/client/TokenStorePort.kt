package dev.axzy.stock.network.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL PUERTO DE TOKENS — inversión de dependencias
 *
 * `stock-network` **no sabe dónde se guardan los tokens**, y es deliberado:
 *  · Si supiera, tendría que depender de `stock-datastore` para guardarlos, y el
 *    grafo de módulos (§3.1) dice que `stock-datastore` no depende de nadie.
 *  · Y si los guardara él, el almacén sería el que le resultara cómodo, que casi
 *    siempre es un archivo de preferencias en claro. Un refresh token de 30 días
 *    en un archivo legible es una sesión regalada.
 *
 * Quien implementa este puerto es `stock-datastore` (Keychain / Keystore) a
 * través de un adaptador que escribe la app que une los dos módulos.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * La sesión tal como se persiste.
 *
 * Se guarda el **instante de caducidad** y no el `expiresIn` que devolvió el
 * servidor: un `expiresIn` guardado no dice nada después de reiniciar la app,
 * porque nadie sabe cuándo se emitió.
 */
data class StoredSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
) {
    /**
     * Indica si el access token ya no sirve.
     *
     * @param leewaySeconds margen para no usarlo justo cuando está a punto de
     *   vencer: una petición que sale con 2 segundos de vida llega caducada, y
     *   provoca un `401` que se podría haber evitado.
     */
    fun isExpired(nowEpochSeconds: Long, leewaySeconds: Long = DEFAULT_LEEWAY_SECONDS): Boolean =
        nowEpochSeconds + leewaySeconds >= expiresAtEpochSeconds

    companion object {
        /** Margen por defecto. Treinta segundos cubren una petición lenta con holgura. */
        const val DEFAULT_LEEWAY_SECONDS = 30L
    }
}

/** Dónde persiste el cliente sus tokens. Lo implementa `stock-datastore`. */
interface TokenStorePort {
    suspend fun load(): StoredSession?
    suspend fun save(session: StoredSession)
    suspend fun clear()
}

/**
 * Implementación en memoria.
 *
 * Existe para que el módulo se pueda usar y probar **sin** almacén de
 * plataforma, y es el default del cliente para que nadie acabe guardando tokens
 * en un archivo por no tener una alternativa a mano.
 *
 * **No sirve para producción en el móvil**: al cerrar la app se pierde la
 * sesión. Eso es preferible a persistirla mal.
 */
class InMemoryTokenStore(initial: StoredSession? = null) : TokenStorePort {

    private val mutex = Mutex()
    private var session: StoredSession? = initial

    override suspend fun load(): StoredSession? = mutex.withLock { session }

    override suspend fun save(session: StoredSession) {
        mutex.withLock { this.session = session }
    }

    override suspend fun clear() {
        mutex.withLock { session = null }
    }
}
