package dev.axzy.stock.adapters.persistence.shared

import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal
import java.net.InetAddress
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** The PostgreSQL driver does not accept `Instant` as a parameter; it does accept `OffsetDateTime`. */
internal fun Instant.toSql(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

internal fun ResultSet.instant(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

internal fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

private val IP_LITERAL = Regex("^[0-9a-fA-F:.]{2,45}$")

/**
 * Returns the address only if it is a literal IP. The column is `INET`: a value that is not one
 * ("unknown", a host name) would make the insert fail, and an accessory client datum must
 * must not bring down the operation being recorded.
 */
internal fun inetOrNull(value: String?): String? {
    val candidate = value?.trim()?.takeIf { IP_LITERAL.matches(it) } ?: return null
    // With a literal IP, `getByName` does not query DNS: it only validates it.
    return runCatching { InetAddress.getByName(candidate).hostAddress }.getOrNull()
}

/**
 * PostgreSQL's `NUMERIC(14,3)` to the domain quantity, which is in thousandths.
 *
 * `null` (a row that does not exist, a `SUM` with no rows) is **zero**: in the inventory
 * "no row" and "zero" are the same thing, and forcing every query to
 * decide it would repeat the same `?:` everywhere.
 */
internal fun BigDecimal?.toQuantity(): Quantity =
    this?.setScale(Quantity.SCALE)?.movePointRight(Quantity.SCALE)?.longValueExact()?.let(Quantity::ofScaled)
        ?: Quantity.ZERO

/** The domain quantity to the `NUMERIC` that PostgreSQL expects. */
internal fun Quantity.toSql(): BigDecimal = BigDecimal.valueOf(value, Quantity.SCALE)

/**
 * The start of an **operating day**, in the business time zone.
 *
 * The zone is a mandatory parameter on purpose. An operating day is not a UTC day:
 * in `America/Mexico_City` it starts at 06:00 UTC, so a movement at 21:00 on the 9th at the
 * warehouse is from the 9th even though in UTC it is already the 10th. Interpreting it in UTC
 * leaves movements outside their own day, and the symptom is an empty report that looks
 * «no hubo movimientos».
 */
internal fun LocalDate.atStartOfDayIn(zone: ZoneId): OffsetDateTime =
    atStartOfDay(zone).toOffsetDateTime()
