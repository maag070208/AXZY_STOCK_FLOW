package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * POLÍTICAS ABAC DEL ALMACÉN
 *
 * Doce políticas concretas, no ejemplos teóricos. Cada una resuelve un problema
 * operativo real, y las tres primeras son las que justifican ABAC en este
 * dominio:
 *
 *  · **PB3 — segregación de funciones** (`DENY` si `capturedBy == user.id`):
 *    es la respuesta a la decisión R-74. En lugar de un `CHECK` rígido en la
 *    base de datos, se expresa como **política configurable**: si el negocio
 *    decide que con poco personal sí puede autoaprobar, se cambia la política y
 *    queda auditado, sin tocar código ni migrar.
 *
 *  · **PB5/PB6 — horario**: `RBAC dice QUÉ; ABAC dice CUÁNDO`. El jefe de
 *    bodega tiene `inventory.adjust_shortage`, pero no a las 3 de la mañana.
 *
 *  · **PB7 — productos de alto valor**: un operador no mueve producto caro.
 *
 * **Las políticas no pueden conceder lo que RBAC no dio** (regla 5 del motor).
 * Es la garantía de que ABAC nunca escala privilegios hacia arriba.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object StockPolicies {

    /**
     * Marcador que el motor resuelve contra el **usuario que intenta la acción**.
     *
     * Es necesario porque la condición de segregación de funciones compara el
     * recurso con quien actúa (`capturedBy == quien aprueba`), y el valor
     * esperado **no es una constante**: depende de quién hace la petición. Sin
     * esto habría que hardcodear la comprobación en el motor, y la política
     * dejaría de ser configurable.
     */
    const val CURRENT_USER_MARKER = "__CURRENT_USER__"

    private val OPERATOR = BaseRole.WAREHOUSE_OPERATOR.key
    private val WAREHOUSE_MANAGER = BaseRole.WAREHOUSE_MANAGER.key
    private val OPERATIONS_MANAGER = BaseRole.OPERATIONS_MANAGER.key
    private val ADMIN = BaseRole.ADMIN.key

    /** Todas las políticas, tal como se sembrarían en la base de datos. */
    val all: List<Policy> = listOf(

        // ═════════════════════════════════════════════════════════════════════
        // SEGREGACIÓN DE FUNCIONES — la más importante
        // ═════════════════════════════════════════════════════════════════════

        Policy(
            id = 1,
            name = "Prohibido aprobar lo propio (segregación de funciones)",
            description = "Nadie puede autorizar una discrepancia que él mismo capturó. " +
                "Evita que alguien tape su propio error de conteo. Decisión R-74.",
            action = "invoice.approve",
            effect = PolicyEffect.DENY,
            priority = 100,
            protected = true,
            roles = setOf(WAREHOUSE_MANAGER, OPERATIONS_MANAGER),
            conditions = listOf(
                PolicyCondition("resource.capturedBy", PolicyOperator.EQUALS, CURRENT_USER_MARKER),
            ),
        ),

        // ═════════════════════════════════════════════════════════════════════
        // APROBACIÓN DE DISCREPANCIAS (sin umbral — decisión R-75)
        // ═════════════════════════════════════════════════════════════════════

        Policy(
            id = 2,
            name = "El jefe de bodega aprueba discrepancias de su bodega",
            description = "Sin umbral de cantidad: toda diferencia requiere aprobación " +
                "(decisión R-75). El control está en que sea OTRA persona y de su bodega.",
            action = "invoice.approve",
            effect = PolicyEffect.ALLOW,
            priority = 10,
            roles = setOf(WAREHOUSE_MANAGER),
        ),
        Policy(
            id = 3,
            name = "Gerencia aprueba discrepancias de cualquier bodega",
            description = "El gerente de operaciones puede aprobar en todas las bodegas.",
            action = "invoice.approve",
            effect = PolicyEffect.ALLOW,
            priority = 10,
            roles = setOf(OPERATIONS_MANAGER),
        ),

        // ═════════════════════════════════════════════════════════════════════
        // AJUSTES DE INVENTARIO
        // ═════════════════════════════════════════════════════════════════════

        Policy(
            id = 4,
            name = "El ajuste por robo requiere gerencia",
            description = "Declarar un robo mueve inventario y tiene implicaciones legales. " +
                "Solo gerencia y administración pueden hacerlo.",
            action = "inventory.adjust_theft",
            effect = PolicyEffect.ALLOW,
            priority = 20,
            protected = true,
            roles = setOf(OPERATIONS_MANAGER, ADMIN),
        ),
        Policy(
            id = 5,
            name = "Ajustes de inventario solo en horario laboral",
            description = "Los ajustes de inventario se realizan entre las 08:00 y las 18:00. " +
                "Fuera de ese horario requieren autorización específica.",
            action = "inventory.adjust_shortage",
            effect = PolicyEffect.ALLOW,
            priority = 20,
            roles = setOf(WAREHOUSE_MANAGER),
            conditions = listOf(
                PolicyCondition("context.hour", PolicyOperator.GREATER_OR_EQUAL, 8L),
                PolicyCondition("context.hour", PolicyOperator.LESS_THAN, 18L),
            ),
        ),
        Policy(
            id = 6,
            name = "Ajustes de daño solo en horario laboral",
            description = "Mismo criterio que los faltantes: fuera de horario, escala.",
            action = "inventory.adjust_damage",
            effect = PolicyEffect.ALLOW,
            priority = 20,
            roles = setOf(WAREHOUSE_MANAGER),
            conditions = listOf(
                PolicyCondition("context.hour", PolicyOperator.GREATER_OR_EQUAL, 8L),
                PolicyCondition("context.hour", PolicyOperator.LESS_THAN, 18L),
            ),
        ),
        Policy(
            id = 7,
            name = "Ajustes de fin de semana escalan a gerencia",
            description = "Sábado y domingo (weekday 6 y 0) el jefe de bodega no ajusta: " +
                "lo hace gerencia, que sí tiene el permiso sin esta restricción.",
            action = "inventory.adjust_shortage",
            effect = PolicyEffect.DENY,
            priority = 30,
            roles = setOf(WAREHOUSE_MANAGER),
            conditions = listOf(
                PolicyCondition("context.weekday", PolicyOperator.IN, listOf(0L, 6L)),
            ),
        ),
        Policy(
            id = 8,
            name = "Corrección de conteo siempre permitida en horario",
            description = "Corregir un error de conteo es la operación de ajuste menos " +
                "sensible: se permite sin restricción de día.",
            action = "inventory.adjust_count_error",
            effect = PolicyEffect.ALLOW,
            priority = 10,
            roles = setOf(WAREHOUSE_MANAGER),
        ),

        // ═════════════════════════════════════════════════════════════════════
        // PRODUCTOS DE ALTO VALOR
        // ═════════════════════════════════════════════════════════════════════

        Policy(
            id = 9,
            name = "Los productos de alto valor los mueve el jefe de bodega",
            description = "Un operador no mueve producto cuyo costo unitario supera 5.000. " +
                "Los productos caros requieren supervisión.",
            action = "inventory.move",
            effect = PolicyEffect.DENY,
            priority = 30,
            roles = setOf(OPERATOR),
            conditions = listOf(
                PolicyCondition("resource.unitCost", PolicyOperator.GREATER_THAN, 5_000L),
            ),
        ),

        // ═════════════════════════════════════════════════════════════════════
        // UBICACIÓN: recepción frente a estante
        // ═════════════════════════════════════════════════════════════════════

        Policy(
            id = 10,
            name = "El operador ubica lo que él recibió, desde recepción",
            description = "Ubicar mercancía recién recibida es la operación más frecuente " +
                "del piso: no se estorba. Es la corrección del hueco del legacy, donde el " +
                "rol de almacén no tenía permiso para ubicar (decisión R-77).",
            action = "invoice.locate",
            effect = PolicyEffect.ALLOW,
            priority = 20,
            roles = setOf(OPERATOR),
            conditions = listOf(
                PolicyCondition("resource.locationKind", PolicyOperator.EQUALS, "RECEIVING"),
            ),
        ),

        // ═════════════════════════════════════════════════════════════════════
        // TRANSFERENCIAS ENTRE BODEGAS
        // ═════════════════════════════════════════════════════════════════════

        Policy(
            id = 11,
            name = "Los traslados entre bodegas no son operación de piso",
            description = "Despachar hacia otra bodega lo hacen jefatura y gerencia, " +
                "no un operador.",
            action = "transfer.dispatch",
            effect = PolicyEffect.ALLOW,
            priority = 20,
            roles = setOf(WAREHOUSE_MANAGER, OPERATIONS_MANAGER),
        ),

        // ═════════════════════════════════════════════════════════════════════
        // INTEGRACIÓN CON EL ERP
        // ═════════════════════════════════════════════════════════════════════

        Policy(
            id = 12,
            name = "La importación del ERP solo entra por el canal de servicio",
            description = "La integración con el ERP usa una API key de servicio. " +
                "Un usuario web o móvil no puede importar facturas externas.",
            action = "invoice.import_external",
            effect = PolicyEffect.ALLOW,
            priority = 20,
            conditions = listOf(
                PolicyCondition("context.deviceType", PolicyOperator.EQUALS, "SERVICE"),
            ),
        ),
    )

    /** Índice por id, para los tests. */
    val byId: Map<Long, Policy> = all.associateBy { it.id }
}
