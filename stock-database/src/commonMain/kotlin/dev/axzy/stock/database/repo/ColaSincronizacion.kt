package dev.axzy.stock.database.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.axzy.stock.database.StockDatabase
import dev.axzy.stock.database.model.EstadoOperacion
import dev.axzy.stock.database.model.OperacionPendiente
import dev.axzy.stock.database.model.TipoOperacion
import dev.axzy.stock.database.model.aModelo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA COLA DE SINCRONIZACIÓN — IDEMPOTENTE POR CONSTRUCCIÓN
 *
 * El problema que resuelve: en el piso del almacén la señal va y viene. El
 * operador captura una línea, la app no puede enviarla, y **tiene que poder
 * seguir trabajando**. Cuando vuelve la cobertura, la operación se reenvía.
 *
 * El riesgo, y es el riesgo grave: **un reintento que duplica un movimiento de
 * inventario**. Reintentar es obligatorio (la red falla), duplicar es
 * inaceptable (el stock queda mal y se descubre con un conteo físico). La única
 * forma de tener las dos cosas es la idempotencia del contrato §1.5.
 *
 * CÓMO SE GARANTIZA AQUÍ, en tres capas:
 *
 *  1. **La clave se genera UNA vez**, cuando el operador confirma la captura, y
 *     se guarda con la operación. Reintentar **reutiliza la misma clave**; es
 *     justo lo que hace que el segundo envío sea un no-op en el servidor.
 *
 *  2. **La base impone la unicidad** (`idx_operacion_pendiente_clave`) y el
 *     `INSERT OR IGNORE` no crea una segunda fila aunque el usuario pulse dos
 *     veces o la app se reinicie a mitad. La garantía es estructural: no
 *     depende de que el código se acuerde de comprobar nada.
 *
 *  3. **[encolar] devuelve si la operación era nueva o ya estaba**, para que la
 *     UI pueda decir «ya estaba en cola» en lugar de mentir diciendo que se
 *     encoló otra vez.
 *
 * Lo que NO hace esta clase: hablar con la red. Solo guarda y ordena el trabajo
 * pendiente; el envío lo hace el worker, que es quien conoce el cliente HTTP.
 * Así la cola se prueba sin red, en milisegundos.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ColaSincronizacion(
    private val db: StockDatabase,
    /**
     * Reloj inyectable. La fecha de creación y la del último intento deciden el
     * backoff, así que en un test se controla el tiempo en lugar de esperarlo.
     */
    private val reloj: () -> Long = { Clock.System.now().epochSeconds },
    /** Hilo de las consultas reactivas. En el móvil, el de E/S. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {

    private val consultas get() = db.stockQueries

    /** Resultado de encolar: las dos únicas cosas que pueden pasar. */
    sealed interface Resultado {
        /** La operación no estaba: se encoló ahora. */
        data class Nueva(val operacion: OperacionPendiente) : Resultado

        /** Ya estaba en la cola con la misma clave. No se ha creado nada. */
        data class YaEstaba(val operacion: OperacionPendiente) : Resultado
    }

    /**
     * Encola una operación.
     *
     * `INSERT OR IGNORE` + índice único es la garantía de que no se duplica;
     * la comprobación previa solo sirve para poder decirle al llamante cuál de
     * los dos casos ocurrió. El orden importa: se comprueba **antes** de
     * insertar, y si dos hilos encolaran la misma clave a la vez, la base
     * seguiría teniendo una sola fila.
     */
    fun encolar(
        idempotencyKey: String,
        tipo: TipoOperacion,
        metodo: String,
        ruta: String,
        cuerpo: String,
    ): Resultado {
        require(idempotencyKey.isNotBlank()) {
            "Una operación sin clave de idempotencia no se puede encolar: " +
                "es lo único que impide que un reintento duplique un movimiento de inventario."
        }
        require(metodo.isNotBlank() && ruta.isNotBlank()) {
            "La operación necesita método y ruta: la cola guarda la petición ya construida."
        }

        val yaExistente = consultas.operacionPorClave(idempotencyKey).executeAsOneOrNull()
        if (yaExistente != null) return Resultado.YaEstaba(yaExistente.aModelo())

        db.transaction {
            consultas.encolarOperacion(
                idempotency_key = idempotencyKey,
                tipo = tipo.valor,
                metodo = metodo,
                ruta = ruta,
                cuerpo = cuerpo,
                estado = EstadoOperacion.PENDIENTE.valor,
                creada_en = reloj(),
            )
        }

        val creada = consultas.operacionPorClave(idempotencyKey).executeAsOne().aModelo()
        return Resultado.Nueva(creada)
    }

    /** Las operaciones que el worker debe intentar, las más antiguas primero. */
    fun porEnviar(limite: Long = LIMITE_POR_CICLO): List<OperacionPendiente> =
        consultas.operacionesPorEnviar(limite).executeAsList().map { it.aModelo() }

    fun porId(id: Long): OperacionPendiente? =
        consultas.operacionPorId(id).executeAsOneOrNull()?.aModelo()

    fun porEstado(estado: EstadoOperacion): List<OperacionPendiente> =
        consultas.operacionesPorEstado(estado.valor).executeAsList().map { it.aModelo() }

    fun listarTodas(): List<OperacionPendiente> =
        consultas.listarOperaciones().executeAsList().map { it.aModelo() }

    fun contarPorEstado(estado: EstadoOperacion): Long =
        consultas.contarOperacionesPorEstado(estado.valor).executeAsOne()

    /** Lo que la UI muestra como «quedan N por sincronizar». */
    fun contarPorSincronizar(): Long =
        contarPorEstado(EstadoOperacion.PENDIENTE) + contarPorEstado(EstadoOperacion.FALLIDA)

    /**
     * Marca la operación como en curso.
     *
     * Se hace **antes** de enviarla: si el proceso muere a mitad del envío, la
     * operación no se queda como `PENDIENTE` y el worker no la reenviará a la
     * vez que la petición que ya salió. Como el servidor es idempotente por la
     * clave, un `EN_CURSO` huérfano tampoco es un desastre: hay que
     * recuperarlo, y para eso existe [recuperarEnCurso].
     */
    fun marcarEnCurso(id: Long) {
        consultas.marcarOperacionEnCurso(id)
    }

    /**
     * Marca la operación como completada.
     *
     * La fila **no se borra**: se conserva para poder responder «¿esto se
     * envió?» si el operador pregunta. Se limpia con [limpiarCompletadas]
     * cuando ya no hace falta.
     */
    fun marcarCompletada(id: Long) {
        consultas.marcarOperacionCompletada(id)
    }

    /**
     * Registra un intento fallido.
     *
     * Guarda el error y **cuándo** se intentó: sin la marca de tiempo no se
     * puede aplicar backoff exponencial, y veinte móviles reintentando cada
     * segundo contra un servidor caído son una denegación de servicio hecha por
     * nosotros mismos.
     */
    fun registrarFallo(id: Long, error: String, ahora: Long = reloj()) {
        consultas.registrarIntentoFallido(
            error = error.take(LIMITE_ERROR),
            ahora = ahora,
            id = id,
        )
    }

    /**
     * Devuelve a `FALLIDA` las operaciones que quedaron `EN_CURSO`.
     *
     * Es lo que se llama al arrancar la app: una operación `EN_CURSO` es, por
     * definición, de un proceso que ya no existe (el envío se hace en el
     * proceso actual). Recuperarla no puede duplicar nada porque la clave de
     * idempotencia sigue siendo la misma.
     */
    fun recuperarEnCurso(): Int {
        val huerfanas = porEstado(EstadoOperacion.EN_CURSO)
        huerfanas.forEach { registrarFallo(it.id, "El proceso anterior terminó antes de confirmar el envío") }
        return huerfanas.size
    }

    fun limpiarCompletadas() {
        consultas.borrarOperacionesCompletadas()
    }

    fun limpiar() {
        consultas.borrarTodasLasOperaciones()
    }

    /**
     * Observa lo que queda por enviar.
     *
     * La pantalla del piso muestra «3 operaciones sin sincronizar» mientras el
     * operador sigue trabajando; con sondeos, esa cifra llegaría tarde y a
     * cambio de batería.
     */
    fun observarPorEnviar(limite: Long = LIMITE_POR_CICLO): Flow<List<OperacionPendiente>> =
        consultas.operacionesPorEnviar(limite)
            .asFlow()
            .mapToList(dispatcher)
            .map { filas -> filas.map { it.aModelo() } }

    companion object {
        /**
         * Operaciones por ciclo del worker.
         *
         * Veinte: suficiente para vaciar una cola de un turno sin bloquear el
         * hilo durante minutos con una lista de mil.
         */
        const val LIMITE_POR_CICLO: Long = 20

        /** Tope del mensaje de error que se guarda: un stacktrace entero no cabe ni ayuda. */
        private const val LIMITE_ERROR = 500
    }
}
