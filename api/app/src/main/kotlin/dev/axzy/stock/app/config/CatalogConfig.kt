package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.web.catalog.CatalogUpload
import dev.axzy.stock.adapters.web.catalog.LocationController
import dev.axzy.stock.adapters.web.catalog.ProductController
import dev.axzy.stock.adapters.web.catalog.WarehouseController
import dev.axzy.stock.application.access.AccessDecider
import dev.axzy.stock.application.audit.AuditEvents
import dev.axzy.stock.application.audit.AuditRecorder
import dev.axzy.stock.application.catalog.CreateLocation
import dev.axzy.stock.application.catalog.CreateProduct
import dev.axzy.stock.application.catalog.CreateWarehouse
import dev.axzy.stock.application.catalog.DeactivateLocation
import dev.axzy.stock.application.catalog.DeactivateProduct
import dev.axzy.stock.application.catalog.GetLocation
import dev.axzy.stock.application.catalog.GetProduct
import dev.axzy.stock.application.catalog.ImportCatalog
import dev.axzy.stock.application.inventory.InventoryCatalog
import dev.axzy.stock.application.inventory.StockLedger
import dev.axzy.stock.application.catalog.ListLocations
import dev.axzy.stock.application.catalog.ListProducts
import dev.axzy.stock.application.catalog.ListWarehouses
import dev.axzy.stock.application.catalog.Locations
import dev.axzy.stock.application.catalog.Products
import dev.axzy.stock.application.catalog.UnitsOfMeasure
import dev.axzy.stock.application.catalog.UpdateLocation
import dev.axzy.stock.application.catalog.UpdateProduct
import dev.axzy.stock.application.catalog.UpdateWarehouse
import dev.axzy.stock.application.catalog.Warehouses
import dev.axzy.stock.application.shared.TransactionRunner
import dev.axzy.stock.domain.catalog.CatalogImporter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The catalog (M01): use cases and routes.
 *
 * Here the order of the layers is visible —the controller receives use cases, the use case
 * receives ports, and neither knows the other— and it is the only place where
 * construyen.
 */
@Configuration(proxyBeanMethods = false)
class CatalogConfig {

    @Bean
    fun catalogImporter() = CatalogImporter()

    // ── Use cases ──────────────────────────────────────────────────────────

    @Bean
    fun listProducts(products: Products, access: AccessDecider) = ListProducts(products, access)

    @Bean
    fun getProduct(products: Products, access: AccessDecider) = GetProduct(products, access)

    @Bean
    fun createProduct(
        products: Products,
        units: UnitsOfMeasure,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = CreateProduct(products, units, audit, auditEvents, access, tx)

    @Bean
    fun updateProduct(
        products: Products,
        units: UnitsOfMeasure,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = UpdateProduct(products, units, audit, auditEvents, access, tx)

    @Bean
    fun deactivateProduct(
        products: Products,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = DeactivateProduct(products, audit, auditEvents, access, tx)

    @Bean
    fun listLocations(locations: Locations, access: AccessDecider) = ListLocations(locations, access)

    @Bean
    fun getLocation(locations: Locations, access: AccessDecider) = GetLocation(locations, access)

    @Bean
    fun createLocation(
        locations: Locations,
        warehouses: Warehouses,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = CreateLocation(locations, warehouses, audit, auditEvents, access, tx)

    @Bean
    fun updateLocation(
        locations: Locations,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = UpdateLocation(locations, audit, auditEvents, access, tx)

    @Bean
    fun deactivateLocation(
        locations: Locations,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = DeactivateLocation(locations, audit, auditEvents, access, tx)

    @Bean
    fun listWarehouses(warehouses: Warehouses, access: AccessDecider) = ListWarehouses(warehouses, access)

    @Bean
    fun createWarehouse(
        warehouses: Warehouses,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = CreateWarehouse(warehouses, audit, auditEvents, access, tx)

    @Bean
    fun updateWarehouse(
        warehouses: Warehouses,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = UpdateWarehouse(warehouses, audit, auditEvents, access, tx)

    @Bean
    fun importCatalog(
        products: Products,
        units: UnitsOfMeasure,
        ledger: StockLedger,
        inventoryCatalog: InventoryCatalog,
        audit: AuditRecorder,
        auditEvents: AuditEvents,
        access: AccessDecider,
        tx: TransactionRunner,
    ) = ImportCatalog(products, units, ledger, inventoryCatalog, audit, auditEvents, access, tx)

    // ── Rutas ───────────────────────────────────────────────────────────────

    @Bean
    fun catalogUpload(importer: CatalogImporter) = CatalogUpload(importer)

    @Bean
    fun productController(
        listProducts: ListProducts,
        getProduct: GetProduct,
        createProduct: CreateProduct,
        updateProduct: UpdateProduct,
        deactivateProduct: DeactivateProduct,
        importCatalog: ImportCatalog,
        upload: CatalogUpload,
    ) = ProductController(
        listProducts, getProduct, createProduct, updateProduct, deactivateProduct, importCatalog, upload,
    )

    @Bean
    fun locationController(
        listLocations: ListLocations,
        getLocation: GetLocation,
        createLocation: CreateLocation,
        updateLocation: UpdateLocation,
        deactivateLocation: DeactivateLocation,
    ) = LocationController(listLocations, getLocation, createLocation, updateLocation, deactivateLocation)

    @Bean
    fun warehouseController(
        listWarehouses: ListWarehouses,
        createWarehouse: CreateWarehouse,
        updateWarehouse: UpdateWarehouse,
    ) = WarehouseController(listWarehouses, createWarehouse, updateWarehouse)
}
