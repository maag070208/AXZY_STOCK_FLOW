package dev.axzy.stock.backend.identity

import dev.axzy.stock.backend.identity.NativeArgon2PasswordHasher.NativeArgon2Config
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DEL HASHER DE ARGON2ID SOBRE LA LIBRERÍA NATIVA
 *
 * **Estos tests son la prueba de que la decisión de escribir los bindings a
 * mano fue correcta.** El binding de `argon2-jvm` fallaba con
 * «Output is too short (-2)» en todas las configuraciones, y la librería nativa
 * llamada directamente devolvía 0. Si estos tests pasan, el camino nuevo
 * funciona; si fallaran, habría que buscar otra librería.
 *
 * Se usan los parámetros reducidos para que la suite tarde milisegundos. Un test
 * aparte comprueba que los de producción son los esperados.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class NativeArgon2PasswordHasherTest : DescribeSpec({

    val hasher = NativeArgon2PasswordHasher(NativeArgon2Config.FOR_TESTS)

    describe("el hash es correcto y tiene el formato estándar") {

        it("produce un hash válido: la librería nativa ya no falla") {
            // Éste es el test que antes fallaba con «Output is too short (-2)».
            val hash = hasher.hash("Bodega2026xyz")

            withClue("El hash no puede estar vacío ni ser un error: $hash") {
                (hash.isNotBlank()) shouldBe true
            }
        }

        it("el hash usa el formato PHC estándar con Argon2id") {
            // El formato estándar importa: permite que otras herramientas
            // —incluido `argon2` de línea de comandos— verifiquen estos hashes,
            // y que un futuro cambio de implementación siga siendo compatible.
            val hash = hasher.hash("Bodega2026xyz")

            withClue("Formato PHC con algoritmo, versión, parámetros, sal y hash: $hash") {
                hash shouldContain "\$argon2id\$"
                hash shouldContain "v=19"
                hash shouldContain "m=${NativeArgon2Config.FOR_TESTS.memoryKib}"
                hash shouldContain "t=${NativeArgon2Config.FOR_TESTS.iterations}"
                hash shouldContain "p=${NativeArgon2Config.FOR_TESTS.parallelism}"
            }
        }

        it("el hash NO contiene la contraseña") {
            // Lo más básico, y lo que el legacy incumplía: comparaba texto plano
            // y guardaba `admin` con `admin12345`.
            hasher.hash("Bodega2026xyz") shouldNotContain "Bodega2026xyz"
        }

        it("dos hashes de la misma contraseña son distintos: hay sal aleatoria") {
            // Sin sal, dos usuarios con la misma contraseña tendrían el mismo
            // hash: romper uno rompería los dos.
            val primero = hasher.hash("Bodega2026xyz")
            val segundo = hasher.hash("Bodega2026xyz")

            withClue("La sal debe hacer que difieran") {
                (primero != segundo) shouldBe true
            }
            withClue("Y los dos deben verificar la misma contraseña") {
                hasher.verify("Bodega2026xyz", primero) shouldBe true
                hasher.verify("Bodega2026xyz", segundo) shouldBe true
            }
        }

        it("la sal del hash tiene la longitud declarada") {
            // La sal se codifica en base64 sin relleno: 16 bytes son 22
            // caracteres. Verificarlo detecta un cambio accidental de longitud.
            val hash = hasher.hash("Bodega2026xyz")

            val sal = hash.split('$')[4]
            withClue("Una sal de 16 bytes son 22 caracteres base64, y son ${sal.length}") {
                sal.length shouldBe 22
            }
        }
    }

    describe("verificación") {

        it("acepta la contraseña correcta") {
            hasher.verify("Bodega2026xyz", hasher.hash("Bodega2026xyz")) shouldBe true
        }

        it("rechaza una contraseña incorrecta") {
            val hash = hasher.hash("Bodega2026xyz")

            withClue("Una letra de diferencia ya la invalida") {
                hasher.verify("Bodega2026xy", hash) shouldBe false
            }
            withClue("Y el cambio de mayúsculas también") {
                hasher.verify("BODEGA2026XYZ", hash) shouldBe false
            }
            hasher.verify("", hash) shouldBe false
        }

        it("distingue mayúsculas de minúsculas") {
            // Importa porque alguien podría pensar en normalizar «por comodidad
            // del usuario». Eso reduce el espacio de búsqueda del atacante.
            val hash = hasher.hash("Bodega2026xyz")

            hasher.verify("bodega2026xyz", hash) shouldBe false
        }

        it("acepta contraseñas con caracteres no ASCII") {
            // La contraseña se pasa como UTF-8. Si se usara la codificación por
            // defecto de la plataforma, una contraseña con acentos o emoji
            // funcionaría en una máquina y no en otra.
            listOf("contraseña2026x", "bodega-ñ-2026", "clave con emoji 🔐 x").forEach { contrasena ->
                val hash = hasher.hash(contrasena)
                withClue("'$contrasena' debe verificar correctamente") {
                    hasher.verify(contrasena, hash) shouldBe true
                }
            }
        }

        it("NO lanza con un hash mal formado: devuelve false") {
            // Un hash corrupto en la base no debe provocar un error 500 en el
            // endpoint de login: bloquear a ESE usuario es correcto, tumbar el
            // servicio no lo es.
            listOf(
                "",
                "   ",
                "no-es-un-hash",
                "\$argon2id\$v=19\$m=65536,t=3,p=1\$solesal\$",
                "\$argon2id\$v=19\$malformado\$corta",
                "a".repeat(1000),
            ).forEach { invalido ->
                withClue("Un hash inválido debe devolver false: '${invalido.take(40)}'") {
                    hasher.verify("Bodega2026xyz", invalido) shouldBe false
                }
            }
        }

        it("un hash con parámetros distintos sigue verificando") {
            // Propiedad clave: los hashes antiguos siguen siendo válidos aunque
            // los parámetros de coste hayan cambiado, porque `argon2_verify` lee
            // los parámetros del propio hash.
            val hashDebil = NativeArgon2PasswordHasher(
                NativeArgon2Config(iterations = 1, memoryKib = 8, parallelism = 1)
            ).hash("Bodega2026xyz")

            val hasherFuerte = NativeArgon2PasswordHasher(
                NativeArgon2Config(iterations = 2, memoryKib = 16, parallelism = 1)
            )

            withClue("El hash antiguo debe verificar con parámetros nuevos") {
                hasherFuerte.verify("Bodega2026xyz", hashDebil) shouldBe true
            }
        }
    }

    describe("needsRehash: endurecer el coste sin invalidar nada") {

        it("detecta un hash con parámetros más débiles") {
            val hashDebil = NativeArgon2PasswordHasher(
                NativeArgon2Config(iterations = 1, memoryKib = 8, parallelism = 1)
            ).hash("Bodega2026xyz")

            val hasherFuerte = NativeArgon2PasswordHasher(NativeArgon2Config.DEFAULT)

            withClue("Debe detectar que el hash usa menos coste del actual") {
                hasherFuerte.needsRehash(hashDebil) shouldBe true
            }
        }

        it("NO pide rehash si los parámetros coinciden") {
            hasher.needsRehash(hasher.hash("Bodega2026xyz")) shouldBe false
        }

        it("pide rehash ante un hash ilegible") {
            // La respuesta segura: fuerza a regenerarlo en el próximo inicio de
            // sesión en lugar de dejarlo como está.
            hasher.needsRehash("no-es-un-hash") shouldBe true
            hasher.needsRehash("") shouldBe true
            hasher.needsRehash("\$argon2id\$v=19\$sin-parametros") shouldBe true
        }

        it("pide rehash si la variante no es Argon2id") {
            // Cubre el caso de una migración desde Argon2i o Argon2d: esos
            // hashes no deben quedarse como están.
            val hasherQueDice = hasher
            withClue("Un hash de otra variante debe pedir rehash") {
                hasherQueDice.needsRehash(
                    "\$argon2i\$v=19\$m=8,t=1,p=1\$c2FsYWRlcHJ1ZWJhMTIzNDU2\$aGFzaGRlcHJ1ZWJh"
                ) shouldBe true
            }
        }

        it("lee correctamente los parámetros de un hash PHC") {
            val parametros = hasher.parsearParametros(
                "\$argon2id\$v=19\$m=65536,t=3,p=2\$c2Fs\$aGFzaA"
            )

            withClue("Debe extraer tipo, versión y los tres parámetros") {
                parametros shouldBe NativeArgon2PasswordHasher.ParametrosPhc(
                    tipo = "argon2id",
                    version = 19,
                    memoryKib = 65536,
                    iterations = 3,
                    parallelism = 2,
                )
            }
        }

        it("devuelve null ante un formato que no reconoce") {
            listOf(
                "",
                "no-es-phc",
                "\$argon2id\$v=19",
                "\$argon2id\$v=19\$m=abc,t=3,p=1\$sal\$hash",
                "\$argon2id\$sin-version\$m=8,t=1,p=1\$sal\$hash",
            ).forEach { raro ->
                withClue("'$raro' no debe parsearse") {
                    hasher.parsearParametros(raro) shouldBe null
                }
            }
        }

        it("permite el ciclo completo de endurecimiento de parámetros") {
            // Es el flujo real: el usuario entra, se verifica con el hash viejo y
            // se re-hashea con los parámetros nuevos.
            val hasherViejo = NativeArgon2PasswordHasher(
                NativeArgon2Config(iterations = 1, memoryKib = 8, parallelism = 1)
            )
            val hasherNuevo = NativeArgon2PasswordHasher(
                NativeArgon2Config(iterations = 3, memoryKib = 64, parallelism = 1)
            )

            val hashViejo = hasherViejo.hash("Bodega2026xyz")

            // 1. Verificar con el hash viejo.
            hasherNuevo.verify("Bodega2026xyz", hashViejo) shouldBe true
            // 2. Detectar que hay que regenerar.
            hasherNuevo.needsRehash(hashViejo) shouldBe true
            // 3. Re-hashear.
            val hashNuevo = hasherNuevo.hash("Bodega2026xyz")
            // 4. El nuevo no pide rehash y verifica.
            hasherNuevo.needsRehash(hashNuevo) shouldBe false
            hasherNuevo.verify("Bodega2026xyz", hashNuevo) shouldBe true
        }
    }

    describe("los parámetros de PRODUCCIÓN son los que deben ser") {

        it("usa 64 MiB y 3 iteraciones (RFC 9106)") {
            // Este test existe para que nadie baje los parámetros de producción
            // por accidente: los tests usan FOR_TESTS, así que sin esta
            // comprobación una reducción en DEFAULT no se notaría.
            val produccion = NativeArgon2Config.DEFAULT

            withClue("La memoria es lo que encarece el ataque con GPU") {
                produccion.memoryKib shouldBe 64 * 1024
            }
            withClue("Las iteraciones siguen la RFC 9106") {
                produccion.iterations shouldBe 3
            }
            withClue("Paralelismo 1: el coste debe ser predecible en el servidor") {
                produccion.parallelism shouldBe 1
            }
        }

        it("el coste real de producción es perceptible: los parámetros se aplican") {
            // Un hash que tarda 0 ms significa que los parámetros no se están
            // aplicando. No se fija un umbral estricto porque depende de la
            // máquina, pero sí que no sea instantáneo.
            val produccion = NativeArgon2PasswordHasher(NativeArgon2Config.DEFAULT)

            val inicio = System.nanoTime()
            produccion.hash("Bodega2026xyz")
            val ms = (System.nanoTime() - inicio) / 1_000_000

            withClue("El hash de producción tardó ${ms}ms; debe ser al menos 10ms") {
                (ms >= 10) shouldBe true
            }
        }

        it("el hash de producción declara los parámetros reales") {
            val hash = NativeArgon2PasswordHasher(
                NativeArgon2Config(iterations = 3, memoryKib = 64 * 1024, parallelism = 1)
            ).hash("Bodega2026xyz")

            withClue("Los parámetros del hash deben ser los de producción: $hash") {
                hash shouldContain "m=65536"
                hash shouldContain "t=3"
                hash shouldContain "p=1"
            }
        }
    }
})
