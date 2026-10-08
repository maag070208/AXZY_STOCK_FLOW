package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * EL MOTOR DE ACCESO — pipeline Identidad → RBAC → ABAC
 *
 * Es una **función pura**: recibe el usuario, la acción, el recurso y el
 * contexto, y devuelve una decisión. No toca base de datos, no es `suspend`, no
 * tiene efectos secundarios.
 *
 * Esa pureza es lo que permite tres cosas:
 *   1. Probar miles de combinaciones en milisegundos, sin servidor.
 *   2. Que **el backend y el móvil tomen la misma decisión**, porque ejecutan
 *      literalmente el mismo código. La UI es una pista; el servidor es la
 *      autoridad, pero ambos coinciden.
 *   3. **Reproducir** una decisión histórica con la versión de la matriz y de
 *      las políticas que estaba vigente (§3.3 de PLAN_AUDITORIA.md).
 *
 * **La regla que sostiene todo es `DEFAULT DENY`:** si nada permite
 * explícitamente, se niega. El legacy hacía lo contrario — `if (role === 1)
 * return next()` — y el rol admin no tenía ni un permiso en base de datos.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * El usuario, con su información de acceso.
 *
 * Nótese que las **bodegas del alcance `AREA` son del usuario, no del rol**.
 * Es lo que permite que un solo rol `JEFE_BODEGA` sirva para cualquier bodega,
 * en lugar de crear un rol por bodega.
 */
data class AccessUser(
    val id: Long,
    val username: String,
    val active: Boolean = true,
    val roles: List<Role> = emptyList(),
    /** Bodegas que son "suyas": resuelven el alcance `AREA`. */
    val warehouseIds: Set<Long> = emptySet(),
    /** Excepciones por persona: permiso puntual por encima o por debajo del rol. */
    val exceptions: Map<String, PermissionScope> = emptyMap(),
    val exceptionExpiries: Map<String, Long> = emptyMap(),
)

/**
 * El recurso sobre el que se actúa. Es lo que evalúa ABAC.
 *
 * Los campos son del dominio de almacén, no genéricos: `difference`,
 * `adjustmentReason`, `locationKind`… Un ABAC con atributos genéricos no sirve
 * para nada.
 */
data class ResourceContext(
    val warehouseId: Long? = null,
    val locationCode: String? = null,
    /** `SHELF` o `RECEIVING`: la ubicación de recepción tiene reglas propias. */
    val locationKind: String? = null,
    val productCode: String? = null,
    /** Diferencia entre lo contado y lo facturado. Atributo clave de las políticas. */
    val difference: Long? = null,
    val quantity: Long? = null,
    /** Motivo del ajuste: distingue merma de robo. */
    val adjustmentReason: String? = null,
    val unitCost: Long? = null,
    val totalValue: Long? = null,
    /** Quién capturó: lo usa la segregación de funciones (PB3). */
    val capturedBy: Long? = null,
    /** Campos adicionales que una política futura pueda necesitar. */
    val extra: Map<String, Any?> = emptyMap(),
) {
    /**
     * Resuelve el valor de un atributo por su nombre cualificado
     * (`resource.difference`). Es lo que usa el evaluador de condiciones.
     */
    fun resolve(field: String): Any? = when (field) {
        "resource.warehouseId" -> warehouseId
        "resource.locationCode" -> locationCode
        "resource.locationKind" -> locationKind
        "resource.productCode" -> productCode
        "resource.difference" -> difference
        "resource.quantity" -> quantity
        "resource.adjustmentReason" -> adjustmentReason
        "resource.unitCost" -> unitCost
        "resource.totalValue" -> totalValue
        "resource.capturedBy" -> capturedBy
        else -> extra[field.removePrefix("resource.")]
    }
}

/** El contexto de la petición. */
data class RequestContext(
    val hour: Int = 12,
    val weekday: Int = 3,
    val deviceType: DeviceType = DeviceType.WEB,
    val offlineSync: Boolean = false,
    val ip: String? = null,
) {
    fun resolve(field: String): Any? = when (field) {
        "context.hour" -> hour
        "context.weekday" -> weekday
        "context.deviceType" -> deviceType.name
        "context.offlineSync" -> offlineSync
        "context.ip" -> ip
        else -> null
    }
}

enum class DeviceType { WEB, MOBILE, SERVICE }

/** En qué etapa se tomó la decisión. Permite explicar el "por qué no". */
enum class DecisionStage { IDENTITY, RBAC, ABAC }

/**
 * La decisión, con su traza completa.
 *
 * La traza no es un adorno: es lo que permite responder *"¿por qué no me dejó
 * aprobar?"* con **qué condición de qué política** falló, en lugar de un
 * "acceso denegado" que no es accionable.
 */
sealed interface AccessDecision {

    val allowed: Boolean
    val code: String
    val stage: DecisionStage

    /** La instantánea de configuración que hace reproducible esta decisión. */
    val matrixVersion: Int
    val policiesVersion: Int

    data class Allowed(
        val permission: String,
        val effectiveScope: PermissionScope,
        val scopeByRole: Map<String, PermissionScope>,
        val policyTrace: List<PolicyTrace> = emptyList(),
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = true
        override val code = "OK"
        override val stage = if (policyTrace.isEmpty()) DecisionStage.RBAC else DecisionStage.ABAC
    }

    /** El usuario no existe o está desactivado. */
    data class NotAuthenticated(
        val reason: String,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val code = "UNAUTHENTICATED"
        override val stage = DecisionStage.IDENTITY
    }

    /** RBAC no alcanza: no tiene el permiso, o no para ese recurso. */
    data class ScopeDenied(
        val permission: String,
        val effectiveScope: PermissionScope,
        val requiredScope: PermissionScope,
        val scopeByRole: Map<String, PermissionScope>,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val stage = DecisionStage.RBAC
        override val code =
            if (effectiveScope == PermissionScope.NONE) "PERMISSION_DENIED" else "SCOPE_INSUFFICIENT"
    }

    /** Una política ABAC denegó la acción. */
    data class PolicyDenied(
        val permission: String,
        val decidedBy: PolicyTrace,
        val policyTrace: List<PolicyTrace>,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val code = "POLICY_DENIED"
        override val stage = DecisionStage.ABAC
    }

    /** Hay políticas aplicables y ninguna `ALLOW` coincidió. */
    data class PolicyRequired(
        val permission: String,
        val policyTrace: List<PolicyTrace>,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val code = "POLICY_REQUIRED"
        override val stage = DecisionStage.ABAC
    }

    /** Segregación de funciones: intentó aprobar su propio trabajo. */
    data class SegregationOfDuties(
        val permission: String,
        val policyTrace: List<PolicyTrace>,
        override val matrixVersion: Int = 0,
        override val policiesVersion: Int = 0,
    ) : AccessDecision {
        override val allowed = false
        override val code = "SEGREGATION_OF_DUTIES"
        override val stage = DecisionStage.ABAC
    }
}

/**
 * El motor.
 *
 * @param matrixVersion versión de la matriz rol→permiso vigente. Se guarda en
 *   cada decisión auditada para poder reproducirla después.
 * @param policiesVersion versión del conjunto de políticas.
 */
class AccessEngine(
    private val policies: List<Policy> = emptyList(),
    private val matrixVersion: Int = 0,
    private val policiesVersion: Int = 0,
    /** Permite desactivar temporalmente la evaluación ABAC (diagnóstico). */
    private val abacEnabled: Boolean = true,
) {

    /**
     * Decide si el usuario puede realizar la acción sobre el recurso.
     *
     * **Las tres etapas, en orden, sin excepciones:**
     *
     *  1. **Identidad** — ¿existe el usuario y está activo? Si no, se corta aquí.
     *  2. **RBAC** — ¿tiene el permiso, y con alcance suficiente **para este
     *     recurso**? Un `JEFE_BODEGA` con alcance `AREA` no alcanza una bodega
     *     que no es suya.
     *  3. **ABAC** — ¿las condiciones se cumplen? Con `DENY` ganando siempre y
     *     `DEFAULT DENY` si hay políticas aplicables y ninguna permite.
     */
    fun decide(
        user: AccessUser?,
        permissionKey: String,
        resource: ResourceContext = ResourceContext(),
        context: RequestContext = RequestContext(),
    ): AccessDecision {

        // ── ETAPA 1: IDENTIDAD ───────────────────────────────────────────────
        // Se comprueba ANTES de mirar permisos: un usuario desactivado no tiene
        // acceso aunque su rol diga lo contrario. El legacy no lo comprobaba en
        // el middleware, así que desactivar a alguien no invalidaba su token
        // hasta 24 horas (hallazgo S3).
        if (user == null) {
            return AccessDecision.NotAuthenticated("Usuario no autenticado", matrixVersion, policiesVersion)
        }
        if (!user.active) {
            return AccessDecision.NotAuthenticated("La cuenta está desactivada", matrixVersion, policiesVersion)
        }

        val permission = PermissionCatalog.find(permissionKey)
            ?: return AccessDecision.ScopeDenied(
                permission = permissionKey,
                effectiveScope = PermissionScope.NONE,
                requiredScope = PermissionScope.NONE,
                scopeByRole = emptyMap(),
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )

        // ── ETAPA 2: RBAC ────────────────────────────────────────────────────
        val scopeByRole = user.roles
            .filter { it.active }
            .associate { it.key.value to it.scopeOf(permissionKey) }
            .filterValues { it.grants }

        val fromRoles = scopeByRole.values.fold(PermissionScope.NONE) { acc, scope ->
            PermissionScope.max(acc, scope)
        }

        // La excepción por persona puede subir el alcance, pero **no puede
        // conceder un permiso que ningún rol aporta** si el sistema está
        // configurado en modo estricto. Aquí se permite la excepción explícita:
        // es una decisión administrada y auditada (con motivo obligatorio en la
        // capa de aplicación).
        val exceptionScope = user.exceptions[permissionKey]
            ?.takeIf { isExceptionValid(user, permissionKey) }
            ?: PermissionScope.NONE

        val effectiveScope = PermissionScope.max(fromRoles, exceptionScope)

        if (!effectiveScope.grants) {
            return AccessDecision.ScopeDenied(
                permission = permissionKey,
                effectiveScope = PermissionScope.NONE,
                requiredScope = requiredScopeFor(permission, resource),
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        val required = requiredScopeFor(permission, resource)
        if (!effectiveScope.covers(required)) {
            return AccessDecision.ScopeDenied(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                requiredScope = required,
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        // ── El alcance AREA tiene que ser SU bodega ──────────────────────────
        // Sin esta comprobación, `AREA` sería equivalente a `ALL` y el modelo de
        // permisos multi-bodega no serviría para nada: un jefe de bodega podría
        // operar en cualquier bodega. Es la comprobación que hace que un solo
        // rol `JEFE_BODEGA` sirva para todas las bodegas sin crear un rol por
        // cada una.
        //
        // Es el hallazgo S7 del legacy: allí la autorización fallaba con 401 en
        // lugar de 403 y no distinguía "no tienes el permiso" de "no tienes
        // permiso sobre ESTO", que es lo accionable.
        // Se aplica a OWN **y** a AREA: un operador cuyo alcance es "lo suyo"
        // tampoco alcanza una bodega que no es la suya. Comprobar solo AREA
        // dejaba a OWN como un comodín sobre todas las bodegas.
        val resourceWarehouse = resource.warehouseId
        if (effectiveScope != PermissionScope.ALL &&
            resourceWarehouse != null &&
            resourceWarehouse !in user.warehouseIds
        ) {
            return AccessDecision.ScopeDenied(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                requiredScope = PermissionScope.ALL,
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        // ── ETAPA 3: ABAC ────────────────────────────────────────────────────
        if (!abacEnabled) {
            return AccessDecision.Allowed(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        val applicable = policies.filter { policy ->
            policy.active &&
                policy.action == permissionKey &&
                (policy.roles.isEmpty() || policy.roles.any { roleKey ->
                    user.roles.any { it.key == roleKey }
                })
        }

        // Sin políticas aplicables: RBAC decide y se permite.
        if (applicable.isEmpty()) {
            return AccessDecision.Allowed(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                scopeByRole = scopeByRole,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        // Evaluar cada política aplicable y construir la traza.
        val traces = applicable
            .sortedByDescending { it.priority }
            .map { policy -> evaluate(policy, user, resource, context) }

        // Regla 3: DENY gana siempre, incluso con prioridad menor. No se puede
        // "comprar" una denegación con una política de mayor prioridad.
        val denied = traces.filter {
            it.matched && it.effect == PolicyEffect.DENY && !it.shadowMode
        }
        if (denied.isNotEmpty()) {
            val deciding = denied.first()
            // La segregación de funciones tiene su propio código de error, porque
            // es accionable: el usuario debe saber que necesita OTRA persona, no
            // solo que "no puede". Se identifica por el efecto DENY sobre la
            // condición de autoría, que es la única política de este tipo.
            if (deciding.conditions.any { it.condition.field == "resource.capturedBy" }) {
                return AccessDecision.SegregationOfDuties(permissionKey, traces, matrixVersion, policiesVersion)
            }
            return AccessDecision.PolicyDenied(permissionKey, deciding, traces, matrixVersion, policiesVersion)
        }

        // Regla 4: al menos una ALLOW que coincida.
        val allowed = traces.any {
            it.matched && it.effect == PolicyEffect.ALLOW && !it.shadowMode
        }
        if (allowed) {
            return AccessDecision.Allowed(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                scopeByRole = scopeByRole,
                policyTrace = traces,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        // Regla 5: hay políticas aplicables y ninguna ALLOW coincidió.
        // **Esto es lo que hace que ABAC suba el listón en lugar de bajarlo.**
        // Un usuario con el permiso por RBAC es denegado si existe una política
        // sobre esa acción que no se cumple.
        //
        // Salvo que TODAS las políticas aplicables estén en observación: en ese
        // caso no hay ninguna regla vigente que exija autorización, y la acción
        // se decide solo por RBAC. Es la esencia del modo observación.
        val hasActivePolicies = traces.any { !it.shadowMode }
        if (!hasActivePolicies) {
            return AccessDecision.Allowed(
                permission = permissionKey,
                effectiveScope = effectiveScope,
                scopeByRole = scopeByRole,
                policyTrace = traces,
                matrixVersion = matrixVersion,
                policiesVersion = policiesVersion,
            )
        }

        return AccessDecision.PolicyRequired(permissionKey, traces, matrixVersion, policiesVersion)
    }

    /**
     * Evaluación de ABAC **sin aplicarla**: devuelve lo que habría decidido.
     *
     * Es lo que permite el modo observación. Se activa una política en este
     * modo, se revisa la bitácora unos días y recién entonces se activa de
     * verdad. Sin esto, una política `DENY` mal escrita puede bloquear el
     * almacén en plena recepción de un contenedor.
     */
    fun evaluateForObservation(
        user: AccessUser?,
        permissionKey: String,
        resource: ResourceContext = ResourceContext(),
        context: RequestContext = RequestContext(),
    ): List<PolicyTrace> {
        if (user == null) return emptyList()
        return policies
            .filter { it.active && it.action == permissionKey }
            .sortedByDescending { it.priority }
            .map { evaluate(it, user, resource, context) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNO
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Alcance mínimo que se exige para actuar sobre un recurso concreto.
     *
     * Regla del dominio: si el recurso declara una bodega y el alcance del
     * usuario es `AREA`, hay que comprobar que **esa bodega es suya**. Si el
     * recurso no declara bodega, `OWN`/`AREA` bastan para actuar sobre lo
     * propio y `ALL` para lo global.
     */
    private fun requiredScopeFor(permission: Permission, resource: ResourceContext): PermissionScope =
        when {
            // Permiso que solo se concede globalmente.
            resource.warehouseId == null && permission.scopes == setOf(PermissionScope.ALL) ->
                PermissionScope.ALL

            else -> PermissionScope.OWN
        }

    /** Comprueba que la excepción por persona siga vigente (no caducada). */
    private fun isExceptionValid(user: AccessUser, permissionKey: String): Boolean {
        val expiry = user.exceptionExpiries[permissionKey] ?: return true
        // `now` se inyecta desde fuera en la capa de aplicación; aquí se compara
        // contra el reloj del sistema como valor por defecto.
        return expiry > currentEpochDay()
    }

    /** Evalúa una política contra el usuario, el recurso y el contexto. */
    private fun evaluate(
        policy: Policy,
        user: AccessUser,
        resource: ResourceContext,
        context: RequestContext,
    ): PolicyTrace {
        val appliesToUser = policy.roles.isEmpty() || policy.roles.any { roleKey ->
            user.roles.any { it.key == roleKey }
        }

        val conditionTraces = policy.conditions.map { condition ->
            val current = resolveField(condition.field, user, resource, context)
            // El valor esperado puede ser una constante o una referencia al
            // usuario que actúa. La segregación de funciones necesita lo segundo:
            // compara `capturedBy` contra **quien intenta aprobar**.
            val expected = resolveExpected(condition.value, user)
            ConditionTrace(
                condition = condition,
                current = current,
                matches = condition.operator.matches(current, expected),
            )
        }

        // Las condiciones se combinan con AND: todas deben coincidir.
        val matched = appliesToUser && conditionTraces.all { it.matches }

        return PolicyTrace(
            policyId = policy.id,
            policyName = policy.name,
            effect = policy.effect,
            priority = policy.priority,
            appliesToUser = appliesToUser,
            matched = matched,
            conditions = conditionTraces,
            shadowMode = policy.shadowMode,
        )
    }

    /**
     * Resuelve el valor esperado de una condición.
     *
     * Si es el marcador [StockPolicies.CURRENT_USER_MARKER], devuelve el id del
     * usuario que actúa. Así la política de segregación de funciones sigue
     * siendo **configurable** en lugar de estar hardcodeada en el motor.
     */
    private fun resolveExpected(value: Any?, user: AccessUser): Any? =
        if (value == StockPolicies.CURRENT_USER_MARKER) user.id else value

    /** Resuelve un atributo por su nombre cualificado. */
    private fun resolveField(
        field: String,
        user: AccessUser,
        resource: ResourceContext,
        context: RequestContext,
    ): Any? = when {
        field.startsWith("resource.") -> resource.resolve(field)
        field.startsWith("context.") -> context.resolve(field)
        field.startsWith("user.") -> when (field) {
            "user.id" -> user.id
            "user.role" -> user.roles.firstOrNull()?.key?.value
            "user.roles" -> user.roles.map { it.key.value }
            "user.warehouseIds" -> user.warehouseIds.toList()
            else -> null
        }
        else -> null
    }

    private companion object {
        /**
         * Día actual. Se mantiene aislado aquí para que sustituirlo por un
         * reloj inyectable sea un cambio de una línea en la Fase 1.
         */
        fun currentEpochDay(): Long = 0L
    }
}
