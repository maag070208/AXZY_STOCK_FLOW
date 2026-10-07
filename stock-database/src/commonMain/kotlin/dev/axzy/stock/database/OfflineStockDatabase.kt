package dev.axzy.stock.database

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.axzy.stock.database.repo.CatalogoLocal
import dev.axzy.stock.database.repo.ColaSincronizacion
import dev.axzy.stock.database.repo.ExistenciasLocales
import dev.axzy.stock.database.repo.UbicacionesLocales

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA BASE LOCAL DEL MÓVIL
 *
 * Es la fachada: quien la usa no ve SQLDelight, solo catálogo, ubicaciones,
 * existencias y cola. Cada repositorio tiene su responsabilidad y ninguno
 * conoce a los demás.
 *
 * `stock-database` **no depende de `stock-network`** a propósito (el grafo de
 * módulos es `database → domain`): la caché local no tiene por qué arrastrar el
 * cliente HTTP. La conversión de DTO a modelo local la hace la app, que es la
 * única que conoce a los dos; son unas pocas líneas por recurso y así ninguno de
 * los dos módulos depende del otro.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class OfflineStockDatabase(
    /** El driver, expuesto por si el consumidor necesita su propia consulta. */
    val driver: SqlDriver,
) {
    /** La base generada por SQLDelight. */
    val db: StockDatabase = StockDatabase(driver)

    val catalogo: CatalogoLocal = CatalogoLocal(db)
    val ubicaciones: UbicacionesLocales = UbicacionesLocales(db)
    val existencias: ExistenciasLocales = ExistenciasLocales(db)
    val cola: ColaSincronizacion = ColaSincronizacion(db)

    fun cerrar() {
        driver.close()
    }
}

/** Nombre por defecto del archivo de base de datos local. */
const val NOMBRE_BASE_LOCAL: String = "stock.db"

/**
 * Abre (o crea) la base local en la plataforma actual.
 *
 * Crea el esquema si la base está vacía y **aplica las migraciones** si viene de
 * una versión anterior. Migrar importa más de lo que parece: un móvil que ya
 * está en el piso con operaciones encoladas sin señal no puede reinstalar la
 * base, porque perdería el trabajo del operador.
 */
fun abrirBaseLocal(nombre: String = NOMBRE_BASE_LOCAL): OfflineStockDatabase {
    val driver = crearDriverPlataforma(nombre)
    asegurarEsquema(driver)
    return OfflineStockDatabase(driver)
}

/**
 * El driver de SQLite de cada plataforma.
 *
 * Solo **abre la conexión**: crear el esquema y migrarlo lo decide
 * [asegurarEsquema], en código común y con tests. Cada driver de plataforma
 * tiene su propia idea de cómo llevar la versión, y tener tres caminos distintos
 * significaba tres oportunidades de que una migración no se aplicara en uno de
 * ellos.
 */
internal expect fun crearDriverPlataforma(nombre: String): SqlDriver

/**
 * Deja el esquema en la versión que espera el código.
 *
 * Decisión de diseño: la versión se lleva en `PRAGMA user_version`, que es el
 * mecanismo estándar de SQLite y el que respetan las herramientas. Si la base
 * está vacía (versión 0) se crea; si es anterior, se migra; si es igual o
 * posterior, no se toca nada (una app antigua abriendo una base más nueva NO
 * debe degradarla).
 */
internal fun asegurarEsquema(driver: SqlDriver) {
    val versionEnLaBase = leerVersionDeEsquema(driver)
    val versionDelCodigo = StockDatabase.Schema.version

    when {
        versionEnLaBase == 0L -> {
            StockDatabase.Schema.create(driver).value
            escribirVersionDeEsquema(driver, versionDelCodigo)
        }

        versionEnLaBase < versionDelCodigo -> {
            StockDatabase.Schema.migrate(driver, versionEnLaBase, versionDelCodigo).value
            escribirVersionDeEsquema(driver, versionDelCodigo)
        }

        // Base más nueva que el código (el usuario volvió a una versión anterior
        // de la app): no se toca. Degradarla perdería datos.
        else -> Unit
    }
}

/** Lee `PRAGMA user_version`. `0` significa «base recién creada». */
internal fun leerVersionDeEsquema(driver: SqlDriver): Long =
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            // `next()` avanza a la primera fila; un PRAGMA siempre devuelve una,
            // pero comprobarlo evita que un cursor vacío se lea como versión 0 y
            // dispare un `create` sobre una base que ya existe.
            val hayFila = cursor.next().value
            QueryResult.Value(if (hayFila) cursor.getLong(0) ?: 0L else 0L)
        },
        parameters = 0,
    ).value

/**
 * Escribe `PRAGMA user_version`.
 *
 * La versión se interpola en el SQL porque **un PRAGMA no admite parámetros
 * enlazados** en SQLite. No es una inyección: el valor es un `Long` que viene de
 * `StockDatabase.Schema.version`, no de fuera.
 */
internal fun escribirVersionDeEsquema(driver: SqlDriver, version: Long) {
    val resultado: QueryResult<Long> = driver.execute(null, "PRAGMA user_version = $version", 0)
    resultado.value
}
