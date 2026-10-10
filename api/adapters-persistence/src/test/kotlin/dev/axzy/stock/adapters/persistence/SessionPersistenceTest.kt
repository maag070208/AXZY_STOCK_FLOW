package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.identity.JdbcAccessCatalogStore
import dev.axzy.stock.adapters.persistence.identity.JdbcLoginAttempts
import dev.axzy.stock.adapters.persistence.identity.JdbcRefreshTokens
import dev.axzy.stock.application.identity.BootstrapIdentity
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.audit.AuditChannel
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.identity.RevocationReason
import dev.axzy.stock.testing.FakePasswordHasher
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SessionPersistenceTest {

    private val db = TestPersistence()
    private val attempts = JdbcLoginAttempts(db.jdbc)
    private val tokens = JdbcRefreshTokens(db.jdbc)
    private val origin = RequestOrigin(AuditChannel.WEB, ip = "192.168.1.20", userAgent = "pruebas")
    private val now: Instant = Instant.parse("2026-10-12T15:00:00Z")
    private var userId = 0L

    @BeforeEach
    fun prepare() {
        TestDatabase.reset()
        BootstrapIdentity(JdbcAccessCatalogStore(db.jdbc, db.json), FakePasswordHasher(), "admin", "Almacen-Seguro-2026").execute()
        userId = db.jdbc.sql("SELECT id FROM users WHERE username = 'admin'").query(Long::class.java).single()
    }

    private fun hash(seed: String) = seed.padEnd(64, '0')
    private fun family() = UUID.randomUUID().toString()

    @Test
    fun `solo cuentan los fallos de credenciales recientes de ese nombre`() {
        attempts.record("jperez", null, succeeded = false, failureCode = "INVALID_CREDENTIALS", origin = origin, at = Instant.now())
        attempts.record("jperez", null, succeeded = false, failureCode = "INVALID_CREDENTIALS", origin = origin, at = Instant.now())
        // No cuentan: otro motivo, un acierto y otro usuario.
        attempts.record("jperez", null, succeeded = false, failureCode = "ACCOUNT_LOCKED", origin = origin, at = Instant.now())
        attempts.record("jperez", userId, succeeded = true, failureCode = null, origin = origin, at = Instant.now())
        attempts.record("otra", null, succeeded = false, failureCode = "INVALID_CREDENTIALS", origin = origin, at = Instant.now())

        assertThat(attempts.failedSince("jperez", Instant.now().minusSeconds(60))).isEqualTo(2)
        assertThat(attempts.failedSince("jperez", Instant.now().plusSeconds(60))).isZero()
    }

    @Test
    fun `un nombre de usuario desmesurado se registra recortado en lugar de fallar`() {
        attempts.record("x".repeat(500), null, succeeded = false, failureCode = "INVALID_CREDENTIALS", origin = origin, at = Instant.now())

        assertThat(attempts.failedSince("x".repeat(500), Instant.now().minusSeconds(60))).isEqualTo(1)
    }

    @Test
    fun `un token se guarda, se encuentra por su hash y conserva de donde se emitio`() {
        val familyId = family()
        tokens.store(hash("a"), userId, familyId, generation = 1, issuedAt = now, expiresAt = now.plusSeconds(3600), origin = origin)

        val stored = db.tx.inTransaction { tokens.findForUpdate(hash("a")).asRight() }.getOrNull()!!

        assertThat(stored.userId).isEqualTo(userId)
        assertThat(stored.familyId).isEqualTo(familyId)
        assertThat(stored.generation).isEqualTo(1)
        assertThat(stored.issuedAt).isEqualTo(now)
        assertThat(stored.expiresAt).isEqualTo(now.plusSeconds(3600))
        assertThat(stored.rotatedAt).isNull()
        assertThat(stored.revokedAt).isNull()
        assertThat(tokens.nextGeneration(familyId)).isEqualTo(2)
        assertThat(db.jdbc.sql("SELECT host(issued_ip) FROM refresh_tokens").query(String::class.java).single()).isEqualTo("192.168.1.20")
    }

    @Test
    fun `rotar un token anota por cual se reemplazo, una sola vez`() {
        tokens.store(hash("a"), userId, family(), 1, now, now.plusSeconds(3600), origin)

        tokens.markRotated(hash("a"), hash("b"), now)
        tokens.markRotated(hash("a"), hash("c"), now.plusSeconds(5))

        val stored = db.tx.inTransaction { tokens.findForUpdate(hash("a")).asRight() }.getOrNull()!!
        assertThat(stored.rotatedAt).isEqualTo(now)
        assertThat(stored.replacedByHash).isEqualTo(hash("b"))
    }

    @Test
    fun `revocar una familia cierra sus tokens vivos y deja intactos los ya rotados`() {
        val familyId = family()
        tokens.store(hash("a"), userId, familyId, 1, now, now.plusSeconds(3600), origin)
        tokens.markRotated(hash("a"), hash("b"), now)
        tokens.store(hash("b"), userId, familyId, 2, now, now.plusSeconds(3600), origin)
        tokens.store(hash("z"), userId, family(), 1, now, now.plusSeconds(3600), origin)

        val revoked = tokens.revokeFamily(familyId, RevocationReason.REUSE_DETECTED)

        assertThat(revoked).isEqualTo(1)
        val reasons = db.jdbc.sql("SELECT trim(token_hash) AS hash, revocation_reason::text AS reason FROM refresh_tokens ORDER BY id")
            .query { rs, _ -> rs.getString("hash").take(1) to rs.getString("reason") }.list()
        assertThat(reasons).containsExactly("a" to null, "b" to "REUSE_DETECTED", "z" to null)
    }

    @Test
    fun `cerrar sesion revoca la familia del token presentado, y repetirlo no falla`() {
        tokens.store(hash("a"), userId, family(), 1, now, now.plusSeconds(3600), origin)

        assertThat(tokens.revokeFamilyOf(hash("a"), RevocationReason.LOGOUT)).isEqualTo(1)
        assertThat(tokens.revokeFamilyOf(hash("a"), RevocationReason.LOGOUT)).isZero()
        assertThat(tokens.revokeFamilyOf(hash("no-existe"), RevocationReason.LOGOUT)).isZero()
    }

    @Test
    fun `revocar todo lo de un usuario cierra cada familia`() {
        tokens.store(hash("a"), userId, family(), 1, now, now.plusSeconds(3600), origin)
        tokens.store(hash("b"), userId, family(), 1, now, now.plusSeconds(3600), origin)

        assertThat(tokens.revokeAllForUser(userId, RevocationReason.PASSWORD_CHANGED)).isEqualTo(2)
        assertThat(tokens.revokeAllForUser(userId, RevocationReason.PASSWORD_CHANGED)).isZero()
    }

    @Test
    fun `dos renovaciones simultaneas del mismo token se atienden una tras otra`() {
        tokens.store(hash("a"), userId, family(), 1, now, now.plusSeconds(3600), origin)
        val firstHasTheRow = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit {
                db.tx.inTransaction {
                    tokens.findForUpdate(hash("a"))
                    firstHasTheRow.countDown()
                    Thread.sleep(400) // mantiene la fila bloqueada mientras la segunda la pide
                    tokens.markRotated(hash("a"), hash("b"), now)
                    Unit.asRight()
                }
            }
            val second = executor.submit<Instant?> {
                firstHasTheRow.await(5, TimeUnit.SECONDS)
                db.tx.inTransaction { tokens.findForUpdate(hash("a")).asRight() }.getOrNull()?.rotatedAt
            }
            first.get(10, TimeUnit.SECONDS)

            // La segunda esperó a la primera: ya ve el token rotado, y no lo rota «por primera vez».
            assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(now)
        } finally {
            executor.shutdownNow()
        }
    }
}
