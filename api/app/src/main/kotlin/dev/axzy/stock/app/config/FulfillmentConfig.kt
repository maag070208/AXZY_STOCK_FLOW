package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.fulfillment.JdbcOrders
import dev.axzy.stock.adapters.web.fulfillment.OrderController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
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
 * Surtido (M04) y exportación al ERP (M09).
 *
 * El puerto del ERP se cablea con una implementación que **falla con motivo**: la
 * conexión real no existe todavía ([ADR-0013]). El caso de uso ya aplica las reglas que
 * no dependen de ella —la confirmación no se revierte, el intento se cuenta y el
 * motivo queda guardado para el reintento—, así que el día que haya ERP solo entra un
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
    fun orderController(
        listOrders: ListOrders,
        getOrder: GetOrder,
        createOrder: CreateOrder,
        packageLine: PackageLine,
        confirmOrder: ConfirmOrder,
        exportOrder: ExportOrder,
        registerShortage: RegisterShortage,
    ) = OrderController(
        listOrders, getOrder, createOrder, packageLine, confirmOrder, exportOrder, registerShortage,
    )

    /**
     * **No hay ERP configurado.** El intento falla con un motivo que empieza por
     * `ERP_NOT_CONNECTED` (S5), el contador sube y el reintento manual lo vuelve a
     * mostrar (S6). Inventar un envío correcto sería mentir en la bitácora y en el
     * estado de la orden.
     */
    @Bean
    fun erpClient(): ErpClient = object : ErpClient {
        override fun send(order: OrderDetail): ErpResult =
            ErpResult.Failed("ERP_NOT_CONNECTED: no hay conexión configurada con el ERP (ADR-0013).")
    }
}
