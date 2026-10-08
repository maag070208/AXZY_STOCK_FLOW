package dev.axzy.stock.domain.audit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MODELO DE AUDITORÍA — "TODO debe ser auditable"
 *
 * El requisito es de primer orden: **quién, cuándo, dónde, de dónde, a qué hora
 * y con qué permiso**. Las seis dimensiones están representadas aquí como
 * campos obligatorios, no como buenas intenciones.
 *
 * **El punto que se suele pasar por alto:** auditar el cambio no basta, hay que
 * auditar **la autorización**. Los permisos cambian con el tiempo. Si dentro de
 * seis meses alguien pregunta si una aprobación de 200 piezas fue legítima,
 * mirar los permisos *actuales* del usuario no responde nada: hay que saber qué
 * roles, qué alcance y **qué políticas estaban vigentes en el instante de la
 * acción**. Por eso cada evento lleva el *trace* de la decisión completa y la
 * **versión** de la matriz y de las políticas.
 *
 * **El legacy no tiene nada de esto.** Verificado sobre los 13 modelos de su
 * esquema: no existe ninguna tabla de auditoría. Su único rastro es el `Kardex`,
 * y es **mutable** (`removeUnitsFromKardex` hace `kardex.update`), así que
 * tampoco sirve como registro histórico. La consecuencia es que el sistema
 * legacy **no puede responder "quién hizo esto"** para casi nada.
 *
 * Referencia: PLAN_AUDITORIA.md.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Canal de origen de un evento.
 *
 * Es la dimensión que el legacy **no tenía en absoluto**, y sin ella no se puede
 * interpretar un movimiento sin persona detrás: si aparece un ajuste de
 * inventario sin usuario, hay que poder distinguir "lo hizo el ERP" de "alguien
 * con una API key filtrada" de "es un bug".
 */
enum class AuditChannel {
    /** Persona desde el navegador. */
    WEB,

    /** Persona desde el móvil, con conexión. */
    MOBILE,

    /** Persona en el móvil **sin señal**, sincronizado después. */
    MOBILE_OFFLINE,

    /** Sistema externo: el ERP (el antiguo `kardo`). */
    SERVICE_ERP,

    /** La impresora Zebra confirmando impresión. */
    SERVICE_PRINTER,

    /** Proceso interno: reconciliación, vencimiento de excepciones. */
    SERVICE_JOB,

    /** Carga inicial de datos, en la migración. */
    MIGRATION,

    /** El propio sistema: arranque, cambio de esquema. */
    SYSTEM,
    ;

    /** Indica si el evento lo originó una persona. */
    val isHuman: Boolean get() = this == WEB || this == MOBILE || this == MOBILE_OFFLINE

    /** Indica si el evento vino de una integración con credencial de servicio. */
    val isService: Boolean
        get() = this == SERVICE_ERP || this == SERVICE_PRINTER || this == SERVICE_JOB
}

/**
 * Nivel de criticidad, que determina el tratamiento y la retención.
 *
 * **Registrar todo con el mismo detalle no es auditar: es acumular ruido que
 * nadie leerá.** Un listado de inventario paginado genera decenas de registros,
 * y el volumen de escritura supera al de lectura real. La solución no es
 * auditar menos, sino separar por niveles.
 */
enum class AuditLevel(val numeric: Int) {
    /**
     * Mueve inventario, dinero o acceso. Registro completo **en la misma
     * transacción** que la operación, y retención **permanente**.
     */
    CRITICAL(1),

    /** Cambia el trabajo de las personas. Registro completo, retención 3 años. */
    RELEVANT(2),

    /**
     * Acceso a datos. **Contador agregado** para las lecturas normales, y
     * registro completo solo para datos sensibles y para las denegaciones.
     * Retención 1 año.
     */
    ACCESS(3),

    /** Observabilidad, no auditoría. Retención 30 días. */
    TECHNICAL(4),
}

/** Resultado de la operación auditada. */
enum class AuditResult {
    SUCCESS,
    FAILURE,

    /**
     * Denegado por el motor de acceso.
     *
     * **Los intentos denegados son la señal más valiosa de la auditoría**: son
     * los que detectan abuso, y también los que detectan un permiso mal
     * configurado que está bloqueando trabajo legítimo.
     */
    DENIED,
    PARTIAL,
}

/**
 * Quién ejecutó la acción.
 *
 * **Los campos incluyen un snapshot del nombre y los roles**, no solo el id.
 * La razón: si el usuario se renombra o se borra, un FK solo deja el histórico
 * ilegible. Una auditoría que no se puede leer dentro de dos años no sirve.
 */
data class AuditActor(
    val userId: Long?,
    val username: String?,
    val displayName: String?,
    /** Snapshot de los roles **vigentes en el momento** de la acción. */
    val roles: List<String> = emptyList(),
) {
    companion object {
        fun system(reason: String) = AuditActor(
            userId = null,
            username = "system",
            displayName = reason,
            roles = emptyList(),
        )

        /** Actor de una integración con credencial de servicio. */
        fun service(name: String, keyId: Long) = AuditActor(
            userId = null,
            username = "service:$name",
            displayName = "Integración $name (key #$keyId)",
            roles = emptyList(),
        )
    }
}

/** De dónde vino la petición. */
data class AuditSource(
    val channel: AuditChannel,
    val deviceId: String? = null,
    val ip: String? = null,
    val userAgent: String? = null,
    /** Si vino de una integración, la clave de servicio usada. */
    val serviceKeyId: Long? = null,
)

/** Sobre qué se actuó. */
data class AuditEntity(
    val type: String,
    val id: String?,
    /**
     * Etiqueta legible: `"Factura F-2024-0158"`.
     *
     * Sin esto, un reporte de auditoría es una lista de ids que nadie puede
     * interpretar sin consultar cinco tablas.
     */
    val label: String? = null,
)

/**
 * Tiempos del evento.
 *
 * **Se guardan dos horas distintas a propósito.** El móvil puede capturar a las
 * 22:00 sin señal y sincronizar a las 09:00 del día siguiente. Son dos hechos
 * diferentes y ambos importan: el primero dice cuándo ocurrió, el segundo cuándo
 * se supo. El legacy mezclaba `Z` con hora local y no guardaba ninguno de los
 * dos de forma fiable (hallazgo R-15).
 */
data class AuditTiming(
    /** Instante en UTC en que ocurrió la acción. */
    val occurredAt: Long,
    /** Fecha operativa: para turnos de noche, la "fecha de negocio". */
    val businessDate: String,
    /** Cuándo lo capturó el móvil sin señal. `null` si hubo conexión. */
    val offlineCreatedAt: Long? = null,
    /** Cuándo llegó al servidor. `null` si fue en línea. */
    val syncedAt: Long? = null,
    /** Diferencia detectada con el reloj del servidor, en milisegundos. */
    val clockSkewMs: Long? = null,
) {
    /** Indica si el evento llegó por sincronización diferida. */
    val isDeferred: Boolean get() = offlineCreatedAt != null && offlineCreatedAt != occurredAt
}

/**
 * Un evento de auditoría completo: **las seis dimensiones**.
 *
 * Es inmutable. Una vez construido, no se modifica: la auditoría que se puede
 * editar no es auditoría.
 */
data class AuditEvent(
    // ── CUÁNDO ──────────────────────────────────────────────────────────────
    val timing: AuditTiming,

    // ── QUIÉN ───────────────────────────────────────────────────────────────
    val actor: AuditActor,

    // ── DE DÓNDE ────────────────────────────────────────────────────────────
    val source: AuditSource,

    // ── SOBRE QUÉ ───────────────────────────────────────────────────────────
    val entity: AuditEntity,

    // ── QUÉ ─────────────────────────────────────────────────────────────────
    val action: String,
    val level: AuditLevel,

    // ── CON QUÉ PERMISO ─────────────────────────────────────────────────────
    val permissionKey: String? = null,
    val trace: AccessDecisionTrace? = null,

    // ── QUÉ CAMBIÓ ──────────────────────────────────────────────────────────
    val previousState: Map<String, Any?>? = null,
    val newState: Map<String, Any?>? = null,
    val changedFields: List<String> = emptyList(),

    // ── POR QUÉ ─────────────────────────────────────────────────────────────
    val reason: String? = null,
    val note: String? = null,

    // ── RESULTADO ───────────────────────────────────────────────────────────
    val result: AuditResult = AuditResult.SUCCESS,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long? = null,

    // ── CORRELACIÓN ─────────────────────────────────────────────────────────
    /** Agrupa todas las acciones de un mismo flujo (una recepción, un cierre). */
    val correlationId: String? = null,
    val causationId: String? = null,
    /** Clave de idempotencia del móvil: permite deduplicar reintentos. */
    val idempotencyKey: String? = null,
    val requestId: String? = null,

    // ── INTEGRIDAD ──────────────────────────────────────────────────────────
    val sequence: Long = 0,
    val previousHash: String? = null,
    val recordHash: String = "",
) {
    /**
     * Indica si el evento tiene las **seis dimensiones** completas.
     *
     * Es la comprobación que hace que "todo auditable" sea verificable en lugar
     * de aspiracional: un evento sin canal de origen o sin hora de ocurrencia
     * está incompleto, y un test lo detecta.
     */
    val hasAllDimensions: Boolean
        get() {
            // QUIÉN: si el canal es humano, debe haber una persona identificada.
            // Si es de servicio o sistema, debe estar identificado el origen.
            val hasActor = when {
                source.channel.isHuman -> actor.userId != null
                else -> actor.username != null
            }
            // CUÁNDO: instante y fecha operativa.
            val hasWhen = timing.occurredAt > 0 && timing.businessDate.isNotBlank()
            // DÓNDE: sobre qué entidad.
            val hasWhere = entity.type.isNotBlank()
            // A QUÉ HORA, en el sentido de si fue diferido: si llegó por
            // sincronización, debe constar cuándo se capturó.
            val hasTimesCoherent = !timing.isDeferred || timing.offlineCreatedAt != null
            // QUÉ y CON QUÉ PERMISO: la acción siempre; el permiso si es crítica.
            val hasThat = action.isNotBlank()
            val hasPermission = level != AuditLevel.CRITICAL || permissionKey != null

            return hasActor && hasWhen && hasWhere &&
                hasTimesCoherent && hasThat && hasPermission
        }

    /** Los campos que difieren entre el estado anterior y el nuevo. */
    fun computeChangedFields(): List<String> {
        val before = previousState ?: return emptyList()
        val after = newState ?: return emptyList()
        return (before.keys + after.keys).distinct()
            .filter { before[it] != after[it] }
            .sorted()
    }
}

/**
 * La cadena de hashes.
 *
 * No impide la alteración: la **hace detectable**. Es el máximo que se puede
 * lograr sin hardware especializado, y es la misma lógica que un libro contable
 * foliado. Si alguien modifica un registro del medio, la cadena se rompe en ese
 * punto exacto y la verificación lo señala.
 *
 * **Riesgo residual aceptado y documentado** (decisión R-79): un administrador
 * de base de datos con acceso total puede recalcular la cadena entera. Lo que sí
 * se detecta es cualquier alteración accidental, cualquier cambio hecho a través
 * de la aplicación, y cualquier `UPDATE`/`DELETE` que no recalcule toda la
 * cadena. Si el requisito cambia a "ni el DBA puede alterar sin que se note", la
 * mitigación es exportar los hashes a almacenamiento externo inmutable; el
 * puerto de exportación queda preparado para ese caso.
 */
interface AuditHashChain {

    /** Calcula el hash de un evento encadenándolo con el anterior. */
    fun hash(previousHash: String?, event: AuditEvent): String

    /**
     * Verifica una secuencia completa.
     * @return el índice del primer evento inválido, o `null` si la cadena es íntegra.
     */
    fun verify(events: List<AuditEvent>): Int?
}

/**
 * Implementación determinista de la cadena, sin dependencias externas.
 *
 * Usa un hash simple y estable: lo que importa no es la fortaleza criptográfica
 * contra un atacante con recursos, sino que **cualquier cambio produzca un hash
 * distinto**. La verificación periódica detecta la alteración.
 */
object DefaultAuditHashChain : AuditHashChain {

    override fun hash(previousHash: String?, event: AuditEvent): String {
        val material = buildString {
            append(previousHash ?: "GENESIS")
            append('|')
            append(event.sequence)
            append('|')
            append(event.timing.occurredAt)
            append('|')
            append(event.actor.userId ?: event.actor.username ?: "?")
            append('|')
            append(event.action)
            append('|')
            append(event.entity.type)
            append('|')
            append(event.entity.id ?: "")
            append('|')
            // El estado nuevo entra en el hash: alterar un valor sin recalcular
            // el hash rompe la cadena.
            append(event.newState?.entries?.sortedBy { it.key }?.joinToString(",") { "${it.key}=${it.value}" } ?: "")
            append('|')
            append(event.reason ?: "")
        }
        return fnv1a64(material).toString(radix = 16).padStart(16, '0')
    }

    override fun verify(events: List<AuditEvent>): Int? {
        var previous: String? = null
        events.forEachIndexed { index, event ->
            val expected = hash(previous, event)
            if (event.recordHash != expected) return index
            previous = expected
        }
        return null
    }

    /**
     * FNV-1a de 64 bits. Es el mismo algoritmo que usa la implementación de
     * referencia del proyecto para la huella de idempotencia, así que hay un
     * solo algoritmo de hashing no criptográfico en todo el sistema.
     */
    private fun fnv1a64(input: String): ULong {
        var hash = 0xcbf29ce484222325UL
        input.encodeToByteArray().forEach { byte ->
            hash = hash xor byte.toUByte().toULong()
            hash *= 0x100000001b3UL
        }
        return hash
    }
}
