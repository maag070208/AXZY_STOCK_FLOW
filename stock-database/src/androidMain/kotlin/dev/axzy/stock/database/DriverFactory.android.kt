package dev.axzy.stock.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

/**
 * El driver de Android.
 *
 * `AndroidSqliteDriver` necesita un `Context`, y un `expect` en `commonMain` no
 * puede pedirlo: la alternativa sería que todo el código común arrastrara un
 * `Context`, que es exactamente lo que rompe la compartición con iOS.
 *
 * La solución es un punto de inicialización explícito que la app llama una vez
 * en `Application.onCreate`. Es preferible a un `ContentProvider` mágico o a un
 * inicializador por reflexión: si falta, el mensaje lo dice.
 */
object AndroidDatabaseContext {

    private var aplicacion: Context? = null

    /** La app llama a esto en `Application.onCreate()`. */
    fun instalar(context: Context) {
        aplicacion = context.applicationContext
    }

    internal fun requerido(): Context = aplicacion
        ?: error(
            "Falta AndroidDatabaseContext.instalar(context): la base local de Android " +
                "no puede abrirse sin el contexto de la aplicación."
        )
}

internal actual fun crearDriverPlataforma(nombre: String): SqlDriver =
    AndroidSqliteDriver(StockDatabase.Schema, AndroidDatabaseContext.requerido(), nombre)
