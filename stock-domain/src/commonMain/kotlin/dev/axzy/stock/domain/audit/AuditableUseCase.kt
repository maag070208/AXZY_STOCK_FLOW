package dev.axzy.stock.domain.audit

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CRITICAL USE CASES AND THEIR AUDIT DECLARATION
 *
 * This is where the strategy lives so that **"EVERYTHING must be auditable" stays**
 * **true within two years**.
 *
 * The real problem: if audit is implemented by hand in each controller, in
 * three months there will be thirty places that record differently and ten that were forgotten.
 * It is not a hypothesis: it is exactly what happened with permissions in the legacy,
 * where four seeded permissions were never enforced on any route.
 *
 * The solution has two parts:
 *
 *  1. **Each critical use case declares its audit** ([CriticalUseCase]). It is
 *     an explicit record, not an implicit convention.
 *  2. **A structural test verifies that the declaration is complete** and fails
 *     the build if something is missing. It does not check a behavior: it checks that
 *     **no record is missing**. It is the only way for the rule not to degrade.
 *
 * The [Auditable] annotation is the declarative mechanism for the use cases of the
 * domain. The [CriticalUseCase] record is the list that the test uses as a
 * reference: **declaring one without the other makes the build fail**.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Marks a domain use case as auditable.
 *
 * It is an annotation, not a logger: the domain does not depend on the audit infrastructure,
 * it only declares **what** must be audited and with what criticality. Whoever
 * implements the use case in the application layer is responsible for building
 * the corresponding [AuditEvent].
 *
 * @param action stable identifier of the action (`INVOICE_LINE_CAPTURED`).
 * @param level criticality, which determines handling and retention.
 * @param entity type of the affected entity (`invoice_line`, `kardex`).
 * @param permission permission required. **Mandatory for the critical ones**: without it one cannot
 *   audit "with which permission".
 * @param captureDiff generates `previousState`/`newState` automatically. A field-by-field
 *   diff by hand is where a field is always forgotten.
 * @param reasonRequired requires a reason. In inventory, a change without a reason is an
 *   audit hole (invariant I6).
 * @param category grouping for the audit reports.
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

/** Grouping of the auditable actions, for the reports. */
enum class AuditCategory(val displayName: String) {
    INVENTORY("inventario"),
    RECEIVING("receiving"),
    FULFILLMENT("surtido"),
    TRANSFER("transferencias"),
    ACCESS_CONTROL("access control"),
    CATALOG("catalog"),
    LABELING("etiquetado"),
    MIGRATION("migration"),
    DOMAIN("dominio"),
}

/**
 * The audit declaration of a critical use case.
 *
 * It is data, not executable code: it allows the structural test to verify the
 * coverage without reflection or classpath scanning, which in Kotlin Multiplatform
 * is not viable.
 */
data class AuditDeclaration(
    /** Name of the use case, for the test's error message. */
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
        require(action.isNotBlank()) { "The audited action cannot be empty: $useCase" }
        require(entity.isNotBlank()) { "The audited entity cannot be empty: $useCase" }
        require(permission.isNotBlank()) {
            "An auditable use case MUST declare its permission: without it, it cannot " +
                "answer 'with which permission was it done'. Case: $useCase"
        }
        require(entity == entity.lowercase()) {
            "The entity type must be lowercase and singular: '$entity' in $useCase"
        }
    }

    /** The equivalent annotation, to compare declaration and annotation. */
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
 * The record of all the critical use cases that **must** be audited.
 *
 * It is organized by flow, and each entry corresponds to an operation that
 * moves inventory, money or access. The list is the reference of the structural test:
 * **if someone adds a use case with `@Auditable` that is not
 * here, or the reverse, the build fails.**
 */
object CriticalUseCases {

    /** Receiving: initial load and invoice creation. */
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
            // Approving a discrepancy moves inventory: without a reason it is an
            // audit hole (invariant I6).
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

    /** Fulfillment and ERP export. */
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

    /** Inventory: the heart of the system. Every ledger movement. */
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
            // For legal reasons, the reason is mandatory.
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

    /** Transfers between warehouses. */
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

    /** Access control: every change of role, permission or policy. */
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

    /** Catalog and labeling. */
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

    /** All the declarations, for the tests and the coverage verification. */
    val all: List<AuditDeclaration> = buildList {
        addAll(receiving)
        addAll(fulfillment)
        addAll(inventory)
        addAll(transfers)
        addAll(accessControl)
        addAll(catalogAndLabels)
    }

    /** Index by action, to resolve an event to its declaration. */
    val byAction: Map<String, AuditDeclaration> = all.associateBy { it.action }

    /** The declarations of a criticality level. */
    fun byLevel(level: AuditLevel): List<AuditDeclaration> = all.filter { it.level == level }

    /** Those that require a reason. */
    val reasonRequired: List<AuditDeclaration> = all.filter { it.reasonRequired }

    /** Those of a category. */
    fun byCategory(category: AuditCategory): List<AuditDeclaration> =
        all.filter { it.category == category }
}
