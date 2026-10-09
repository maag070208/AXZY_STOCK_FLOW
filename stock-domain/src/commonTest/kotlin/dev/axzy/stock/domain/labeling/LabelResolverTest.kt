package dev.axzy.stock.domain.labeling

import dev.axzy.stock.domain.error.DomainError
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL RESOLUTOR DE ZPL
 *
 * Lo que estas pruebas protegen no es el reemplazo de texto: es que **una etiqueta
 * incompleta no llegue nunca a la impresora**. Una etiqueta con
 * `{{productCode}}` literal, o con un hueco donde iba el código, acaba pegada en
 * un estante y alguien la lee para decidir qué hay dentro.
 *
 * Y protegen que un nombre con `^` no rompa el ZPL: en ZPL el `^` abre un comando,
 * así que un nombre de producto con ese carácter **cierra el campo antes de
 * tiempo y el resto del nombre se interpreta como instrucciones para la
 * impresora**, que puede quedarse en blanco o cambiar de configuración.
 *
 * Son pruebas PURAS: sin base, sin servidor y sin impresora.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class LabelResolverTest : DescribeSpec({

    /** Redondea a la micra: evita el ruido de la coma flotante al comparar. */
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

    describe("la sustitución") {

        it("sustituye los marcadores por sus datos") {
            val resuelto = template("^XA^FD{{productCode}}^FS^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA"))
                .getOrNull()!!

            resuelto shouldContain "ABRAA1B"
            resuelto shouldContain "ABRAZADERA"
            resuelto shouldNotContain "{{"
        }

        it("tolera espacios dentro del marcador: `{{ name }}` es el mismo campo") {
            // Una plantilla escrita a mano lleva espacios. Rechazarlos obligaría a
            // editar la plantilla para que el sistema la aceptara, y el error
            // aparecería al imprimir, no al guardarla.
            val resuelto = template("^XA^FD{{ name }}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "TORNILLO"))
                .getOrNull()!!

            resuelto shouldContain "TORNILLO"
        }

        it("recorta los espacios del valor: no se imprimen bordes en blanco") {
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "  TORNILLO  "))
                .getOrNull()!!

            resuelto shouldContain "TORNILLO"
            resuelto shouldNotContain "  TORNILLO"
        }

        it("un campo numérico va SIN comillas y un texto CON ellas") {
            // En ZPL el valor va entre `^FD` y `^FS`, y las comillas son parte del
            // contenido: ponerlas de más **las imprime en la etiqueta**.
            val resuelto = template("^XA^FD{{quantity}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "Y", "quantity" to "100"))
                .getOrNull()!!

            resuelto shouldContain "100"
            resuelto shouldNotContain "\"100\""
        }
    }

    describe("la regla que importa: NO se imprime una etiqueta incompleta") {

        it("un campo obligatorio que falta devuelve un fallo, no una etiqueta a medias") {
            val error = template()
                .resolve(mapOf("productCode" to "ABRAA1B"))
                .errorOrNull()

            error.shouldBeInstanceOf<DomainError.MissingLabelField>()
            (error as DomainError.MissingLabelField).code shouldBe "MISSING_LABEL_FIELD"
            error.fields shouldBe listOf("name")
        }

        it("un marcador USADO pero no declarado también se rechaza") {
            // **Este es el caso que la comprobación de campos declarados no vería.**
            // La plantilla usa `{{lote}}` y no lo declaró en su `fieldSchema`. Si
            // solo se miraran los declarados, `{{lote}}` se imprimiría literal en
            // la etiqueta.
            val sinDeclarar = template(
                zpl = "^XA^FD{{productCode}}^FS^FD{{lote}}^FS^XZ",
                fields = listOf(LabelField("productCode", "Código", required = true)),
            )

            val error = sinDeclarar.resolve(mapOf("productCode" to "ABRAA1B")).errorOrNull()

            error.shouldBeInstanceOf<DomainError.MissingLabelField>()
            (error as DomainError.MissingLabelField).fields shouldBe listOf("lote")
        }

        it("un valor en blanco cuenta como ausente, no como cadena vacía") {
            // Un campo con `"   "` es un campo sin rellenar. Sustituirlo dejaría un
            // hueco en la etiqueta que parece una plantilla mal hecha.
            val error = template()
                .resolve(mapOf("productCode" to "ABRAA1B", "name" to "   "))
                .errorOrNull()

            error.shouldBeInstanceOf<DomainError.MissingLabelField>()
        }

        it("el mensaje NOMBRA los campos que faltan") {
            // Sin los nombres, el usuario ve «faltan datos» y tiene que adivinar
            // cuáles de los ocho campos de la plantilla.
            val error = template()
                .resolve(mapOf("productCode" to "ABRAA1B"))
                .errorOrNull() as DomainError.MissingLabelField

            error.message shouldContain "name"
        }

        it("con TODOS los datos resuelve sin quejarse") {
            template()
                .resolve(mapOf("productCode" to "ABRAA1B", "name" to "ABRAZADERA"))
                .isRight shouldBe true
        }
    }

    describe("el escapado del ZPL, que puede estropear la impresora") {

        it("un `^` en el nombre no cierra el campo antes de tiempo") {
            // En ZPL, `^FS` cierra el campo y `^` abre CUALQUIER comando. Un nombre
            // con `^` sin escapar permite que el texto se interprete como
            // instrucciones: la etiqueta puede salir en blanco o la impresora
            // cambiar de configuración.
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "TORNILLO ^M6"))
                .getOrNull()!!

            resuelto shouldContain """TORNILLO \^M6"""
            // El `^FS` que cierra el campo sigue siendo UNO solo: el del nombre no
            // añadió otro.
            resuelto.split("^FS").size shouldBe 2
        }

        it("la `~` también se escapa: en ZPL abre comandos de impresora") {
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "~JR"))
                .getOrNull()!!

            resuelto shouldContain """\~JR"""
        }

        it("la barra invertida se escapa primero, para no duplicar escapes") {
            // El orden importa: si se escapara `^` antes que `\`, el escape que se
            // acaba de añadir se escaparía a su vez y la etiqueta mostraría barras.
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to """A\B"""))
                .getOrNull()!!

            resuelto shouldContain """A\\B"""
        }

        it("un nombre normal no se toca") {
            val resuelto = template("^XA^FD{{name}}^FS^XZ")
                .resolve(mapOf("productCode" to "X", "name" to "ABRAZADERA 1-5/8"))
                .getOrNull()!!

            // Los guiones, las barras y los puntos no son metacaracteres de ZPL.
            resuelto shouldContain "ABRAZADERA 1-5/8"
        }
    }

    describe("los marcadores que la plantilla usa") {

        it("`usedPlaceholders` los lista sin repetir") {
            val t = template("^XA^FD{{a}}^FS^FD{{b}}^FS^FD{{a}}^FS^XZ")

            t.usedPlaceholders shouldBe listOf("a", "b")
        }

        it("una plantilla sin marcadores no usa ninguno") {
            template("^XA^FDETIQUETA FIJA^FS^XZ").usedPlaceholders shouldBe emptyList()
        }
    }

    describe("el tamaño de la etiqueta") {

        it("`4x6` son 101,6 × 152,4 mm, como dice el ejemplo del contrato") {
            // Los números están en el propio contrato, así que son verificables.
            val tamano = LabelSize.parse("4x6")

            // **Se redondea a la micra, y no por comodidad: `6 * 25.4` da
            // `152.39999999999998` en coma flotante.** Comparar con `shouldBe`
            // exacto haría fallar la prueba por cómo representa el ordenador los
            // decimales, no por un error del código. Una micra es mucho más fina
            // que cualquier uso real del dato (el lienzo de una previsualización).
            redondear(tamano.widthMm) shouldBe 101.6
            redondear(tamano.heightMm) shouldBe 152.4
        }

        it("tolera espacios y mayúsculas: ` 4 X 6 `") {
            redondear(LabelSize.parse(" 4 X 6 ").widthMm) shouldBe 101.6
        }

        it("un tamaño ilegible cae al valor por defecto en vez de fallar") {
            // **Decisión deliberada.** Un tamaño mal escrito en la base no debe
            // impedir imprimir: el ZPL no depende de él, solo la previsualización.
            // Fallar aquí cambiaría un problema cosmético por uno operativo.
            listOf(null, "", "grande", "4", "4x", "0x6", "-1x2").forEach { entrada ->
                redondear(LabelSize.parse(entrada).widthMm) shouldBe 101.6
            }
        }
    }
})
