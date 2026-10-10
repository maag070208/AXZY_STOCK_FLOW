package dev.axzy.stock.adapters.security

import dev.axzy.stock.domain.identity.PasswordHasher
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder

/**
 * Passwords with Argon2id, over the Spring Security implementation. The hash is a standard
 * string (`$argon2id$v=19$m=…,t=…,p=…$salt$hash`) that carries its own parameters, so raising
 * the cost does not invalidate the existing hashes: [needsRehash] says which ones should be regenerated.
 *
 * Parameters: 19 MiB of memory, 2 iterations, 1 thread. It is the minimum that OWASP recommends for
 * Argon2id; raising the memory is what most increases the cost of a GPU attack.
 */
class Argon2idPasswordHasher : PasswordHasher {

    private val encoder = Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, PARALLELISM, MEMORY_KIB, ITERATIONS)

    override fun hash(password: String): String = encoder.encode(password)!!

    /** `false` also for a hash in another format: a string that is not Argon2 verifies nothing. */
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
