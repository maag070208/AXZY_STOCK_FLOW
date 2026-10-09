package dev.axzy.stock.backend.catalog
import dev.axzy.stock.backend.catalog.location.LocationService
import dev.axzy.stock.backend.catalog.product.ProductService

import dev.axzy.stock.backend.catalog.location.LocationRepository
import dev.axzy.stock.backend.catalog.location.locationRoutes
import dev.axzy.stock.backend.catalog.product.ProductRepository
import dev.axzy.stock.backend.catalog.product.productRoutes
import dev.axzy.stock.backend.catalog.warehouse.WarehouseRepository
import dev.axzy.stock.backend.catalog.warehouse.warehouseRoutes

import dev.axzy.stock.backend.security.DenyAllPermissionChecker
import dev.axzy.stock.backend.security.PermissionChecker
import io.ktor.server.routing.Route
import javax.sql.DataSource

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CABLEADO DEL CATÁLOGO — un solo sitio donde se construyen los repositorios
 *
 * **ESTE FICHERO ANTES ERA DE 741 LÍNEAS Y TENÍA TRES RECURSOS DENTRO.**
 *
 * `products`, `locations` y `warehouses` vivían aquí como tres funciones
 * `private fun Route.*`, junto con **14 helpers de validación** y las consultas
 * de los 7 handlers. El diagnóstico está en `docs/historico/ARQUITECTURA.md` §«Migración a
 * capas»: un fichero de 741 líneas no lo lee nadie entero, y tocar una ruta de
 * productos obligaba a recorrer las de ubicaciones y bodegas.
 *
 * Ahora cada recurso tiene su fichero y este solo **compone**:
 *
 *     CatalogRoutes.kt      ← esto: los repositorios y el orden de montaje
 *     ProductRoutes.kt        GET|POST|PUT|DELETE /v1/products
 *     LocationRoutes.kt       GET|POST|PUT|DELETE /v1/locations
 *     WarehouseRoutes.kt      GET /v1/warehouses
 *     CatalogValidation.kt    los helpers, `internal` para que sigan siendo
 *                             accesibles desde los tres ficheros de rutas
 *
 * ── LO QUE CASI ROMPE ESTE CORTE, y por qué los helpers son `internal` ──────
 *
 * Los 14 helpers eran `private` de **nivel de fichero**. `private` en Kotlin, a
 * nivel de fichero, significa «visible solo dentro de ESTE fichero»: al repartir
 * las rutas en tres ficheros, cada llamada habría fallado con
 * «Cannot access ...: it is private in file». Se pasaron a `internal` y se
 * agruparon en `CatalogValidation.kt`. **No es un descuido de visibilidad: es la
 * consecuencia obligada de partir el fichero**, y por eso la regla del proyecto
 * es que la visibilidad se elige explícitamente en cada caso.
 *
 * ── EL CORTE SE HIZO POR CIERRE DE FUNCIÓN ─────────────────────────────────
 *
 * No por número de línea. Los dos primeros intentos fallaron así: el primero se
 * llevó las tres funciones enteras y dejó *overloads* duplicados; el segundo
 * cortó un helper por la mitad y el fichero no compilaba (error de sintaxis en la
 * línea 257). Un rango de líneas no sabe dónde termina una función; el cierre
 * real de la llave, sí.
 * ═══════════════════════════════════════════════════════════════════════════
 */
fun Route.catalogRoutes(
    dataSource: DataSource,
    permissionChecker: PermissionChecker = DenyAllPermissionChecker,
) {
    // Una sola conexión lógica para todo el módulo. `Database.connect` no abre
    // conexiones: envuelve el pool, y cada transacción toma la suya.
    val db = org.jetbrains.exposed.sql.Database.connect(dataSource)

    val products = ProductRepository(db)
    val locations = LocationRepository(db)
    val warehouses = WarehouseRepository(db)
    val units = UnitOfMeasureRepository(db)
    val importService = CatalogImportService(db, products, units)

    productRoutes(ProductService(products, units), importService, permissionChecker)
    locationRoutes(LocationService(locations, warehouses), permissionChecker)
    warehouseRoutes(warehouses, permissionChecker)
}
