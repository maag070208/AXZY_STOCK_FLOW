package dev.axzy.stock.testing

import dev.axzy.stock.domain.identity.PasswordHasher
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * An instant hasher for the tests that are not about cryptography. Argon2id is slow on
 * purpose, and using it in every test of a flow would make them take seconds without testing anything else.
 * The real hasher is tested in `adapters-security`.
 */
class FakePasswordHasher : PasswordHasher {
    override fun hash(password: String): String = "fake:$password"
    override fun verify(password: String, hash: String): Boolean = hash == "fake:$password"
    override fun needsRehash(hash: String): Boolean = false
}

/** A clock that only advances when the test moves it. */
class MutableClock(private var now: Instant = Instant.parse("2026-10-12T15:00:00Z")) : Clock() {
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
    fun advanceSeconds(seconds: Long) { now = now.plusSeconds(seconds) }
    fun set(instant: Instant) { now = instant }
}
