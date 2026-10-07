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

    val politica = SessionPolicy.DEFAULT
    val ahora = 1_700_000_000_000L
    val unaHora = 60 * 60 * 1000L

    fun registro(
        id: Long = 1,
        familia: String = "fam-1",
        usuario: Long = 7,
        emitidoHace: Long = unaHora,
        expiraEn: Long = 24 * unaHora,
        rotadoHaceMs: Long? = null,
        revocadoHaceMs: Long? = null,
        motivoRevocacion: RevocationReason? = null,
    ) = RefreshTokenRecord(
        id = TokenId(id),
        familyId = TokenFamilyId(familia),
        userId = usuario,
        fingerprint = "abc123…xyz9",
        issuedAt = ahora - emitidoHace,
        expiresAt = ahora + expiraEn,
        rotatedAt = rotadoHaceMs?.let { ahora - it },
        replacedBy = rotadoHaceMs?.let { "abc999…new1" },
        revokedAt = revocadoHaceMs?.let { ahora - it },
        revocationReason = motivoRevocacion,
    )

    // ═════════════════════════════════════════════════════════════════════════
    describe("el caso normal: un token válido se rota") {

        it("un token nuevo y sin usar se acepta para rotar") {
            val decision = decideRefresh(registro(), ahora, politica)

            decision.shouldBeInstanceOf<RefreshDecision.Rotate>()
        }

        it("un token que aún no venció se acepta aunque sea viejo") {
            // Un refresh token de 29 días sigue siendo válido: su vida es de 30.
            val decision = decideRefresh(
                registro(emitidoHace = 29L * 24 * unaHora, expiraEn = unaHora),
                ahora,
                politica,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Rotate>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("DETECCIÓN DE REUSO: el caso que justifica la rotación") {

        it("un token ya rotado, fuera del margen, se detecta como reuso") {
            // ÉSTE es el test central. Un refresh token usado dos veces significa
            // que hay dos partes con el mismo token, y una no es el usuario.
            //
            // No se puede distinguir de un reintento por fallo de red, así que se
            // asume lo peor: se revoca la familia y el usuario vuelve a entrar.
            val decision = decideRefresh(
                registro(rotadoHaceMs = 10 * 60 * 1000),   // rotado hace 10 minutos
                ahora,
                politica,
            )

            val reuso = decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
            withClue("Debe señalar el registro comprometido") {
                reuso.record.id shouldBe TokenId(1)
            }
        }

        it("el reuso se detecta aunque el token esté además revocado") {
            // La rotación marca el token como rotado Y como revocado. Si se
            // comprobara la revocación primero, el reuso se reportaría como «token
            // revocado», que es un mensaje inofensivo y NO revocaría la familia.
            //
            // El orden de las comprobaciones es una decisión de seguridad, no un
            // detalle de implementación.
            val decision = decideRefresh(
                registro(
                    rotadoHaceMs = 10 * 60 * 1000,
                    revocadoHaceMs = 10 * 60 * 1000,
                    motivoRevocacion = RevocationReason.REUSE_DETECTED,
                ),
                ahora,
                politica,
            )

            withClue("Debe reportarse como REUSO, no como «revocado»") {
                decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
            }
        }

        it("un token rotado hace mucho también se detecta") {
            val decision = decideRefresh(
                registro(rotadoHaceMs = 20L * 24 * unaHora, expiraEn = 10 * unaHora),
                ahora,
                politica,
            )

            decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el margen de gracia: evita revocar sesiones por un fallo de red") {

        it("un token rotado DENTRO del margen se acepta: es un reintento") {
            // El cliente recibió el token nuevo pero la respuesta se perdió, así
            // que reintenta con el viejo. Sin este margen, un fallo de red
            // cerraría la sesión del usuario.
            //
            // Es la clase de detalle que, si falta, hace que la gente pida
            // desactivar la rotación porque «cierra sesiones sola».
            val decision = decideRefresh(
                registro(rotadoHaceMs = 5_000),   // rotado hace 5 s, margen 30 s
                ahora,
                politica,
            )

            withClue("Dentro del margen debe tratarse como reintento, no como robo") {
                decision.shouldBeInstanceOf<RefreshDecision.Rotate>()
            }
        }

        it("justo en el límite del margen se acepta") {
            val decision = decideRefresh(
                registro(rotadoHaceMs = politica.rotationGraceMs),
                ahora,
                politica,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Rotate>()
        }

        it("un milisegundo fuera del margen ya se detecta como reuso") {
            // El límite es exacto y el test lo fija: un cambio accidental de `<=`
            // a `<` o al revés abriría o cerraría la ventana sin que nadie lo note.
            val decision = decideRefresh(
                registro(rotadoHaceMs = politica.rotationGraceMs + 1),
                ahora,
                politica,
            )

            decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
        }

        it("un token rotado en el FUTURO no se acepta") {
            // Un `rotatedAt` futuro solo puede venir de un reloj mal, de datos
            // manipulados o de un desajuste entre servidores. Tratarlo como
            // reintento abriría una ventana de gracia sin límite.
            val decision = decideRefresh(
                registro(rotadoHaceMs = -unaHora),   // rotado una hora en el futuro
                ahora,
                politica,
            )

            withClue("Un instante futuro no debe conceder el margen de gracia") {
                decision.shouldBeInstanceOf<RefreshDecision.ReuseDetected>()
            }
        }

        it("el margen de gracia es corto: 30 segundos") {
            // La ventana es la misma para el atacante que para el reintento
            // legítimo, así que debe ser corta. Este test la fija.
            politica.rotationGraceMs shouldBe 30_000
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("los casos que NO son reuso y no deben confundirse con él") {

        it("un token que no existe devuelve Unknown, no reuso") {
            // Confundirlo con reuso haría que un token inventado por un escáner
            // de puertos revocara la sesión de un usuario real.
            decideRefresh(null, ahora, politica) shouldBe RefreshDecision.Unknown
        }

        it("un token revocado por cierre de sesión devuelve Revoked") {
            val decision = decideRefresh(
                registro(revocadoHaceMs = unaHora, motivoRevocacion = RevocationReason.LOGOUT),
                ahora,
                politica,
            )

            val revocado = decision.shouldBeInstanceOf<RefreshDecision.Revoked>()
            withClue("El motivo debe conservarse para el diagnóstico") {
                revocado.record.revocationReason shouldBe RevocationReason.LOGOUT
            }
        }

        it("un token revocado por cambio de contraseña devuelve Revoked") {
            val decision = decideRefresh(
                registro(
                    revocadoHaceMs = unaHora,
                    motivoRevocacion = RevocationReason.PASSWORD_CHANGED,
                ),
                ahora,
                politica,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Revoked>()
        }

        it("un token vencido devuelve Expired, no reuso") {
            // Un usuario que vuelve después de 31 días no es un atacante: es un
            // usuario. Se le pide iniciar sesión de nuevo, sin alarmas.
            val decision = decideRefresh(
                registro(emitidoHace = 31L * 24 * unaHora, expiraEn = -unaHora),
                ahora,
                politica,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Expired>()
        }

        it("un token vencido Y revocado se reporta como revocado") {
            // El orden: la revocación explícita es más informativa que el
            // vencimiento, porque dice que alguien actuó.
            val decision = decideRefresh(
                registro(
                    expiraEn = -unaHora,
                    revocadoHaceMs = 2 * unaHora,
                    motivoRevocacion = RevocationReason.ADMIN_REVOKED,
                ),
                ahora,
                politica,
            )

            decision.shouldBeInstanceOf<RefreshDecision.Revoked>()
        }

        it("los cinco resultados son distinguibles entre sí") {
            // Si dos casos colapsaran en el mismo valor, el llamador no podría
            // decidir bien: por ejemplo, revocar la familia solo ante un reuso
            // real y no ante un token vencido.
            val decisiones = listOf(
                decideRefresh(registro(), ahora, politica),
                decideRefresh(registro(rotadoHaceMs = 10 * 60 * 1000), ahora, politica),
                decideRefresh(null, ahora, politica),
                decideRefresh(registro(revocadoHaceMs = unaHora), ahora, politica),
                decideRefresh(registro(expiraEn = -unaHora), ahora, politica),
            )

            withClue("Cada caso debe producir un tipo distinto") {
                decisiones.map { it::class.simpleName }.distinct().size shouldBe 5
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el refresh token es opaco") {

        it("acepta un valor con la longitud y el alfabeto correctos") {
            val valido = "a".repeat(43)
            RefreshToken.of(valido)?.value shouldBe valido
        }

        it("rechaza un valor demasiado corto") {
            // 42 caracteres son menos de 256 bits: adivinable por fuerza bruta.
            RefreshToken.of("a".repeat(42)) shouldBe null
        }

        it("rechaza un valor con caracteres fuera de base64url") {
            // Una barra o un signo más indican base64 estándar, no base64url: si
            // aparecen, el valor no lo generó este sistema o viajó mal por una URL.
            listOf(
                "a".repeat(42) + "/",
                "a".repeat(42) + "+",
                "a".repeat(42) + "=",
                "a".repeat(42) + " ",
            ).forEach { invalido ->
                withClue("'$invalido' no es base64url y debe rechazarse") {
                    RefreshToken.of(invalido) shouldBe null
                }
            }
        }

        it("acepta guion y guion bajo, que sí son base64url") {
            val valido = "a-b_c" + "d".repeat(38)
            RefreshToken.of(valido)?.value shouldBe valido
        }

        it("rechaza un valor absurdamente largo") {
            RefreshToken.of("a".repeat(513)) shouldBe null
        }

        it("su representación NO revela el token completo") {
            // Un token en un log es un token que, si el log se filtra, da sesiones
            // válidas. La huella permite correlacionar sin exponer.
            val token = RefreshToken.of("abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG")!!

            val texto = token.toString()
            withClue("La representación debe ser una huella, no el valor: $texto") {
                (texto.contains(token.value)) shouldBe false
                (texto.contains("abcdef")) shouldBe true
                (texto.contains("DEFG")) shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("la política de sesiones") {

        it("el access token vive 15 minutos y el refresh 30 días") {
            val p = SessionPolicy.DEFAULT

            withClue("Un access token no se puede revocar, así que su vida es corta") {
                p.accessTokenTtlMs shouldBe 15 * 60 * 1000L
            }
            withClue("El refresh sí se revoca, así que puede ser largo") {
                p.refreshTokenTtlMs shouldBe 30L * 24 * 60 * 60 * 1000
            }
        }

        it("el refresh token debe vivir más que el access token") {
            // Si no, no serviría para refrescar: habría vencido antes.
            val invalida = runCatching {
                SessionPolicy(
                    accessTokenTtlMs = 1000,
                    refreshTokenTtlMs = 500,
                    rotationGraceMs = 100,
                )
            }

            invalida.isFailure shouldBe true
        }

        it("el margen de gracia no puede superar la vida del refresh token") {
            val invalida = runCatching {
                SessionPolicy(
                    accessTokenTtlMs = 1000,
                    refreshTokenTtlMs = 2000,
                    rotationGraceMs = 3000,
                )
            }

            invalida.isFailure shouldBe true
        }

        it("rechaza un access token de vida no positiva") {
            val invalida = runCatching {
                SessionPolicy(accessTokenTtlMs = 0, refreshTokenTtlMs = 1000, rotationGraceMs = 0)
            }

            invalida.isFailure shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el cambio de contraseña invalida las sesiones") {

        it("siempre, sin preguntar al usuario") {
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
    describe("el estado del registro, con datos tabulados") {

        withData(
            nameFn = { (revocado, rotado, vencido, esperado) ->
                "revocado=$revocado rotado=$rotado vencido=$vencido → usable=$esperado"
            },
            // (revocado, rotado, vencido, usable)
            listOf(false, false, false, true),
            listOf(false, false, true, false),
            listOf(false, true, false, false),
            listOf(true, false, false, false),
            listOf(true, true, true, false),
        ) { (revocado, rotado, vencido, usableEsperado) ->
            val r = RefreshTokenRecord(
                id = TokenId(1),
                familyId = TokenFamilyId("fam"),
                userId = 1,
                fingerprint = "huella",
                issuedAt = ahora - unaHora,
                expiresAt = if (vencido) ahora - unaHora else ahora + unaHora,
                rotatedAt = if (rotado) ahora - 1000 else null,
                revokedAt = if (revocado) ahora - 1000 else null,
                revocationReason = if (revocado) RevocationReason.ADMIN_REVOKED else null,
            )

            r.isUsable(ahora) shouldBe usableEsperado
        }
    }
})
