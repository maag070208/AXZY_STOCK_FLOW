package dev.axzy.stock.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * El driver de JVM: **SQLite embebido por JDBC**.
 *
 * La ruta es la que llega como nombre de base, así que un consumidor de JVM
 * puede pedir `abrirBaseLocal("build/tmp/stock.db")` y tenerla donde quiera. En
 * el móvil no aplica: Android e iOS tienen su propio driver.
 *
 * No hay nada específico de JVM en el esquema ni en la cola: el mismo código de
 * `commonMain` (incluida la migración) se ejecuta aquí, que es lo que permite
 * probar en milisegundos lo que en el móvil se probaría con un emulador.
 */
internal actual fun crearDriverPlataforma(nombre: String): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite:$nombre")
