package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.persistence.inventory.JdbcInventoryCatalog
import dev.axzy.stock.adapters.persistence.inventory.JdbcInventoryQueries
import dev.axzy.stock.adapters.persistence.inventory.JdbcStockLedger
import dev.axzy.stock.adapters.web.inventory.InventoryController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.catalog.Locations
import dev.axzy.stock.application.catalog.Products
import dev.axzy.stock.application.catalog.Warehouses
import dev.axzy.stock.application.identity.UserAccounts
import dev.axzy.stock.application.inventory.AdjustStock
import dev.axzy.stock.adapters.persistence.receiving.JdbcInvoices
import dev.axzy.stock.application.inventory.InventoryCatalog
import dev.axzy.stock.application.inventory.LocatingLines
import dev.axzy.stock.application.inventory.KardexQueries
import dev.axzy.stock.application.inventory.ListKardex
import dev.axzy.stock.application.inventory.ListStock
import dev.axzy.stock.application.inventory.LocateStock
import dev.axzy.stock.application.inventory.MoveStock
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.application.inventory.StockQueries
import dev.axzy.stock.application.shared.TransactionRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock

/**
 * Stock (M02): the engine, the queries and the routes.
 *
 * **No adapter writes stock.** The only write port
 * is [StockLedger], which calls `record_movement`; the rest of the class is
 * reading. It is the plan's rule and the second risk in its table: two
 * write paths to the inventory outside the engine.
 */
@Configuration(proxyBeanMethods = false)
class InventoryConfig {

    @Bean
    fun stockLedger(jdbc: JdbcClient): StockLedger = JdbcStockLedger(jdbc)

    @Bean
    fun inventoryCatalog(
        products: Products,
        locations: Locations,
        warehouses: Warehouses,
    ): InventoryCatalog = JdbcInventoryCatalog(products, locations, warehouses)

    @Bean
    fun inventoryQueries(jdbc: JdbcClient, properties: StockProperties): JdbcInventoryQueries =
        JdbcInventoryQueries(jdbc, properties.businessZone)

    @Bean
    fun listStock(queries: JdbcInventoryQueries, access: AccessDecider): ListStock =
        ListStock(queries as StockQueries, access)

    @Bean
    fun listKardex(queries: JdbcInventoryQueries, access: AccessDecider): ListKardex =
        ListKardex(queries as KardexQueries, access)

    @Bean
    fun adjustStock(
        catalog: InventoryCatalog,
        ledger: StockLedger,
        accounts: UserAccounts,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = AdjustStock(catalog, ledger, accounts, audit, auditEvents, access, tx)

    @Bean
    fun locateStock(
        catalog: InventoryCatalog,
        ledger: StockLedger,
        locatingLines: JdbcInvoices,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = LocateStock(catalog, ledger, locatingLines as LocatingLines, audit, auditEvents, access, tx, clock)

    @Bean
    fun moveStock(
        catalog: InventoryCatalog,
        ledger: StockLedger,
        locatingLines: JdbcInvoices,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
        clock: Clock,
    ) = MoveStock(catalog, ledger, locatingLines as LocatingLines, audit, auditEvents, access, tx, clock)

    @Bean
    fun inventoryController(
        listStock: ListStock,
        listKardex: ListKardex,
        adjustStock: AdjustStock,
        locateStock: LocateStock,
        moveStock: MoveStock,
    ) = InventoryController(listStock, listKardex, adjustStock, locateStock, moveStock)
}
