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
 * The catalog against real PostgreSQL, with the API user (a member of
 * `stock_api`): if a permission of the role were missing, these tests would fail.
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
    fun `a product is created and read back by id and by code`() {
        val created = insertProduct("ABRAA1B", "ABRAZADERA DE ARRANQUE")

        assertThat(created.product.id).isPositive()
        assertThat(created.product.code.value).isEqualTo("ABRAA1B")
        assertThat(created.product.unitMeasure).isEqualTo("PZA")
        assertThat(created.stock).isEqualTo(Quantity.ZERO)
        assertThat(products.findById(created.product.id)!!.product.name).isEqualTo("ABRAZADERA DE ARRANQUE")
        assertThat(products.findByCode(ProductCode.of("ABRAA1B"))!!.id).isEqualTo(created.product.id)
    }

    @Test
    fun `R-P2 - a list takes two statements, whether it has one row or fifty`() {
        repeat(50) { insertProduct("COD${it.toString().padStart(4, '0')}") }
        insertProduct("UNICO01")

        val request = PageRequest.of(page = 0, size = 50)
        val statements = db.statements.during { products.page(ProductFilter(query = null, active = null), request) }
        val page = products.page(ProductFilter(query = "UNICO", active = null), request)

        assertThat(statements).isEqualTo(2)   // el conteo y la página, nunca una por fila
        assertThat(page.items).hasSize(1)
    }

    @Test
    fun `the count uses the same filters as the list`() {
        repeat(5) { insertProduct("ACT${it.toString().padStart(3, '0')}") }
        val inactive = insertProduct("BAJA001")
        products.update(inactive.product.copy(active = false))

        val page = products.page(ProductFilter(query = "ACT", active = true), PageRequest.of())

        assertThat(page.totalItems).isEqualTo(5)
        assertThat(page.totalPages).isEqualTo(1)
        assertThat(page.items).hasSize(5)
    }

    @Test
    fun `the list can be sorted by name in both directions`() {
        insertProduct("COD0001", "ZAPATO")
        insertProduct("COD0002", "ABRAZADERA")

        val asc = products.page(ProductFilter(null, null), PageRequest.of(sort = SortSpec("name", SortDirection.ASC)))
        val desc = products.page(ProductFilter(null, null), PageRequest.of(sort = SortSpec("name", SortDirection.DESC)))

        assertThat(asc.items.map { it.product.name }).containsExactly("ABRAZADERA", "ZAPATO")
        assertThat(desc.items.map { it.product.name }).containsExactly("ZAPATO", "ABRAZADERA")
    }

    @Test
    fun `R-P3 - the balance comes from the projection, not from summing the history`() {
        val product = insertProduct("CONS001")
        db.asOwner.sql("INSERT INTO product_stock (product_id, quantity) VALUES (:id, 5193.000)")
            .param("id", product.product.id).update()

        assertThat(products.stockOf(product.product.id)).isEqualTo(Quantity.of(5193))
    }

    @Test
    fun `the balance travels in the list, without a query per product`() {
        val product = insertProduct("CONS002")
        db.asOwner.sql("INSERT INTO product_stock (product_id, quantity) VALUES (:id, 12.500)")
            .param("id", product.product.id).update()

        val page = products.page(ProductFilter(null, null), PageRequest.of())

        assertThat(page.items.single().stock).isEqualTo(Quantity.ofScaled(12_500))
    }

    // ── Import ─────────────────────────────────────────────────────────

    @Test
    fun `K2 - importing twice does not duplicate, and the second import changes nothing`() {
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
    fun `the import updates the name when it changes, and counts it`() {
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
    fun `the import stores the ERP identifier and does not reactivate what was deactivated`() {
        products.upsertAll(listOf(ValidatedProduct(2, "77", ProductCode.of("AAA001"), "Uno", "PZA", Quantity.of(5))))
        val created = products.findByCode(ProductCode.of("AAA001"))!!
        assertThat(created.externalId).isEqualTo(77L)

        products.update(created.copy(active = false))
        products.upsertAll(listOf(ValidatedProduct(2, "77", ProductCode.of("AAA001"), "Uno", "PZA", Quantity.of(5))))

        assertThat(products.findByCode(ProductCode.of("AAA001"))!!.active).isFalse()
    }

    // ── Ubicaciones ─────────────────────────────────────────────────────────

    @Test
    fun `a location is created, found by its natural identity and listed`() {
        val created = locations.insert(NewLocation(defaultWarehouse, "04", "B", "C", 3, LocationKind.SHELF))

        assertThat(created.location.displayName).isEqualTo("04-B-C")
        assertThat(created.warehouseCode).isEqualTo("MAIN")
        assertThat(locations.find(defaultWarehouse, "04", "B", "C")!!.location.id).isEqualTo(created.location.id)
        assertThat(locations.findById(created.location.id)!!.location.levels).isEqualTo(3)

        val page = locations.page(LocationFilter(null, defaultWarehouse, null, null), PageRequest.of())
        assertThat(page.items.map { it.location.displayName }).contains("04-B-C", "RECEIVING")
    }

    @Test
    fun `R-P2 - the location list also takes two statements`() {
        repeat(20) { locations.insert(NewLocation(defaultWarehouse, "1$it", "A", "A", 1, LocationKind.SHELF)) }

        val filter = LocationFilter(null, defaultWarehouse, null, null)
        val statements = db.statements.during { locations.page(filter, PageRequest.of(page = 0, size = 10)) }
        val page = locations.page(filter, PageRequest.of(page = 0, size = 10))

        assertThat(statements).isEqualTo(2)
        // 20 shelves, the receiving location and the balance location inherited (UNASSIGNED),
        // which migration V6 seeds.
        assertThat(page.totalItems).isEqualTo(22)
        assertThat(page.items).hasSize(10)
    }

    @Test
    fun `R-P2 - the warehouse list is a single statement`() {
        warehouses.insert(NewWarehouse("NORTE", "Bodega norte", null, isDefault = false))

        val statements = db.statements.during { warehouses.list(null, null) }

        assertThat(statements).isEqualTo(1)
    }

    @Test
    fun `the location list filters by warehouse and by kind`() {
        locations.insert(NewLocation(defaultWarehouse, "07", "A", "A", 1, LocationKind.SHELF))

        val receiving = locations.page(
            LocationFilter(null, defaultWarehouse, LocationKind.RECEIVING, null),
            PageRequest.of(),
        )

        assertThat(receiving.totalItems).isEqualTo(1)
        assertThat(receiving.items.single().location.displayName).isEqualTo("RECEIVING")
    }

    @Test
    fun `the warehouse's receiving location is real, with a real id`() {
        val receiving = locations.receivingOf(defaultWarehouse)!!

        assertThat(receiving.location.id).isPositive()
        assertThat(receiving.location.kind).isEqualTo(LocationKind.RECEIVING)
        assertThat(receiving.location.displayName).isEqualTo("RECEIVING")
    }

    @Test
    fun `a location with stock says so`() {
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
    fun `K6 - only one warehouse remains the default`() {
        // The use case does both things in the SAME transaction; here the
        // effect is tested in the database, which is where the partial index enforces it: if the
        // `clearDefault` ran after the `insert`, this would fail.
        warehouses.clearDefault()
        val second = warehouses.insert(NewWarehouse("NORTE", "Bodega norte", null, isDefault = true))

        assertThat(second.isDefault).isTrue()
        assertThat(db.jdbc.sql("SELECT count(*) FROM warehouses WHERE is_default").query(Int::class.java).single())
            .isEqualTo(1)
        assertThat(warehouses.findById(defaultWarehouse)!!.isDefault).isFalse()
    }

    @Test
    fun `the warehouse list is not paginated and brings the default one first`() {
        warehouses.insert(NewWarehouse("NORTE", "Bodega norte", null, isDefault = false))

        val list = warehouses.list(null, null)

        assertThat(list).hasSize(2)
        assertThat(list.first().isDefault).isTrue()
        assertThat(list.first().code).isEqualTo("MAIN")
    }

    @Test
    fun `units of measure are a closed and readable catalog`() {
        val all = units.all()

        assertThat(all.map { it.code }).containsExactly("PZA")
    }
}
