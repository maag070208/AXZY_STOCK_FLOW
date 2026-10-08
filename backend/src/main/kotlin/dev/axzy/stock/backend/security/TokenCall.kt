package dev.axzy.stock.backend.security

import io.ktor.server.application.ApplicationCall
import java.util.Base64

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * QUIÉN ESTÁ HACIENDO LA PETICIÓN
 *
 * El `sub` del JWT es el identificador del usuario. **Se lee sin volver a verificar
 * la firma**, y eso es correcto aquí por una razón concreta: todas las rutas que
 * usan esto llaman antes a `exigirPermiso`, que ya verificó el token. Volver a
 * verificar sería hacer el mismo trabajo dos veces por petición.
 *
 * **Vive en su propio archivo y no dentro de las rutas de inventario.** Antes estaba
 * privada allí y las rutas de recepción no podían usarla; la alternativa habría sido
 * copiarla, y dos copias de una lectura de token es exactamente la clase de
 * duplicación que hace que un día una de las dos se arregle y la otra no.
 *
 * Devuelve `null` si no hay cabecera, si no es `Bearer` o si el token está mal
 * formado. Quien llama decide qué hacer: las rutas lanzan 401.
 * ═══════════════════════════════════════════════════════════════════════════
 */
internal fun ApplicationCall.usuarioDelToken(): Long? {
    val cabecera = request.headers["Authorization"] ?: return null
    if (!cabecera.startsWith("Bearer ", ignoreCase = true)) return null

    val partes = cabecera.substring(7).trim().split('.')
    if (partes.size != 3) return null

    return runCatching {
        val payload = String(Base64.getUrlDecoder().decode(partes[1]))
        // `sub` es una cadena en el JWT aunque su contenido sea numérico: el RFC lo
        // define como StringOrURI. Por eso se busca con comillas.
        Regex("\"sub\":\"(\\d+)\"").find(payload)?.groupValues?.get(1)?.toLong()
    }.getOrNull()
}
