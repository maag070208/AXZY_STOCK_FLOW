package dev.axzy.stock.domain.labeling

import dev.axzy.stock.domain.error.DomainError
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * THE ZPL RESOLVER
 *
 * What these tests protect is not the text replacement: it is that **an incomplete label
 * never reaches the printer**. A label with
 * a literal `{{productCode}}`, or with a gap where the code should be, ends up stuck on
 * a shelf and someone reads it to decide what is inside.
 *
 * And they protect against a name with `^` breaking the ZPL: in ZPL the `^` opens a command,
 * so a product name with that character **closes the field too early and
 * the rest of the name is read as instructions for the
 * printer**, which can come out blank or change its configuration.
 *
 * They are PURE tests: no database, no server and no printer.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LabelResolverTest : DescribeSpec({

    /** Rounds to the micron: avoids floating-point noise when comparing. */
    fun redondear(valor: Double): Double = kotlin.math.round(valor * 1000) / 1000

    fun template(
        zpl: String = "^XA^FD{{productCode}}^FS^XZ",
        fields: List<LabelField> = listOf(
            LabelField("productCode", "Código", required = true),
            LabelField("name", "Nombre", required = true),
            LabelField("quantity", "Cantidad", type = "number", required = false),
        ),
        size: String? = "4x6",
    ) = LabelTemplate(
        id = 1,
        code = "receipt",
        name = "Recibo",
        zpl = zpl,
        size = size,
        fields = fields,
    )

    describe("the substitution") {

        it("replaces the markers with their data") {
            val resuelto = template("^XA^FD{{productCode}}^FS^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA"))
                .getOrNull()!!

            resuelto shouldContain "ABRAA1B"
            resuelto shouldContain "ABRAZADERA"
            resuelto shouldNotContain "{{"
        }

        it("tolerates spaces inside the marker: `{{ name }}` is the same field") {
            // A template written by hand contains spaces. Rejecting them would force
            // editing the template so the system accepts it, and the error
            // would appear when printing, not when saving it.
            val resuelto = template("^XA^FD{{ name }}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "TORNILLO"))
                .getOrNull()!!

            resuelto shouldContain "TORNILLO"
        }

        it("trims the spaces of the value: no blank edges are printed") {
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "  TORNILLO  "))
                .getOrNull()!!

            resuelto shouldContain "TORNILLO"
            resuelto shouldNotContain "  TORNILLO"
        }

        it("a numeric field goes WITHOUT quotes and a text one WITH them") {
            // In ZPL the value goes between `^FD` and `^FS`, and the quotes are part of the
            // content: adding them in excess **prints them on the label**.
            val resuelto = template("^XA^FD{{quantity}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "Y", "quantity" to "100"))
                .getOrNull()!!

            resuelto shouldContain "100"
            resuelto shouldNotContain "\"100\""
        }
    }

    describe("the rule that matters: an incomplete label is NOT printed") {

        it("a missing required field returns a failure, not a half-made label") {
            val error = template()
                .resolve(mapOf("productCode" to "ABRAA1B"))
                .errorOrNull()

            error.shouldBeInstanceOf<DomainError.MissingLabelField>()
            (error as DomainError.MissingLabelField).code shouldBe "MISSING_LABEL_FIELD"
            error.fields shouldBe listOf("name")
        }

        it("a marker USED but not declared is also rejected") {
            // **This is the case that the check of declared fields would not see.**
            // The template uses `{{lote}}` and did not declare it in its `fieldSchema`. If
            // only the declared ones were checked, `{{lote}}` would be printed literally on
            // the label.
            val sinDeclarar = template(
                zpl = "^XA^FD{{productCode}}^FS^FD{{lote}}^FS^XZ",
                fields = listOf(LabelField("productCode", "Código", required = true)),
            )

            val error = sinDeclarar.resolve(mapOf("productCode" to "ABRAA1B")).errorOrNull()

            error.shouldBeInstanceOf<DomainError.MissingLabelField>()
            (error as DomainError.MissingLabelField).fields shouldBe listOf("lote")
        }

        it("a blank value counts as absent, not as an empty string") {
            // A field with `"   "` is an unfilled field. Replacing it would leave a
            // gap on the label that looks like a badly made template.
            val error = template()
                .resolve(mapOf("productCode" to "ABRAA1B", "name" to "   "))
                .errorOrNull()

            error.shouldBeInstanceOf<DomainError.MissingLabelField>()
        }

        it("the message NAMES the missing fields") {
            // Without the names, the user sees "missing data" and has to guess
            // which of the template's eight fields.
            val error = template()
                .resolve(mapOf("productCode" to "ABRAA1B"))
                .errorOrNull() as DomainError.MissingLabelField

            error.message shouldContain "name"
        }

        it("with ALL the data it resolves without complaint") {
            template()
                .resolve(mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA"))
                .isRight shouldBe true
        }
    }

    describe("the escaping of the ZPL, which can damage the printer") {

        it("a `^` in the name does not close the field early") {
            // In ZPL, `^FS` closes the field and `^` opens ANY command. A name
            // with an unescaped `^` lets the text be read as
            // instructions: the label may come out blank or the printer
            // change its configuration.
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "TORNILLO ^M6"))
                .getOrNull()!!

            resuelto shouldContain """TORNILLO \^M6"""
            // The `^FS` that closes the field is still ONE only: the name's did not
            // add another one.
            resuelto.split("^FS").size shouldBe 2
        }

        it("the `~` is also escaped: in ZPL it opens printer commands") {
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "~JR"))
                .getOrNull()!!

            resuelto shouldContain """\~JR"""
        }

        it("the backslash is escaped first, so as not to duplicate escapes") {
            // Order matters: if `^` were escaped before the backslash, the escape just added would be
            // escaped in turn and the label would show backslashes.
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to """A\B"""))
                .getOrNull()!!

            resuelto shouldContain """A\\B"""
        }

        it("a normal name is not touched") {
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "ABRAZADERA 1-5/8"))
                .getOrNull()!!

            // Hyphens, slashes and dots are not ZPL metacharacters.
            resuelto shouldContain "ABRAZADERA 1-5/8"
        }
    }

    describe("the markers the template uses") {

        it("`usedPlaceholders` lists them without repetition") {
            val t = template("^XA^FD{{a}}^FS^FD{{b}}^FS^FD{{a}}^FS^XZ")

            t.usedPlaceholders shouldBe listOf("a", "b")
        }

        it("a template without markers uses none") {
            template("^XA^FDETIQUETA FIJA^FS^XZ").usedPlaceholders shouldBe emptyList()
        }
    }

    describe("the size of the label") {

        it("`4x6` is 101.6 × 152.4 mm, as the contract example says") {
            // The numbers are in the contract itself, so they are verifiable.
            val tamano = LabelSize.parse("4x6")

            // **It is rounded to the micron, and not for convenience: `6 * 25.4` gives
            // `152.39999999999998` in floating point.** Comparing with an exact `shouldBe`
            // would make the test fail because of how the computer represents the
            // decimals, not because of a bug in the code. A micron is much finer
            // than any real use of the data (the canvas of a preview).
            redondear(tamano.widthMm) shouldBe 101.6
            redondear(tamano.heightMm) shouldBe 152.4
        }

        it("tolerates spaces and uppercase: ` 4 X 6 `") {
            redondear(LabelSize.parse(" 4 X 6 ").widthMm) shouldBe 101.6
        }

        it("an unreadable size falls back to the default instead of failing") {
            // **Deliberate decision.** A badly written size in the database must not
            // prevent printing: the ZPL does not depend on it, only the preview does.
            // Failing here would turn a cosmetic problem into an operational one.
            listOf(null, "", "grande", "4", "4x", "0x6", "-1x2").forEach { entrada ->
                redondear(LabelSize.parse(entrada).widthMm) shouldBe 101.6
            }
        }
    }
})
