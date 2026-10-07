package dev.axzy.stock.datastore

import kotlinx.coroutines.flow.Flow

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ALMACÉN DE CLAVE-VALOR MULTIPLATAFORMA
 *
 * Guarda **preferencias**: la URL del servidor, el idioma, el último usuario,
 * si el operador activó el desbloqueo biométrico. Nada de esto es secreto, y
 * por eso va aquí y no en [SecureStore].
 *
 * Esa separación es la decisión importante de este módulo, y viene de un defecto
 * concreto: **un refresh token de 30 días guardado en un archivo de
 * preferencias legible es una sesión regalada**. Con dos almacenes distintos, la
 * pregunta «¿esto es un secreto?» se contesta una vez, al escribir el código, y
 * no cada vez que alguien añade un campo.
 *
 * La API es `suspend` aunque en Android `SharedPreferences` fuera síncrona:
 * DataStore escribe en disco de verdad y su API es asíncrona, y fingir que no lo
 * es obligaría a bloquear el hilo de la UI.
 * ═══════════════════════════════════════════════════════════════════════════
 */
interface KeyValueStore {

    suspend fun getString(key: String): String?

    /** Con `value = null` la clave se borra: es el `remove` explícito. */
    suspend fun putString(key: String, value: String?)

    suspend fun remove(key: String)

    /** Borra **todo** lo de este almacén, y solo lo de este almacén. */
    suspend fun clear()

    /** Las claves presentes. Lo usa la pantalla de diagnóstico y los tests. */
    suspend fun keys(): Set<String>

    /**
     * Observa el valor de una clave.
     *
     * La pantalla de configuración muestra la URL del servidor y debe
     * actualizarse cuando cambia, sin sondeos: en un móvil, sondear es gastar
     * batería para enterarse tarde.
     */
    fun observeString(key: String): Flow<String?>
}

/**
 * Crea (o abre) el almacén de preferencias de una plataforma.
 *
 * @param name nombre del almacén. Cada nombre es un espacio aislado: borrar el
 *   almacén «stock» no puede llevarse por delante preferencias de otra cosa.
 */
expect fun createKeyValueStore(name: String): KeyValueStore

/** Nombre del almacén de preferencias de la app. */
const val DEFAULT_KEY_VALUE_STORE: String = "stock"
