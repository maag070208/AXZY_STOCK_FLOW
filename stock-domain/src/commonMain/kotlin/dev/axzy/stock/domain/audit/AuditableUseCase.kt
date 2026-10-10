package dev.axzy.stock.domain.audit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CASOS DE USO CRÍTICOS Y SU DECLARACIÓN DE AUDITORÍA
 *
 * Aquí vive la estrategia para que **"TODO debe ser auditable" siga siendo
 * cierto dentro de dos años**.
 *
 * El problema real: si se implementa la auditoría a mano en cada controlador, en
 * tres meses habrá treinta sitios que registran distinto y diez que se olvidaron.
 * No es una hipótesis: es exactamente lo que pasó con los permisos en el legacy,
 * donde cuatro permisos sembrados nunca se exigían en ninguna ruta.
 *
 * La solución tiene dos partes:
 *
 *  1. **Cada caso de uso crítico declara su auditoría** ([CriticalUseCase]). Es
 *     un registro explícito, no una convención implícita.
 *  2. **Un test estructural verifica que la declaración esté completa** y falla
 *     el build si falta algo. No comprueba un comportamiento: comprueba que
 *     **no falta un registro**. Es la única forma de que la regla no se degrade.
 *
 * La anotación [Auditable] es el mecanismo declarativo para los casos de uso del
 * dominio. El registro [CriticalUseCase] es la lista que el test usa como
 * referencia: **declarar uno sin el otro hace fallar el build**.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Marca un caso de uso del dominio como auditable.
 *
 * Es una anotación, no un logger: el dominio no depende de la infraestructura de
 * auditoría, solo declara **qué** debe auditarse y con qué criticidad. Quien
 * implemente el caso de uso en la capa de aplicación es responsable de construir
 * el [AuditEvent] correspondiente.
 *
 * @param action identificador estable de la acción (`INVOICE_LINE_CAPTURED`).
 * @param level criticidad, que determina tratamiento y retención.
 * @param entity tipo de entidad afectada (`invoice_line`, `kardex`).
 * @param permission permiso exigido. **Obligatorio en los críticos**: sin él no
 *   se puede auditar "con qué permiso".
 * @param captureDiff genera `previousState`/`newState` automáticamente. El diff
 *   campo por campo a mano es donde siempre se olvida un campo.
 * @param reasonRequired exige motivo. En inventario, un cambio sin motivo es un
 *   agujero de auditoría (invariante I6).
 * @param category agrupación para los reportes de auditoría.
 */
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Auditable(
    val action: String,
    val level: AuditLevel,
    val entity: String,
    val permission: String = "",
    val captureDiff: Boolean = true,
    val reasonRequired: Boolean = false,
    val category: AuditCategory = AuditCategory.DOMAIN,
)

/** Agrupación de las acciones auditables, para los reportes. */
enum class AuditCategory(val displayName: String) {
    INVENTORY("inventario"),
    RECEIVING("recepción"),
    FULFILLMENT("surtido"),
    TRANSFER("transferencias"),
    ACCESS_CONTROL("control de acceso"),
    CATALOG("catálogo"),
    LABELING("etiquetado"),
    MIGRATION("migración"),
    DOMAIN("dominio"),
}

/**
 * La declaración de auditoría de un caso de uso crítico.
 *
 * Es un dato, no código ejecutable: permite que el test estructural verifique la
 * cobertura sin reflexión ni escaneo de classpath, que en Kotlin Multiplatform
 * no es viable.
 */
data class AuditDeclaration(
    /** Nombre del caso de uso, para el mensaje de error del test. */
    val useCase: String,
    val action: String,
    val level: AuditLevel,
    val entity: String,
    val permission: String,
    val category: AuditCategory,
    val captureDiff: Boolean = true,
    val reasonRequired: Boolean = false,
) {
    init {
        require(action.isNotBlank()) { "La acción auditada no puede estar vacía: $useCase" }
        require(entity.isNotBlank()) { "La entidad auditada no puede estar vacía: $useCase" }
        require(permission.isNotBlank()) {
            "Un caso de uso auditable DEBE declarar su permiso: sin él no se puede " +
                "responder 'con qué permiso se hizo'. Caso: $useCase"
        }
        require(entity == entity.lowercase()) {
            "El tipo de entidad debe ser minúsculas y singular: '$entity' en $useCase"
        }
    }

    /** La anotación equivalente, para comparar declaración y anotación. */
    fun toAnnotation(): Auditable = Auditable(
        action = action,
        level = level,
        entity = entity,
        permission = permission,
        captureDiff = captureDiff,
        reasonRequired = reasonRequired,
        category = category,
    )
}

/**
 * El registro de todos los casos de uso críticos que **deben** estar auditados.
 *
 * Está organizado por flujo, y cada entrada corresponde a una operación que
 * mueve inventario, dinero o acceso. La lista es la referencia del test
 * estructural: **si alguien añade un caso de uso con `@Auditable` que no está
 * aquí, o al contrario, el build falla.**
 */
object CriticalUseCases {

    /** Recepción: carga inicial y alta de factura. */
    val receiving: List<AuditDeclaration> = listOf(
        AuditDeclaration(
            useCase = "initialLoadCatalog",
            action = "CATALOG_INITIAL_LOAD",
            level = AuditLevel.CRITICAL,
            entity = "product",
            permission = "product.import",
            category = AuditCategory.MIGRATION,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "createInvoice",
            action = "INVOICE_CREATED",
            level = AuditLevel.CRITICAL,
            entity = "invoice",
            permission = "invoice.create",
            category = AuditCategory.RECEIVING,
        ),
        AuditDeclaration(
            useCase = "captureInvoiceLine",
            action = "INVOICE_LINE_CAPTURED",
            level = AuditLevel.CRITICAL,
            entity = "invoice_line",
            permission = "invoice.capture",
            category = AuditCategory.RECEIVING,
        ),
        AuditDeclaration(
            useCase = "approveDiscrepancy",
            action = "INVOICE_DISCREPANCY_APPROVED",
            level = AuditLevel.CRITICAL,
            entity = "invoice_line",
            permission = "invoice.approve",
            category = AuditCategory.RECEIVING,
            // Aprobar una discrepancia mueve inventario: sin motivo es un
            // agujero de auditoría (invariante I6).
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "rejectDiscrepancy",
            action = "INVOICE_DISCREPANCY_REJECTED",
            level = AuditLevel.RELEVANT,
            entity = "invoice_line",
            permission = "invoice.reject",
            category = AuditCategory.RECEIVING,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "locateInvoiceLine",
            action = "INVOICE_LINE_LOCATED",
            level = AuditLevel.CRITICAL,
            entity = "invoice_line",
            permission = "invoice.locate",
            category = AuditCategory.RECEIVING,
        ),
        AuditDeclaration(
            useCase = "closeInvoice",
            action = "INVOICE_CLOSED",
            level = AuditLevel.CRITICAL,
            entity = "invoice",
            permission = "invoice.close",
            category = AuditCategory.RECEIVING,
        ),
        AuditDeclaration(
            useCase = "cancelInvoiceLine",
            action = "INVOICE_LINE_CANCELLED",
            level = AuditLevel.CRITICAL,
            entity = "invoice_line",
            permission = "invoice.edit",
            category = AuditCategory.RECEIVING,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "importInvoiceFromErp",
            action = "INVOICE_IMPORTED_FROM_ERP",
            level = AuditLevel.CRITICAL,
            entity = "invoice",
            permission = "invoice.import_external",
            category = AuditCategory.RECEIVING,
        ),
    )

    /** Surtido y exportación al ERP. */
    val fulfillment: List<AuditDeclaration> = listOf(
        AuditDeclaration(
            useCase = "createOrder",
            action = "ORDER_CREATED",
            level = AuditLevel.CRITICAL,
            entity = "order",
            permission = "order.create",
            category = AuditCategory.FULFILLMENT,
        ),
        AuditDeclaration(
            useCase = "assignOrderLine",
            action = "ORDER_LINE_ASSIGNED",
            level = AuditLevel.RELEVANT,
            entity = "order_line",
            permission = "order.assign",
            category = AuditCategory.FULFILLMENT,
        ),
        AuditDeclaration(
            useCase = "packageOrderLine",
            action = "ORDER_LINE_PACKAGED",
            level = AuditLevel.CRITICAL,
            entity = "order_line",
            permission = "order.package",
            category = AuditCategory.FULFILLMENT,
        ),
        AuditDeclaration(
            useCase = "confirmOrder",
            action = "ORDER_CONFIRMED",
            level = AuditLevel.CRITICAL,
            entity = "order",
            permission = "order.confirm",
            category = AuditCategory.FULFILLMENT,
        ),
        AuditDeclaration(
            useCase = "exportOrderToErp",
            action = "ORDER_EXPORTED_TO_ERP",
            level = AuditLevel.CRITICAL,
            entity = "order",
            permission = "order.convert",
            category = AuditCategory.FULFILLMENT,
        ),
        AuditDeclaration(
            useCase = "cancelOrder",
            action = "ORDER_CANCELLED",
            level = AuditLevel.CRITICAL,
            entity = "order",
            permission = "order.delete",
            category = AuditCategory.FULFILLMENT,
            reasonRequired = true,
        ),
    )

    /** Inventario: el corazón del sistema. Cada movimiento del ledger. */
    val inventory: List<AuditDeclaration> = listOf(
        AuditDeclaration(
            useCase = "moveWithinWarehouse",
            action = "STOCK_MOVED_WITHIN_WAREHOUSE",
            level = AuditLevel.CRITICAL,
            entity = "kardex",
            permission = "inventory.move",
            category = AuditCategory.INVENTORY,
        ),
        AuditDeclaration(
            useCase = "adjustInventoryIncrease",
            action = "STOCK_ADJUSTED_INCREASE",
            level = AuditLevel.CRITICAL,
            entity = "kardex",
            permission = "inventory.adjust_increase",
            category = AuditCategory.INVENTORY,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "adjustInventoryShortage",
            action = "STOCK_ADJUSTED_SHORTAGE",
            level = AuditLevel.CRITICAL,
            entity = "kardex",
            permission = "inventory.adjust_shortage",
            category = AuditCategory.INVENTORY,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "adjustInventoryDamage",
            action = "STOCK_ADJUSTED_DAMAGE",
            level = AuditLevel.CRITICAL,
            entity = "kardex",
            permission = "inventory.adjust_damage",
            category = AuditCategory.INVENTORY,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "adjustInventoryTheft",
            action = "STOCK_ADJUSTED_THEFT",
            level = AuditLevel.CRITICAL,
            entity = "kardex",
            permission = "inventory.adjust_theft",
            category = AuditCategory.INVENTORY,
            // Por implicación legal, el motivo es obligatorio.
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "adjustInventoryCountError",
            action = "STOCK_ADJUSTED_COUNT_ERROR",
            level = AuditLevel.CRITICAL,
            entity = "kardex",
            permission = "inventory.adjust_count_error",
            category = AuditCategory.INVENTORY,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "approveAdjustment",
            action = "STOCK_ADJUSTMENT_APPROVED",
            level = AuditLevel.CRITICAL,
            entity = "kardex",
            permission = "inventory.approve_adjustment",
            category = AuditCategory.INVENTORY,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "countInventory",
            action = "INVENTORY_COUNTED",
            level = AuditLevel.RELEVANT,
            entity = "location",
            permission = "inventory.count",
            category = AuditCategory.INVENTORY,
        ),
        AuditDeclaration(
            useCase = "reconcileInventory",
            action = "INVENTORY_RECONCILED",
            level = AuditLevel.CRITICAL,
            entity = "location",
            permission = "inventory.reconcile",
            category = AuditCategory.INVENTORY,
            reasonRequired = true,
        ),
    )

    /** Transferencias entre bodegas. */
    val transfers: List<AuditDeclaration> = listOf(
        AuditDeclaration(
            useCase = "createTransfer",
            action = "TRANSFER_CREATED",
            level = AuditLevel.CRITICAL,
            entity = "transfer",
            permission = "transfer.create",
            category = AuditCategory.TRANSFER,
        ),
        AuditDeclaration(
            useCase = "dispatchTransfer",
            action = "TRANSFER_DISPATCHED",
            level = AuditLevel.CRITICAL,
            entity = "transfer",
            permission = "transfer.dispatch",
            category = AuditCategory.TRANSFER,
        ),
        AuditDeclaration(
            useCase = "receiveTransfer",
            action = "TRANSFER_RECEIVED",
            level = AuditLevel.CRITICAL,
            entity = "transfer",
            permission = "transfer.receive",
            category = AuditCategory.TRANSFER,
        ),
        AuditDeclaration(
            useCase = "cancelTransfer",
            action = "TRANSFER_CANCELLED",
            level = AuditLevel.CRITICAL,
            entity = "transfer",
            permission = "transfer.cancel",
            category = AuditCategory.TRANSFER,
            reasonRequired = true,
        ),
    )

    /** Control de acceso: cada cambio de rol, permiso o política. */
    val accessControl: List<AuditDeclaration> = listOf(
        AuditDeclaration(
            useCase = "createRole",
            action = "ROLE_CREATED",
            level = AuditLevel.CRITICAL,
            entity = "role",
            permission = "role.manage",
            category = AuditCategory.ACCESS_CONTROL,
        ),
        AuditDeclaration(
            useCase = "updateRole",
            action = "ROLE_UPDATED",
            level = AuditLevel.CRITICAL,
            entity = "role",
            permission = "role.manage",
            category = AuditCategory.ACCESS_CONTROL,
        ),
        AuditDeclaration(
            useCase = "deleteRole",
            action = "ROLE_DELETED",
            level = AuditLevel.CRITICAL,
            entity = "role",
            permission = "role.manage",
            category = AuditCategory.ACCESS_CONTROL,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "updateRolePermissions",
            action = "ROLE_PERMISSIONS_UPDATED",
            level = AuditLevel.CRITICAL,
            entity = "role",
            permission = "role.manage",
            category = AuditCategory.ACCESS_CONTROL,
        ),
        AuditDeclaration(
            useCase = "createPolicy",
            action = "POLICY_CREATED",
            level = AuditLevel.CRITICAL,
            entity = "policy",
            permission = "role.manage",
            category = AuditCategory.ACCESS_CONTROL,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "updatePolicy",
            action = "POLICY_UPDATED",
            level = AuditLevel.CRITICAL,
            entity = "policy",
            permission = "role.manage",
            category = AuditCategory.ACCESS_CONTROL,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "deletePolicy",
            action = "POLICY_DELETED",
            level = AuditLevel.CRITICAL,
            entity = "policy",
            permission = "role.manage",
            category = AuditCategory.ACCESS_CONTROL,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "assignRoleToUser",
            action = "USER_ROLE_ADDED",
            level = AuditLevel.CRITICAL,
            entity = "user",
            permission = "role.assign",
            category = AuditCategory.ACCESS_CONTROL,
        ),
        AuditDeclaration(
            useCase = "removeRoleFromUser",
            action = "USER_ROLE_REMOVED",
            level = AuditLevel.CRITICAL,
            entity = "user",
            permission = "role.assign",
            category = AuditCategory.ACCESS_CONTROL,
        ),
        AuditDeclaration(
            useCase = "setPermissionException",
            action = "PERMISSION_EXCEPTION_SET",
            level = AuditLevel.CRITICAL,
            entity = "user",
            permission = "permission.exception",
            category = AuditCategory.ACCESS_CONTROL,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "removePermissionException",
            action = "PERMISSION_EXCEPTION_REMOVED",
            level = AuditLevel.CRITICAL,
            entity = "user",
            permission = "permission.exception",
            category = AuditCategory.ACCESS_CONTROL,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "recordAccessDenied",
            action = "ACCESS_DENIED",
            level = AuditLevel.ACCESS,
            entity = "user",
            permission = "user.view",
            category = AuditCategory.ACCESS_CONTROL,
            captureDiff = false,
        ),
    )

    /** Catálogo y etiquetado. */
    val catalogAndLabels: List<AuditDeclaration> = listOf(
        AuditDeclaration(
            useCase = "createProduct",
            action = "PRODUCT_CREATED",
            level = AuditLevel.RELEVANT,
            entity = "product",
            permission = "product.create",
            category = AuditCategory.CATALOG,
        ),
        AuditDeclaration(
            useCase = "updateProduct",
            action = "PRODUCT_UPDATED",
            level = AuditLevel.RELEVANT,
            entity = "product",
            permission = "product.edit",
            category = AuditCategory.CATALOG,
        ),
        AuditDeclaration(
            useCase = "deactivateProduct",
            action = "PRODUCT_DEACTIVATED",
            level = AuditLevel.CRITICAL,
            entity = "product",
            permission = "product.delete",
            category = AuditCategory.CATALOG,
            reasonRequired = true,
        ),
        AuditDeclaration(
            useCase = "createLocation",
            action = "LOCATION_CREATED",
            level = AuditLevel.RELEVANT,
            entity = "location",
            permission = "location.create",
            category = AuditCategory.CATALOG,
        ),
        AuditDeclaration(
            useCase = "updateLocation",
            action = "LOCATION_UPDATED",
            level = AuditLevel.RELEVANT,
            entity = "location",
            permission = "location.edit",
            category = AuditCategory.CATALOG,
        ),
        AuditDeclaration(
            useCase = "editLabelTemplate",
            action = "LABEL_TEMPLATE_EDITED",
            level = AuditLevel.RELEVANT,
            entity = "label_template",
            permission = "label.template_edit",
            category = AuditCategory.LABELING,
        ),
        AuditDeclaration(
            useCase = "printLabels",
            action = "LABELS_ENQUEUED",
            level = AuditLevel.RELEVANT,
            entity = "print_job",
            permission = "label.print",
            category = AuditCategory.LABELING,
        ),
        AuditDeclaration(
            useCase = "reprintLabels",
            action = "LABELS_REPRINTED",
            level = AuditLevel.RELEVANT,
            entity = "print_job",
            permission = "label.reprint",
            category = AuditCategory.LABELING,
            reasonRequired = true,
        ),
    )

    /** Todas las declaraciones, para los tests y la verificación de cobertura. */
    val all: List<AuditDeclaration> = buildList {
        addAll(receiving)
        addAll(fulfillment)
        addAll(inventory)
        addAll(transfers)
        addAll(accessControl)
        addAll(catalogAndLabels)
    }

    /** Índice por acción, para resolver un evento a su declaración. */
    val byAction: Map<String, AuditDeclaration> = all.associateBy { it.action }

    /** Las declaraciones de un nivel de criticidad. */
    fun byLevel(level: AuditLevel): List<AuditDeclaration> = all.filter { it.level == level }

    /** Las que exigen motivo. */
    val reasonRequired: List<AuditDeclaration> = all.filter { it.reasonRequired }

    /** Las de una categoría. */
    fun byCategory(category: AuditCategory): List<AuditDeclaration> =
        all.filter { it.category == category }
}
