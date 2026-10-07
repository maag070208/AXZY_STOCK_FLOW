package dev.axzy.stock.domain.value

import kotlinx.serialization.Serializable

/**
 * Objetos de valor del dominio.
 *
 * Todos validan en el constructor: **un objeto de valor inválido no se puede
 * construir**. Es la primera línea de defensa y la razón por la que el dominio
 * puede confiar en sus propios datos sin revalidar.
 *
 * Referencia: PLAN_ALMACEN_INTEGRAL.md §3.2-ter y §2.4.
 */

/** Identificador de una entidad persistida. */
@JvmInline
@Serializable
value class Id<T>(val value: Long) {
    init {
        require(value > 0) { "El id debe ser positivo, se recibió $value" }
    }
}

// ── Códigos ─────────────────────────────────────────────────────────────────

/**
 * Código de producto: el identificador de negocio y también el código de barras.
 *
 * Alfabeto real verificado contra el Excel de inventario (1.396 productos):
 * `0-9` + `A-Z`, sin Ñ, O ni W; longitud 6 a 14; sin minúsculas ni espacios.
 *
 * La normalización es **determinista** y **estricta**: si el código viene en
 * minúsculas o con espacios, se **rechaza** — no se corrige en silencio. Un
 * importador tolerante esconde errores de captura que después aparecen como
 * stock mal contado.
 */
@JvmInline
@Serializable
value class ProductCode private constructor(val value: String) {

    companion object {
        const val MAX_LENGTH = 20

        /** Alfabeto real observado en producción. */
        private val ALLOWED = Regex("^[0-9A-Z]+$")

        /**
         * Construye un código de producto validándolo.
         * @throws IllegalArgumentException si el código no cumple el formato.
         */
        fun of(raw: String): ProductCode {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "El código de producto no puede estar vacío" }
            require(trimmed.length <= MAX_LENGTH) {
                "El código de producto excede $MAX_LENGTH caracteres: '$trimmed'"
            }
            require(trimmed.uppercase() == trimmed) {
                "El código de producto debe estar en mayúsculas: '$trimmed'. " +
                    "Si viene de un archivo, la fila debe rechazarse, no normalizarse en silencio."
            }
            require(trimmed.none { it.isWhitespace() }) {
                "El código de producto no puede contener espacios: '$trimmed'"
            }
            require(ALLOWED.matches(trimmed)) {
                "El código de producto contiene caracteres no permitidos: '$trimmed'. " +
                    "Alfabeto válido: 0-9 y A-Z"
            }
            return ProductCode(trimmed)
        }

        /**
         * Igual que [of] pero devuelve el error en vez de lanzarlo.
         * Es la variante que usa el importador para su informe por fila.
         */
        fun validate(raw: String): Result<ProductCode> =
            runCatching { of(raw) }
    }

    override fun toString(): String = value
}

/**
 * Código de ubicación física.
 *
 * Formato canónico: `hall + code + position + <letra de nivel>` en mayúsculas
 * y sin separadores. Ejemplo real del legacy: `04` + `B` + `C` + `A` = `04BCA`.
 *
 * Se conserva el formato del legacy porque **es lo que está impreso en los
 * estantes y en las etiquetas**. Cambiarlo obligaría a reetiquetar el almacén.
 */
@JvmInline
@Serializable
value class LocationCode private constructor(val value: String) {

    companion object {
        const val MAX_LENGTH = 24

        /** Alfabeto español del legacy: incluye Ñ, excluye O y W. */
        const val LEVEL_ALPHABET = "ABCDEFGHIJKLMNÑPQRSTUVXYZ"

        private val ALLOWED = Regex("^[0-9A-ZÑ]+$")

        fun of(raw: String): LocationCode {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "El código de ubicación no puede estar vacío" }
            require(trimmed.length <= MAX_LENGTH) {
                "El código de ubicación excede $MAX_LENGTH caracteres: '$trimmed'"
            }
            require(trimmed.uppercase() == trimmed) {
                "El código de ubicación debe estar en mayúsculas: '$trimmed'"
            }
            require(ALLOWED.matches(trimmed)) {
                "El código de ubicación contiene caracteres no permitidos: '$trimmed'"
            }
            return LocationCode(trimmed)
        }

        /**
         * Construye el código de una ubicación a partir de sus partes.
         * @param level índice de nivel 0-based; se convierte en letra del alfabeto español.
         */
        fun of(hall: String, code: String, position: String, level: Int): LocationCode {
            require(level in LEVEL_ALPHABET.indices) {
                "El nivel $level está fuera del alfabeto (0..${LEVEL_ALPHABET.lastIndex})"
            }
            val prefix = (hall + code + position).trim().uppercase()
            require(prefix.isNotEmpty()) { "Pasillo, localidad y posición no pueden estar vacíos" }
            return of(prefix + LEVEL_ALPHABET[level])
        }

        fun validate(raw: String): Result<LocationCode> = runCatching { of(raw) }
    }

    override fun toString(): String = value
}

/**
 * Código de ubicación de RECEPCIÓN (la antigua pseudo-ubicación `ADMIN`).
 *
 * El legacy la devolvía hardcodeada con `id: 1` ficticio, sin tocar la base de
 * datos, y ese id podía colisionar con la ubicación real 1 (hallazgo R-69).
 * Aquí es una ubicación **real** con `kind = RECEIVING`.
 */
object ReceivingLocation {
    const val CODE = "RECEPCION"
}

// ── Cantidades y medidas ────────────────────────────────────────────────────

/**
 * Cantidad de unidades, con 3 decimales.
 *
 * El legacy usaba `Int` en `Kardex.units` y `Decimal` en los detalles: dos
 * representaciones para el mismo concepto, y una de ellas incapaz de
 * representar fracciones. Aquí hay **una sola**.
 */
@JvmInline
@Serializable
value class Quantity private constructor(val value: Long) {

    companion object {
        /** Escala fija: 3 decimales. Se representa internamente como milésimas. */
        const val SCALE = 3
        private const val FACTOR = 1000L

        val ZERO = Quantity(0)

        /** Crea una cantidad a partir de unidades enteras. */
        fun of(units: Long): Quantity = Quantity(units * FACTOR)

        /** Crea una cantidad desde un valor decimal ya escalado (milésimas). */
        fun ofScaled(scaled: Long): Quantity = Quantity(scaled)

        /**
         * Crea una cantidad desde un texto decimal, sin tolerancia.
         * Rechaza valores no numéricos en lugar de asumir 0.
         */
        fun parse(raw: String): Result<Quantity> = runCatching {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "La cantidad no puede estar vacía" }

            val negative = trimmed.startsWith("-")
            val body = if (negative) trimmed.drop(1) else trimmed
            val parts = body.split('.')

            require(parts.size <= 2) { "Cantidad con formato inválido: '$raw'" }
            val integerPart = parts[0].ifEmpty { "0" }
            require(integerPart.all { it.isDigit() }) { "Cantidad no numérica: '$raw'" }

            val decimals = parts.getOrNull(1) ?: ""
            require(decimals.length <= SCALE) {
                "La cantidad '$raw' tiene más de $SCALE decimales"
            }
            require(decimals.all { it.isDigit() }) { "Cantidad no numérica: '$raw'" }

            val padded = decimals.padEnd(SCALE, '0')
            val scaled = integerPart.toLong() * FACTOR + padded.toLong()
            Quantity(if (negative) -scaled else scaled)
        }
    }

    /** Parte entera de la cantidad. */
    val units: Long get() = value / FACTOR

    /** Milésimas restantes (los 3 decimales). */
    val thousandths: Long get() = value % FACTOR

    /** Indica si la cantidad tiene fracción. */
    val hasFraction: Boolean get() = thousandths != 0L

    val isZero: Boolean get() = value == 0L
    val isPositive: Boolean get() = value > 0L
    val isNegative: Boolean get() = value < 0L

    operator fun plus(other: Quantity): Quantity = Quantity(value + other.value)
    operator fun minus(other: Quantity): Quantity = Quantity(value - other.value)

    operator fun compareTo(other: Quantity): Int = value.compareTo(other.value)

    /** Representación decimal legible, sin ceros finales innecesarios. */
    override fun toString(): String {
        val sign = if (value < 0) "-" else ""
        val abs = if (value < 0) -value else value
        val int = abs / FACTOR
        val frac = abs % FACTOR
        return if (frac == 0L) "$sign$int"
        else "$sign$int.${frac.toString().padStart(SCALE, '0').trimEnd('0')}"
    }
}

/**
 * Cantidad de cajas de una línea de recepción.
 * Se mantiene separada de [Quantity] porque tiene reglas propias (debe ser entera
 * y `cantidad / unidadesPorCaja` debe dar exacto — decisión R-80).
 */
@JvmInline
@Serializable
value class BoxQuantity private constructor(val value: Long) {

    companion object {
        val ZERO = BoxQuantity(0)

        fun of(boxes: Long): BoxQuantity {
            require(boxes >= 0) { "La cantidad de cajas no puede ser negativa: $boxes" }
            return BoxQuantity(boxes)
        }
    }

    /**
     * Comprueba que las cajas declaradas **quepan** en la cantidad declarada.
     *
     * Es una validación de coherencia, no de divisibilidad exacta: contar 80
     * piezas con cajas de 50 significa "1 caja y 30 sueltas", y eso es válido.
     * Lo que no es válido es declarar 2 cajas para 80 piezas, porque 2 cajas de
     * 50 son 100 y el dato se contradice a sí mismo.
     */
    fun isConsistentWith(quantity: Quantity, unitsPerBox: UnitsPerBox): Boolean {
        val maximumBoxes = quantity.units / unitsPerBox.value
        return value <= maximumBoxes
    }

    override fun toString(): String = value.toString()
}

/**
 * Unidades por caja.
 *
 * Es el campo que en el legacy provocaba `Array(NaN)` → `RangeError` en el móvil
 * cuando no era numérico (hallazgo R-48), y división por cero en el cálculo de
 * cajas. Aquí **no puede ser cero ni negativo**: el tipo lo impide.
 */
@JvmInline
@Serializable
value class UnitsPerBox private constructor(val value: Long) {

    companion object {
        fun of(units: Long): UnitsPerBox {
            require(units > 0) {
                "Las unidades por caja deben ser mayores que cero (se recibió $units). " +
                    "Un valor 0 provoca división por cero en el cálculo de cajas."
            }
            return UnitsPerBox(units)
        }

        fun validate(units: Long): Result<UnitsPerBox> = runCatching { of(units) }
    }

    override fun toString(): String = value.toString()
}
