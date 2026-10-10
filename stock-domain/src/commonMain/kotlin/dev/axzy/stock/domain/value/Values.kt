package dev.axzy.stock.domain.value

import kotlinx.serialization.Serializable

/**
 * Value objects of the domain.
 *
 * All of them validate in the constructor: **an invalid value object cannot be
 * built**. It is the first line of defense and the reason why the domain
 * can trust its own data without revalidating it.
 *
 * Reference: docs/historico/PLAN_ALMACEN_INTEGRAL.md §3.2-ter and §2.4.
 */

/** Identifier of a persisted entity. */
@kotlin.jvm.JvmInline
@Serializable
value class Id<T>(val value: Long) {
    init {
        require(value > 0) { "The id must be positive, got $value" }
    }
}

// ── Codes ───────────────────────────────────────────────────────────────

/**
 * Product code: the business identifier and also the barcode.
 *
 * Real alphabet verified against the inventory Excel (1,396 products):
 * `0-9` + `A-Z`, without Ñ, O or W; length 6 to 14; no lowercase or spaces.
 *
 * Normalization is **deterministic** and **strict**: if the code comes in
 * lowercase or with spaces, it is **rejected** — it is not corrected in silence. A
 * tolerant importer hides capture errors that later appear as miscounted stock.
 * stock mal contado.
 */
@kotlin.jvm.JvmInline
@Serializable
value class ProductCode private constructor(val value: String) {

    companion object {
        const val MAX_LENGTH = 20

        /** Real alphabet observed in production. */
        private val ALLOWED = Regex("^[0-9A-Z]+$")

        /**
         * Builds a product code by validating it.
         * @throws IllegalArgumentException if the code does not meet the format.
         */
        fun of(raw: String): ProductCode {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "The product code cannot be empty" }
            require(trimmed.length <= MAX_LENGTH) {
                "The product code exceeds $MAX_LENGTH characters: '$trimmed'"
            }
            require(trimmed.uppercase() == trimmed) {
                "The product code must be in uppercase: '$trimmed'. " +
                    "If it comes from a file, the row must be rejected, not silently normalized."
            }
            require(trimmed.none { it.isWhitespace() }) {
                "The product code cannot contain spaces: '$trimmed'"
            }
            require(ALLOWED.matches(trimmed)) {
                "The product code contains characters that are not allowed: '$trimmed'. " +
                    "Valid alphabet: 0-9 and A-Z"
            }
            return ProductCode(trimmed)
        }

        /**
         * Same as [of] but returns the error instead of throwing it.
         * It is the variant the importer uses for its per-row report.
         */
        fun validate(raw: String): Result<ProductCode> =
            runCatching { of(raw) }
    }

    override fun toString(): String = value
}

/**
 * Physical location code.
 *
 * Canonical format: `hall + code + position + <level letter>` in uppercase
 * and without separators. Real example from the legacy: `04` + `B` + `C` + `A` = `04BCA`.
 *
 * The legacy format is kept because **it is what is printed on the
 * shelves and on the labels**. Changing it would force relabeling the warehouse.
 */
@kotlin.jvm.JvmInline
@Serializable
value class LocationCode private constructor(val value: String) {

    companion object {
        const val MAX_LENGTH = 24

        /** The legacy's Spanish alphabet: includes Ñ, excludes O and W. */
        const val LEVEL_ALPHABET = "ABCDEFGHIJKLMNÑPQRSTUVXYZ"

        private val ALLOWED = Regex("^[0-9A-ZÑ]+$")

        fun of(raw: String): LocationCode {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "The location code cannot be empty" }
            require(trimmed.length <= MAX_LENGTH) {
                "The location code exceeds $MAX_LENGTH characters: '$trimmed'"
            }
            require(trimmed.uppercase() == trimmed) {
                "The location code must be in uppercase: '$trimmed'"
            }
            require(ALLOWED.matches(trimmed)) {
                "The location code contains characters that are not allowed: '$trimmed'"
            }
            return LocationCode(trimmed)
        }

        /**
         * Builds the location code from its parts.
         * @param level 0-based level index; it is converted to a letter of the Spanish alphabet.
         */
        fun of(hall: String, code: String, position: String, level: Int): LocationCode {
            require(level in LEVEL_ALPHABET.indices) {
                "Level $level is outside the alphabet (0..${LEVEL_ALPHABET.lastIndex})"
            }
            val prefix = (hall + code + position).trim().uppercase()
            require(prefix.isNotEmpty()) { "Aisle, locality and position cannot be empty" }
            return of(prefix + LEVEL_ALPHABET[level])
        }

        fun validate(raw: String): Result<LocationCode> = runCatching { of(raw) }
    }

    override fun toString(): String = value
}

/**
 * Code of the RECEIVING location (the old pseudo-location `ADMIN`).
 *
 * The legacy returned it hard-coded with a fictitious `id: 1`, without touching the
 * database, and that id could collide with the real location 1 (finding R-69).
 * Here it is a **real** location with `kind = RECEIVING`.
 */
object ReceivingLocation {
    const val CODE = "RECEIVING"
}

// ── Quantities and measures ────────────────────────────────────────────────

/**
 * Quantity of units, with 3 decimals.
 *
 * The legacy used `Int` in `Kardex.units` and `Decimal` in the details: two
 * representations for the same concept, and one of them unable to
 * represent fractions. Here there is **only one**.
 */
@kotlin.jvm.JvmInline
@Serializable
value class Quantity private constructor(val value: Long) {

    companion object {
        /** Fixed scale: 3 decimals. Internally represented as thousandths. */
        const val SCALE = 3
        private const val FACTOR = 1000L

        val ZERO = Quantity(0)

        /** Creates a quantity from whole units. */
        fun of(units: Long): Quantity = Quantity(units * FACTOR)

        /** Creates a quantity from an already scaled decimal value (thousandths). */
        fun ofScaled(scaled: Long): Quantity = Quantity(scaled)

        /**
         * Creates a quantity from a decimal text, with no tolerance.
         * Rejects non-numeric values instead of assuming 0.
         */
        fun parse(raw: String): Result<Quantity> = runCatching {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "The quantity cannot be empty" }

            val negative = trimmed.startsWith("-")
            val body = if (negative) trimmed.drop(1) else trimmed
            val parts = body.split('.')

            require(parts.size <= 2) { "Quantity with invalid format: '$raw'" }
            val integerPart = parts[0].ifEmpty { "0" }
            require(integerPart.all { it.isDigit() }) { "Non-numeric quantity: '$raw'" }

            val decimals = parts.getOrNull(1) ?: ""
            require(decimals.length <= SCALE) {
                "The quantity '$raw' has more than $SCALE decimals"
            }
            require(decimals.all { it.isDigit() }) { "Non-numeric quantity: '$raw'" }

            val padded = decimals.padEnd(SCALE, '0')
            val scaled = integerPart.toLong() * FACTOR + padded.toLong()
            Quantity(if (negative) -scaled else scaled)
        }
    }

    /** Integer part of the quantity. */
    val units: Long get() = value / FACTOR

    /** Remaining thousandths (the 3 decimals). */
    val thousandths: Long get() = value % FACTOR

    /** Indicates whether the quantity has a fraction. */
    val hasFraction: Boolean get() = thousandths != 0L

    val isZero: Boolean get() = value == 0L
    val isPositive: Boolean get() = value > 0L
    val isNegative: Boolean get() = value < 0L

    operator fun plus(other: Quantity): Quantity = Quantity(value + other.value)
    operator fun minus(other: Quantity): Quantity = Quantity(value - other.value)

    operator fun compareTo(other: Quantity): Int = value.compareTo(other.value)

    /** Readable decimal representation, without unnecessary trailing zeros. */
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
 * Quantity of boxes of a receiving line.
 * It is kept separate from [Quantity] because it has its own rules (it must be an integer
 * and `quantity / unitsPerBox` must divide exactly — decision R-80).
 */
@kotlin.jvm.JvmInline
@Serializable
value class BoxQuantity private constructor(val value: Long) {

    companion object {
        val ZERO = BoxQuantity(0)

        fun of(boxes: Long): BoxQuantity {
            require(boxes >= 0) { "The box quantity cannot be negative: $boxes" }
            return BoxQuantity(boxes)
        }
    }

    /**
     * Checks that the declared boxes **fit** in the declared quantity.
     *
     * It is a coherence validation, not an exact divisibility one: counting 80
     * pieces with boxes of 50 means "1 box and 30 loose", and that is valid.
     * What is not valid is declaring 2 boxes for 80 pieces, because 2 boxes of
     * 50 are 100 and the datum contradicts itself.
     */
    fun isConsistentWith(quantity: Quantity, unitsPerBox: UnitsPerBox): Boolean {
        val maximumBoxes = quantity.units / unitsPerBox.value
        return value <= maximumBoxes
    }

    override fun toString(): String = value.toString()
}

/**
 * Units per box.
 *
 * It is the field that in the legacy caused `Array(NaN)` → `RangeError` on the mobile
 * app when it was not numeric (finding R-48), and division by zero in the box
 * calculation. Here it **cannot be zero or negative**: the type prevents it.
 */
@kotlin.jvm.JvmInline
@Serializable
value class UnitsPerBox private constructor(val value: Long) {

    companion object {
        fun of(units: Long): UnitsPerBox {
            require(units > 0) {
                "Units per box must be greater than zero (got $units). " +
                    "A value of 0 causes division by zero in the box calculation."
            }
            return UnitsPerBox(units)
        }

        fun validate(units: Long): Result<UnitsPerBox> = runCatching { of(units) }
    }

    override fun toString(): String = value.toString()
}
