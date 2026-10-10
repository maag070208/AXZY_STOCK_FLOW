package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.fulfillment.JdbcOrders
import dev.axzy.stock.adapters.web.fulfillment.OrderController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.catalog.Locations
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.fulfillment.AssignOrderLine
import dev.axzy.stock.application.fulfillment.ConfirmOrder
import dev.axzy.stock.application.fulfillment.CreateOrder
import dev.axzy.stock.application.fulfillment.ErpClient
import dev.axzy.stock.application.fulfillment.ErpResult
import dev.axzy.stock.application.fulfillment.ExportOrder
import dev.axzy.stock.application.fulfillment.GetOrder
import dev.axzy.stock.application.fulfillment.ListOrders
import dev.axzy.stock.application.fulfillment.OrderCatalog
import dev.axzy.stock.application.fulfillment.OrderDetail
import dev.axzy.stock.application.fulfillment.Orders
import dev.axzy.stock.application.fulfillment.PackageLine
import dev.axzy.stock.application.fulfillment.RegisterShortage
import dev.axzy.stock.application.inventory.AdjustStock
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.application.shared.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * Fulfillment (M04) and export to the ERP (M09).
 *
 * The ERP port is wired with an implementation that **fails with a reason**: the real
 * connection does not exist yet ([ADR-0013]). The use case already applies the rules that
 * do not depend on it —the confirmation is not rolled back, the attempt is counted and the
 * reason is kept for the retry—, so the day there is an ERP, only one adapter changes.
 * adaptador nuevo.
 */
@Configuration(proxyBeanMethods = false)
class FulfillmentConfig {

    @Bean
    fun orders(jdbc: JdbcClient): JdbcOrders = JdbcOrders(jdbc)

    @Bean
    fun listOrders(orders: JdbcOrders, access: AccessDecider) = ListOrders(orders as Orders, access)

    @Bean
    fun getOrder(orders: JdbcOrders, access: AccessDecider) = GetOrder(orders as Orders, access)

    @Bean
    fun createOrder(
        orders: JdbcOrders,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = CreateOrder(orders as OrderCatalog, orders as Orders, audit, auditEvents, access, tx, clock)

    @Bean
    fun packageLine(
        orders: JdbcOrders,
        ledger: StockLedger,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = PackageLine(orders as OrderCatalog, orders as Orders, ledger, audit, auditEvents, access, tx, clock)

    @Bean
    fun confirmOrder(
        orders: JdbcOrders,
        ledger: StockLedger,
        erp: ErpClient,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = ConfirmOrder(orders as Orders, ledger, orders as OrderCatalog, erp, audit, auditEvents, access, tx, clock)

    @Bean
    fun exportOrder(
        orders: JdbcOrders,
        erp: ErpClient,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = ExportOrder(orders as Orders, erp, audit, auditEvents, access, tx, clock)

    @Bean
    fun registerShortage(
        orders: JdbcOrders,
        adjustStock: AdjustStock,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = RegisterShortage(orders as Orders, adjustStock, audit, auditEvents, access, tx)

    @Bean
    fun assignOrderLine(
        orders: JdbcOrders,
        accounts: UserAccounts,
        locations: Locations,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = AssignOrderLine(orders as Orders, accounts, locations, audit, auditEvents, access, tx, clock)

    @Bean
    fun orderController(
        listOrders: ListOrders,
        getOrder: GetOrder,
        createOrder: CreateOrder,
        packageLine: PackageLine,
        confirmOrder: ConfirmOrder,
        exportOrder: ExportOrder,
        registerShortage: RegisterShortage,
        assignOrderLine: AssignOrderLine,
    ) = OrderController(
        listOrders, getOrder, createOrder, packageLine, confirmOrder, exportOrder, registerShortage, assignOrderLine,
    )

    /**
     * **No ERP is configured.** The attempt fails with a reason that starts with
     * `ERP_NOT_CONNECTED` (S5), the counter goes up and the manual retry shows it again
     * show it (S6). Making up a successful export would lie in the audit log and in the
     * status of the order.
     */
    @Bean
    fun erpClient(): ErpClient = object : ErpClient {
        override fun send(order: OrderDetail): ErpResult =
            ErpResult.Failed("ERP_NOT_CONNECTED: no ERP connection is configured (ADR-0013).")
    }
}
