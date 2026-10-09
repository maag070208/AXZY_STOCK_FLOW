package dev.axzy.stock.backend.infrastructure.outbound.persistence.shared

import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import javax.sql.DataSource

/*
 * Lo que toda la persistencia JDBC hace igual. Es el único sitio donde se escribe el patrón
 * de transacción del proyecto, para que no haya una variante por repositorio.
 */

/**
 * Ejecuta [block] en **una** transacción: `autoCommit = false`, commit al terminar, rollback
 * ante cualquier fallo.
 *
 * El rollback va en `runCatching`: si falla —la conexión ya estaba rota—, la excepción que
 * importa es la original, no la del rollback. El pool se construye con `autoCommit = false`
 * (`config/DatabaseConfig.kt`), así que una escritura sin commit explícito se pierde en silencio:
 * por eso toda escritura pasa por aquí.
 */
internal fun <T> DataSource.transactional(block: (Connection) -> T): T = connection.use { connection ->
    connection.autoCommit = false
    try {
        val result = block(connection)
        connection.commit()
        result
    } catch (failure: Exception) {
        runCatching { connection.rollback() }
        throw failure
    }
}

/** `setLong` que acepta nulo. `setObject(i, null)` sin tipo falla en algunas columnas. */
internal fun PreparedStatement.setNullableLong(index: Int, value: Long?) {
    if (value != null) setLong(index, value) else setNull(index, Types.BIGINT)
}

/**
 * `getLong` devuelve **0** para un NULL. Aquí el nulo es un dato —un movimiento del sistema no
 * tiene usuario— y hay que distinguirlo de «el usuario 0», que no existe.
 */
internal fun ResultSet.getLongOrNull(column: String): Long? {
    val value = getLong(column)
    return if (wasNull()) null else value
}

/**
 * La cantidad del dominio (entero escalado en milésimas) como `NUMERIC(14,3)`.
 *
 * La conversión es exacta y nunca pasa por `Double`: el `Decimal` contra `number` del legacy es
 * justo el origen de sus descuadres de redondeo.
 */
internal fun Quantity.toDecimal(): BigDecimal = BigDecimal.valueOf(value).movePointLeft(Quantity.SCALE)

/** Un `NUMERIC(14,3)` de la base como cantidad del dominio. */
internal fun BigDecimal.toQuantity(): Quantity = Quantity.ofScaled(movePointRight(Quantity.SCALE).toLong())

/**
 * ¿Es una dirección IP que PostgreSQL acepta en una columna `inet`?
 *
 * Existe porque `inet` rechaza nombres de host: `"localhost"` hacía fallar el inicio de sesión
 * con un error interno. Se valida para guardar `NULL` en lugar de perder el registro entero.
 * No resuelve DNS: `InetAddress.getByName` con un nombre sí lo haría.
 */
internal fun isInetAddress(value: String): Boolean {
    if (value.isBlank() || value == "localhost") return false
    return runCatching {
        val parts = value.split('.')
        if (parts.size == 4) {
            parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }
        } else {
            value.contains(':') && value.all { it.isDigit() || it in "abcdefABCDEF:" }
        }
    }.getOrDefault(false)
}
