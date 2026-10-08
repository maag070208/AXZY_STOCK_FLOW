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
        add(p("user.view", PermissionModule.IDENTITY, "Ver usuarios", Ladder))
        add(p("user.create", PermissionModule.IDENTITY, "Crear usuarios", ONLY_ALL))
        add(p("user.edit", PermissionModule.IDENTITY, "Editar usuarios", ONLY_ALL))
        add(p("user.delete", PermissionModule.IDENTITY, "Eliminar usuarios", ONLY_ALL, sensitive = true))
        add(p("user.reset_password", PermissionModule.IDENTITY, "Restablecer contraseña", ONLY_ALL, sensitive = true))
        add(p("role.manage", PermissionModule.IDENTITY, "Administrar roles y permisos", ONLY_ALL, sensitive = true))
        add(p("role.assign", PermissionModule.IDENTITY, "Asignar roles a personas", ONLY_ALL, sensitive = true))
        add(p("permission.exception", PermissionModule.IDENTITY, "Conceder excepciones de permiso", ONLY_ALL, sensitive = true))
        add(p("audit.view", PermissionModule.IDENTITY, "Consultar la auditoría", Ladder, sensitive = true))
        add(p("session.revoke", PermissionModule.IDENTITY, "Revocar sesiones", ONLY_ALL, sensitive = true))

        // ═══ CATÁLOGO ════════════════════════════════════════════════════════
        add(p("product.view", PermissionModule.CATALOG, "Ver productos", ONLY_ALL))
        add(p("product.create", PermissionModule.CATALOG, "Crear productos", ONLY_ALL))
        add(p("product.edit", PermissionModule.CATALOG, "Editar productos", ONLY_ALL))
        add(p("product.delete", PermissionModule.CATALOG, "Desactivar productos", ONLY_ALL, sensitive = true))
        add(p("product.import", PermissionModule.CATALOG, "Importar catálogo", ONLY_ALL, sensitive = true))
        add(p("product.sync_barcode", PermissionModule.CATALOG, "Reasignar códigos de barras", ONLY_ALL, sensitive = true))

        // ═══ ALMACÉN FÍSICO ══════════════════════════════════════════════════
        add(p("warehouse.view", PermissionModule.WAREHOUSE, "Ver bodegas", ONLY_ALL))
        add(p("warehouse.create", PermissionModule.WAREHOUSE, "Crear bodegas", ONLY_ALL))
        add(p("warehouse.edit", PermissionModule.WAREHOUSE, "Editar bodegas", ONLY_ALL))
        add(p("location.view", PermissionModule.WAREHOUSE, "Ver ubicaciones", Ladder))
        add(p("location.create", PermissionModule.WAREHOUSE, "Crear ubicaciones", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("location.edit", PermissionModule.WAREHOUSE, "Editar ubicaciones", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("location.delete", PermissionModule.WAREHOUSE, "Eliminar ubicaciones", ONLY_ALL, sensitive = true))
        add(p("location.print_labels", PermissionModule.WAREHOUSE, "Imprimir etiquetas de ubicación", setOf(PermissionScope.AREA, PermissionScope.ALL)))

        // ═══ RECEPCIÓN ═══════════════════════════════════════════════════════
        add(p("invoice.view", PermissionModule.RECEIVING, "Ver facturas", Ladder))
        add(p("invoice.create", PermissionModule.RECEIVING, "Crear facturas", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("invoice.edit", PermissionModule.RECEIVING, "Editar facturas", Ladder))
        add(p("invoice.delete", PermissionModule.RECEIVING, "Anular facturas", ONLY_ALL, sensitive = true))
        add(p("invoice.capture", PermissionModule.RECEIVING, "Capturar conteo físico", Ladder))
        add(p("invoice.assign", PermissionModule.RECEIVING, "Asignar trabajo de recepción", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("invoice.locate", PermissionModule.RECEIVING, "Ubicar mercancía en estantes", Ladder))
        add(p("invoice.approve", PermissionModule.RECEIVING, "Aprobar discrepancias", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("invoice.reject", PermissionModule.RECEIVING, "Rechazar discrepancias", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("invoice.close", PermissionModule.RECEIVING, "Cerrar facturas", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("invoice.import_external", PermissionModule.RECEIVING, "Importar facturas del ERP", ONLY_ALL, sensitive = true))

        // ═══ SURTIDO ═════════════════════════════════════════════════════════
        add(p("order.view", PermissionModule.FULFILLMENT, "Ver órdenes", Ladder))
        add(p("order.create", PermissionModule.FULFILLMENT, "Crear órdenes", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("order.edit", PermissionModule.FULFILLMENT, "Editar órdenes", Ladder))
        add(p("order.delete", PermissionModule.FULFILLMENT, "Anular órdenes", ONLY_ALL, sensitive = true))
        add(p("order.assign", PermissionModule.FULFILLMENT, "Asignar trabajo de surtido", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("order.package", PermissionModule.FULFILLMENT, "Surtir y empacar", Ladder))
        add(p("order.confirm", PermissionModule.FULFILLMENT, "Confirmar empaque", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("order.convert", PermissionModule.FULFILLMENT, "Exportar orden al ERP", ONLY_ALL, sensitive = true))

        // ═══ INVENTARIO ══════════════════════════════════════════════════════
        add(p("inventory.view", PermissionModule.INVENTORY, "Ver inventario", Ladder))
        add(p("inventory.view_cost", PermissionModule.INVENTORY, "Ver el costo del inventario", ONLY_ALL, sensitive = true))
        add(p("inventory.move", PermissionModule.INVENTORY, "Mover entre estantes", Ladder))
        add(p("inventory.count", PermissionModule.INVENTORY, "Contar inventario", Ladder))
        add(p("inventory.adjust_increase", PermissionModule.INVENTORY, "Ajustar inventario al alza", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("inventory.adjust_count_error", PermissionModule.INVENTORY, "Ajustar por error de conteo", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("inventory.adjust_shortage", PermissionModule.INVENTORY, "Ajustar por faltante", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("inventory.adjust_damage", PermissionModule.INVENTORY, "Ajustar por daño", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("inventory.adjust_theft", PermissionModule.INVENTORY, "Ajustar por robo", ONLY_ALL, sensitive = true))
        add(p("inventory.approve_adjustment", PermissionModule.INVENTORY, "Aprobar ajustes", setOf(PermissionScope.AREA, PermissionScope.ALL), sensitive = true))
        add(p("inventory.reconcile", PermissionModule.INVENTORY, "Reconciliar inventario", ONLY_ALL, sensitive = true))
        add(p("kardex.view", PermissionModule.INVENTORY, "Ver el kardex", Ladder))
        add(p("kardex.export", PermissionModule.INVENTORY, "Exportar el kardex", setOf(PermissionScope.AREA, PermissionScope.ALL)))

        // ═══ TRANSFERENCIAS ══════════════════════════════════════════════════
        add(p("transfer.view", PermissionModule.TRANSFER, "Ver transferencias", Ladder))
        add(p("transfer.create", PermissionModule.TRANSFER, "Crear transferencias", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("transfer.dispatch", PermissionModule.TRANSFER, "Despachar transferencias", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("transfer.receive", PermissionModule.TRANSFER, "Recibir transferencias", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("transfer.cancel", PermissionModule.TRANSFER, "Cancelar transferencias", ONLY_ALL, sensitive = true))
        add(p("transit.view", PermissionModule.TRANSFER, "Ver mercancía en tránsito", setOf(PermissionScope.AREA, PermissionScope.ALL)))

        // ═══ ETIQUETADO ══════════════════════════════════════════════════════
        add(p("label.print", PermissionModule.LABELING, "Imprimir etiquetas", Ladder))
        add(p("label.reprint", PermissionModule.LABELING, "Reimprimir etiquetas", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("label.template_view", PermissionModule.LABELING, "Ver plantillas de etiqueta", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("label.template_edit", PermissionModule.LABELING, "Editar plantillas de etiqueta", ONLY_ALL, sensitive = true))
        add(p("label.queue_view", PermissionModule.LABELING, "Ver la cola de impresión", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("label.queue_manage", PermissionModule.LABELING, "Gestionar la cola de impresión", ONLY_ALL, sensitive = true))

        // ═══ REPORTES ════════════════════════════════════════════════════════
        add(p("report.view", PermissionModule.REPORT, "Ver reportes", Ladder))
        add(p("report.export", PermissionModule.REPORT, "Exportar reportes", setOf(PermissionScope.AREA, PermissionScope.ALL)))
        add(p("report.financial", PermissionModule.REPORT, "Ver reportes financieros", ONLY_ALL, sensitive = true))
        add(p("report.audit", PermissionModule.REPORT, "Ver reportes de auditoría", ONLY_ALL, sensitive = true))

        // ═══ SISTEMA ═════════════════════════════════════════════════════════
        add(p("system.config", PermissionModule.SYSTEM, "Configurar el sistema", ONLY_ALL, sensitive = true))
        add(p("system.health", PermissionModule.SYSTEM, "Ver el estado del sistema", ONLY_ALL))
        add(p("print_device.manage", PermissionModule.SYSTEM, "Gestionar la impresora", ONLY_ALL, sensitive = true))
        add(p("integration.manage", PermissionModule.SYSTEM, "Gestionar integraciones", ONLY_ALL, sensitive = true))
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
            "Permiso desconocido: '$key'. Si es nuevo, debe declararse en el catálogo " +
                "y sembrarse en la base de datos; si es un typo, el test de biyección " +
                "permisos ↔ rutas lo habría detectado."
        )

    /** Todos los permisos de un módulo, en orden. */
    fun byModule(module: PermissionModule): List<Permission> =
        all.filter { it.module == module }
}
