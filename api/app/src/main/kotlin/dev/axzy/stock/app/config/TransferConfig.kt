package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.transfer.JdbcTransfers
import dev.axzy.stock.adapters.web.transfer.TransferController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.inventory.InventoryCatalog
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.application.transfer.CancelTransfer
import dev.axzy.stock.application.transfer.CreateTransfer
import dev.axzy.stock.application.transfer.DispatchTransfer
import dev.axzy.stock.application.transfer.GetTransfer
import dev.axzy.stock.application.transfer.InTransitReport
import dev.axzy.stock.application.transfer.ListTransfers
import dev.axzy.stock.application.transfer.ReceiveTransfer
import dev.axzy.stock.application.transfer.TransferCatalog
import dev.axzy.stock.application.transfer.Transfers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * Traslados (M05).
 *
 * The three operations that move goods call the engine's functions (V13);
 * this wiring does not add any balance write route.
 */
@Configuration(proxyBeanMethods = false)
class TransferConfig {

    @Bean
    fun transfers(jdbc: JdbcClient): JdbcTransfers = JdbcTransfers(jdbc)

    @Bean
    fun listTransfers(transfers: JdbcTransfers, access: AccessDecider) = ListTransfers(transfers as Transfers, access)

    @Bean
    fun getTransfer(transfers: JdbcTransfers, access: AccessDecider) = GetTransfer(transfers as Transfers, access)

    @Bean
    fun inTransitReport(
        transfers: JdbcTransfers,
        access: AccessDecider,
        clock: Clock,
    ) = InTransitReport(transfers as Transfers, access, clock)

    @Bean
    fun createTransfer(
        transfers: JdbcTransfers,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = CreateTransfer(
        transfers as TransferCatalog, transfers as Transfers, audit, auditEvents, access, tx, clock,
    )

    @Bean
    fun dispatchTransfer(
        transfers: JdbcTransfers,
        ledger: StockLedger,
        inventoryCatalog: InventoryCatalog,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = DispatchTransfer(
        transfers as Transfers,
        transfers as TransferCatalog,
        ledger,
        inventoryCatalog,
        audit,
        auditEvents,
        access,
        tx,
        clock,
    )

    @Bean
    fun receiveTransfer(
        transfers: JdbcTransfers,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = ReceiveTransfer(
        transfers as Transfers, transfers as TransferCatalog, audit, auditEvents, access, tx, clock,
    )

    @Bean
    fun cancelTransfer(
        transfers: JdbcTransfers,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = CancelTransfer(
        transfers as Transfers, transfers as TransferCatalog, audit, auditEvents, access, tx, clock,
    )

    @Bean
    fun transferController(
        listTransfers: ListTransfers,
        getTransfer: GetTransfer,
        createTransfer: CreateTransfer,
        dispatchTransfer: DispatchTransfer,
        receiveTransfer: ReceiveTransfer,
        cancelTransfer: CancelTransfer,
        inTransitReport: InTransitReport,
    ) = TransferController(
        listTransfers, getTransfer, createTransfer, dispatchTransfer, receiveTransfer, cancelTransfer, inTransitReport,
    )
}
