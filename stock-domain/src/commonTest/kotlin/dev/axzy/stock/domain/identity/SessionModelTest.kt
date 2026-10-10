package dev.axzy.stock.domain.identity

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DE LA ROTACIÓN DE TOKENS Y LA DETECCIÓN DE REUSO
 *
 * El test que más importa es el de **reuso fuera del margen de gracia**: es el
 * que distingue un sistema que de verdad detecta el robo de uno que solo dice
 * que rota tokens.
 *
 * Todos son puros: sin base de datos, sin servidor, sin criptografía. Corren en
 * milisegundos, que es lo único que hace viable cubrir la lógica de seguridad de
 * una sesión con tests exhaustivos.
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
            // Un refresh token de 29 días sigue siendo válido: su vida es de 30.
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
            // ÉSTE es el test central. Un refresh token usado dos veces significa
            // que hay dos partes con el mismo token, y una no es el usuario.
            //
            // No se puede distinguir de un reintento por fallo de red, así que se
            // asume lo peor: se revoca la familia y el usuario vuelve a entrar.
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
            // La rotación marca el token como rotado Y como revocado. Si se
            // comprobara la revocación primero, el reuso se reportaría como «token
            // revocado», que es un mensaje inofensivo y NO revocaría la familia.
            //
            // El orden de las comprobaciones es una decisión de seguridad, no un
            // detalle de implementación.
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
            // El cliente recibió el token nuevo pero la respuesta se perdió, así
            // que reintenta con el viejo. Sin este margen, un fallo de red
            // cerraría la sesión del usuario.
            //
            // Es la clase de detalle que, si falta, hace que la gente pida
            // desactivar la rotación porque «cierra sesiones sola».
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
            // El límite es exacto y el test lo fija: un cambio accidental de `<=`
            // a `<` o al revés abriría o cerraría la ventana sin que nadie lo note.
            val decision = decideRefresh(
                record(rotatedDoesMs = policy.rotationGraceMs + 1),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
        }

        it("a token rotated in the FUTURE is not accepted") {
            // Un `rotatedAt` futuro solo puede venir de un reloj mal, de datos
            // manipulados o de un desajuste entre servidores. Tratarlo como
            // reintento abriría una ventana de gracia sin límite.
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
            // La ventana es la misma para el atacante que para el reintento
            // legítimo, así que debe ser corta. Este test la fija.
            policy.rotationGraceMs shouldBe 30_000
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the cases that are NOT reuse and must not be confused with it") {

        it("a token that does not exist returns Unknown, not reuse") {
            // Confundirlo con reuso haría que un token inventado por un escáner
            // de puertos revocara la sesión de un usuario real.
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
            // Un usuario que vuelve después de 31 días no es un atacante: es un
            // usuario. Se le pide iniciar sesión de nuevo, sin alarmas.
            val decision = decideRefresh(
                record(issuedDoes = 31L * 24 * oneHour, expiresIn = -oneHour),
                now,
                policy,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Expired>()
        }

        it("a token that is expired AND revoked is reported as revoked") {
            // El orden: la revocación explícita es más informativa que el
            // vencimiento, porque dice que alguien actuó.
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
            // Si dos casos colapsaran en el mismo valor, el llamador no podría
            // decidir bien: por ejemplo, revocar la familia solo ante un reuso
            // real y no ante un token vencido.
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
            // 42 caracteres son menos de 256 bits: adivinable por fuerza bruta.
            RefreshToken.of("a".repeat(42)) shouldBe null
        }

        it("rejects a value with characters outside base64url") {
            // Una barra o un signo más indican base64 estándar, no base64url: si
            // aparecen, el valor no lo generó este sistema o viajó mal por una URL.
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
            // Un token en un log es un token que, si el log se filtra, da sesiones
            // válidas. La huella permite correlacionar sin exponer.
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
            // Si no, no serviría para refrescar: habría vencido antes.
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
            // Quien cambia su contraseña suele sospechar que está comprometida.
            // Dejar vivas las sesiones mantendría el acceso del atacante justo
            // cuando el usuario intenta cerrarlo.
            //
            // Preguntar «¿cerrar las demás sesiones?» es peor: en ese momento el
            // usuario no quiere decidir, quiere que se cierre.
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
