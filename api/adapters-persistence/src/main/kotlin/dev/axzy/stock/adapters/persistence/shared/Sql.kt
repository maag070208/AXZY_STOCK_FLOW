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

/** El controlador de PostgreSQL no acepta `Instant` como parámetro; sí `OffsetDateTime`. */
internal fun Instant.toSql(): OffsetDateTime = OffsetDateTime.ofInstant(this, ZoneOffset.UTC)

internal fun ResultSet.instant(column: String): Instant? =
    getObject(column, OffsetDateTime::class.java)?.toInstant()

internal fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

private val IP_LITERAL = Regex("^[0-9a-fA-F:.]{2,45}$")

/**
 * Devuelve la dirección solo si es una IP literal. La columna es `INET`: un valor que no lo sea
 * («unknown», un nombre de host) haría fallar la inserción, y un dato accesorio del cliente no
 * debe tumbar la operación que se está registrando.
 */
internal fun inetOrNull(value: String?): String? {
    val candidate = value?.trim()?.takeIf { IP_LITERAL.matches(it) } ?: return null
    // Con una IP literal `getByName` no consulta el DNS: solo la valida.
    return runCatching { InetAddress.getByName(candidate).hostAddress }.getOrNull()
}

/**
 * `NUMERIC(14,3)` de PostgreSQL a la cantidad del dominio, que va en milésimas.
 *
 * `null` (una fila que no existe, un `SUM` sin filas) es **cero**: en el inventario
 * «no hay fila» y «hay cero» son la misma cosa, y obligar a cada consulta a
 * decidirlo sería repetir el mismo `?:` en todas.
 */
internal fun BigDecimal?.toQuantity(): Quantity =
    this?.setScale(Quantity.SCALE)?.movePointRight(Quantity.SCALE)?.longValueExact()?.let(Quantity::ofScaled)
        ?: Quantity.ZERO

/** La cantidad del dominio al `NUMERIC` que espera PostgreSQL. */
internal fun Quantity.toSql(): BigDecimal = BigDecimal.valueOf(value, Quantity.SCALE)

/**
 * El comienzo de un **día operativo**, en la zona del negocio.
 *
 * La zona es un parámetro obligatorio a propósito. Un día operativo no es un día UTC:
 * en `America/Mexico_City` empieza a las 06:00 UTC, así que un movimiento de las 21:00
 * del día 9 en el almacén es del día 9 aunque en UTC sea ya el 10. Interpretarlo en UTC
 * deja movimientos fuera de su propio día, y el síntoma es un reporte vacío que parece
 * «no hubo movimientos».
 */
internal fun LocalDate.atStartOfDayIn(zone: ZoneId): OffsetDateTime =
    atStartOfDay(zone).toOffsetDateTime()
