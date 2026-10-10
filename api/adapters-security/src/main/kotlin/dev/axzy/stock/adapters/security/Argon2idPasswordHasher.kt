package dev.axzy.stock.adapters.security

import dev.axzy.stock.domain.identity.PasswordHasher
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

/**
 * Contraseñas con Argon2id, sobre la implementación de Spring Security. El hash es una cadena
 * estándar (`$argon2id$v=19$m=…,t=…,p=…$sal$hash`) que lleva sus propios parámetros, así que subir
 * el coste no invalida los hashes existentes: [needsRehash] dice cuáles conviene regenerar.
 *
 * Parámetros: 19 MiB de memoria, 2 iteraciones, 1 hilo. Es el mínimo que recomienda OWASP para
 * Argon2id; subir la memoria es lo que más encarece un ataque con GPU.
 */
class Argon2idPasswordHasher : PasswordHasher {

    private val encoder = Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS)

    override fun hash(password: String): String = encoder.encode(password)!!

    /** `false` también ante un hash con otro formato: una cadena que no es Argon2 no verifica nada. */
    override fun verify(password: String, hash: String): Boolean =
        runCatching { encoder.matches(password, hash) }.getOrDefault(false)

    override fun needsRehash(hash: String): Boolean =
        runCatching { encoder.upgradeEncoding(hash) }.getOrDefault(true)

    private companion object {
        const val SALT_BYTES = 16
        const val HASH_BYTES = 32
        const val PARALLELISM = 1
        const val MEMORY_KIB = 19_456
        const val ITERATIONS = 2
    }
}
