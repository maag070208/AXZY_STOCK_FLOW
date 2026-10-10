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
            name = "Approving your own work is forbidden (segregation of duties)",
            description = "No one can authorize a discrepancy they counted themselves. " +
                "Prevents someone from covering up their own count error. Decision R-74.",
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
            name = "The warehouse manager approves discrepancies in their warehouse",
            description = "No quantity threshold: every difference requires approval " +
                "(decision R-75). The control is that it is a DIFFERENT person from the same warehouse.",
            action = "invoice.approve",
            effect = PolicyEffect.ALLOW,
            priority = 10,
            roles = setOf(WAREHOUSE_MANAGER),
        ),
        Policy(
            id = 3,
            name = "Management approves discrepancies in any warehouse",
            description = "The operations manager can approve in all warehouses.",
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
            name = "A theft adjustment requires management",
            description = "Declaring a theft moves inventory and has legal implications. " +
                "Only management and administration can do this.",
            action = "inventory.adjust_theft",
            effect = PolicyEffect.ALLOW,
            priority = 20,
            protected = true,
            roles = setOf(OPERATIONS_MANAGER, ADMIN),
        ),
        Policy(
            id = 5,
            name = "Inventory adjustments only during working hours",
            description = "Inventory adjustments are made between 08:00 and 18:00. " +
                "Outside those hours, specific authorization is required.",
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
            name = "Damage adjustments only during working hours",
            description = "Same criterion as shortages: outside working hours, it escalates.",
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
            name = "Weekend adjustments escalate to management",
            description = "Saturday and Sunday (weekday 6 and 0) the warehouse manager does not adjust: " +
                "management does it, which does have the permission without this restriction.",
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
            name = "Count correction always allowed during working hours",
            description = "Correcting a count error is the least risky adjustment operation " +
                "sensitive: allowed without a day restriction.",
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
            name = "High-value products are moved by the warehouse manager",
            description = "An operator does not move a product whose unit cost exceeds 5,000. " +
                "Expensive products require supervision.",
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
            name = "The operator locates what they received, from receiving",
            description = "Locating freshly received goods is the most frequent operation " +
                "of the floor: it is not in the way. It fixes the legacy gap, where the " +
                "warehouse role had no permission to locate (decision R-77).",
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
            name = "Transfers between warehouses are not a floor operation",
            description = "Dispatching to another warehouse is done by the warehouse manager and management, " +
                "not an operator.",
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
            name = "The ERP import only comes through the service channel",
            description = "The ERP integration uses a service API key. " +
                "A web or mobile user cannot import external invoices.",
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
