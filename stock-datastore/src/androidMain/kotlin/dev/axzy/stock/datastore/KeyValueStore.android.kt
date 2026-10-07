package dev.axzy.stock.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Punto de inicialización del almacén de Android.
 *
 * DataStore necesita un `Context`, y un `expect` en `commonMain` no puede
 * pedirlo: si lo hiciera, todo el código común arrastraría un `Context` y
 * dejaría de compilarse para iOS. Se resuelve con un inicializador explícito que
 * la app llama **una vez**, en `Application.onCreate()`.
 *
 * Se prefiere esto a un `ContentProvider` mágico o a la reflexión: si falta la
 * llamada, el error lo dice con esas palabras en lugar de aparecer como un
 * `NullPointerException` en una pantalla cualquiera.
 */
object AndroidDatastoreContext {

    private var aplicacion: Context? = null

    fun install(context: Context) {
        aplicacion = context.applicationContext
    }

    internal fun requireContext(): Context = aplicacion
        ?: error(
            "Falta AndroidDatastoreContext.install(context): llama a install() en " +
                "Application.onCreate() antes de usar el almacén de preferencias."
        )
}

/**
 * El almacén de preferencias de Android: **DataStore Preferences**.
 *
 * **Por qué no `SharedPreferences`:** su `apply()` es asíncrono y **no avisa si
 * la escritura falla**. En un iPad de bodega eso significa que la URL del
 * servidor puede no guardarse, la app no enterarse, y el operador descubrirlo al
 * día siguiente cuando el turno cambie de dispositivo. Con DataStore, guardar es
 * una corrutina que termina o lanza, y eso se puede probar.
 */
internal class AndroidDataStoreKeyValueStore(
    private val dataStore: DataStore<Preferences>,
) : KeyValueStore {

    private fun clave(key: String) = stringPreferencesKey(key)

    override suspend fun getString(key: String): String? =
        dataStore.data.map { it[clave(key)] }.firstValue()

    override suspend fun putString(key: String, value: String?) {
        dataStore.edit { prefs ->
            if (value == null) prefs.remove(clave(key)) else prefs[clave(key)] = value
        }
    }

    override suspend fun remove(key: String) {
        dataStore.edit { prefs -> prefs.remove(clave(key)) }
    }

    override suspend fun clear() {
        // `clear()` es del archivo ENTERO de este DataStore: cada nombre de
        // almacén tiene su propio archivo, así que no se lleva nada de otro.
        dataStore.edit { prefs -> prefs.clear() }
    }

    override suspend fun keys(): Set<String> =
        dataStore.data.map { prefs -> prefs.asMap().keys.map { it.name }.toSet() }.firstValue()

    override fun observeString(key: String): Flow<String?> =
        dataStore.data.map { prefs -> prefs[clave(key)] }
}

actual fun createKeyValueStore(name: String): KeyValueStore {
    val context = AndroidDatastoreContext.requireContext()
    val archivo = File(context.filesDir, "datastore/$name.preferences_pb")
    val dataStore = PreferenceDataStoreFactory.create(
        produceFile = { archivo },
    )
    return AndroidDataStoreKeyValueStore(dataStore)
}

/**
 * Primera emisión del flujo.
 *
 * DataStore emite siempre un valor inicial, aunque el archivo no exista
 * (`emptyPreferences`), así que esto no puede quedarse colgado por un almacén
 * vacío: es la diferencia con leer un archivo que puede no estar.
 */
private suspend fun <T> Flow<T>.firstValue(): T = first()
