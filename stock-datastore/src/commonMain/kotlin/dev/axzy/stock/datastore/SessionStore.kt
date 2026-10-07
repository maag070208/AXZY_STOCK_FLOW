package dev.axzy.stock.datastore

import kotlinx.datetime.Clock

/**
 * La sesión tal como se persiste.
 *
 * Se guarda el **instante de caducidad**, no el `expiresIn` que devolvió el
 * servidor: un `expiresIn` guardado no dice nada después de reiniciar la app,
 * porque ya nadie sabe cuándo se emitió.
 */
data class StoredTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSeconds: Long,
) {
    /**
     * Indica si el access token ya no sirve.
     *
     * @param leewaySeconds margen para no usarlo justo cuando está a punto de
     *   vencer: una petición que sale con dos segundos de vida llega caducada y
     *   provoca un `401` que se podría haber evitado.
     */
    fun isExpired(nowEpochSeconds: Long, leewaySeconds: Long = DEFAULT_LEEWAY): Boolean =
        nowEpochSeconds + leewaySeconds >= expiresAtEpochSeconds

    companion object {
        const val DEFAULT_LEEWAY: Long = 30
    }
}

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA SESIÓN, EN EL ALMACÉN SEGURO
 *
 * Nota de diseño: los tres valores se guardan **en claves separadas** y no como
 * un JSON en una sola. El motivo es qué pasa si la escritura se interrumpe (la
 * app se cierra, el sistema mata el proceso): con un JSON, el archivo queda a
 * medias y **ilegible**, y no se puede distinguir «no hay sesión» de «la sesión
 * está corrupta». Con claves separadas, lo peor que ocurre es que falte la
 * caducidad, y eso se interpreta como «caducada» —el lado seguro— sin arrastrar
 * nada más.
 *
 * También evita añadir una dependencia de serialización a un módulo que solo
 * necesita guardar tres textos.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class SessionStore(
    private val secure: SecureStore,
    /** Reloj inyectable: la caducidad se prueba sin esperar quince minutos. */
    private val nowEpochSeconds: () -> Long = { Clock.System.now().epochSeconds },
) {

    suspend fun save(tokens: StoredTokens) {
        secure.put(CLAVE_ACCESS, tokens.accessToken)
        secure.put(CLAVE_REFRESH, tokens.refreshToken)
        secure.put(CLAVE_EXPIRA, tokens.expiresAtEpochSeconds.toString())
    }

    /**
     * Lee la sesión guardada.
     *
     * Devuelve `null` si falta cualquier pieza: un refresh token sin access token
     * no es una sesión, es un resto. Y si el instante de caducidad no es un
     * número, también.
     */
    suspend fun load(): StoredTokens? {
        val access = secure.get(CLAVE_ACCESS)?.takeIf { it.isNotBlank() } ?: return null
        val refresh = secure.get(CLAVE_REFRESH)?.takeIf { it.isNotBlank() } ?: return null
        val expira = secure.get(CLAVE_EXPIRA)?.toLongOrNull() ?: return null
        return StoredTokens(access, refresh, expira)
    }

    /** El access token, **solo si sigue siendo válido**. */
    suspend fun validAccessToken(): String? {
        val sesion = load() ?: return null
        return if (sesion.isExpired(nowEpochSeconds())) null else sesion.accessToken
    }

    /** El refresh token, que es lo que permite recuperar la sesión sin credenciales. */
    suspend fun refreshToken(): String? = load()?.refreshToken

    suspend fun clear() {
        secure.remove(CLAVE_ACCESS)
        secure.remove(CLAVE_REFRESH)
        secure.remove(CLAVE_EXPIRA)
    }

    private companion object {
        const val CLAVE_ACCESS = "access_token"
        const val CLAVE_REFRESH = "refresh_token"
        const val CLAVE_EXPIRA = "expires_at"
    }
}
