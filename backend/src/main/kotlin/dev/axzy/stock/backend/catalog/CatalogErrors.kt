package dev.axzy.stock.backend.catalog

import dev.axzy.stock.backend.error.ConflictException
import dev.axzy.stock.backend.error.StockException
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.postgresql.util.PSQLException
import java.sql.SQLException

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TRADUCCIÓN DE ERRORES DE POSTGRESQL
 *
 * **La unicidad la impone la base de datos, no el código.** El prechequeo del
 * servicio («¿existe el código?») tiene una carrera evidente: dos peticiones
 * simultáneas con el mismo código pasan las dos la comprobación y la segunda
 * choca contra el índice único. Traducir ese choque a un 409 con el código del
 * contrato es lo que hace que la carrera termine en la respuesta correcta en
 * lugar de en un 500.
 *
 * Es la corrección del defecto del legacy, donde el error de Prisma llegaba al
 * cliente con el SQL y el nombre de la tabla dentro.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** `unique_violation` de PostgreSQL. */
private const val SQLSTATE_UNIQUENESS = "23505"

/**
 * Una violación de unicidad traducida al contrato, o `null` si el error es otro.
 *
 * El nombre de la restricción se busca **por fragmento** y no por igualdad: los
 * nombres de los índices únicos los genera PostgreSQL (`products_product_code_key`,
 * `locations_warehouse_id_hall_code_position_key`…) y depender del nombre exacto
 * ataría el código al esquema. El fragmento sí es estable: habla de la columna.
 */
internal fun translateUniqueViolation(error: Throwable): StockException? {
    if (error.sqlState() != SQLSTATE_UNIQUENESS) return null

    val constraint = error.constraintViolated().orEmpty()

    return when {
        constraint.contains("external_id") -> ConflictException(
            code = "DUPLICATE_EXTERNAL_ID",
            message = "Ya existe un producto con ese identificador del ERP. " +
                "El identificador externo es único para que la referencia cruzada " +
                "no apunte a dos productos.",
        )

        constraint.contains("product_code") -> ConflictException(
            code = "DUPLICATE_PRODUCT_CODE",
            message = "Ya existe un producto con ese código.",
        )

        constraint.contains("hall") || constraint.contains("locations") -> ConflictException(
            code = "DUPLICATE_LOCATION",
            message = "Ya existe una ubicación con ese pasillo, localidad y posición " +
                "en esa bodega.",
        )

        constraint.contains("warehouses") -> ConflictException(
            code = "DUPLICATE_WAREHOUSE_CODE",
            message = "Ya existe una bodega con ese código.",
        )

        else -> ConflictException(
            code = "DUPLICATE_KEY",
            message = "Ya existe un registro con esos datos (restricción '$constraint').",
        )
    }
}

/**
 * Ejecuta el bloque traduciendo las violaciones de unicidad.
 *
 * Si el error no es una violación de unicidad se vuelve a lanzar tal cual: el
 * manejador de errores de la aplicación lo convertirá en un 500 sin filtrar el
 * detalle interno. Convertirlo en un 409 «por si acaso» escondería un fallo real
 * detrás de un mensaje que invita a repetir la operación.
 */
internal inline fun <T> translatingDbErrors(block: () -> T): T =
    try {
        block()
    } catch (error: ExposedSQLException) {
        throw translateUniqueViolation(error) ?: error
    }

/** Recorre la cadena de causas buscando el `SQLSTATE` de JDBC. */
private fun Throwable.sqlState(): String? =
    generateSequence(this as Throwable?) { it.cause }
        .mapNotNull { (it as? SQLException)?.sqlState }
        .firstOrNull()

/**
 * Nombre de la restricción que PostgreSQL rechazó.
 *
 * `ExposedSQLException` envuelve la `PSQLException` del driver, así que hay que
 * bajar por la cadena de causas para encontrarla.
 */
private fun Throwable.constraintViolated(): String? =
    generateSequence(this as Throwable?) { it.cause }
        .filterIsInstance<PSQLException>()
        .mapNotNull { it.serverErrorMessage?.constraint }
        .firstOrNull()
