package dev.axzy.stock.domain.labeling

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ZPL TEMPLATE RESOLUTION (contract §8)
 *
 * A template stores ZPL with **placeholders** (`{{productCode}}`) and the data
 * arrive separately. They are substituted here.
 *
 * ── WHY THIS LIVES IN THE DOMAIN AND NOT IN THE PRINT LAYER ────────────
 *
 * **Three places** use it: the web preview, the mobile app's preview
 * —which can print without coverage— and the real sending to the Zebra. If each one
 * resolved the template in its own way, **the label printed from the mobile app
 * could come out different from the web's**, and the failure would appear in the warehouse
 * as an unreadable label, not in a test.
 *
 * ── THE RULE THAT MATTERS: A MISSING MANDATORY FIELD IS AN ERROR ───────
 *
 * A `{{productCode}}` placeholder without data is **neither left as is nor substituted**
 * with blank**. The two alternatives are worse:
 *
 *   · **Leaving it as is** prints `{{productCode}}` literally on the shelf label.
 *     It is seen, it is noticed, and it has to be reprinted.
 *   · **Substituting it with blank** prints a label that **looks correct and
 *     is not**: a gap where the code goes is not distinguishable from a badly designed template,
 *     and that label ends up stuck on a shelf.
 *
 * A typed failure is returned, and the caller decides: the preview shows the
 * error to the user and the sending to the printer enqueues nothing.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** A field declared by the template. */
data class LabelField(
    val name: String,
    val label: String,
    /** `string` or `number`. Decides whether the value is quoted in the ZPL. */
    val type: String = "string",
    val required: Boolean = false,
)

/** A label template. */
data class LabelTemplate(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    /** The ZPL with placeholders. */
    val zpl: String,
    /** Nominal size, `4x6`. */
    val size: String? = null,
    val fields: List<LabelField> = emptyList(),
    val isActive: Boolean = true,
) {

    /** The placeholders the template **actually uses**, without repeating. */
    val usedPlaceholders: List<String>
        get() = PLACEHOLDER.findAll(zpl).map { it.groupValues[1] }.distinct().toList()

    /**
     * Resolves the template with the data.
     *
     * @param data values by field name.
     */
    fun resolve(data: Map<String, String>): Either<DomainError, String> {
        // ── 1. The DECLARED mandatory fields must be present ─────────────────
        val faltantesDeclarados = fields
            .filter { it.required }
            .map { it.name }
            .filter { data[it].isNullOrBlank() }

        if (faltantesDeclarados.isNotEmpty()) {
            return DomainError.MissingLabelField(faltantesDeclarados).asLeft()
        }

        // ── 2. Every USED placeholder must have data ────────────
        //
        // This is the check that really protects: a template can use `{{quantity}}`
        // without having declared it in its `fieldSchema`, and then the
        // check above would not see it. The ZPL placeholders are checked, not
        // only the declared fields.
        val faltantesUsados = usedPlaceholders.filter { data[it].isNullOrBlank() }

        if (faltantesUsados.isNotEmpty()) {
            return DomainError.MissingLabelField(faltantesUsados).asLeft()
        }

        // ── 3. Substitution ─────────────────────────────────────────────────
        val resuelto = PLACEHOLDER.replace(zpl) { coincidencia ->
            val clave = coincidencia.groupValues[1]
            val valor = data.getValue(clave).trim()
            val campo = fields.firstOrNull { it.name == clave }

            // A numeric field goes WITHOUT quotes and a text one WITH them: the ZPL
            // distinguishes `^FD100^FS` from `^FD"ABRAA1B"^FS`, and extra quotes
            // print the quotes on the label.
            if (campo?.type == "number") valor else escaparZpl(valor)
        }

        return resuelto.asRight()
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}}""")
    }
}

/**
 * Escapes a value so that it fits in a `^FD...^FS`.
 *
 * In ZPL, `^`, `~` and `\` have control meaning: a product name with
 * a `^` —"TORNILLO ^M6"— would close the field too early and **the rest of the
 * name would be read as a command**, which can leave the label blank or change the
 * printer's configuration.
 */
private fun escaparZpl(valor: String): String = valor
    .replace("\\", "\\\\")
    .replace("^", "\\^")
    .replace("~", "\\~")

/**
 * The size of the label in millimeters.
 *
 * It is used so that the preview can draw the canvas with the correct
 * proportions **on the server**: if each client computed them, the web preview and
 * the mobile preview would show different sizes for the same label.
 * misma plantilla.
 */
data class LabelSize(val widthMm: Double, val heightMm: Double) {
    companion object {
        /** Interprets `4x6`, in inches, which is how labels are named. */
        fun parse(raw: String?): LabelSize {
            val limpio = raw?.trim()?.lowercase() ?: return DEFAULT
            val partes = limpio.split("x").map { it.trim() }
            if (partes.size != 2) return DEFAULT
            val ancho = partes[0].toDoubleOrNull() ?: return DEFAULT
            val alto = partes[1].toDoubleOrNull() ?: return DEFAULT
            if (ancho <= 0 || alto <= 0) return DEFAULT
            // Inches → millimeters. `4x6` is 101.6 × 152.4 mm, which is the size
            // of the contract's own example.
            return LabelSize(ancho * MM_PER_INCH, alto * MM_PER_INCH)
        }

        private const val MM_PER_INCH = 25.4
        val DEFAULT = LabelSize(4 * MM_PER_INCH, 6 * MM_PER_INCH)
    }
}
