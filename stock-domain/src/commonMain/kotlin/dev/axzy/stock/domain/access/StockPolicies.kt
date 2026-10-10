package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ABAC POLICIES OF THE WAREHOUSE
 *
 * Twelve concrete policies, not theoretical examples. Each one solves a real operational
 * problem, and the first three are the ones that justify ABAC in this
 * dominio:
 *
 *  · **PB3 — segregation of duties** (`DENY` if `capturedBy == user.id`):
 *    it is the answer to decision R-74. Instead of a rigid `CHECK` in the
 *    database, it is expressed as a **configurable policy**: if the business
 *    decides that with little staff it may self-approve, the policy is changed and
 *    it is audited, without touching code or migrating.
 *
 *  · **PB5/PB6 — schedule**: `RBAC says WHAT; ABAC says WHEN`. The warehouse
 *    manager has `inventory.adjust_shortage`, but not at 3 in the morning.
 *
 *  · **PB7 — high-value products**: an operator does not move expensive product.
 *
 * **Policies cannot grant what RBAC did not give** (rule 5 of the engine).
 * It is the guarantee that ABAC never escalates privileges upward.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object StockPolicies {

    /**
     * Marker that the engine resolves against the **user attempting the action**.
     *
     * It is needed because the segregation-of-duties condition compares the
     * resource with whoever acts (`capturedBy == whoever approves`), and the expected value
     * **is not a constant**: it depends on who makes the request. Without
     * this, the check would have to be hard-coded in the engine, and the policy
     * would stop being configurable.
     */
    const val CURRENT_USER_MARKER = "__CURRENT_USER__"

    private val OPERATOR = BaseRole.WAREHOUSE_OPERATOR.key
    private val WAREHOUSE_MANAGER = BaseRole.WAREHOUSE_MANAGER.key
    private val OPERATIONS_MANAGER = BaseRole.OPERATIONS_MANAGER.key
    private val ADMIN = BaseRole.ADMIN.key

    /** All the policies, as they would be seeded into the database. */
    val all: List<Policy> = listOf(

        // ═════════════════════════════════════════════════════════════════════
        // SEGREGATION OF DUTIES — the most important
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
        // APPROVAL OF DISCREPANCIES (no threshold — decision R-75)
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
        // INVENTORY ADJUSTMENTS
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
        // HIGH-VALUE PRODUCTS
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
        // LOCATION: receiving versus shelf
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
        // TRANSFERS BETWEEN WAREHOUSES
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
        // ERP INTEGRATION
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

    /** Index by id, for the tests. */
    val byId: Map<Long, Policy> = all.associateBy { it.id }
}
