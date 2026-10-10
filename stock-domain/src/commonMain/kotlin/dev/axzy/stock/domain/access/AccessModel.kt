package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MODELO DE ACCESO — RBAC + ABAC híbrido
 *
 * Adapta el modelo que ya funciona en producción en `RESPONSIVA/web`
 * (consola de roles, políticas, simulación y bitácora) al dominio de almacén,
 * donde el alcance no es "mi área" sino **bodega y ubicación**.
 *
 * Cuatro conceptos, en orden de dependencia:
 *
 *   1. [PermissionScope] — cuánto alcanza un permiso: propio, su bodega, todo.
 *   2. [Permission]      — QUÉ se puede hacer (`invoice.approve`), con su nombre
 *                          y el catálogo de módulos.
 *   3. [RoleKey]         — clave estable de rol. Reemplaza el `roleId` numérico
 *                          que el legacy hardcodeaba en cuatro puntos (§S2).
 *   4. [Policy]          — BAJO QUÉ CONDICIONES se permite o deniega (ABAC).
 *
 * Referencia: docs/historico/PLAN_ROLES_PERMISOS_POLITICAS.md.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Alcance de un permiso sobre los datos.
 *
 * Es el segundo eje de RBAC: un permiso no es solo sí/no. `inventory.view`
 * puede significar "ver todo el almacén" o "ver solo mi bodega".
 *
 * **`AREA` es la clave de la multi-bodega**: significa "su bodega", y qué
 * bodegas son suyas se determina por la asignación de la **persona**, no por
 * el rol. Eso evita crear un rol por bodega (`JEFE_BODEGA_NORTE`,
 * `JEFE_BODEGA_SUR`…), que es el error clásico.
 */
enum class PermissionScope {
    /** Sin acceso. */
    NONE,

    /** Solo sus propias asignaciones o su ubicación. */
    OWN,

    /** Su bodega (o las bodegas que tenga asignadas). */
    AREA,

    /** Todas las bodegas. */
    ALL,
    ;

    /** Indica si concede algún acceso. */
    val grants: Boolean get() = this != NONE

    /**
     * Comprueba si este alcance cubre el alcance requerido.
     *
     * Es una escalera: `ALL ⊇ AREA ⊇ OWN ⊇ NONE`. Conceder `AREA` **no**
     * concede `ALL`, y ese error (tratar el alcance como un booleano) es el que
     * convierte un modelo de permisos en un agujero.
     */
    fun covers(required: PermissionScope): Boolean = when (this) {
        ALL -> true
        AREA -> required == AREA || required == OWN || required == NONE
        OWN -> required == OWN || required == NONE
        NONE -> required == NONE
    }

    companion object {
        /** Ordena de menor a mayor alcance, para resolver el máximo efectivo. */
        fun max(a: PermissionScope, b: PermissionScope): PermissionScope =
            if (a.ordinal >= b.ordinal) a else b
    }
}

/**
 * Módulos del catálogo. Agrupan los permisos para la matriz de administración
 * y determinan el orden en que se muestran.
 */
enum class PermissionModule(val displayName: String) {
    IDENTITY("identidad"),
    CATALOG("catálogo"),
    WAREHOUSE("almacén físico"),
    RECEIVING("recepción"),
    FULFILLMENT("surtido"),
    INVENTORY("inventario"),
    TRANSFER("transferencias"),
    LABELING("etiquetado"),
    REPORT("reportes"),
    SYSTEM("sistema"),
}

/**
 * Un permiso del catálogo, con clave `recurso.accion`.
 *
 * **La convención `recurso.accion` en minúsculas está deliberada**: es la que
 * usa producción, y permite que el editor de políticas agrupe por recurso y
 * ordene por ciclo de vida (ver → crear → editar → eliminar → acciones de
 * negocio). El ejemplo `ALMACEN:CREA` que planteaste no escalaba: tenía
 * dirección inconsistente y un recurso demasiado amplio.
 *
 * @param key clave estable, inmutable. Es el contrato con la base de datos.
 * @param module módulo al que pertenece, para agrupar en la UI.
 * @param scopes alcances **admitidos**. Un permiso que no admite `OWN` no
 *   ofrece esa opción en la pantalla de administración.
 * @param sensitive indica si su uso debe auditarse siempre (Nivel 3).
 */
data class Permission(
    val key: String,
    val module: PermissionModule,
    val name: String,
    val description: String? = null,
    val scopes: Set<PermissionScope> = setOf(PermissionScope.ALL),
    val sensitive: Boolean = false,
    val active: Boolean = true,
    val sortOrder: Int = 0,
) {
    init {
        require(key.isNotBlank()) { "La clave del permiso no puede estar vacía" }
        require('.' in key) {
            "La clave del permiso debe tener formato 'recurso.accion': '$key'"
        }
        require(key == key.lowercase()) {
            "La clave del permiso debe estar en minúsculas: '$key'"
        }
        require(scopes.isNotEmpty()) { "Un permiso debe admitir al menos un alcance: '$key'" }
        require(PermissionScope.NONE !in scopes) {
            "El alcance NONE no se concede: no debe listarse en los alcances admitidos de '$key'"
        }
    }

    /** El recurso del permiso (`invoice` en `invoice.approve`). */
    val resource: String get() = key.substringBefore('.')

    /** La acción del permiso (`approve` en `invoice.approve`). */
    val action: String get() = key.substringAfter('.')

    /** Alcances concedibles, sin `NONE`, en orden canónico. */
    val grantableScopes: List<PermissionScope>
        get() = listOf(PermissionScope.OWN, PermissionScope.AREA, PermissionScope.ALL)
            .filter { it in scopes }
}

/**
 * Clave de un rol. **Estable e inmutable**, a diferencia del `roleId` numérico
 * del legacy, que se hardcodeaba como `if (role === 1)` en cuatro puntos del
 * backend y dependía de que el seed insertara `admin` primero (hallazgo S2).
 *
 * El nombre visible es renombrable; la clave no.
 */
@kotlin.jvm.JvmInline
value class RoleKey private constructor(val value: String) {

    companion object {
        const val MAX_LENGTH = 50

        private val PATTERN = Regex("^[A-Z][A-Z0-9_]*$")

        fun of(raw: String): RoleKey {
            val trimmed = raw.trim()
            require(trimmed.isNotEmpty()) { "La clave del rol no puede estar vacía" }
            require(trimmed.length <= MAX_LENGTH) {
                "La clave del rol excede $MAX_LENGTH caracteres: '$trimmed'"
            }
            require(PATTERN.matches(trimmed)) {
                "La clave del rol debe ser MAYÚSCULAS, dígitos y guion bajo, empezando por letra: '$trimmed'"
            }
            return RoleKey(trimmed)
        }

        /**
         * Genera una clave a partir del nombre visible, como ya hace la
         * implementación de referencia:
         * `"Jefe de bodega"` → `JEFE_DE_BODEGA`, sin choques con las existentes.
         */
        fun fromName(name: String, taken: Set<String> = emptySet()): RoleKey {
            var base = name.trim().uppercase()
                .map { if (it.isLetterOrDigit()) it else '_' }
                .joinToString("")
                .replace(Regex("_+"), "_")
                .trim('_')

            if (base.isEmpty()) return of("ROL")
            if (!base[0].isLetter()) base = "R_$base"
            base = base.take(MAX_LENGTH).trimEnd('_')

            var candidate = base
            var suffix = 2
            while (candidate in taken) {
                val tail = "_$suffix"
                candidate = base.take(MAX_LENGTH - tail.length).trimEnd('_') + tail
                suffix++
            }
            return of(candidate)
        }
    }

    override fun toString(): String = value
}

/** Rol del sistema: clave estable + nombre visible + sus permisos con alcance. */
data class Role(
    val key: RoleKey,
    val name: String,
    val description: String? = null,
    /** Los permisos concedidos y **con qué alcance**. */
    val permissions: Map<String, PermissionScope> = emptyMap(),
    /** Un rol de sistema no se puede eliminar. */
    val isSystem: Boolean = false,
    val active: Boolean = true,
    val sortOrder: Int = 0,
) {
    /**
     * Alcance que este rol aporta para un permiso.
     * `NONE` si no lo tiene o si está inactivo.
     */
    fun scopeOf(permissionKey: String): PermissionScope =
        if (!active) PermissionScope.NONE
        else permissions[permissionKey] ?: PermissionScope.NONE
}

/**
 * Los cinco roles base del almacén.
 *
 * Cada uno se explica en una frase. **Si un rol no se puede explicar en una
 * frase, está mal cortado.** Es la prueba que descarta el rol `SUPERVISOR`
 * genérico, que en la práctica termina con permisos de todo para no bloquear
 * el trabajo, y es el rol que destruye un modelo de permisos.
 */
enum class BaseRole(val key: RoleKey, val displayName: String, val oneLiner: String) {
    ADMIN(RoleKey.of("ADMIN"), "Administrador", "Puede todo, incluida la configuración"),
    OPERATIONS_MANAGER(
        RoleKey.of("GERENTE_OPERACIONES"),
        "Gerente de operaciones",
        "Ve y aprueba todo, sin administrar el sistema",
    ),
    WAREHOUSE_MANAGER(
        RoleKey.of("JEFE_BODEGA"),
        "Jefe de bodega",
        "Gobierna su bodega: captura, ubica, aprueba y ajusta",
    ),
    WAREHOUSE_OPERATOR(
        RoleKey.of("OPERADOR_ALMACEN"),
        "Operador de almacén",
        "Ejecuta el trabajo que le asignan, sin aprobar nada",
    ),
    AUDITOR(RoleKey.of("AUDITOR"), "Auditor", "Solo mira: reportes, kardex y bitácora"),
}

// ═════════════════════════════════════════════════════════════════════════════
// ABAC — políticas
// ═════════════════════════════════════════════════════════════════════════════

/** Efecto de una política. */
enum class PolicyEffect {
    ALLOW,
    DENY,
}

/** Operadores de condición. */
enum class PolicyOperator(val symbol: String) {
    EQUALS("=="),
    NOT_EQUALS("!="),
    LESS_THAN("<"),
    LESS_OR_EQUAL("<="),
    GREATER_THAN(">"),
    GREATER_OR_EQUAL(">="),
    IN("in"),
    NOT_IN("not_in"),
    ;

    /**
     * Compara un valor real contra el esperado, según el operador.
     *
     * **Cuidado con los números: en Kotlin `Int(0) != Long(0)`.** `equals` entre
     * tipos numéricos distintos devuelve falso, así que `0 in listOf(0L, 6L)` es
     * **falso** aunque conceptualmente sea verdadero. Ese detalle hacía que una
     * política `DENY` de fin de semana no coincidiera nunca con el día real.
     *
     * Por eso TODAS las comparaciones numéricas pasan por [asLong], y la
     * igualdad solo usa `equals` cuando no hay números de por medio.
     */
    fun matches(current: Any?, expected: Any?): Boolean = when (this) {
        EQUALS -> areEqual(current, expected)
        NOT_EQUALS -> !areEqual(current, expected)
        IN -> when (expected) {
            is Collection<*> -> expected.any { areEqual(current, it) }
            else -> false
        }
        NOT_IN -> when (expected) {
            is Collection<*> -> expected.none { areEqual(current, it) }
            else -> false
        }
        LESS_THAN -> compareNumbers(current, expected) { a, b -> a < b }
        LESS_OR_EQUAL -> compareNumbers(current, expected) { a, b -> a <= b }
        GREATER_THAN -> compareNumbers(current, expected) { a, b -> a > b }
        GREATER_OR_EQUAL -> compareNumbers(current, expected) { a, b -> a >= b }
    }

    /**
     * Igualdad tolerante al tipo numérico.
     *
     * Si **ambos** lados son números, compara como `Long` (de modo que
     * `Int(8) == Long(8)`). Si alguno no lo es, usa `equals` normal.
     */
    private fun areEqual(a: Any?, b: Any?): Boolean {
        val na = asLong(a)
        val nb = asLong(b)
        if (na != null && nb != null) return na == nb
        return a == b
    }

    private inline fun compareNumbers(
        current: Any?,
        expected: Any?,
        predicate: (Long, Long) -> Boolean,
    ): Boolean {
        val a = asLong(current) ?: return false
        val b = asLong(expected) ?: return false
        return predicate(a, b)
    }

    private fun asLong(value: Any?): Long? = when (value) {
        null -> null
        is Long -> value
        is Int -> value.toLong()
        is Short -> value.toLong()
        is Byte -> value.toLong()
        is Double -> value.toLong()
        is Float -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }
}

/**
 * Una condición de una política: `campo operador valor`.
 * Ejemplo real: `resource.difference <= 50`.
 */
data class PolicyCondition(
    val field: String,
    val operator: PolicyOperator,
    val value: Any?,
)

/** Traza de la evaluación de **una** condición. Es lo que hace explicable el "por qué no". */
data class ConditionTrace(
    val condition: PolicyCondition,
    val current: Any?,
    val matches: Boolean,
)

/** Traza de la evaluación de **una** política. */
data class PolicyTrace(
    val policyId: Long,
    val policyName: String,
    val effect: PolicyEffect,
    val priority: Int,
    /** Indica si la política aplicaba al usuario (por rol). */
    val appliesToUser: Boolean,
    /** Indica si todas sus condiciones coincidieron. */
    val matched: Boolean,
    val conditions: List<ConditionTrace>,
    /**
     * Modo observación de **esta** política, copiado en la traza.
     *
     * Se copia aquí en lugar de consultarlo después por id: las trazas se
     * ordenan por prioridad y buscar la política de nuevo era una fuente de
     * errores. Una política en observación **registra** su decisión pero **no
     * la aplica**, y eso debe ser imposible de confundir.
     */
    val shadowMode: Boolean = false,
)

/**
 * Una política ABAC.
 *
 * Reglas de evaluación, que hay que implementar exactamente así:
 *  1. Solo se evalúan políticas `active` cuya `action` coincida y cuyos `roles`
 *     incluyan alguno del usuario (o estén vacíos).
 *  2. Las condiciones se combinan con **AND**.
 *  3. **Si alguna `DENY` coincide, se deniega**, sin importar cuántas `ALLOW`.
 *  4. Si no hay `DENY` y al menos una `ALLOW` coincide, se permite.
 *  5. Si hay políticas aplicables pero **ninguna `ALLOW` coincide**, se deniega
 *     con `POLICY_REQUIRED`. **Esto es lo que hace que ABAC suba el listón.**
 *  6. Sin políticas aplicables: `DEFAULT DENY`.
 */
data class Policy(
    val id: Long,
    val name: String,
    val description: String,
    val action: String,
    val effect: PolicyEffect,
    val priority: Int = 0,
    val active: Boolean = true,
    /** En observación: registra la decisión pero **no la aplica**. */
    val shadowMode: Boolean = false,
    /** No desactivable sin confirmación explícita (por implicación legal). */
    val protected: Boolean = false,
    /** Roles a los que aplica. Vacío = a todos. */
    val roles: Set<RoleKey> = emptySet(),
    val conditions: List<PolicyCondition> = emptyList(),
) {
    init {
        require(name.isNotBlank()) { "Una política debe tener nombre" }
        require(description.isNotBlank()) {
            "Una política debe tener descripción: en seis meses nadie recordará por qué existe. " +
                "Política sin descripción: '$name'"
        }
        require(action.isNotBlank()) { "Una política debe indicar sobre qué acción actúa" }
    }
}
