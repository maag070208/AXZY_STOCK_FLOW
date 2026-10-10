package dev.axzy.stock.domain.identity

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TOKEN ROTATION AND REUSE DETECTION TESTS
 *
 * The test that matters most is **reuse outside the grace window**: it is the
 * one that tells apart a system that really detects theft from one that only says
 * that it rotates tokens.
 *
 * They are all pure: no database, no server, no cryptography. They run in
 * milliseconds, which is the only thing that makes covering the security logic of
 * a session with exhaustive tests viable.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class SessionModelTest : DescribeSpec({

    val policy = SessionPolicy.DEFAULT
    val now = 1_700_000_000_000L
    val oneHour = 60 * 60 * 1000L

    fun record(
        id: Long = 1,
        family: String = "fam-1",
        user: Long = 7,
        issuedDoes: Long = oneHour,
        expiresIn: Long = 24 * oneHour,
        rotatedDoesMs: Long? = null,
        revokedDoesMs: Long? = null,
        reasonRevocation: RevocationReason? = null,
    ) = RefreshTokenRecord(
        id = TokenId(id),
        familyId = TokenFamilyId(family),
        userId = user,
        fingerprint = "abc123…xyz9",
        issuedAt = now - issuedDoes,
        expiresAt = now + expiresIn,
        rotatedAt = rotatedDoesMs?.let { now - it },
        replacedBy = rotatedDoesMs?.let { "abc999…new1" },
        revokedAt = revokedDoesMs?.let { now - it },
        revocationReason = reasonRevocation,
    )

    // ═════════════════════════════════════════════════════════════════════════
    describe("the normal case: a valid token is rotated") {

        it("a new, unused token is accepted to rotate") {
            val decision = decideRefresh(record(), now, policy)

            decision.shouldBeInstanceOf<RefreshDecision.Rotate>()
        }

        it("a token that has not expired yet is accepted even if it is old") {
            // A refresh token of 29 days is still valid: its lifetime is 30.
            val decision = decideRefresh(
                record(issuedDoes = 29L * 24 * oneHour, expiresIn = oneHour),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Rotate>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("REUSE DETECTION: the case that justifies rotation") {

        it("an already rotated token, outside the margin, is detected as reuse") {
            // THIS is the central test. A refresh token used twice means
            // there are two parties with the same token, and one of them is not the user.
            //
            // It cannot be told apart from a retry after a network failure, so
            // the worst is assumed: the family is revoked and the user logs in again.
            val decision = decideRefresh(
                record(rotatedDoesMs = 10 * 60 * 1000),   // rotado hace 10 minutos
                now,
                policy,
            )

            val reuse = decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
            withClue("Debe señalar el registro comprometido") {
                reuse.record.id shouldBe TokenId(1)
            }
        }

        it("reuse is detected even if the token is also revoked") {
            // Rotation marks the token as rotated AND as revoked. If the
            // revocation were checked first, reuse would be reported as "token
            // revoked", a harmless message that would NOT revoke the family.
            //
            // The order of the checks is a security decision, not an
            // implementation detail.
            val decision = decideRefresh(
                record(
                    rotatedDoesMs = 10 * 60 * 1000,
                    revokedDoesMs = 10 * 60 * 1000,
                    reasonRevocation = RevocationReason.REUSE_DETECTED,
                ),
                now,
                policy,
            )

            withClue("Debe reportarse como REUSO, no como «revocado»") {
                decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
            }
        }

        it("a token rotated long ago is also detected") {
            val decision = decideRefresh(
                record(rotatedDoesMs = 20L * 24 * oneHour, expiresIn = 10 * oneHour),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the grace window: avoids revoking sessions because of a network failure") {

        it("a token rotated WITHIN the margin is accepted: it is a retry") {
            // The client received the new token but the response was lost, so
            // it retries with the old one. Without this margin, a network failure
            // would log the user out.
            //
            // It is the kind of detail that, if missing, makes people ask to
            // turn rotation off because it "logs sessions out by itself".
            val decision = decideRefresh(
                record(rotatedDoesMs = 5_000),   // rotado hace 5 s, margen 30 s
                now,
                policy,
            )

            withClue("Dentro del margen debe tratarse como reintento, no como robo") {
                decision.shouldBeInstanceOf<RefreshDecision.Rotate>()
            }
        }

        it("right at the edge of the margin it is accepted") {
            val decision = decideRefresh(
                record(rotatedDoesMs = policy.rotationGraceMs),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Rotate>()
        }

        it("a millisecond outside the margin is already detected as reuse") {
            // The limit is exact and the test pins it: an accidental change from `<=`
            // to `<` or the other way round would open or close the window without anyone noticing.
            val decision = decideRefresh(
                record(rotatedDoesMs = policy.rotationGraceMs + 1),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
        }

        it("a token rotated in the FUTURE is not accepted") {
            // A future `rotatedAt` can only come from a wrong clock, from data
            // tampered with or from a mismatch between servers. Treating it as a
            // retry would open a grace window without limit.
            val decision = decideRefresh(
                record(rotatedDoesMs = -oneHour),   // rotado una hora en el futuro
                now,
                policy,
            )

            withClue("Un instante futuro no debe conceder el margen de gracia") {
                decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
            }
        }

        it("the grace window is short: 30 seconds") {
            // The window is the same for the attacker as for the
            // legitimate retry, so it must be short. This test pins it.
            policy.rotationGraceMs shouldBe 30_000
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the cases that are NOT reuse and must not be confused with it") {

        it("a token that does not exist returns Unknown, not reuse") {
            // Confusing it with reuse would let a token invented by a port scanner
            // revoke a real user's session.
            decideRefresh(null, now, policy) shouldBe RefreshDecision.Unknown
        }

        it("a token revoked by sign-out returns Revoked") {
            val decision = decideRefresh(
                record(revokedDoesMs = oneHour, reasonRevocation = RevocationReason.LOGOUT),
                now,
                policy,
            )

            val revoked = decision.shouldBeInstanceOf<RefreshDecision.Revoked>()
            withClue("El motivo debe conservarse para el diagnóstico") {
                revoked.record.revocationReason shouldBe RevocationReason.LOGOUT
            }
        }

        it("a token revoked by a password change returns Revoked") {
            val decision = decideRefresh(
                record(
                    revokedDoesMs = oneHour,
                    reasonRevocation = RevocationReason.PASSWORD_CHANGED,
                ),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Revoked>()
        }

        it("an expired token returns Expired, not reuse") {
            // A user who comes back after 31 days is not an attacker: they are a
            // user. They are asked to log in again, with no alarms.
            val decision = decideRefresh(
                record(issuedDoes = 31L * 24 * oneHour, expiresIn = -oneHour),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Expired>()
        }

        it("a token that is expired AND revoked is reported as revoked") {
            // The order: explicit revocation is more informative than the
            // expiry, because it says that someone acted.
            val decision = decideRefresh(
                record(
                    expiresIn = -oneHour,
                    revokedDoesMs = 2 * oneHour,
                    reasonRevocation = RevocationReason.ADMIN_REVOKED,
                ),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Revoked>()
        }

        it("the five results are distinguishable from one another") {
            // If two cases collapsed into the same value, the caller could not
            // decide well: for example, revoking the family only on a real
            // reuse and not on an expired token.
            val decisions = listOf(
                decideRefresh(record(), now, policy),
                decideRefresh(record(rotatedDoesMs = 10 * 60 * 1000), now, policy),
                decideRefresh(null, now, policy),
                decideRefresh(record(revokedDoesMs = oneHour), now, policy),
                decideRefresh(record(expiresIn = -oneHour), now, policy),
            )

            withClue("Cada caso debe producir un tipo distinto") {
                decisions.map { it::class.simpleName }.distinct().size shouldBe 5
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the refresh token is opaque") {

        it("accepts a value with the right length and alphabet") {
            val valid = "a".repeat(43)
            RefreshToken.of(valid)?.value shouldBe valid
        }

        it("rejects a value that is too short") {
            // 42 characters is less than 256 bits: guessable by brute force.
            RefreshToken.of("a".repeat(42)) shouldBe null
        }

        it("rejects a value with characters outside base64url") {
            // A slash or a plus sign indicates standard base64, not base64url: if
            // they appear, the value was not generated by this system or it travelled badly through a URL.
            listOf(
                "a".repeat(42) + "/",
                "a".repeat(42) + "+",
                "a".repeat(42) + "=",
                "a".repeat(42) + " ",
            ).forEach { invalid ->
                withClue("'$invalid' no es base64url y debe rechazarse") {
                    RefreshToken.of(invalid) shouldBe null
                }
            }
        }

        it("accepts hyphen and underscore, which are base64url") {
            val valid = "a-b_c" + "d".repeat(38)
            RefreshToken.of(valid)?.value shouldBe valid
        }

        it("rejects an absurdly long value") {
            RefreshToken.of("a".repeat(513)) shouldBe null
        }

        it("its representation does NOT reveal the full token") {
            // A token in a log is a token that, if the log leaks, gives away sessions
            // that are valid. The fingerprint allows correlating without exposing it.
            val token = RefreshToken.of("abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG")!!

            val text = token.toString()
            withClue("La representación debe ser una huella, no el valor: $text") {
                (text.contains(token.value)) shouldBe false
                (text.contains("abcdef")) shouldBe true
                (text.contains("DEFG")) shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the session policy") {

        it("the access token lives 15 minutes and the refresh 30 days") {
            val p = SessionPolicy.DEFAULT

            withClue("Un access token no se puede revocar, así que su vida es corta") {
                p.accessTokenTtlMs shouldBe 15 * 60 * 1000L
            }
            withClue("El refresh sí se revoca, así que puede ser largo") {
                p.refreshTokenTtlMs shouldBe 30L * 24 * 60 * 60 * 1000
            }
        }

        it("the refresh token must live longer than the access token") {
            // If not, it would be useless for refreshing: it would already have expired.
            val invalid = runCatching {
                SessionPolicy(
                    accessTokenTtlMs = 1000,
                    refreshTokenTtlMs = 500,
                    rotationGraceMs = 100,
                )
            }

            invalid.isFailure shouldBe true
        }

        it("the grace window cannot exceed the refresh token lifetime") {
            val invalid = runCatching {
                SessionPolicy(
                    accessTokenTtlMs = 1000,
                    refreshTokenTtlMs = 2000,
                    rotationGraceMs = 3000,
                )
            }

            invalid.isFailure shouldBe true
        }

        it("rejects an access token with a non-positive lifetime") {
            val invalid = runCatching {
                SessionPolicy(accessTokenTtlMs = 0, refreshTokenTtlMs = 1000, rotationGraceMs = 0)
            }

            invalid.isFailure shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("changing the password invalidates the sessions") {

        it("always, without asking the user") {
            // Whoever changes their password usually suspects it has been compromised.
            // Leaving the sessions alive would keep the attacker's access exactly
            // when the user is trying to close it.
            //
            // Asking "close the other sessions?" is worse: at that moment the
            // user does not want to decide, they want it closed.
            passwordChangeRevokesSessions() shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the record's state, with tabulated data") {

        withData(
            nameFn = { (revoked, rotated, expired, expected) ->
                "revocado=$revoked rotado=$rotated vencido=$expired → usable=$expected"
            },
            // (revocado, rotado, vencido, usable)
            listOf(false, false, false, true),
            listOf(false, false, true, false),
            listOf(false, true, false, false),
            listOf(true, false, false, false),
            listOf(true, true, true, false),
        ) { (revoked, rotated, expired, usableExpected) ->
            val r = RefreshTokenRecord(
                id = TokenId(1),
                familyId = TokenFamilyId("fam"),
                userId = 1,
                fingerprint = "huella",
                issuedAt = now - oneHour,
                expiresAt = if (expired) now - oneHour else now + oneHour,
                rotatedAt = if (rotated) now - 1000 else null,
                revokedAt = if (revoked) now - 1000 else null,
                revocationReason = if (revoked) RevocationReason.ADMIN_REVOKED else null,
            )

            r.isUsable(now) shouldBe usableExpected
        }
    }
})
