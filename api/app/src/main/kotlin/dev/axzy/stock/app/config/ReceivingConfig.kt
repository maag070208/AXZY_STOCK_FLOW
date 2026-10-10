package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.receiving.JdbcInvoices
import dev.axzy.stock.adapters.web.receiving.InvoiceController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.receiving.ApproveInvoice
import dev.axzy.stock.application.inventory.InventoryCatalog
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.receiving.AssignInvoiceLine
import dev.axzy.stock.application.receiving.CaptureLine
import dev.axzy.stock.application.receiving.CloseInvoice
import dev.axzy.stock.application.receiving.CreateInvoice
import dev.axzy.stock.application.receiving.GetInvoice
import dev.axzy.stock.application.receiving.Invoices
import dev.axzy.stock.application.receiving.ListInvoices
import dev.axzy.stock.application.receiving.PendingApprovalInbox
import dev.axzy.stock.application.receiving.ReceivingCatalog
import dev.axzy.stock.application.receiving.RejectInvoiceDiscrepancy
import dev.axzy.stock.application.shared.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * Recepción (M03): facturas, captura física y decisiones sobre las discrepancias.
 *
 * El mismo adaptador implementa los dos puertos —el de facturas y el catálogo que
 * necesita el alta— porque las dos cosas son la misma tabla vista de dos maneras, y
 * separarlas daría dos conexiones y dos consultas para resolver un producto.
 */
@Configuration(proxyBeanMethods = false)
class ReceivingConfig {

    @Bean
    fun invoices(jdbc: JdbcClient): JdbcInvoices = JdbcInvoices(jdbc)

    @Bean
    fun listInvoices(invoices: JdbcInvoices, access: AccessDecider) = ListInvoices(invoices as Invoices, access)

    @Bean
    fun getInvoice(invoices: JdbcInvoices, access: AccessDecider) = GetInvoice(invoices as Invoices, access)

    @Bean
    fun pendingApprovalInbox(invoices: JdbcInvoices, access: AccessDecider) =
        PendingApprovalInbox(invoices as Invoices, access)

    @Bean
    fun createInvoice(
        invoices: JdbcInvoices,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = CreateInvoice(invoices as ReceivingCatalog, invoices as Invoices, audit, auditEvents, access, tx)

    @Bean
    fun captureLine(
        invoices: JdbcInvoices,
        ledger: StockLedger,
        inventoryCatalog: InventoryCatalog,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = CaptureLine(invoices as Invoices, ledger, inventoryCatalog, audit, auditEvents, access, tx, clock)

    @Bean
    fun assignInvoiceLine(
        invoices: JdbcInvoices,
        accounts: UserAccounts,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = AssignInvoiceLine(invoices as Invoices, accounts, audit, auditEvents, access, tx, clock)

    @Bean
    fun approveInvoice(
        invoices: JdbcInvoices,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = ApproveInvoice(invoices as Invoices, audit, auditEvents, access, tx, clock)

    @Bean
    fun rejectInvoiceDiscrepancy(
        invoices: JdbcInvoices,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = RejectInvoiceDiscrepancy(invoices as Invoices, audit, auditEvents, access, tx, clock)

    @Bean
    fun closeInvoice(
        invoices: JdbcInvoices,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = CloseInvoice(invoices as Invoices, audit, auditEvents, access, tx, clock)

    @Bean
    fun invoiceController(
        listInvoices: ListInvoices,
        getInvoice: GetInvoice,
        createInvoice: CreateInvoice,
        captureLine: CaptureLine,
        approveInvoice: ApproveInvoice,
        rejectInvoiceDiscrepancy: RejectInvoiceDiscrepancy,
        closeInvoice: CloseInvoice,
        pendingApprovalInbox: PendingApprovalInbox,
        assignInvoiceLine: AssignInvoiceLine,
    ) = InvoiceController(
        listInvoices,
        getInvoice,
        createInvoice,
        captureLine,
        assignInvoiceLine,
        approveInvoice,
        rejectInvoiceDiscrepancy,
        closeInvoice,
        pendingApprovalInbox,
    )
}
