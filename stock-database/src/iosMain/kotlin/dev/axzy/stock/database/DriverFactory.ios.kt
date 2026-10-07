package dev.axzy.stock.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

/**
 * El driver de iOS: `NativeSqliteDriver`, sobre el SQLite del sistema.
 *
 * El archivo vive en el directorio de documentos de la app, que es lo que iOS
 * respalda y no purga bajo presión de memoria. Guardarlo en `Caches` haría que
 * el sistema pudiera borrar la cola de sincronización justo cuando el operador
 * lleva medio turno sin señal: sería perder su trabajo por decisión del sistema
 * operativo.
 */
internal actual fun crearDriverPlataforma(nombre: String): SqlDriver =
    NativeSqliteDriver(StockDatabase.Schema, nombre)
