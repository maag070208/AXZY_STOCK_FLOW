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
import dev.axzy.stock.application.inventory.AdjustStock
import dev.axzy.stock.application.inventory.InventoryCatalog
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

/**
 * Existencias (M02): el motor, las consultas y las rutas.
 *
 * **No hay ningún adaptador que escriba existencias.** El único puerto de escritura
 * es [StockLedger], que llama a `registrar_movimiento`; el resto de la clase es
 * lectura. Es la regla del plan y el riesgo número dos de su tabla: dos rutas de
 * escritura al inventario por fuera del motor.
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
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = AdjustStock(catalog, ledger, audit, auditEvents, access, tx)

    @Bean
    fun locateStock(
        catalog: InventoryCatalog,
        ledger: StockLedger,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = LocateStock(catalog, ledger, audit, auditEvents, access, tx)

    @Bean
    fun moveStock(
        catalog: InventoryCatalog,
        ledger: StockLedger,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = MoveStock(catalog, ledger, audit, auditEvents, access, tx)

    @Bean
    fun inventoryController(
        listStock: ListStock,
        listKardex: ListKardex,
        adjustStock: AdjustStock,
        locateStock: LocateStock,
        moveStock: MoveStock,
    ) = InventoryController(listStock, listKardex, adjustStock, locateStock, moveStock)
}
