package dev.axzy.stock.adapters.persistence

import dev.axzy.stock.adapters.persistence.catalog.JdbcLocations
import dev.axzy.stock.adapters.persistence.catalog.JdbcProducts
import dev.axzy.stock.adapters.persistence.catalog.JdbcUnitsOfMeasure
import dev.axzy.stock.adapters.persistence.catalog.JdbcWarehouses
import dev.axzy.stock.application.catalog.LocationFilter
import dev.axzy.stock.application.catalog.NewLocation
import dev.axzy.stock.application.catalog.NewProduct
import dev.axzy.stock.application.catalog.NewWarehouse
import dev.axzy.stock.application.catalog.ProductFilter
import dev.axzy.stock.application.shared.PageRequest
import dev.axzy.stock.application.shared.SortDirection
import dev.axzy.stock.application.shared.SortSpec
import dev.axzy.stock.domain.catalog.LocationKind
import dev.axzy.stock.domain.catalog.ValidatedProduct
import dev.axzy.stock.domain.value.ProductCode
import dev.axzy.stock.domain.value.Quantity
import dev.axzy.stock.testing.TestDatabase
import dev.axzy.stock.testing.TestPersistence
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * El catálogo contra PostgreSQL de verdad, con el usuario de la API (miembro de
 * `stock_api`): si un permiso del rol faltara, estas pruebas fallarían.
 */
class CatalogPersistenceTest {

    private val db = TestPersistence()
    private val products = JdbcProducts(db.jdbc, db.json)
    private val locations = JdbcLocations(db.jdbc)
    private val warehouses = JdbcWarehouses(db.jdbc)
    private val units = JdbcUnitsOfMeasure(db.jdbc)

    private val defaultWarehouse = 1L

    @BeforeEach
    fun clean() = TestDatabase.reset()

    private fun unitId() = units.all().first { it.code == "PZA" }.id

    private fun newProduct(code: String, name: String = "Producto $code"): NewProduct =
        NewProduct(ProductCode.of(code), name, null, unitId(), "PZA")

    private fun insertProduct(code: String, name: String = "Producto $code") = products.insert(newProduct(code, name))

    // ── Productos ───────────────────────────────────────────────────────────

    @Test
    fun `un producto se crea y se vuelve a leer por id y por codigo`() {
        val created = insertProduct("ABRAA1B", "ABRAZADERA DE ARRANQUE")

        assertThat(created.product.id).isPositive()
        assertThat(created.product.code.value).isEqualTo("ABRAA1B")
        assertThat(created.product.unitMeasure).isEqualTo("PZA")
        assertThat(created.stock).isEqualTo(Quantity.ZERO)
        assertThat(products.findById(created.product.id)!!.product.name).isEqualTo("ABRAZADERA DE ARRANQUE")
        assertThat(products.findByCode(ProductCode.of("ABRAA1B"))!!.id).isEqualTo(created.product.id)
    }

    @Test
    fun `R-P2 - un listado hace dos sentencias, tenga una fila o cincuenta`() {
        repeat(50) { insertProduct("COD${it.toString().padStart(4, '0')}") }
        insertProduct("UNICO01")

        val request = PageRequest.of(page = 0, size = 50)
        val statements = db.statements.during { products.page(ProductFilter(query = null, active = null), request) }
        val page = products.page(ProductFilter(query = "UNICO", active = null), request)

        assertThat(statements).isEqualTo(2)   // el conteo y la página, nunca una por fila
        assertThat(page.items).hasSize(1)
    }

    @Test
    fun `el conteo usa los mismos filtros que el listado`() {
        repeat(5) { insertProduct("ACT${it.toString().padStart(3, '0')}") }
        val inactive = insertProduct("BAJA001")
        products.update(inactive.product.copy(active = false))

        val page = products.page(ProductFilter(query = "ACT", active = true), PageRequest.of())

        assertThat(page.totalItems).isEqualTo(5)
        assertThat(page.totalPages).isEqualTo(1)
        assertThat(page.items).hasSize(5)
    }

    @Test
    fun `el listado se puede ordenar por nombre en las dos direcciones`() {
        insertProduct("COD0001", "ZAPATO")
        insertProduct("COD0002", "ABRAZADERA")

        val asc = products.page(ProductFilter(null, null), PageRequest.of(sort = SortSpec("name", SortDirection.ASC)))
        val desc = products.page(ProductFilter(null, null), PageRequest.of(sort = SortSpec("name", SortDirection.DESC)))

        assertThat(asc.items.map { it.product.name }).containsExactly("ABRAZADERA", "ZAPATO")
        assertThat(desc.items.map { it.product.name }).containsExactly("ZAPATO", "ABRAZADERA")
    }

    @Test
    fun `R-P3 - el saldo sale de la proyeccion, no de sumar el historial`() {
        val product = insertProduct("CONS001")
        db.asOwner.sql("INSERT INTO product_stock (product_id, quantity) VALUES (:id, 5193.000)")
            .param("id", product.product.id).update()

        assertThat(products.stockOf(product.product.id)).isEqualTo(Quantity.of(5193))
    }

    @Test
    fun `el saldo viaja en el listado, sin una consulta por producto`() {
        val product = insertProduct("CONS002")
        db.asOwner.sql("INSERT INTO product_stock (product_id, quantity) VALUES (:id, 12.500)")
            .param("id", product.product.id).update()

        val page = products.page(ProductFilter(null, null), PageRequest.of())

        assertThat(page.items.single().stock).isEqualTo(Quantity.ofScaled(12_500))
    }

    // ── Importación ─────────────────────────────────────────────────────────

    @Test
    fun `K2 - importar dos veces no duplica y la segunda no cambia nada`() {
        val rows = listOf(
            ValidatedProduct(2, "1", ProductCode.of("AAA001"), "Uno", "PZA", Quantity.of(5)),
            ValidatedProduct(3, "2", ProductCode.of("AAA002"), "Dos", "PZA", Quantity.of(7)),
        )

        val first = products.upsertAll(rows)
        val second = products.upsertAll(rows)

        assertThat(first.created).isEqualTo(2)
        assertThat(first.updated).isZero()
        assertThat(second.created).isZero()
        assertThat(second.updated).isZero()
        assertThat(second.skipped).isEqualTo(2)
        assertThat(products.page(ProductFilter(null, null), PageRequest.of()).totalItems).isEqualTo(2)
    }

    @Test
    fun `la importacion actualiza el nombre cuando cambia, y lo cuenta`() {
        products.upsertAll(listOf(ValidatedProduct(2, "1", ProductCode.of("AAA001"), "Uno", "PZA", Quantity.of(5))))

        val summary = products.upsertAll(
            listOf(
                ValidatedProduct(2, "1", ProductCode.of("AAA001"), "Uno corregido", "PZA", Quantity.of(5)),
                ValidatedProduct(3, "2", ProductCode.of("AAA002"), "Dos", "PZA", Quantity.of(7)),
            ),
        )

        assertThat(summary.created).isEqualTo(1)
        assertThat(summary.updated).isEqualTo(1)
        assertThat(products.findByCode(ProductCode.of("AAA001"))!!.name).isEqualTo("Uno corregido")
    }

    @Test
    fun `la importacion guarda el identificador del ERP y no reactiva lo desactivado`() {
        products.upsertAll(listOf(ValidatedProduct(2, "77", ProductCode.of("AAA001"), "Uno", "PZA", Quantity.of(5))))
        val created = products.findByCode(ProductCode.of("AAA001"))!!
        assertThat(created.externalId).isEqualTo(77L)

        products.update(created.copy(active = false))
        products.upsertAll(listOf(ValidatedProduct(2, "77", ProductCode.of("AAA001"), "Uno", "PZA", Quantity.of(5))))

        assertThat(products.findByCode(ProductCode.of("AAA001"))!!.active).isFalse()
    }

    // ── Ubicaciones ─────────────────────────────────────────────────────────

    @Test
    fun `una ubicacion se crea, se busca por su identidad natural y se lista`() {
        val created = locations.insert(NewLocation(defaultWarehouse, "04", "B", "C", 3, LocationKind.SHELF))

        assertThat(created.location.displayName).isEqualTo("04-B-C")
        assertThat(created.warehouseCode).isEqualTo("PRINCIPAL")
        assertThat(locations.find(defaultWarehouse, "04", "B", "C")!!.location.id).isEqualTo(created.location.id)
        assertThat(locations.findById(created.location.id)!!.location.levels).isEqualTo(3)

        val page = locations.page(LocationFilter(null, defaultWarehouse, null, null), PageRequest.of())
        assertThat(page.items.map { it.location.displayName }).contains("04-B-C", "RECEPCION")
    }

    @Test
    fun `R-P2 - el listado de ubicaciones tambien son dos sentencias`() {
        repeat(20) { locations.insert(NewLocation(defaultWarehouse, "1$it", "A", "A", 1, LocationKind.SHELF)) }

        val filter = LocationFilter(null, defaultWarehouse, null, null)
        val statements = db.statements.during { locations.page(filter, PageRequest.of(page = 0, size = 10)) }
        val page = locations.page(filter, PageRequest.of(page = 0, size = 10))

        assertThat(statements).isEqualTo(2)
        // 20 estantes, la recepción y la ubicación del saldo heredado (SIN-UBICAR),
        // que siembra la migración V6.
        assertThat(page.totalItems).isEqualTo(22)
        assertThat(page.items).hasSize(10)
    }

    @Test
    fun `R-P2 - el listado de bodegas es una sola sentencia`() {
        warehouses.insert(NewWarehouse("NORTE", "Bodega norte", null, isDefault = false))

        val statements = db.statements.during { warehouses.list(null, null) }

        assertThat(statements).isEqualTo(1)
    }

    @Test
    fun `el listado de ubicaciones filtra por bodega y por tipo`() {
        locations.insert(NewLocation(defaultWarehouse, "07", "A", "A", 1, LocationKind.SHELF))

        val receiving = locations.page(
            LocationFilter(null, defaultWarehouse, LocationKind.RECEIVING, null),
            PageRequest.of(),
        )

        assertThat(receiving.totalItems).isEqualTo(1)
        assertThat(receiving.items.single().location.displayName).isEqualTo("RECEPCION")
    }

    @Test
    fun `la ubicacion de recepcion de la bodega es real, con id real`() {
        val receiving = locations.receivingOf(defaultWarehouse)!!

        assertThat(receiving.location.id).isPositive()
        assertThat(receiving.location.kind).isEqualTo(LocationKind.RECEIVING)
        assertThat(receiving.location.displayName).isEqualTo("RECEPCION")
    }

    @Test
    fun `una ubicacion con existencias lo dice`() {
        val product = insertProduct("STOCK01")
        val created = locations.insert(NewLocation(defaultWarehouse, "08", "A", "A", 1, LocationKind.SHELF))
        db.asOwner.sql(
            "INSERT INTO stock_by_location (product_id, location_id, warehouse_id, quantity) " +
                "VALUES (:product, :location, :warehouse, 4)",
        )
            .param("product", product.product.id)
            .param("location", created.location.id)
            .param("warehouse", defaultWarehouse)
            .update()

        assertThat(locations.stockOf(created.location.id)).isEqualTo(Quantity.of(4))
    }

    // ── Bodegas ─────────────────────────────────────────────────────────────

    @Test
    fun `K6 - solo una bodega queda como predeterminada`() {
        // El caso de uso hace las dos cosas en la MISMA transacción; aquí se prueba
        // el efecto en la base, que es donde el índice parcial lo impone: si el
        // `clearDefault` no fuera antes del `insert`, esto fallaría.
        warehouses.clearDefault()
        val second = warehouses.insert(NewWarehouse("NORTE", "Bodega norte", null, isDefault = true))

        assertThat(second.isDefault).isTrue()
        assertThat(db.jdbc.sql("SELECT count(*) FROM warehouses WHERE is_default").query(Int::class.java).single())
            .isEqualTo(1)
        assertThat(warehouses.findById(defaultWarehouse)!!.isDefault).isFalse()
    }

    @Test
    fun `el listado de bodegas no se pagina y trae la predeterminada primero`() {
        warehouses.insert(NewWarehouse("NORTE", "Bodega norte", null, isDefault = false))

        val list = warehouses.list(null, null)

        assertThat(list).hasSize(2)
        assertThat(list.first().isDefault).isTrue()
        assertThat(list.first().code).isEqualTo("PRINCIPAL")
    }

    @Test
    fun `las unidades de medida son un catalogo cerrado y leible`() {
        val all = units.all()

        assertThat(all.map { it.code }).containsExactly("PZA")
    }
}
