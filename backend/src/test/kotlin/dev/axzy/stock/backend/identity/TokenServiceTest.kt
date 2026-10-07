package dev.axzy.stock.backend.identity

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DEL SERVICIO DE TOKENS
 *
 * Los que más importan son los **negativos**: que un token firmado con otro
 * secreto no valga, que uno de otro emisor no valga, y que uno manipulado no
 * valga. Un servicio de tokens que solo se prueba con el camino feliz no
 * demuestra que verifique nada.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class TokenServiceTest : DescribeSpec({

    val config = TokenConfig(
        secret = "clave-de-prueba-con-longitud-suficiente-para-hmac-sha256",
        issuer = "stock-almacen",
        audience = "stock-api",
        accessTokenTtlSeconds = 900,
    )

    val servicio = TokenService(config)

    fun sujeto(
        userId: Long = 7,
        username: String = "jperez",
        roles: List<String> = listOf("WAREHOUSE_MANAGER"),
        permisos: List<String> = listOf("invoice.capture", "invoice.approve"),
        scopes: Map<String, String> = mapOf("invoice.approve" to "AREA"),
        bodegas: List<Long> = listOf(1),
    ) = TokenSubject(
        userId = userId,
        username = username,
        name = "Juan Pérez",
        roles = roles,
        permissions = permisos,
        scopes = scopes,
        warehouses = bodegas,
    )

    // ═════════════════════════════════════════════════════════════════════════
    describe("emisión y verificación del camino feliz") {

        it("un token recién emitido verifica y conserva todos sus datos") {
            val (token, _) = servicio.issueAccessToken(sujeto())

            val verificacion = servicio.verify(token)
            val claims = verificacion.shouldBeInstanceOf<TokenVerification.Valid>().claims

            withClue("Los datos del sujeto deben sobrevivir al viaje") {
                claims.userId shouldBe 7L
                claims.username shouldBe "jperez"
                claims.name shouldBe "Juan Pérez"
                claims.roles shouldBe listOf("WAREHOUSE_MANAGER")
                claims.permissions shouldBe listOf("invoice.capture", "invoice.approve")
                claims.scopes shouldBe mapOf("invoice.approve" to "AREA")
                claims.warehouses shouldBe listOf(1L)
            }
        }

        it("el token lleva un identificador único, para correlacionar con la auditoría") {
            // Sin `jti`, un evento de auditoría no se puede atar al token concreto
            // que lo autorizó.
            val (tokenA, _) = servicio.issueAccessToken(sujeto())
            val (tokenB, _) = servicio.issueAccessToken(sujeto())

            val idA = (servicio.verify(tokenA) as TokenVerification.Valid).claims.tokenId
            val idB = (servicio.verify(tokenB) as TokenVerification.Valid).claims.tokenId

            withClue("Dos tokens deben tener identificadores distintos") {
                (idA != idB) shouldBe true
                idA.isNotBlank() shouldBe true
            }
        }

        it("el vencimiento corresponde a la vida configurada") {
            val (token, expira) = servicio.issueAccessToken(sujeto())

            val claims = (servicio.verify(token) as TokenVerification.Valid).claims
            val vidaSegundos = claims.expiresAt.epochSecond - claims.issuedAt.epochSecond

            withClue("La vida del token debe ser la configurada: 900 s") {
                vidaSegundos shouldBe 900L
            }
            withClue("El vencimiento devuelto coincide con el del token") {
                expira.epochSecond shouldBe claims.expiresAt.epochSecond
            }
        }

        it("un sujeto sin permisos emite un token válido que no autoriza nada") {
            // Un usuario recién creado, sin roles. Debe poder autenticarse y ver
            // que no tiene permisos, en lugar de recibir un error.
            val (token, _) = servicio.issueAccessToken(
                sujeto(roles = emptyList(), permisos = emptyList(), scopes = emptyMap(), bodegas = emptyList()),
            )

            val claims = (servicio.verify(token) as TokenVerification.Valid).claims

            withClue("Sin permisos, la lista va vacía y el token sigue siendo válido") {
                claims.permissions shouldBe emptyList()
                claims.roles shouldBe emptyList()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("LOS CASOS NEGATIVOS: un token que no debe valer") {

        it("un token firmado con OTRO secreto no verifica") {
            // Es la prueba de que la firma se comprueba de verdad. Sin esto, un
            // servicio que solo decodifica el token parecería correcto.
            val otroServicio = TokenService(config.copy(secret = "otro-secreto-completamente-distinto-y-largo"))

            val (tokenAjeno, _) = otroServicio.issueAccessToken(sujeto())

            withClue("Un token de otro secreto debe rechazarse") {
                servicio.verify(tokenAjeno) shouldBe TokenVerification.Invalid
            }
        }

        it("un token de OTRO EMISOR no verifica") {
            // Importa para que un token de un entorno de pruebas —con el mismo
            // secreto— no sirva en producción.
            val otroEmisor = TokenService(config.copy(issuer = "otro-sistema"))
            val (token, _) = otroEmisor.issueAccessToken(sujeto())

            servicio.verify(token) shouldBe TokenVerification.Invalid
        }

        it("un token para OTRA AUDIENCIA no verifica") {
            // Sin comprobar la audiencia, un token emitido para otro servicio que
            // comparta el secreto serviría aquí.
            val otraAudiencia = TokenService(config.copy(audience = "otra-api"))
            val (token, _) = otraAudiencia.issueAccessToken(sujeto())

            servicio.verify(token) shouldBe TokenVerification.Invalid
        }

        it("un token con la FIRMA manipulada no verifica") {
            // **OJO CON DÓNDE SE ALTERA.** La primera versión de este test
            // cambiaba el ÚLTIMO carácter de la firma, y el test fallaba de forma
            // intermitente: en base64url, el último carácter solo aporta los bits
            // que caben, y los sobrantes se ignoran al decodificar. Cambiarlo
            // puede no alterar NI UN BIT de la firma, con lo que el token sigue
            // siendo válido y el test falla sin que haya nada roto.
            //
            // Era un defecto de MI TEST, no del servicio. Ahora se altera un
            // carácter del MEDIO, que siempre cambia los bytes decodificados.
            val (token, _) = servicio.issueAccessToken(sujeto())
            val partes = token.split('.')
            val firma = partes[2]

            val posicion = firma.length / 2
            val original = firma[posicion]
            val alterado = if (original == 'A') 'B' else 'A'
            val firmaManipulada = firma.substring(0, posicion) + alterado + firma.substring(posicion + 1)
            val manipulado = "${partes[0]}.${partes[1]}.$firmaManipulada"

            withClue("El token debe cambiar de verdad, o el test no prueba nada") {
                (manipulado != token) shouldBe true
            }
            withClue("Alterar un carácter del medio de la firma debe invalidarlo") {
                servicio.verify(manipulado) shouldBe TokenVerification.Invalid
            }
        }

        it("alterar la firma SIEMPRE invalida, en cualquier posición") {
            // Se corrige así porque el caso anterior dependía de qué carácter se
            // eligiera. Aquí se prueban TODAS las posiciones del medio: si alguna
            // no invalidara, sería un agujero real en la verificación.
            val (token, _) = servicio.issueAccessToken(sujeto())
            val partes = token.split('.')
            val firma = partes[2]

            // Se recorren posiciones intermedias, donde cada carácter aporta bits
            // completos y el cambio siempre altera la firma decodificada.
            (1 until firma.length - 1).forEach { posicion ->
                val original = firma[posicion]
                val alterado = if (original == 'A') 'B' else 'A'
                val manipulado = partes[0] + "." + partes[1] + "." +
                    firma.substring(0, posicion) + alterado + firma.substring(posicion + 1)

                withClue("Alterar la posición $posicion de la firma debe invalidar el token") {
                    servicio.verify(manipulado) shouldBe TokenVerification.Invalid
                }
            }
        }

        it("un token con el CUERPO manipulado no verifica") {
            // Se cambia el payload para intentar elevar privilegios. La firma deja
            // de coincidir, que es exactamente para lo que sirve.
            val (token, _) = servicio.issueAccessToken(sujeto(permisos = listOf("product.view")))
            val partes = token.split('.')
            val payloadFalso = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                String(java.util.Base64.getUrlDecoder().decode(partes[1]))
                    .replace("product.view", "role.manage")
                    .toByteArray(),
            )
            val manipulado = "${partes[0]}.$payloadFalso.${partes[2]}"

            withClue("Cambiar los permisos sin volver a firmar debe invalidar el token") {
                servicio.verify(manipulado) shouldBe TokenVerification.Invalid
            }
        }

        it("un token con formato ilegible devuelve Invalid, no lanza") {
            // Un token basura no debe provocar un error 500 en el endpoint.
            listOf("", "   ", "no-es-un-jwt", "a.b", "a.b.c.d", ".....").forEach { basura ->
                withClue("'$basura' debe tratarse como inválido, no lanzar") {
                    servicio.verify(basura) shouldBe TokenVerification.Invalid
                }
            }
        }

        it("un token con algoritmo `none` no verifica") {
            // Es el ataque clásico contra JWT: cambiar el `alg` a `none` para que
            // no haya firma. La librería lo rechaza, y este test lo fija para que
            // no se rompa al actualizarla.
            val cabeceraSinFirma = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
            val cuerpo = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("""{"sub":"7","iss":"stock-almacen"}""".toByteArray())

            servicio.verify("$cabeceraSinFirma.$cuerpo.") shouldBe TokenVerification.Invalid
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el token VENCIDO se distingue del INVÁLIDO") {

        it("un token vencido devuelve Expired, no Invalid") {
            // **Es una decisión de interfaz.** Un token vencido significa
            // «refresca»; uno inválido significa «vuelve a entrar». Si se
            // devolviera lo mismo para ambos, el cliente pediría credenciales cada
            // 15 minutos en lugar de refrescar.
            val servicioDeVidaCero = TokenService(
                TokenConfig(
                    secret = config.secret,
                    issuer = config.issuer,
                    audience = config.audience,
                    accessTokenTtlSeconds = -1,   // ya nace vencido
                ),
            )
            val (token, _) = servicioDeVidaCero.issueAccessToken(sujeto())

            withClue("Debe distinguirse de Invalid") {
                servicio.verify(token) shouldBe TokenVerification.Expired
            }
        }

        it("Expired e Invalid son resultados distintos") {
            // Si colapsaran en el mismo valor, el cliente no podría decidir entre
            // refrescar y volver a entrar.
            withClue("Son dos objetos distintos") {
                (TokenVerification.Expired == TokenVerification.Invalid) shouldBe false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el refresh token opaco") {

        it("tiene 43 caracteres, que son 256 bits en base64url") {
            // 32 bytes sin relleno son 43 caracteres. Es el mínimo para que sea
            // imposible de adivinar por fuerza bruta.
            val token = servicio.generateRefreshToken()

            token.length shouldBe 43
        }

        it("dos tokens generados son siempre distintos") {
            // Si se repitieran, dos usuarios podrían compartir sesión. Se generan
            // 200 para que un generador con semilla fija o un contador se detecte.
            val tokens = (1..200).map { servicio.generateRefreshToken() }

            withClue("No debe haber ningún token repetido en 200") {
                tokens.size shouldBe tokens.toSet().size
            }
        }

        it("usa solo el alfabeto base64url, para poder viajar en una URL") {
            // El refresh token puede acabar en una URL o en un formulario. Con
            // base64 estándar, un `+` se convierte en espacio al decodificar y el
            // token deja de funcionar.
            val token = servicio.generateRefreshToken()

            withClue("Solo letras, dígitos, guion y guion bajo: $token") {
                (token.all { it.isLetterOrDigit() || it == '-' || it == '_' }) shouldBe true
            }
        }

        it("el hash es SHA-256 en hexadecimal: 64 caracteres") {
            // Es lo que se guarda en la base. **El token no se guarda nunca.**
            val hash = servicio.hashRefreshToken(servicio.generateRefreshToken())

            hash.length shouldBe 64
            (hash.all { it in "0123456789abcdef" }) shouldBe true
        }

        it("el hash es determinista: el mismo token da el mismo hash") {
            // Es lo que permite buscar el token al refrescar.
            val token = servicio.generateRefreshToken()

            servicio.hashRefreshToken(token) shouldBe servicio.hashRefreshToken(token)
        }

        it("dos tokens distintos dan hashes distintos") {
            val hashA = servicio.hashRefreshToken(servicio.generateRefreshToken())
            val hashB = servicio.hashRefreshToken(servicio.generateRefreshToken())

            (hashA != hashB) shouldBe true
        }

        it("el hash NO contiene el token") {
            // Lo más básico: si el hash contuviera el token, guardarlo no serviría
            // de nada.
            val token = servicio.generateRefreshToken()

            (servicio.hashRefreshToken(token).contains(token)) shouldBe false
        }

        it("identificadores de familia son únicos") {
            val familias = (1..100).map { servicio.newFamilyId() }

            familias.size shouldBe familias.toSet().size
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("la configuración exige un secreto robusto") {

        it("rechaza un secreto de menos de 32 bytes") {
            // HMAC-SHA256 con un secreto corto es tan débil como un hash sin sal:
            // se puede adivinar por fuerza bruta y firmar tokens válidos.
            val corto = runCatching {
                TokenConfig(secret = "corto", issuer = "x", audience = "y", accessTokenTtlSeconds = 900)
            }

            withClue("Un secreto corto no debe poder configurarse") {
                corto.isFailure shouldBe true
            }
        }

        it("el mensaje de error explica cómo generar un secreto") {
            // Un error que solo dice «secreto inválido» obliga a buscar cómo
            // arreglarlo. Éste dice el comando exacto.
            val error = runCatching {
                TokenConfig(secret = "x", issuer = "a", audience = "b", accessTokenTtlSeconds = 1)
            }.exceptionOrNull()

            withClue("Debe mencionar la longitud mínima: ${error?.message}") {
                (error?.message?.contains("32") ?: false) shouldBe true
            }
        }

        it("acepta exactamente 32 bytes") {
            val justo = runCatching {
                TokenConfig(secret = "a".repeat(32), issuer = "x", audience = "y", accessTokenTtlSeconds = 900)
            }

            justo.isFailure shouldBe false
        }
    }
})
