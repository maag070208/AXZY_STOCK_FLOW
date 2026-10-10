package dev.axzy.stock.domain.labeling

import dev.axzy.stock.domain.common.Either
import dev.axzy.stock.domain.common.asLeft
import dev.axzy.stock.domain.common.asRight
import dev.axzy.stock.domain.error.DomainError

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RESOLUCIÓN DE PLANTILLAS ZPL (contrato §8)
 *
 * Una plantilla guarda ZPL con **marcadores** (`{{productCode}}`) y los datos
 * llegan por separado. Aquí se sustituyen.
 *
 * ── POR QUÉ ESTO VIVE EN EL DOMINIO Y NO EN LA CAPA DE IMPRESIÓN ───────────
 *
 * Lo usan **tres sitios**: la previsualización de la web, la de la app móvil
 * —que puede imprimir sin cobertura— y el envío real a la Zebra. Si cada uno
 * resolviera la plantilla a su manera, **la etiqueta impresa desde el móvil
 * podría salir distinta de la de la web**, y el fallo aparecería en el almacén
 * con una etiqueta ilegible, no en un test.
 *
 * ── LA REGLA QUE IMPORTA: UN CAMPO OBLIGATORIO QUE FALTA ES UN ERROR ───────
 *
 * Un marcador `{{productCode}}` sin dato **no se deja tal cual ni se sustituye
 * por vacío**. Las dos alternativas son peores:
 *
 *   · **Dejarlo tal cual** imprime literalmente `{{productCode}}` en la etiqueta
 *     del estante. Se ve, se nota y hay que reimprimir.
 *   · **Sustituirlo por vacío** imprime una etiqueta que **parece correcta y no
 *     lo está**: un hueco donde va el código no se distingue de una plantilla
 *     mal diseñada, y esa etiqueta acaba pegada en un estante.
 *
 * Se devuelve un fallo tipado, y quien llama decide: la previsualización enseña el
 * error al usuario y el envío a la impresora no encola nada.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Un campo declarado por la plantilla. */
data class LabelField(
    val name: String,
    val label: String,
    /** `string` o `number`. Decide si el valor se cita en el ZPL. */
    val type: String = "string",
    val required: Boolean = false,
)

/** Una plantilla de etiqueta. */
data class LabelTemplate(
    val id: Long,
    val code: String,
    val name: String,
    val description: String? = null,
    /** El ZPL con marcadores. */
    val zpl: String,
    /** Tamaño nominal, `4x6`. */
    val size: String? = null,
    val fields: List<LabelField> = emptyList(),
    val isActive: Boolean = true,
) {

    /** Los marcadores que la plantilla USA de verdad, sin repetir. */
    val usedPlaceholders: List<String>
        get() = PLACEHOLDER.findAll(zpl).map { it.groupValues[1] }.distinct().toList()

    /**
     * Resuelve la plantilla con los datos.
     *
     * @param data valores por nombre de campo.
     */
    fun resolve(data: Map<String, String>): Either<DomainError, String> {
        // ── 1. Los obligatorios DECLARADOS tienen que venir ─────────────────
        val faltantesDeclarados = fields
            .filter { it.required }
            .map { it.name }
            .filter { data[it].isNullOrBlank() }

        if (faltantesDeclarados.isNotEmpty()) {
            return DomainError.MissingLabelField(faltantesDeclarados).asLeft()
        }

        // ── 2. Todos los marcadores USADOS tienen que tener dato ────────────
        //
        // Esta es la comprobación que de verdad protege: una plantilla puede usar
        // `{{quantity}}` sin haberlo declarado en su `fieldSchema`, y entonces la
        // comprobación de arriba no lo vería. Se miran los marcadores del ZPL, no
        // solo los campos declarados.
        val faltantesUsados = usedPlaceholders.filter { data[it].isNullOrBlank() }

        if (faltantesUsados.isNotEmpty()) {
            return DomainError.MissingLabelField(faltantesUsados).asLeft()
        }

        // ── 3. La sustitución ──────────────────────────────────────────────
        val resuelto = PLACEHOLDER.replace(zpl) { coincidencia ->
            val clave = coincidencia.groupValues[1]
            val valor = data.getValue(clave).trim()
            val campo = fields.firstOrNull { it.name == clave }

            // Un campo numérico va SIN comillas y un texto CON ellas: el ZPL
            // distingue `^FD100^FS` de `^FD"ABRAA1B"^FS`, y comillas de más
            // imprimen las comillas en la etiqueta.
            if (campo?.type == "number") valor else escaparZpl(valor)
        }

        return resuelto.asRight()
    }

    private companion object {
        val PLACEHOLDER = Regex("""\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*}}""")
    }
}

/**
 * Escapa un valor para que quepa en un `^FD...^FS`.
 *
 * En ZPL, `^`, `~` y `\` tienen significado de control: un nombre de producto con
 * un `^` —«TORNILLO ^M6»— cerraría el campo antes de tiempo y **el resto del
 * nombre se interpretaría como un comando**, lo que puede dejar la etiqueta en
 * blanco o cambiar la configuración de la impresora.
 */
private fun escaparZpl(valor: String): String = valor
    .replace("\\", "\\\\")
    .replace("^", "\\^")
    .replace("~", "\\~")

/**
 * El tamaño de la etiqueta en milímetros.
 *
 * Se usa para que la previsualización pueda dibujar el lienzo con las
 * proporciones correctas **en el servidor**: si lo calculara cada cliente, la
 * vista previa de la web y la del móvil mostrarían tamaños distintos para la
 * misma plantilla.
 */
data class LabelSize(val widthMm: Double, val heightMm: Double) {
    companion object {
        /** Interpreta `4x6`, en pulgadas, que es como se nombran las etiquetas. */
        fun parse(raw: String?): LabelSize {
            val limpio = raw?.trim()?.lowercase() ?: return DEFAULT
            val partes = limpio.split("x").map { it.trim() }
            if (partes.size != 2) return DEFAULT
            val ancho = partes[0].toDoubleOrNull() ?: return DEFAULT
            val alto = partes[1].toDoubleOrNull() ?: return DEFAULT
            if (ancho <= 0 || alto <= 0) return DEFAULT
            // Pulgadas → milímetros. `4x6` son 101,6 × 152,4 mm, que es el tamaño
            // del ejemplo del propio contrato.
            return LabelSize(ancho * MM_PER_INCH, alto * MM_PER_INCH)
        }

        private const val MM_PER_INCH = 25.4
        val DEFAULT = LabelSize(4 * MM_PER_INCH, 6 * MM_PER_INCH)
    }
}
