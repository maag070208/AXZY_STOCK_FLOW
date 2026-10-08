package dev.axzy.stock.backend.audit

import java.sql.Connection
import java.sql.Types
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ESCRITOR DE AUDITORÍA — el único sitio que escribe en `audit_log`
 *
 * **POR QUÉ EXISTE ESTE ARCHIVO, y es el hallazgo más grave del proyecto.**
 *
 * La tabla `audit_log` tenía el esquema completo —44 columnas con las 6 dimensiones,
 * cinco particiones mensuales, disparadores de inmutabilidad, cadena de hash— y
 * **CERO FILAS**. El requisito central del usuario, pedido el primer día, era:
 *
 *   «TODO DEBE DE SER AUDITABLE: quién, cuándo, dónde, de dónde, a qué hora, tenía
 *    permiso, etc.»
 *
 * Y no se cumplía en absoluto. La auditoría era una fachada: completa por fuera y
 * vacía por dentro. Este archivo es lo que la conecta.
 *
 * **NO HAY SQL DE AUDITORÍA REPARTIDO POR LOS SERVICIOS.** Todo pasa por [record].
 * Esa centralización es lo que permite cambiar la cadena de hash, añadir una columna
 * o corregir un mapeo **en un solo sitio**.
 *
 * ---
 *
 * ## LA DECISIÓN DE DISEÑO QUE MÁS IMPORTA: la auditoría NO es «best effort»
 *
 * `record` recibe la **conexión del llamador** y participa de su transacción. Si la
 * auditoría falla, **la operación se revierte con ella**. Es deliberado.
 *
 * La alternativa —tragarse el error y seguir— parece más robusta y es peor: si el
 * movimiento de inventario se aplica y su registro no, **el inventario cambió sin
 * dejar rastro de quién lo cambió**. Y eso no se descubre en el momento: se descubre
 * un año después, cuando alguien pregunta quién autorizó una diferencia y no hay
 * respuesta. Un sistema que a veces audita es más peligroso que uno que no audita,
 * porque genera confianza sin merecerla.
 *
 * **El precio, dicho claro:** si la tabla de auditoría no está disponible, el almacén
 * deja de poder operar. Es correcto para una operación que mueve patrimonio — con una
 * excepción deliberada: ver [recordSafely] para el registro de **accesos denegados**,
 * donde sí interesa que un fallo no tumbe la petición.
 *
 * ---
 *
 * ## LAS 6 DIMENSIONES
 *
 * `record` las pide **en el orden en que se leen**, para que la llamada se entienda:
 * quién → desde dónde → sobre qué → qué → con qué permiso → y cómo salió.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AuditWriter(private val dataSource: DataSource) {

    /**
     * De dónde viene la petición.
     *
     * Es un enum de PostgreSQL (`audit_channel`), así que los valores están
     * **cerrados y comprobados en la base**: un canal inventado falla al insertar en
     * lugar de guardarse como texto libre.
     */
    enum class Channel { WEB, MOBILE, MOBILE_OFFLINE, SERVICE_ERP, SERVICE_PRINTER, SERVICE_JOB, MIGRATION, SYSTEM }

    /**
     * Cuánto importa lo que se registra.
     *
     * **`CRITICAL` deja de ser una etiqueta decorativa:** la tabla tiene el CHECK
     * `audit_critical_has_trace`, así que un registro crítico **exige trazabilidad**
     * (una correlación, una petición o una clave de idempotencia). Si no la hay, el
     * INSERT falla. La base impone que lo importante sea rastreable, en lugar de
     * confiar en que quien escribe se acuerde.
     */
    enum class Level { CRITICAL, RELEVANT, ACCESS, TECHNICAL }

    enum class Result { SUCCESS, FAILURE, DENIED, PARTIAL }

    enum class Decision { ALLOWED, DENIED, NOT_APPLICABLE }

    /**
     * Escribe un registro de auditoría **dentro de la transacción de quien llama**.
     *
     * @param connection la conexión del llamador. **No se abre una nueva**: el
     *   registro tiene que confirmarse o revertirse con la operación que audita.
     * @param actorUserId quién. `null` solo si [channel] no es humano — la tabla lo
     *   impone con `audit_human_has_actor`, y hace bien: una acción WEB sin usuario
     *   identificado no es auditable.
     * @param correlationId **la pieza que permite seguir una operación completa.** Si
     *   aprobar una factura escribe tres registros, los tres lo comparten. Ver
     *   [newCorrelationId].
     * @return el id del registro.
     */
    @Suppress("LongParameterList")
    fun record(
        connection: Connection,
        // ── QUIÉN ──────────────────────────────────────────────────────────
        actorUserId: Long?,
        actorUsername: String?,
        actorName: String? = null,
        actorRoles: List<String>? = null,
        // ── DESDE DÓNDE ────────────────────────────────────────────────────
        channel: Channel,
        sourceIp: String? = null,
        sourceUserAgent: String? = null,
        sourceDevice: String? = null,
        // ── SOBRE QUÉ ──────────────────────────────────────────────────────
        entityType: String,
        entityId: String? = null,
        entityLabel: String? = null,
        // ── QUÉ ────────────────────────────────────────────────────────────
        action: String,
        level: Level = Level.RELEVANT,
        previousState: String? = null,
        newState: String? = null,
        reason: String? = null,
        note: String? = null,
        // ── CON QUÉ PERMISO ────────────────────────────────────────────────
        permissionKey: String? = null,
        decision: Decision = Decision.ALLOWED,
        decisionCode: String? = null,
        // ── CÓMO SALIÓ ─────────────────────────────────────────────────────
        result: Result = Result.SUCCESS,
        errorCode: String? = null,
        errorMessage: String? = null,
        durationMs: Int? = null,
        // ── TRAZABILIDAD ───────────────────────────────────────────────────
        correlationId: String? = null,
        causationId: String? = null,
        idempotencyKey: String? = null,
        requestId: String? = null,
        businessDate: java.time.LocalDate? = null,
    ): Long {
        val sql = """
            SELECT audit_log_record(
                ?::bigint, ?::text, ?::text, ?::text[],
                ?::audit_channel, ?::inet, ?::text, ?::text,
                ?::text, ?::text, ?::text,
                ?::text, ?::audit_level, ?::jsonb, ?::jsonb, ?::text, ?::text,
                ?::text, ?::audit_decision, ?::text,
                ?::audit_result, ?::text, ?::text, ?::integer,
                ?::uuid, ?::uuid, ?::text, ?::text,
                ?::date
            )
        """.trimIndent()

        // `Level.CRITICAL` exige trazabilidad (CHECK `audit_critical_has_trace`). Si
        // quien llama no la dio, se genera aquí una correlación en lugar de dejar que
        // el INSERT falle: perder el registro de una operación crítica por un detalle
        // de fontanería sería peor que el detalle.
        val correlation = correlationId
            ?: if (level == Level.CRITICAL) newCorrelationId() else null

        // Una IP que no es IP (un `localhost` sin resolver, una cabecera manipulada)
        // haría fallar el CAST a `inet` y tumbaría la operación por un dato accesorio.
        // Se descarta en lugar de propagar el fallo.
        val validIp = sourceIp?.takeIf { looksLikeIp(it) }

        // ── EL NOMBRE DEL ACTOR SE RESUELVE AQUÍ ───────────────────────────
        //
        // La primera versión dejaba `actorUsername` a `null` porque los servicios
        // solo tienen el ID, y el resultado era que la vista legible mostraba
        // **«WEB»** en lugar de **«Administrador»**: el `COALESCE(name, username,
        // channel)` caía hasta el canal. El «quién» no se perdía —`actor_user_id`
        // estaba y es lo que identifica de verdad— pero un registro de auditoría que
        // no dice el nombre de la persona obliga a cruzar con `users` para leerlo, y
        // **una auditoría que hay que interpretar no se lee**.
        //
        // Se resuelve AQUÍ y no en cada llamador por una razón concreta: hay cuatro
        // servicios auditando y cada uno tendría que acordarse de mandarlo. Poniéndolo
        // en el escritor, **los cuatro lo obtienen sin saber que existe**, y el quinto
        // que se añada también.
        //
        // Sin consulta adicional cuando el llamador ya lo manda.
        val actorName = actorUsername ?: resolveActorName(connection, actorUserId)

        connection.prepareStatement(sql).use { st ->
            var i = 1
            st.setObject(i++, actorUserId, Types.BIGINT)
            st.setString(i++, actorName)
            st.setString(i++, actorName)
            st.setArray(i++, actorRoles?.let { connection.createArrayOf("text", it.toTypedArray()) })
            st.setString(i++, channel.name)
            st.setString(i++, validIp)
            st.setString(i++, sourceUserAgent)
            st.setString(i++, sourceDevice)
            st.setString(i++, entityType)
            st.setString(i++, entityId)
            st.setString(i++, entityLabel)
            st.setString(i++, action)
            st.setString(i++, level.name)
            st.setString(i++, previousState)
            st.setString(i++, newState)
            st.setString(i++, reason)
            st.setString(i++, note)
            st.setString(i++, permissionKey)
            st.setString(i++, decision.name)
            st.setString(i++, decisionCode)
            st.setString(i++, result.name)
            st.setString(i++, errorCode)
            st.setString(i++, errorMessage)
            st.setObject(i++, durationMs, Types.INTEGER)
            st.setString(i++, correlation)
            st.setString(i++, causationId)
            st.setString(i++, idempotencyKey)
            st.setString(i++, requestId)
            st.setObject(i, businessDate, Types.DATE)

            st.executeQuery().use { rs ->
                rs.next()
                return rs.getLong(1)
            }
        }
    }

    /**
     * El nombre de usuario a partir de su id.
     *
     * Devuelve `null` si el id es nulo o el usuario no existe — **y no es un error**:
     * se audita a un usuario borrado, o a un actor anónimo en un intento fallido. La
     * clave foránea ya garantiza que un id presente existe; esto solo añade la parte
     * legible.
     */
    private fun resolveActorName(connection: Connection, userId: Long?): String? {
        if (userId == null) return null
        return connection.prepareStatement(
            "SELECT username FROM users WHERE id = ?",
        ).use { st ->
            st.setLong(1, userId)
            st.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    /**
     * Registra un **acceso denegado**, y se traga el fallo.
     *
     * **Es la única excepción deliberada a la regla de «si la auditoría falla, la
     * operación falla».** Y la razón es concreta: cuando alguien intenta algo que no
     * tiene permiso, la respuesta correcta para él es **403**, no un 500 porque la
     * tabla de auditoría está caída. Negarle el acceso es la decisión de seguridad, y
     * esa decisión ya está tomada; el registro es información para después.
     *
     * Se abre su propia conexión porque el acceso denegado no forma parte de ninguna
     * transacción de negocio: no hay nada que revertir.
     *
     * Devuelve el id, o `null` si no se pudo registrar. **El `null` no es un error**:
     * es la admisión de que el registro se perdió, y quien lo llame puede contarlo.
     */
    fun recordDeniedAccessSafely(
        actorUserId: Long?,
        actorUsername: String?,
        channel: Channel,
        sourceIp: String?,
        permissionKey: String,
        entityType: String,
        entityId: String?,
        reason: String,
    ): Long? = runCatching {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            record(
                connection = connection,
                actorUserId = actorUserId,
                actorUsername = actorUsername,
                channel = channel,
                sourceIp = sourceIp,
                entityType = entityType,
                entityId = entityId,
                action = "access.denied",
                level = Level.ACCESS,
                permissionKey = permissionKey,
                decision = Decision.DENIED,
                decisionCode = "FORBIDDEN",
                result = Result.DENIED,
                reason = reason,
            )
        }
    }.getOrNull()

    companion object {
        /**
         * Una correlación nueva, para seguir una operación completa.
         *
         * Se genera en el servidor cuando el cliente no la manda. **No se usa el
         * `requestId`**: ese identifica UNA petición HTTP, y una operación de negocio
         * puede abarcar varias (capturar, luego aprobar). La correlación sobrevive a
         * las peticiones; el `requestId`, no.
         */
        fun newCorrelationId(): String = java.util.UUID.randomUUID().toString()

        /**
         * ¿Parece una dirección IP?
         *
         * Comprobación deliberadamente laxa: solo descarta lo que PostgreSQL
         * rechazaría al castear a `inet`. No se intenta validar de verdad, porque
         * `X-Forwarded-For` **es falsificable** y una validación estricta daría una
         * falsa sensación de seguridad. La IP es un dato indicativo, no una prueba.
         */
        private fun looksLikeIp(valor: String): Boolean {
            val v = valor.trim()
            if (v.isEmpty()) return false
            // IPv4 o IPv6 con los caracteres que `inet` admite.
            return v.matches(Regex("^[0-9a-fA-F.:]+$"))
        }
    }
}
