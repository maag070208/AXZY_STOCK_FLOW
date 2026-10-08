package dev.axzy.stock.backend.audit

import kotlinx.serialization.Serializable
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SERVICIO DE CONSULTA DE AUDITORÍA — `GET /v1/audit/log`
 *
 * Este archivo es la mitad de lectura del ledger de auditoría. El esquema existe
 * desde la migración `V2__audit_and_traceability.sql`; lo que **no existía** era
 * la forma de leerlo, así que la pantalla de auditoría de la web estaba vacía y
 * el endpoint devolvía 404 con el contrato (`CONTRATO_API.md` §11) ya firmado.
 *
 * Responde las seis dimensiones del requisito original —quién, cuándo, desde
 * dónde, sobre qué, qué y con qué permiso— mapeadas a las columnas REALES de la
 * tabla, que son más ricas que las del contrato:
 *
 * | Dimensión        | Columnas reales                                          |
 * |------------------|----------------------------------------------------------|
 * | Quién            | `actor_user_id`, `actor_username`, `actor_name`, `actor_roles` |
 * | Cuándo           | `occurred_at`, `business_date`, `offline_created_at`     |
 * | De dónde         | `source_channel`, `source_device`, `source_ip`, `source_user_agent` |
 * | Sobre qué        | `entity_type`, `entity_id`, `entity_label`, `changed_fields` |
 * | Qué              | `action`, `action_level`                                 |
 * | Con qué permiso  | `permission_key`, `decision`, `result`, `access_trace`   |
 * | De dónde (flujo) | `correlation_id`, `causation_id`, `request_id`           |
 * | Integridad       | `sequence_no`, `prev_hash`, `record_hash`                |
 *
 * ── TRES COSAS DEL ESQUEMA QUE CAMBIAN CÓMO SE CONSULTA ────────────────────
 *
 * 1. **ESTÁ PARTICIONADO POR MES.** `audit_log` es el padre de
 *    `audit_log_2026_10`, `_2026_11`, `_2026_12`, `_2027_01` y del
 *    `audit_log_default`. Consultar el padre es correcto y recorre todas las
 *    particiones, pero **cada filtro de fecha que se le pasa a PostgreSQL le
 *    permite descartar particiones enteras** (partition pruning): un rango de
 *    un solo día no lee los otros tres meses. Por eso `from`/`to` no son un
 *    adorno de la interfaz: son lo que separa una consulta que toca una
 *    partición de otra que las abre todas. Sin rango, la consulta sigue siendo
 *    correcta —no se pierde ningún registro— pero paga el coste de todo el
 *    ledger. Y si alguna fila vive en `audit_log_default`, la consulta también
 *    la devuelve: esa partición significa que faltó crear la partición del mes,
 *    y el job de verificación lo reporta (comentario del propio esquema).
 *
 * 2. **LA TABLA ES INMUTABLE POR DISPARADORES.** `audit_log_attach_immutability`
 *    instala un trigger `BEFORE UPDATE` y otro `BEFORE DELETE` **en cada
 *    partición** —un trigger del padre no se hereda, y ese detalle ya dejó una
 *    falsa sensación de protección—. Cualquier `UPDATE` o `DELETE` falla con
 *    `restrict_violation` a propósito. Por eso **este servicio solo emite
 *    `SELECT`**: no hay ningún `UPDATE` ni `DELETE` en el archivo, y no es un
 *    olvido, es la única forma de escribir este código. La corrección de un
 *    registro se hace añadiendo otro que lo compensa.
 *
 * 3. **TIENE CADENA DE HASH** (`sequence_no`, `prev_hash`, `record_hash`), que lo
 *    hace *tamper-evident*. Se expone la terna en cada item para que quien
 *    consuma la API pueda comprobar la cadena por su cuenta. **Lo que NO existe
 *    hoy en el proyecto es la verificación**: ni función de PostgreSQL, ni
 *    endpoint, ni job. `PLAN_AUDITORIA.md` §7.6 y §8 especifican
 *    `GET /v1/audit/integrity` (permiso `report.audit`, que sí está sembrado) y
 *    ese endpoint no está implementado; `report.audit` es hoy un permiso huérfano,
 *    justo la clase de defecto que `CONTRATO_API.md` §11 denuncia. Con los hashes
 *    en la respuesta, una verificación externa es posible; la interna sigue
 *    pendiente.
 *
 * ── EL ERROR DE PAGINACIÓN QUE NO SE REPITE AQUÍ ───────────────────────────
 *
 * `totalItems` es el **total real** que cumple el filtro, no `items.size`: el
 * bug del inventario hacía que el sobre dijera «50 resultados» siempre. Y el
 * conteo usa **los mismos filtros** que el listado porque hay **un solo**
 * constructor de condiciones ([condicionesDe]) que alimenta a las dos consultas.
 * En el kardex el conteo ignoraba el rango y una búsqueda sin resultados
 * devolvía `items: []` con `totalItems: 1064`. Aquí eso no puede escribirse sin
 * borrar la función compartida, que es a propósito.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AuditQueryService(private val dataSource: DataSource) {

    /**
     * Una página del ledger, más reciente primero si [descendente].
     *
     * `limite` y `offset` ya vienen validados por `PageRequest` (el tope de 200
     * se RECHAZA, no se recorta); aquí no se vuelve a comprobar.
     */
    fun list(
        filters: AuditFilters,
        descending: Boolean = true,
        offset: Int = 0,
        limit: Int = 50,
    ): List<AuditEntryDto> = dataSource.connection.use { connection ->
        // El orden desempata por `id` además de por fecha, y no es cosmético: dos
        // registros del mismo milisegundo —una recepción de 30 líneas los
        // produce— sin desempate pueden salir en orden distinto en cada consulta,
        // y entonces una fila se repite en la página 1 y desaparece de la 2. El
        // `id` sale de una secuencia ÚNICA compartida por todas las particiones,
        // así que da un orden total del ledger.
        val order = if (descending) "DESC" else "ASC"
        val (conditions, parameters) = conditionsOf(filters)

        val sql = """
            SELECT id, sequence_no, occurred_at, business_date,
                   actor_user_id, actor_username, actor_name, actor_roles,
                   action, action_level,
                   entity_type, entity_id, entity_label, changed_fields,
                   source_channel, source_device, source_ip::text AS source_ip,
                   source_user_agent,
                   permission_key, decision, result, error_code, error_message, reason,
                   correlation_id, causation_id, request_id,
                   prev_hash, record_hash
            FROM audit_log
            WHERE $conditions
            ORDER BY occurred_at $order, id $order
            LIMIT ? OFFSET ?
        """.trimIndent()

        connection.prepareStatement(sql).use { st ->
            st.link(parameters)
            st.setInt(parameters.size + 1, limit)
            st.setInt(parameters.size + 2, offset)

            st.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) add(rows.toAuditEntryDto())
                }
            }
        }
    }

    /**
     * El total de registros que cumplen los MISMOS filtros que [listar].
     *
     * Es una consulta aparte y no `items.size` porque el cliente necesita saber
     * cuántas páginas hay; y comparte [condicionesDe] con el listado para que no
     * puedan divergir. PostgreSQL resuelve el `count(*)` sobre las mismas
     * particiones que el listado, con el mismo plan de poda.
     */
    fun count(filters: AuditFilters): Long = dataSource.connection.use { connection ->
        val (conditions, parameters) = conditionsOf(filters)

        connection.prepareStatement("SELECT count(*) FROM audit_log WHERE $conditions").use { st ->
            st.link(parameters)
            st.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else 0L }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNOS — el único sitio donde se decide qué filtra
    // ═══════════════════════════════════════════════════════════════════════

    /** Condiciones ya en SQL, con sus parámetros en el mismo orden. */
    private data class Conditions(val sql: String, val parameters: List<Any?>)

    /**
     * **La única puerta por la que se decide qué registros entran.** [listar] y
     * [contar] la usan; no hay una segunda copia de los filtros que pueda
     * quedarse atrás. Si mañana se añade un filtro, se añade aquí o no existe.
     *
     * Cada filtro se apoya en un índice real del esquema:
     * `audit_log_occurred_at_idx` (fecha), `audit_log_actor_idx` (actor),
     * `audit_log_entity_idx` (entidad), `audit_log_action_idx` (acción) y el
     * índice parcial `audit_log_correlation_idx`. Los de `decision`, `result`,
     * `action_level` y `source_channel` no tienen índice propio: se filtran
     * dentro de las particiones que la fecha ya haya dejado, que es suficiente
     * para el uso previsto (una investigación, no un recorrido masivo).
     */
    private fun conditionsOf(filters: AuditFilters): Conditions {
        val conditions = mutableListOf<String>()
        val parameters = mutableListOf<Any?>()

        // ── CUÁNDO: el rango, que además es lo que poda particiones ────────
        if (filters.from != null) {
            conditions += "occurred_at >= ?"
            parameters += filters.from
        }
        if (filters.until != null) {
            conditions += "occurred_at <= ?"
            parameters += filters.until
        }

        // ── QUIÉN ───────────────────────────────────────────────────────────
        if (filters.actorUserId != null) {
            conditions += "actor_user_id = ?"
            parameters += filters.actorUserId
        }

        // ── SOBRE QUÉ ───────────────────────────────────────────────────────
        // Igualdad exacta sobre `entity_type` (no `LIKE`): es lo que usa el
        // índice `(entity_type, entity_id, occurred_at DESC)`, y una búsqueda
        // por texto forzaría un recorrido completo de la partición.
        if (filters.entityType != null) {
            conditions += "entity_type = ?"
            parameters += filters.entityType
        }
        if (filters.entityId != null) {
            conditions += "entity_id = ?"
            parameters += filters.entityId
        }

        // ── QUÉ ─────────────────────────────────────────────────────────────
        if (filters.action != null) {
            conditions += "action = ?"
            parameters += filters.action
        }

        // ── EL FLUJO COMPLETO: de dónde viene esta operación ────────────────
        // `correlation_id` es lo que permite seguir UNA operación a través de
        // varios registros —la recepción con sus 30 líneas, quién hizo cada una
        // y qué se denegó por el camino—, que es el «de dónde» que pidió el
        // usuario. Todas las filas de una misma operación comparten UUID.
        if (filters.correlationId != null) {
            conditions += "correlation_id = ?"
            parameters += filters.correlationId
        }

        // ── CON QUÉ PERMISO / RESULTADO ─────────────────────────────────────
        // Los tres son tipos ENUM de PostgreSQL, así que se castean de forma
        // explícita. **El valor se valida en la ruta antes de llegar aquí**: un
        // texto que no es un valor del enum haría que PostgreSQL lanzara un
        // error de conversión que saldría como 500, cuando el culpable es quien
        // llamó y merece un 400. Es el mismo tropiezo que ya tuvimos con
        // `Idempotency-Key` no-UUID.
        if (filters.decision != null) {
            conditions += "decision = ?::audit_decision"
            parameters += filters.decision
        }
        if (filters.result != null) {
            conditions += "result = ?::audit_result"
            parameters += filters.result
        }
        if (filters.actionLevel != null) {
            conditions += "action_level = ?::audit_level"
            parameters += filters.actionLevel
        }
        if (filters.channel != null) {
            conditions += "source_channel = ?::audit_channel"
            parameters += filters.channel
        }

        // `1 = 1` cuando no hay filtros: mantiene la sentencia válida sin
        // construir un `WHERE` condicional, que es donde se cuelan los errores
        // de concatenación (y el `AND` huérfano).
        val sql = if (conditions.isEmpty()) "1 = 1" else conditions.joinToString(" AND ")
        return Conditions(sql, parameters)
    }

    /** Enlaza parámetros por tipo. `Instant` y `UUID` necesitan su conversión. */
    private fun PreparedStatement.link(parameters: List<Any?>) {
        parameters.forEachIndexed { index, value ->
            when (value) {
                null -> setObject(index + 1, null)
                is Instant -> setTimestamp(index + 1, Timestamp.from(value))
                is UUID -> setObject(index + 1, value)
                is Long -> setLong(index + 1, value)
                is Int -> setInt(index + 1, value)
                else -> setString(index + 1, value.toString())
            }
        }
    }

    /**
     * Una fila del ledger tal como sale por la API.
     *
     * `source_ip` es `INET` en la base y se selecciona con `::text`: el driver lo
     * devolvería igual, pero el cast deja explícito que el contrato publica una
     * dirección como cadena y no un tipo binario que el cliente no sabría leer.
     *
     * `previous_state` y `new_state` (JSONB) **no** se incluyen a propósito: son
     * el diff completo de cada cambio y en un listado de 200 registros con
     * facturas de 30 líneas convierten la respuesta en megabytes. El diff tiene
     * su sitio en el detalle de un registro (`PLAN_AUDITORIA.md` §7.5); lo que sí
     * viaja es `changed_fields`, que dice QUÉ campos cambiaron sin el contenido.
     */
    /** `getLong` devuelve 0 para SQL NULL; aquí el nulo es un dato, no un cero. */

    companion object {
        /**
         * Los valores reales de los enums de PostgreSQL. Viven aquí, junto a la
         * consulta que los castea, y la ruta los usa para **rechazar con 400** un
         * valor que la base no conoce en lugar de dejar que PostgreSQL lance la
         * conversión y el cliente reciba un 500 engañoso.
         */
        val Channels = setOf(
            "WEB", "MOBILE", "MOBILE_OFFLINE",
            "SERVICE_ERP", "SERVICE_PRINTER", "SERVICE_JOB",
            "MIGRATION", "SYSTEM",
        )
        val Levels = setOf("CRITICAL", "RELEVANT", "ACCESS", "TECHNICAL")
        val RESULTS = setOf("SUCCESS", "FAILURE", "DENIED", "PARTIAL")
        val DECISIONS = setOf("ALLOWED", "DENIED", "NOT_APPLICABLE")
    }
}

/**
 * Los filtros de la consulta, ya validados y traducidos a tipos.
 *
 * Va aparte del DTO para poder probar la construcción de la consulta sin HTTP, y
 * para que `null` signifique siempre «este filtro no se pidió»: es lo que hace
 * imposible que un filtro ausente y un filtro vacío se comporten distinto.
 */
data class AuditFilters(
    val from: Instant? = null,
    val until: Instant? = null,
    val actorUserId: Long? = null,
    val entityType: String? = null,
    val entityId: String? = null,
    val action: String? = null,
    val correlationId: UUID? = null,
    val decision: String? = null,
    val result: String? = null,
    val actionLevel: String? = null,
    val channel: String? = null,
)

/**
 * Un registro de auditoría en la respuesta (`items[]`).
 *
 * Los nombres son los del contrato y los del `AuditLogEntry` que espera la web;
 * el mapeo a las columnas reales está en [AuditQueryService.listar]. Los nulos se
 * omiten del JSON (`explicitNulls = false` en el módulo): un registro del sistema
 * no tiene usuario, y mandar `"userId": null` en cada fila es ruido.
 */
@Serializable
data class AuditEntryDto(
    val id: Long,
    /** Orden total del ledger entre particiones. Con `prevHash` forma la cadena. */
    val sequenceNo: Long,

    // ── CUÁNDO ──────────────────────────────────────────────────────────────
    /** Instante en ISO-8601 UTC. */
    val occurredAt: String,
    /**
     * Fecha de negocio (`business_date`). No es redundante con `occurredAt`: un
     * cierre de mes capturado de madrugada pertenece al día de negocio anterior.
     */
    val businessDate: String,

    // ── QUIÉN — el snapshot queda congelado, para que el histórico se lea igual
    // aunque después se renombre o se desactive el usuario.
    val userId: Long? = null,
    val username: String? = null,
    val userName: String? = null,
    val roles: List<String> = emptyList(),

    // ── QUÉ ─────────────────────────────────────────────────────────────────
    val action: String,
    val actionLevel: String,

    // ── SOBRE QUÉ ───────────────────────────────────────────────────────────
    val entity: String,
    val entityId: String? = null,
    val entityLabel: String? = null,
    val changedFields: List<String> = emptyList(),

    // ── DESDE DÓNDE — la dimensión que el legacy no tenía en absoluto ───────
    val channel: String,
    val device: String? = null,
    val ip: String? = null,
    val userAgent: String? = null,

    // ── CON QUÉ PERMISO Y CON QUÉ RESULTADO ─────────────────────────────────
    val permission: String? = null,
    val decision: String? = null,
    val result: String,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val reason: String? = null,

    // ── EL FLUJO: de dónde viene, qué lo causó ──────────────────────────────
    val correlationId: String? = null,
    val causationId: String? = null,
    val requestId: String? = null,

    // ── INTEGRIDAD: la cadena de hashes, expuesta para poder verificarla ────
    val prevHash: String? = null,
    val recordHash: String,
)
