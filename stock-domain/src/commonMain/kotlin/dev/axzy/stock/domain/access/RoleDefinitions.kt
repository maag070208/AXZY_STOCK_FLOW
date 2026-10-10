package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BASE ROLES AND THEIR PERMISSION MATRIX
 *
 * Five roles. The three that are absent are also a design decision:
 *
 *  · **There are no roles per warehouse** (`JEFE_BODEGA_NORTE`, `JEFE_BODEGA_SUR`…).
 *    The `AREA` scope plus the **person's** warehouse assignment solves
 *    multi-warehouse without multiplying roles.
 *
 *  · **There is no generic `SUPERVISOR` role.** "Supervisor" does not say of what, and
 *    in practice it ends up with permissions for everything so as not to block the work.
 *    It is the role that destroys a permission model.
 *
 *  · **The operator does not have `inventory.adjust_*`.** Adjusting inventory without
 *    approval is the fastest route to a discrepancy that nobody detects.
 *
 * Three cells of this matrix are deliberate and worth remembering:
 *
 *  1. The **operator does not have `invoice.approve`**: that is what makes rule
 *     B1 real. If they had it, they could approve their own counting error and the control
 *     would be decorative.
 *  2. **`inventory.adjust_theft` is exclusive to admin and manager**: declaring a theft
 *     moves inventory and has legal implications.
 *  3. The **auditor has no write permission at all**: it is the role that
 *     allows auditing without being able to alter, and the reason the ledger must
 *     be append-only. An auditor cannot trust a book that gets edited.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object RoleDefinitions {

    private val A = PermissionScope.ALL
    private val R = PermissionScope.AREA
    private val O = PermissionScope.OWN

    /**
     * Matriz rol → (permiso → alcance).
     *
     * Only the **granted** cells are declared: what is absent is `NONE`, and that
     * means a new permission in the catalog **is not granted by oversight**.
     * The system's default behavior is *default deny*, and the matrix
     * reflects it.
     */
    private val admin: Map<String, PermissionScope> =
        // The admin receives ALL the catalog's permissions with ALL scope,
        // explicitly. It is the correction of the S3 finding: in the legacy the
        // admin role had not a single permission in the database and its access was
        // resolved with a hard-coded `if (role === 1)`. Here what the admin can do is
        // VISIBLE in the matrix and auditable.
        PermissionCatalog.keys.associateWith { A }

    private val managerOperations: Map<String, PermissionScope> = mapOf(
        // Identity: views and queries, does not administer.
        "user.view" to A,
        "audit.view" to A,
        // Catalog: read.
        "product.view" to A,
        // Physical warehouse.
        "warehouse.view" to A,
        "location.view" to A,
        "location.print_labels" to A,
        // Receiving: everything except cancelling.
        "invoice.view" to A,
        "invoice.create" to A,
        "invoice.edit" to A,
        "invoice.capture" to A,
        "invoice.assign" to A,
        "invoice.locate" to A,
        "invoice.approve" to A,
        "invoice.reject" to A,
        "invoice.close" to A,
        // Surtido.
        "order.view" to A,
        "order.create" to A,
        "order.edit" to A,
        "order.assign" to A,
        "order.package" to A,
        "order.confirm" to A,
        // Inventory: including the theft adjustment, because of its legal implication.
        "inventory.view" to A,
        "inventory.view_cost" to A,
        "inventory.move" to A,
        "inventory.count" to A,
        "inventory.adjust_increase" to A,
        "inventory.adjust_count_error" to A,
        "inventory.adjust_shortage" to A,
        "inventory.adjust_damage" to A,
        "inventory.adjust_theft" to A,
        "inventory.approve_adjustment" to A,
        "kardex.view" to A,
        "kardex.export" to A,
        // Transferencias.
        "transfer.view" to A,
        "transfer.create" to A,
        "transfer.dispatch" to A,
        "transfer.receive" to A,
        "transit.view" to A,
        // Etiquetado.
        "label.print" to A,
        "label.reprint" to A,
        "label.template_view" to A,
        "label.queue_view" to A,
        // Reports: all of them, including the sensitive ones.
        "report.view" to A,
        "report.export" to A,
        "report.financial" to A,
        "report.audit" to A,
        // System: only the status.
        "system.health" to A,
    )

    private val managerWarehouse: Map<String, PermissionScope> = mapOf(
        "user.view" to R,
        "audit.view" to R,
        "product.view" to A,
        "warehouse.view" to A,
        "location.view" to R,
        "location.create" to R,
        "location.edit" to R,
        "location.print_labels" to R,
        // Receiving: everything except cancelling, including approving discrepancies
        // WITHIN their warehouse. The PB3 policy prevents them from approving their own.
        "invoice.view" to R,
        "invoice.create" to R,
        "invoice.edit" to R,
        "invoice.capture" to R,
        "invoice.assign" to R,
        "invoice.locate" to R,
        "invoice.approve" to R,
        "invoice.reject" to R,
        "invoice.close" to R,
        // Surtido.
        "order.view" to R,
        "order.create" to R,
        "order.edit" to R,
        "order.assign" to R,
        "order.package" to R,
        "order.confirm" to R,
        // Inventory: adjusts, but NOT for theft (that belongs to management).
        "inventory.view" to R,
        "inventory.move" to R,
        "inventory.count" to R,
        "inventory.adjust_increase" to R,
        "inventory.adjust_count_error" to R,
        "inventory.adjust_shortage" to R,
        "inventory.adjust_damage" to R,
        "inventory.approve_adjustment" to R,
        "kardex.view" to R,
        "kardex.export" to R,
        // Transferencias.
        "transfer.view" to R,
        "transfer.create" to R,
        "transfer.dispatch" to R,
        "transfer.receive" to R,
        "transit.view" to R,
        // Etiquetado.
        "label.print" to R,
        "label.reprint" to R,
        "label.template_view" to R,
        "label.queue_view" to R,
        // Reports: without the financial and audit ones.
        "report.view" to R,
        "report.export" to R,
        "system.health" to A,
    )

    private val operatorWarehouse: Map<String, PermissionScope> = mapOf(
        "product.view" to A,
        "warehouse.view" to A,
        "location.view" to O,
        // Receiving: counts and LOCATES. Without approving anything.
        //
        // `invoice.locate` is the correction of a gap in the legacy: the role
        // `almacen` did not have permission to locate, although locating is the operator's main
        // job. It was not a business decision, it was a
        // missing permission in the model (decision R-77).
        "invoice.view" to O,
        "invoice.capture" to O,
        "invoice.locate" to O,
        // Surtido.
        "order.view" to O,
        "order.package" to O,
        // Inventory: queries, counts and moves. It does NOT adjust.
        "inventory.view" to O,
        "inventory.move" to O,
        "inventory.count" to O,
        "kardex.view" to O,
        "transfer.view" to O,
        "transit.view" to O,
        "label.print" to O,
        "system.health" to A,
    )

    private val auditor: Map<String, PermissionScope> = mapOf(
        // Read-only, with full scope. Not a single write permission.
        "user.view" to A,
        "audit.view" to A,
        "product.view" to A,
        "warehouse.view" to A,
        "location.view" to A,
        "invoice.view" to A,
        "order.view" to A,
        "inventory.view" to A,
        "inventory.view_cost" to A,
        "kardex.view" to A,
        "kardex.export" to A,
        "transfer.view" to A,
        "transit.view" to A,
        "label.template_view" to A,
        "label.queue_view" to A,
        "report.view" to A,
        "report.export" to A,
        "report.financial" to A,
        "report.audit" to A,
        "system.health" to A,
    )

    /** The five base roles, ready to seed in the database. */
    val baseRoles: List<Role> = listOf(
        Role(
            key = BaseRole.ADMIN.key,
            name = BaseRole.ADMIN.displayName,
            description = BaseRole.ADMIN.oneLiner,
            permissions = admin,
            isSystem = true,
            sortOrder = 0,
        ),
        Role(
            key = BaseRole.OPERATIONS_MANAGER.key,
            name = BaseRole.OPERATIONS_MANAGER.displayName,
            description = BaseRole.OPERATIONS_MANAGER.oneLiner,
            permissions = managerOperations,
            isSystem = true,
            sortOrder = 1,
        ),
        Role(
            key = BaseRole.WAREHOUSE_MANAGER.key,
            name = BaseRole.WAREHOUSE_MANAGER.displayName,
            description = BaseRole.WAREHOUSE_MANAGER.oneLiner,
            permissions = managerWarehouse,
            isSystem = true,
            sortOrder = 2,
        ),
        Role(
            key = BaseRole.WAREHOUSE_OPERATOR.key,
            name = BaseRole.WAREHOUSE_OPERATOR.displayName,
            description = BaseRole.WAREHOUSE_OPERATOR.oneLiner,
            permissions = operatorWarehouse,
            isSystem = true,
            sortOrder = 3,
        ),
        Role(
            key = BaseRole.AUDITOR.key,
            name = BaseRole.AUDITOR.displayName,
            description = BaseRole.AUDITOR.oneLiner,
            permissions = auditor,
            isSystem = true,
            sortOrder = 4,
        ),
    )

    /** Index by key. */
    val byKey: Map<RoleKey, Role> = baseRoles.associateBy { it.key }

    /**
     * Action suffixes that **do not alter data**.
     *
     * They are compared by suffix, not by equality: `label.template_view` and
     * `report.financial` are read-only even though their full action does not match
     * `view`. Misclassifying a read permission as a write would make
     * the auditor's test give a false positive —it would say the auditor can write when
     * it really only queries— and that false positive hides
     * the real problem.
     */
    private val READ_ONLY_ACTION_SUFFIXES = setOf(
        "view", "view_cost", "export", "print", "reprint", "health", "financial", "audit",
    )

    /**
     * The **write** permissions. They are used in the tests to verify that the auditor
     * has none: it is the property that makes the role work.
     *
     * It is a `get()` so that it is computed on first use and does not depend on the
     * initialization order of the object.
     */
    val writePermissions: Set<String>
        get() = PermissionCatalog.all
            .filterNot { permission ->
                READ_ONLY_ACTION_SUFFIXES.any { permission.action.endsWith(it) }
            }
            .map { it.key }
            .toSet()
}
