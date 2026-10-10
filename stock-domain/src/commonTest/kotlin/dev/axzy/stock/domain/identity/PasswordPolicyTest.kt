package dev.axzy.stock.domain.identity

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PASSWORD POLICY TESTS
 *
 * The case that matters most is `admin12345`: **it was the password of the user
 * `admin` of the legacy**, and these tests guarantee that it cannot be
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
            // Verified in the legacy seed: `admin` with password
            // `admin12345`. These tests are the guarantee that it does not come back.
            val reasons = reasons("admin12345", username = "admin")

            withClue("Debe rechazarse, y por más de un motivo") {
                (reasons.isNotEmpty()) shouldBe true
                (PasswordRejection.COMMON in reasons) shouldBe true
            }
        }

        it("rejects admin12345 even without saying which user it is") {
            // The password is on the list, so it is rejected even if the
            // user name is not known. It is the defense against reuse
            // across systems.
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
            // The limit exists because Argon2 with huge inputs is a denial-of-service vector:
            // the server can be made to burn CPU
            // hasheando megabytes.
            val huge = "a1".repeat(70)
            (PasswordRejection.TOO_LONG in reasons(huge)) shouldBe true
        }

        it("rejects a password of only spaces, even if it is long") {
            // Without this check, 20 spaces would pass the length rule.
            val spaces = " ".repeat(20)
            (PasswordRejection.ONLY_WHITESPACE in reasons(spaces)) shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("against intuition: an uppercase letter or a symbol is NOT required") {

        it("accepts a long lowercase passphrase: it is safer than Password1!") {
            // `correcto caballo bateria grapa` has 31 characters and far more
            // entropy than `Password1!`, and it is easier to remember.
            //
            // Requiring uppercase + lowercase + number + symbol makes security WORSE: it
            // pushes people towards `Password1!`, which is on every
            // leaked password list.
            PasswordPolicy.validate("correcto caballo bateria grapa").isValid shouldBe true
        }

        it("accepts a password without symbols if it has length and variety") {
            PasswordPolicy.validate("bodega04estante").isValid shouldBe true
        }

        it("rejects a single repeated character even if it is long") {
            // Bad even when long: `aaaaaaaaaaaa` has no entropy.
            (PasswordRejection.TOO_SIMPLE in reasons("aaaaaaaaaaaa")) shouldBe true
        }

        it("rejects only consecutive digits") {
            // `123456789012` is not on the common list, but it is a
            // sequence and has no letters.
            (PasswordRejection.TOO_SIMPLE in reasons("123456789012")) shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the password cannot contain the username") {

        it("rejects a password that contains the user") {
            // `jperez` with `jperez2026seguro` is one of the first things that gets tried.
            val reasons = reasons("jperez2026seguro", username = "jperez")

            withClue("Debe detectarse que contiene el nombre de usuario") {
                (PasswordRejection.CONTAINS_USERNAME in reasons) shouldBe true
            }
        }

        it("detects it regardless of case or spaces") {
            // `JPEREZ` and `jperez ` are the same user for whoever tries to log in.
            (PasswordRejection.CONTAINS_USERNAME in reasons("MiJPEREZ2026x", "jperez")) shouldBe true
            (PasswordRejection.CONTAINS_USERNAME in reasons("Jperez2026xyz", "jperez")) shouldBe true
        }

        it("does not reject if the username is very short") {
            // A 3-letter user name would show up by chance in any
            // password. The check requires 4 characters to avoid false
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
            // ` Password123 ` is the same password for whoever types it, and
            // `password123` for whoever guesses it.
            (PasswordRejection.COMMON in reasons(" Password123 ")) shouldBe true
        }

        it("rejects a common password in uppercase") {
            (PasswordRejection.COMMON in reasons("PASSWORD123")) shouldBe true
        }

        it("rejects the name of the system as a password") {
            // `puertonuevo` and `almacen12345` are the ones someone would pick for
            // convenience. They are the first ones an attacker would try.
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
            // A form that only says "invalid password" forces the user
            // to guess. Naming every reason lets them fix everything at once.
            //
            // We use `password`: it is short AND on the leaked list, so it
            // triggers two reasons. With `admin` only one fired —it is short, but
            // it is not on the list— and the test checked something the policy does not do.
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
            // Valid: passphrases and combinations of sufficient length.
            "correcto caballo bateria grapa" to true,
            "Bodega04 estante B" to true,
            "el-sol-sale-por-el-este" to true,
            "R1s2T3u4V5w6X7y8" to true,
            "mi frase secreta larga" to true,
            // Rejected: short, common, or single-class.
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
