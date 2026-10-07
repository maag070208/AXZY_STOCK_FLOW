package dev.axzy.stock.domain.audit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TRAZA DE LA DECISIÓN DE ACCESO
 *
 * Es **el corazón del requisito** "con qué permiso". No guarda solo el
 * resultado: guarda **cómo se llegó a él**, con cada etapa y cada condición
 * evaluada.
 *
 * Tres razones concretas, no teóricas:
 *
 *  1. **Los permisos cambian.** Sin la traza, dentro de seis meses no se puede
 *     reconstruir por qué algo se permitió.
 *  2. **El "por qué no" es tan valioso como el "por qué sí".** Cuando alguien
 *     reclama *"no me dejó aprobar"*, la traza dice **qué condición de qué
 *     política** lo bloqueó. Sin eso, el soporte técnico es adivinación.
 *  3. **Las políticas evolucionan.** Si mañana el umbral cambia, la traza
 *     responde por qué una decisión pasada fue válida con las reglas de
 *     entonces.
 *
 * Referencia: PLAN_AUDITORIA.md §3.1.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Estado del usuario y de su autenticación en el momento de la decisión. */
data class IdentityTrace(
    val userId: Long,
    val active: Boolean,
    /** Cuándo se emitió el token con el que se hizo la petición. */
    val authenticatedAt: Long? = null,
)

/** Lo que aportó RBAC, con el estado **exacto del momento**. */
data class RbacTrace(
    /** Alcance efectivo resultante. */
    val effectiveScope: String,
    /** Qué aportó cada rol. */
    val byRole: Map<String, String>,
    /** Snapshot de los roles vigentes **entonces**, para que el histórico sea legible. */
    val rolesAtTime: List<String>,
    /** Excepción por persona, si hubo. */
    val exceptionId: Long? = null,
    val exceptionScope: String? = null,
    /** Las bodegas del alcance `AREA` en ese momento. */
    val warehousesAtTime: List<Long> = emptyList(),
)

/** Traza de **una** condición evaluada. */
data class AuditedCondition(
    val field: String,
    val operator: String,
    val expected: Any?,
    val actual: Any?,
    val matches: Boolean,
)

/** Traza de **una** política evaluada. */
data class AuditedPolicy(
    val policyId: Long,
    val policyName: String,
    val effect: String,
    val priority: Int,
    val appliesToUser: Boolean,
    val matched: Boolean,
    val shadowMode: Boolean,
    val conditions: List<AuditedCondition>,
)

/** Lo que decidió ABAC. */
data class AbacTrace(
    val evaluated: Boolean,
    val allowed: Boolean,
    val decidedByPolicyId: Long? = null,
    val decidedByPolicyName: String? = null,
    val deniedByPolicyId: Long? = null,
    val rules: List<AuditedPolicy> = emptyList(),
)

/** El contexto de la petición. */
data class AuditedContext(
    val hour: Int,
    val weekday: Int,
    val deviceType: String,
    val offlineSync: Boolean,
    val ip: String? = null,
)

/**
 * La instantánea de configuración que hace **reproducible** esta decisión.
 *
 * Es la pieza que convierte la auditoría en algo defendible: permite
 * **reproducir** una decisión histórica tal como se tomó, y **detectar** que una
 * decisión pasada no se habría tomado hoy (porque las reglas cambiaron). Eso
 * último es información valiosa para auditar la evolución del control interno.
 */
data class ConfigSnapshot(
    val matrixVersion: Int,
    val policiesVersion: Int,
)

/**
 * La traza completa: las tres etapas del pipeline, en orden.
 */
data class AccessDecisionTrace(
    val permissionKey: String,
    val decision: String,
    val decisionCode: String,
    /** Etapa en que se decidió: `IDENTITY`, `RBAC` o `ABAC`. */
    val stage: String,
    val identity: IdentityTrace,
    val rbac: RbacTrace,
    val abac: AbacTrace,
    val context: AuditedContext,
    val snapshot: ConfigSnapshot,
) {
    /** Indica si la decisión fue denegar. */
    val wasDenied: Boolean get() = decision != "ALLOWED"

    /**
     * Explicación legible de la decisión, para mostrarla al usuario.
     *
     * Se construye desde la traza, así que **no puede contradecirla**: si la
     * explicación dice que falló una condición, esa condición está en la traza
     * con su valor real.
     */
    fun explain(): String = when {
        !wasDenied -> "Permitido por $stage (alcance ${rbac.effectiveScope})"

        stage == "IDENTITY" -> "Usuario no válido"

        stage == "RBAC" && decisionCode == "PERMISSION_DENIED" ->
            "El usuario no tiene el permiso $permissionKey. " +
                "Roles: ${rbac.rolesAtTime.joinToString(", ").ifEmpty { "ninguno" }}"

        stage == "RBAC" && decisionCode == "SCOPE_INSUFFICIENT" ->
            "Tiene el permiso pero no alcanza este recurso " +
                "(alcance ${rbac.effectiveScope}, bodegas ${rbac.warehousesAtTime})"

        stage == "ABAC" -> {
            val regla = abac.rules.firstOrNull { it.policyId == (abac.deniedByPolicyId ?: abac.decidedByPolicyId) }
            val condicionesQueFallaron = regla?.conditions?.filterNot { it.matches }.orEmpty()
            buildString {
                append("Denegado por la política '${regla?.policyName ?: "?"}'")
                if (condicionesQueFallaron.isNotEmpty()) {
                    append(". Condiciones no cumplidas: ")
                    append(
                        condicionesQueFallaron.joinToString("; ") {
                            "${it.field} ${it.operator} ${it.expected} (valor real: ${it.actual})"
                        }
                    )
                }
            }
        }

        else -> "Denegado ($decisionCode)"
    }
}

/**
 * Constructor de la traza a partir de una decisión del motor de acceso.
 *
 * Vive en el dominio y no en la capa HTTP para que el móvil pueda construir la
 * misma traza: la decisión se toma localmente con el mismo motor, y lo que se
 * envía al servidor es el resultado, no un registro de auditoría inventado por
 * el cliente.
 */
object AccessTraceBuilder {

    fun build(
        permissionKey: String,
        decisionCode: String,
        stage: String,
        allowed: Boolean,
        identity: IdentityTrace,
        rbac: RbacTrace,
        abac: AbacTrace,
        context: AuditedContext,
        snapshot: ConfigSnapshot,
    ): AccessDecisionTrace = AccessDecisionTrace(
        permissionKey = permissionKey,
        decision = if (allowed) "ALLOWED" else "DENIED",
        decisionCode = decisionCode,
        stage = stage,
        identity = identity,
        rbac = rbac,
        abac = abac,
        context = context,
        snapshot = snapshot,
    )
}
