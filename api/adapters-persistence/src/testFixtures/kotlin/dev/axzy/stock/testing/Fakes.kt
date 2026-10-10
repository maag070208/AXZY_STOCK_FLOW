package dev.axzy.stock.testing

import dev.axzy.stock.domain.identity.PasswordHasher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Un hasher instantáneo para las pruebas que no tratan de criptografía. Argon2id es lento a
 * propósito, y usarlo en cada prueba de un flujo las haría tardar segundos sin probar nada más.
 * El hasher real se prueba en `adapters-security`.
 */
class FakePasswordHasher : PasswordHasher {
    override fun hash(password: String): String = "fake:$password"
    override fun verify(password: String, hash: String): Boolean = hash == "fake:$password"
    override fun needsRehash(hash: String): Boolean = false
}

/** Un reloj que solo avanza cuando la prueba lo mueve. */
class MutableClock(private var now: Instant = Instant.parse("2026-10-12T15:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
    fun set(instant: Instant) { now = instant }
}
