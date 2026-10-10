package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * WAREHOUSE PERMISSION CATALOG
 *
 * Eight modules. Each permission declares its **admitted scopes** and whether it is
 * **sensitive** (its use is always audited).
 *
 * The most important break from the legacy is **"taking out"**: the legacy
 * had a single `location:read` covering querying, moving **and adjusting**
 * inventory. Here they are different permissions, because they are operations with risk
 * distinto:
 *
 *   · `order.package`             — taking out to fulfill (normal operation)
 *   · `inventory.adjust_shortage` — taking out as shrinkage (requires a reason and approval)
 *   · `transfer.dispatch`         — taking out for another warehouse
 *
 * And `inventory.adjust_*` is broken down **by reason**: it is not the same thing
 * to correct a counting error as to declare a theft. Declaring a theft has
 * legal implications and requires more authority.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object PermissionCatalog {

    // ── Scope shortcuts, so that the declarations read well ───────────────────
    private val ONLY_ALL = setOf(PermissionScope.ALL)
    private val Ladder = setOf(PermissionScope.OWN, PermissionScope.AREA, PermissionScope.ALL)

    /**
     * All the permissions, in presentation order.
     *
     * The `sortOrder` is assigned AFTER building the list: using `all.size`
     * inside the construction would reference the list that is still being
     * built, with an unpredictable order.
     */
    private val declared: List<Permission> = buildList {
        // ═══ IDENTIDAD ═══════════════════════════════════════════════════════
        add(p("user.view", PermissionModule.IDENTITY, "View users", Ladder))
        add(p("user.create", PermissionModule.IDENTITY, "Create users", ONLY_ALL))
        add(p("user.edit", PermissionModule.IDENTITY, "Edit users", ONLY_ALL))
        add(p("user.delete", PermissionModule.IDENTITY, "Delete users", ONLY_ALL, sensitive = true))
        add(p("user.reset_password", PermissionModule.IDENTITY, "Reset password", ONLY_ALL, sensitive = true))
        add(p("role.manage", PermissionModule.IDENTITY, "Manage roles and permissions", ONLY_ALL, sensitive = true))
        add(p("role.assign", PermissionModule.IDENTITY, "Assign roles to people", ONLY_ALL, sensitive = true))
        add(p("permission.exception", PermissionModule.IDENTITY, "Grant permission exceptions", ONLY_ALL, sensitive = true))
        add(p("audit.view", PermissionModule.IDENTITY, "View the audit log", Ladder, sensitive = true))
        add(p("session.revoke", PermissionModule.IDENTITY, "Revocar sesiones", ONLY_ALL, sensitive = true))

        // ═══ CATALOG ═══════════════════════════════════════════════════════════
        add(p("product.view", PermissionModule.CATALOG, "View products", ONLY_ALL))
        add(p("product.create", PermissionModule.CATALOG, "Create products", ONLY_ALL))
        add(p("product.edit", PermissionModule.CATALOG, "Edit products", ONLY_ALL))
        add(p("product.delete", PermissionModule.CATALOG, "Deactivate products", ONLY_ALL, sensitive = true))
        add(p("product.import", PermissionModule.CATALOG, "Import catalog", ONLY_ALL, sensitive = true))
        add(p("product.sync_barcode", PermissionModule.CATALOG, "Reassign barcodes", ONLY_ALL, sensitive = true))

        // ═══ PHYSICAL WAREHOUSE ═══════════════════════════════════════════════════
        add(p("warehouse.view", PermissionModule.WAREHOUSE, "View warehouses", ONLY_ALL))
        add(p("warehouse.create", PermissionModule.WAREHOUSE, "Create warehouses", ONLY_ALL))
        add(p("warehouse.edit", PermissionModule.WAREHOUSE, "Edit warehouses", ONLY_ALL))
        add(p("location.view", PermissionModule.WAREHOUSE, "View locations", Ladder))
        add(p("location.create", PermissionModule.WAREHOUSE, "Create locations", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("location.edit", PermissionModule.WAREHOUSE, "Edit locations", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("location.delete", PermissionModule.WAREHOUSE, "Delete locations", ONLY_ALL, sensitive = true))
        add(p("location.print_labels", PermissionModule.WAREHOUSE, "Print location labels", setOf(PermissionScope.AREA, PermissionScope.ALL)))

        // ═══ RECEIVING ════════════════════════════════════════════════════════
        add(p("invoice.view", PermissionModule.RECEIVING, "View invoices", Ladder))
        add(p("invoice.create", PermissionModule.RECEIVING, "Create invoices", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("invoice.edit", PermissionModule.RECEIVING, "Edit invoices", Ladder))
        add(p("invoice.delete", PermissionModule.RECEIVING, "Void invoices", ONLY_ALL, sensitive = true))
        add(p("invoice.capture", PermissionModule.RECEIVING, "Capture physical count", Ladder))
        add(p("invoice.assign", PermissionModule.RECEIVING, "Assign receiving work", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("invoice.locate", PermissionModule.RECEIVING, "Locate goods on shelves", Ladder))
        add(p("invoice.approve", PermissionModule.RECEIVING, "Approve discrepancies", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("invoice.reject", PermissionModule.RECEIVING, "Reject discrepancies", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("invoice.close", PermissionModule.RECEIVING, "Close invoices", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("invoice.import_external", PermissionModule.RECEIVING, "Import ERP invoices", ONLY_ALL, sensitive = true))

        // ═══ SURTIDO ═════════════════════════════════════════════════════════
        add(p("order.view", PermissionModule.FULFILLMENT, "View orders", Ladder))
        add(p("order.create", PermissionModule.FULFILLMENT, "Create orders", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("order.edit", PermissionModule.FULFILLMENT, "Edit orders", Ladder))
        add(p("order.delete", PermissionModule.FULFILLMENT, "Cancel orders", ONLY_ALL, sensitive = true))
        add(p("order.assign", PermissionModule.FULFILLMENT, "Assign fulfillment work", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("order.package", PermissionModule.FULFILLMENT, "Fulfill and pack", Ladder))
        add(p("order.confirm", PermissionModule.FULFILLMENT, "Confirm packing", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("order.convert", PermissionModule.FULFILLMENT, "Export order to ERP", ONLY_ALL, sensitive = true))

        // ═══ INVENTARIO ══════════════════════════════════════════════════════
        add(p("inventory.view", PermissionModule.INVENTORY, "View inventory", Ladder))
        add(p("inventory.view_cost", PermissionModule.INVENTORY, "View the inventory cost", ONLY_ALL, sensitive = true))
        add(p("inventory.move", PermissionModule.INVENTORY, "Move between shelves", Ladder))
        add(p("inventory.count", PermissionModule.INVENTORY, "Count inventory", Ladder))
        add(p("inventory.adjust_increase", PermissionModule.INVENTORY, "Adjust inventory upward", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("inventory.adjust_count_error", PermissionModule.INVENTORY, "Adjust for count error", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("inventory.adjust_shortage", PermissionModule.INVENTORY, "Adjust for shortage", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("inventory.adjust_damage", PermissionModule.INVENTORY, "Adjust for damage", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("inventory.adjust_theft", PermissionModule.INVENTORY, "Adjust for theft", ONLY_ALL, sensitive = true))
        add(p("inventory.approve_adjustment", PermissionModule.INVENTORY, "Approve adjustments", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("inventory.reconcile", PermissionModule.INVENTORY, "Reconciliar inventario", ONLY_ALL, sensitive = true))
        add(p("kardex.view", PermissionModule.INVENTORY, "View the ledger", Ladder))
        add(p("kardex.export", PermissionModule.INVENTORY, "Export the ledger", setOf(PermissionScope.AREA, PermissionScope.ALL)))

        // ═══ TRANSFERENCIAS ══════════════════════════════════════════════════
        add(p("transfer.view", PermissionModule.TRANSFER, "View transfers", Ladder))
        add(p("transfer.create", PermissionModule.TRANSFER, "Create transfers", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("transfer.dispatch", PermissionModule.TRANSFER, "Dispatch transfers", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("transfer.receive", PermissionModule.TRANSFER, "Receive transfers", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("transfer.cancel", PermissionModule.TRANSFER, "Cancel transfers", ONLY_ALL, sensitive = true))
        add(p("transit.view", PermissionModule.TRANSFER, "View goods in transit", setOf(PermissionScope.AREA, PermissionScope.ALL)))

        // ═══ ETIQUETADO ══════════════════════════════════════════════════════
        add(p("label.print", PermissionModule.LABELING, "Print labels", Ladder))
        add(p("label.reprint", PermissionModule.LABELING, "Reprint labels", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("label.template_view", PermissionModule.LABELING, "View label templates", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("label.template_edit", PermissionModule.LABELING, "Edit label templates", ONLY_ALL, sensitive = true))
        add(p("label.queue_view", PermissionModule.LABELING, "View the print queue", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("label.queue_manage", PermissionModule.LABELING, "Manage the print queue", ONLY_ALL, sensitive = true))

        // ═══ REPORTES ════════════════════════════════════════════════════════
        add(p("report.view", PermissionModule.REPORT, "View reports", Ladder))
        add(p("report.export", PermissionModule.REPORT, "Export reports", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("report.financial", PermissionModule.REPORT, "View financial reports", ONLY_ALL, sensitive = true))
        add(p("report.audit", PermissionModule.REPORT, "View audit reports", ONLY_ALL, sensitive = true))

        // ═══ SISTEMA ═════════════════════════════════════════════════════════
        add(p("system.config", PermissionModule.SYSTEM, "Configure the system", ONLY_ALL, sensitive = true))
        add(p("system.health", PermissionModule.SYSTEM, "View system status", ONLY_ALL))
        add(p("print_device.manage", PermissionModule.SYSTEM, "Manage the printer", ONLY_ALL, sensitive = true))
        add(p("integration.manage", PermissionModule.SYSTEM, "Manage integrations", ONLY_ALL, sensitive = true))
    }

    private fun p(
        key: String,
        module: PermissionModule,
        name: String,
        scopes: Set<PermissionScope>,
        sensitive: Boolean = false,
    ) = Permission(
        key = key,
        module = module,
        name = name,
        scopes = scopes,
        sensitive = sensitive,
    )

    // ═════════════════════════════════════════════════════════════════════════
    // MIEMBROS DERIVADOS
    //
    // ORDER matters: in Kotlin, a `val` cannot reference another declared
    // further down. That is why `all` goes first and the indices after, and the costly members
    // are `get()` so that they are computed on first use.
    // ═════════════════════════════════════════════════════════════════════════

    /** The permissions with their presentation order already set. */
    val all: List<Permission> = declared.mapIndexed { index, permission ->
        permission.copy(sortOrder = index)
    }

    /** Index by key, for O(1) resolution. */
    private val byKey: Map<String, Permission> by lazy { all.associateBy { it.key } }

    /** Keys of all the permissions. It is the contract with the database. */
    val keys: Set<String> get() = byKey.keys

    /** The sensitive permissions: their use is always audited (Level 3). */
    val sensitive: List<Permission> get() = all.filter { it.sensitive }

    /** Looks up a permission by its key. */
    fun find(key: String): Permission? = byKey[key]

    /**
     * Requires that the key exists. It is used where an unknown key is a programming error,
     * not a user input.
     */
    fun require_(key: String): Permission = byKey[key]
        ?: error(
            "Unknown permission: '$key'. If it is new, it must be declared in the catalog " +
                "and be seeded into the database; if it is a typo, the bijection test" +
                "permissions ↔ routes would have detected it."
        )

    /** All the permissions of a module, in order. */
    fun byModule(module: PermissionModule): List<Permission> =
        all.filter { it.module == module }
}
