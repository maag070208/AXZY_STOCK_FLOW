package dev.axzy.stock.domain.identity

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DE LA POLÍTICA DE CONTRASEÑAS
 *
 * El caso que más importa es el de `admin12345`: **era la contraseña del usuario
 * `admin` del legacy**, y estos tests garantizan que no se pueda volver a
 * aceptar.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class PasswordPolicyTest : DescribeSpec({

    fun reasons(password: String, username: String? = null): List<PasswordRejection> {
        val result = PasswordPolicy.validate(password, username)
        return (result as? PasswordValidation.Invalid)?.rejections ?: emptyList()
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the legacy defect: admin12345 cannot be accepted") {

        it("rejects the password the legacy admin user had") {
            // Verificado en el seed del legacy: `admin` con contraseña
            // `admin12345`. Estos tests son la garantía de que no vuelve.
            val reasons = reasons("admin12345", username = "admin")

            withClue("Debe rechazarse, y por más de un motivo") {
                (reasons.isNotEmpty()) shouldBe true
                (PasswordRejection.COMMON in reasons) shouldBe true
            }
        }

        it("rejects admin12345 even without saying which user it is") {
            // La contraseña está en la lista, así que se rechaza aunque no se
            // sepa el nombre de usuario. Es la defensa contra la reutilización
            // entre sistemas.
            (PasswordRejection.COMMON in reasons("admin12345")) shouldBe true
        }

        it("rejects the obvious variants of admin") {
            listOf("admin123456", "administrador", "administrator").forEach { variant ->
                withClue("La variante '$variant' debe rechazarse") {
                    (PasswordRejection.COMMON in reasons(variant)) shouldBe true
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("length: the only thing that truly matters") {

        it("rejects fewer than 12 characters") {
            listOf("corta", "abc", "Stock2026!", "A1b2C3d4E5f").forEach { short ->
                withClue("'$short' tiene ${short.length} caracteres y debe rechazarse") {
                    (PasswordRejection.TOO_SHORT in reasons(short)) shouldBe true
                }
            }
        }

        it("accepts exactly 12 characters if it is not a common password") {
            val twelve = "Bodega2026xy"
            withClue("12 caracteres es el mínimo: '$twelve' debe aceptarse") {
                twelve.length shouldBe 12
                PasswordPolicy.validate(twelve).isValid shouldBe true
            }
        }

        it("rejects more than 128 characters") {
            // El límite existe porque Argon2 con entradas enormes es un vector de
            // denegación de servicio: se puede hacer que el servidor consuma CPU
            // hasheando megabytes.
            val huge = "a1".repeat(70)
            (PasswordRejection.TOO_LONG in reasons(huge)) shouldBe true
        }

        it("rejects a password of only spaces, even if it is long") {
            // Sin esta comprobación, 20 espacios pasaría la regla de longitud.
            val spaces = " ".repeat(20)
            (PasswordRejection.ONLY_WHITESPACE in reasons(spaces)) shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("against intuition: an uppercase letter or a symbol is NOT required") {

        it("accepts a long lowercase passphrase: it is safer than Password1!") {
            // `correcto caballo bateria grapa` tiene 31 caracteres y mucha más
            // entropía que `Password1!`, y es más fácil de recordar.
            //
            // Exigir mayúscula + minúscula + número + símbolo EMPEORA la
            // seguridad: empuja a la gente a `Password1!`, que está en todas las
            // listas de contraseñas filtradas.
            PasswordPolicy.validate("correcto caballo bateria grapa").isValid shouldBe true
        }

        it("accepts a password without symbols if it has length and variety") {
            PasswordPolicy.validate("bodega04estante").isValid shouldBe true
        }

        it("rejects a single repeated character even if it is long") {
            // Malo incluso siendo largo: `aaaaaaaaaaaa` no tiene entropía.
            (PasswordRejection.TOO_SIMPLE in reasons("aaaaaaaaaaaa")) shouldBe true
        }

        it("rejects only consecutive digits") {
            // `123456789012` no está en la lista de comunes, pero es una
            // secuencia y no tiene letras.
            (PasswordRejection.TOO_SIMPLE in reasons("123456789012")) shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the password cannot contain the username") {

        it("rejects a password that contains the user") {
            // `jperez` con `jperez2026seguro` es de lo primero que se prueba.
            val reasons = reasons("jperez2026seguro", username = "jperez")

            withClue("Debe detectarse que contiene el nombre de usuario") {
                (PasswordRejection.CONTAINS_USERNAME in reasons) shouldBe true
            }
        }

        it("detects it regardless of case or spaces") {
            // `JPEREZ` y `jperez ` son el mismo usuario para quien intenta entrar.
            (PasswordRejection.CONTAINS_USERNAME in reasons("MiJPEREZ2026x", "jperez")) shouldBe true
            (PasswordRejection.CONTAINS_USERNAME in reasons("Jperez2026xyz", "jperez")) shouldBe true
        }

        it("does not reject if the username is very short") {
            // Un usuario de 3 letras aparecería por casualidad en cualquier
            // contraseña. La comprobación exige 4 caracteres para evitar falsos
            // positivos molestos.
            val reasons = reasons("Bodega2026xyz", username = "abc")

            withClue("Un usuario de 3 letras no debe provocar un rechazo") {
                (PasswordRejection.CONTAINS_USERNAME in reasons) shouldBe false
            }
        }

        it("works without a username") {
            PasswordPolicy.validate("Bodega2026xyz", username = null).isValid shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("common passwords are rejected by their normalised form") {

        it("rejects a common password with spaces around it") {
            // ` Password123 ` es la misma contraseña para quien la escribe, y
            // `password123` para quien la adivina.
            (PasswordRejection.COMMON in reasons(" Password123 ")) shouldBe true
        }

        it("rejects a common password in uppercase") {
            (PasswordRejection.COMMON in reasons("PASSWORD123")) shouldBe true
        }

        it("rejects the name of the system as a password") {
            // `puertonuevo` y `almacen12345` son las que alguien pondría por
            // comodidad. Son las primeras que probaría un atacante.
            (PasswordRejection.COMMON in reasons("puertonuevo")) shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the result and its explanation") {

        it("a valid password returns Valid and a positive message") {
            val result = PasswordPolicy.validate("Bodega2026xyz")

            result.shouldBeInstanceOf<PasswordValidation.Valid>()
            withClue("El mensaje de una válida confirma, no advierte") {
                PasswordPolicy.explain(result) shouldBe "The password meets the requirements"
            }
        }

        it("the explanation names ALL the reasons, not only the first") {
            // Un formulario que solo dice «contraseña inválida» obliga al usuario
            // a adivinar. Nombrar todos los motivos le permite corregir de una vez.
            //
            // Se usa `password`: es corta Y está en la lista de filtradas, así que
            // dispara dos motivos. Con `admin` solo saltaba uno —es corta, pero no
            // está en la lista— y el test comprobaba algo que la política no hace.
            val result = PasswordPolicy.validate("password", username = "admin")

            val explanation = PasswordPolicy.explain(result)

            withClue("Debe mencionar la longitud y que es una contraseña común") {
                (explanation.contains("12 characters")) shouldBe true
                (explanation.contains("leaked")) shouldBe true
            }
        }

        it("the reasons are not repeated even when detected by two routes") {
            val result = PasswordPolicy.validate(" ", username = " ")

            val rejections = (result as PasswordValidation.Invalid).rejections
            withClue("Cada motivo debe aparecer una sola vez: $rejections") {
                rejections.size shouldBe rejections.distinct().size
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("varied cases with tabulated data") {

        withData(
            nameFn = { "«${it.first}» → ${if (it.second) "válida" else "rechazada"}" },
            // Válidas: frases y combinaciones con longitud suficiente.
            "correcto caballo bateria grapa" to true,
            "Bodega04 estante B" to true,
            "el-sol-sale-por-el-este" to true,
            "R1s2T3u4V5w6X7y8" to true,
            "mi frase secreta larga" to true,
            // Rechazadas: cortas, comunes o de una sola clase.
            "admin12345" to false,
            "password123" to false,
            "corta" to false,
            "aaaaaaaaaaaaaaaa" to false,
            "1111111111111111" to false,
            "                " to false,
            "puertonuevo" to false,
        ) { (password, expectedValid) ->
            PasswordPolicy.validate(password).isValid shouldBe expectedValid
        }
    }
})
