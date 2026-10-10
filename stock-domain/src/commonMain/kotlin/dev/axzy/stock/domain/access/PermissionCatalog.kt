package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CATÁLOGO DE PERMISOS DEL ALMACÉN
 *
 * Ocho módulos. Cada permiso declara sus **alcances admitidos** y si es
 * **sensible** (su uso se audita siempre).
 *
 * La descomposición más importante respecto al legacy es **"sacar"**: el legacy
 * tenía un solo `location:read` cubriendo consultar, mover **y ajustar**
 * inventario. Aquí son permisos distintos, porque son operaciones con riesgo
 * distinto:
 *
 *   · `order.package`             — sacar para surtir (operación normal)
 *   · `inventory.adjust_shortage` — sacar por merma (exige motivo y aprobación)
 *   · `transfer.dispatch`         — sacar para otra bodega
 *
 * Y `inventory.adjust_*` está descompuesto **por motivo**: no es lo mismo
 * corregir un error de conteo que declarar un robo. Declarar un robo tiene
 * implicaciones legales y requiere más autoridad.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object PermissionCatalog {

    // ── Atajos de alcance, para que las declaraciones se lean ───────────────
    private val ONLY_ALL = setOf(PermissionScope.ALL)
    private val Ladder = setOf(PermissionScope.OWN, PermissionScope.AREA, PermissionScope.ALL)

    /**
     * Todos los permisos, en orden de presentación.
     *
     * El `sortOrder` se asigna DESPUÉS de construir la lista: usar `all.size`
     * dentro de la construcción referenciaría la lista que aún se está
     * construyendo, con un orden impredecible.
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

        // ═══ CATÁLOGO ════════════════════════════════════════════════════════
        add(p("product.view", PermissionModule.CATALOG, "View products", ONLY_ALL))
        add(p("product.create", PermissionModule.CATALOG, "Create products", ONLY_ALL))
        add(p("product.edit", PermissionModule.CATALOG, "Edit products", ONLY_ALL))
        add(p("product.delete", PermissionModule.CATALOG, "Deactivate products", ONLY_ALL, sensitive = true))
        add(p("product.import", PermissionModule.CATALOG, "Import catalog", ONLY_ALL, sensitive = true))
        add(p("product.sync_barcode", PermissionModule.CATALOG, "Reassign barcodes", ONLY_ALL, sensitive = true))

        // ═══ ALMACÉN FÍSICO ══════════════════════════════════════════════════
        add(p("warehouse.view", PermissionModule.WAREHOUSE, "View warehouses", ONLY_ALL))
        add(p("warehouse.create", PermissionModule.WAREHOUSE, "Create warehouses", ONLY_ALL))
        add(p("warehouse.edit", PermissionModule.WAREHOUSE, "Edit warehouses", ONLY_ALL))
        add(p("location.view", PermissionModule.WAREHOUSE, "View locations", Ladder))
        add(p("location.create", PermissionModule.WAREHOUSE, "Create locations", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("location.edit", PermissionModule.WAREHOUSE, "Edit locations", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("location.delete", PermissionModule.WAREHOUSE, "Delete locations", ONLY_ALL, sensitive = true))
        add(p("location.print_labels", PermissionModule.WAREHOUSE, "Print location labels", setOf(PermissionScope.AREA, PermissionScope.ALL)))

        // ═══ RECEPCIÓN ═══════════════════════════════════════════════════════
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
    // El ORDEN importa: en Kotlin, un `val` no puede referenciar otro declarado
    // más abajo. Por eso `all` va primero y los índices después, y los miembros
    // costosos son `get()` para que se calculen en el primer uso.
    // ═════════════════════════════════════════════════════════════════════════

    /** Los permisos con su orden de presentación ya fijado. */
    val all: List<Permission> = declared.mapIndexed { index, permission ->
        permission.copy(sortOrder = index)
    }

    /** Índice por clave, para resolución O(1). */
    private val byKey: Map<String, Permission> by lazy { all.associateBy { it.key } }

    /** Claves de todos los permisos. Es el contrato con la base de datos. */
    val keys: Set<String> get() = byKey.keys

    /** Los permisos sensibles: su uso se audita siempre (Nivel 3). */
    val sensitive: List<Permission> get() = all.filter { it.sensitive }

    /** Busca un permiso por su clave. */
    fun find(key: String): Permission? = byKey[key]

    /**
     * Exige que la clave exista. Se usa donde una clave desconocida es un error
     * de programación, no una entrada de usuario.
     */
    fun require_(key: String): Permission = byKey[key]
        ?: error(
            "Unknown permission: '$key'. If it is new, it must be declared in the catalog " +
                "and be seeded into the database; if it is a typo, the bijection test" +
                "permissions ↔ routes would have detected it."
        )

    /** Todos los permisos de un módulo, en orden. */
    fun byModule(module: PermissionModule): List<Permission> =
        all.filter { it.module == module }
}
