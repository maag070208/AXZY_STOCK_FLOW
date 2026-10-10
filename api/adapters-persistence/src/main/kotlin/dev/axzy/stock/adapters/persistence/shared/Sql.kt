package dev.axzy.stock.adapters.persistence.shared

import java.net.InetAddress
import java.sql.ResultSet
import java.time.Instant
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
