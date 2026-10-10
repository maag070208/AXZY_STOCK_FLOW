package dev.axzy.stock.domain.access

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ROLES BASE Y SU MATRIZ DE PERMISOS
 *
 * Cinco roles. Los tres que están ausentes también son una decisión de diseño:
 *
 *  · **No hay roles por bodega** (`JEFE_BODEGA_NORTE`, `JEFE_BODEGA_SUR`…).
 *    El alcance `AREA` más la asignación de bodegas de la **persona** resuelve
 *    la multi-bodega sin multiplicar roles.
 *
 *  · **No hay un rol `SUPERVISOR` genérico.** "Supervisor" no dice de qué, y
 *    en la práctica termina con permisos de todo para no bloquear el trabajo.
 *    Es el rol que destruye un modelo de permisos.
 *
 *  · **El operador no tiene `inventory.adjust_*`.** Ajustar inventario sin
 *    aprobación es la vía más rápida a un descuadre que nadie detecta.
 *
 * Tres celdas de esta matriz son deliberadas y conviene recordarlas:
 *
 *  1. El **operador no tiene `invoice.approve`**: es lo que hace real la regla
 *     B1. Si lo tuviera, podría aprobar su propio error de conteo y el control
 *     sería decorativo.
 *  2. **`inventory.adjust_theft` es exclusivo de admin y gerente**: declarar un
 *     robo mueve inventario y tiene implicaciones legales.
 *  3. El **auditor no tiene un solo permiso de escritura**: es el rol que
 *     permite auditar sin poder alterar, y la razón por la que el kardex debe
 *     ser append-only. Un auditor no puede confiar en un libro que se edita.
 * ═══════════════════════════════════════════════════════════════════════════
 */
object RoleDefinitions {

    private val A = PermissionScope.ALL
    private val R = PermissionScope.AREA
    private val O = PermissionScope.OWN

    /**
     * Matriz rol → (permiso → alcance).
     *
     * Solo se declaran las celdas **concedidas**: lo ausente es `NONE`, y eso
     * hace que un permiso nuevo en el catálogo **no se conceda por olvido**.
     * El comportamiento por defecto del sistema es *default deny*, y la matriz
     * lo refleja.
     */
    private val admin: Map<String, PermissionScope> =
        // El admin recibe TODOS los permisos del catálogo con alcance ALL,
        // de forma explícita. Es la corrección del hallazgo S3: en el legacy el
        // rol admin no tenía ni un permiso en base de datos y su acceso se
        // resolvía con un `if (role === 1)` hardcodeado. Aquí lo que el admin
        // puede hacer es VISIBLE en la matriz y auditable.
        PermissionCatalog.keys.associateWith { A }

    private val managerOperations: Map<String, PermissionScope> = mapOf(
        // Identidad: ve y consulta, no administra.
        "user.view" to A,
        "audit.view" to A,
        // Catálogo: lectura.
        "product.view" to A,
        // Almacén físico.
        "warehouse.view" to A,
        "location.view" to A,
        "location.print_labels" to A,
        // Recepción: todo salvo anular.
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
        // Inventario: incluido el ajuste por robo, por su implicación legal.
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
        // Reportes: todos, incluidos los sensibles.
        "report.view" to A,
        "report.export" to A,
        "report.financial" to A,
        "report.audit" to A,
        // Sistema: solo el estado.
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
        // Recepción: todo menos anular, incluida la aprobación de discrepancias
        // DENTRO de su bodega. La política PB3 le impide aprobar lo suyo.
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
        // Inventario: ajusta, pero NO por robo (eso es de gerencia).
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
        // Reportes: sin los financieros ni los de auditoría.
        "report.view" to R,
        "report.export" to R,
        "system.health" to A,
    )

    private val operatorWarehouse: Map<String, PermissionScope> = mapOf(
        "product.view" to A,
        "warehouse.view" to A,
        "location.view" to O,
        // Recepción: captura y UBICA. Sin aprobar nada.
        //
        // `invoice.locate` es la corrección de un hueco del legacy: el rol
        // `almacen` no tenía permiso para ubicar, aunque ubicar es el trabajo
        // principal del operador. No era una decisión de negocio, era un
        // permiso que faltaba en el modelo (decisión R-77).
        "invoice.view" to O,
        "invoice.capture" to O,
        "invoice.locate" to O,
        // Surtido.
        "order.view" to O,
        "order.package" to O,
        // Inventario: consulta, cuenta y mueve. NO ajusta.
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
        // Solo lectura, con alcance total. Ni un permiso de escritura.
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

    /** Los cinco roles base, listos para sembrar en la base de datos. */
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

    /** Índice por clave. */
    val byKey: Map<RoleKey, Role> = baseRoles.associateBy { it.key }

    /**
     * Sufijos de acción que **no alteran datos**.
     *
     * Se comparan por sufijo, no por igualdad: `label.template_view` y
     * `report.financial` son de lectura aunque su acción completa no coincida
     * con `view`. Clasificar mal un permiso de lectura como escritura haría que
     * el test del auditor diera un falso positivo —diría que el auditor puede
     * escribir cuando en realidad solo consulta— y ese falso positivo esconde
     * el problema real.
     */
    private val READ_ONLY_ACTION_SUFFIXES = setOf(
        "view", "view_cost", "export", "print", "reprint", "health", "financial", "audit",
    )

    /**
     * Los permisos de **escritura**. Se usan en los tests para verificar que el
     * auditor no tiene ninguno: es la propiedad que hace que el rol sirva.
     *
     * Es un `get()` para que se calcule en el primer uso y no dependa del orden
     * de inicialización del objeto.
     */
    val writePermissions: Set<String>
        get() = PermissionCatalog.all
            .filterNot { permission ->
                READ_ONLY_ACTION_SUFFIXES.any { permission.action.endsWith(it) }
            }
            .map { it.key }
            .toSet()
}
