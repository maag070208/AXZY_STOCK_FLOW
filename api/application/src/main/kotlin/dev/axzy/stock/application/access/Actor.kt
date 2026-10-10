package dev.axzy.stock.application.access

import dev.axzy.stock.domain.access.AccessUser

/**
 * Quién ejecuta un caso de uso. Llega como ARGUMENTO, nunca desde un contexto global: un caso de
 * uso que lee al usuario de una variable de hilo no se puede probar ni llamar desde un job.
 *
 * Se reconstruye de la base en cada petición. El token solo dice quién es; qué puede hacer se lee
 * en el momento, así que desactivar una cuenta o quitar un permiso surte efecto de inmediato.
 */
data class Actor(
    val user: AccessUser,
    val displayName: String,
    val mustChangePassword: Boolean = false,
) {
    val id: Long get() = user.id
    val username: String get() = user.username
    val roleKeys: List<String> get() = user.roles.map { it.key.value }
}
