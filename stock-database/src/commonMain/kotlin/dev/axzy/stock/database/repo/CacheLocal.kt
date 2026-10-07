package dev.axzy.stock.database.repo

import dev.axzy.stock.database.StockDatabase
import dev.axzy.stock.database.model.AlmacenLocal
import dev.axzy.stock.database.model.ProductoLocal
import dev.axzy.stock.database.model.UbicacionLocal
import dev.axzy.stock.database.model.aModelo

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CACHÉ DEL CATÁLOGO Y DEL ALMACÉN FÍSICO
 *
 * El móvil necesita **el catálogo y las ubicaciones precargados** para poder
 * operar sin señal: no se puede validar un código de barras contra un servidor
 * que no está. Es la diferencia entre «la app falla sin red» (legacy) y «la app
 * captura y sincroniza después».
 *
 * Todas las escrituras van con `INSERT OR REPLACE`: la caché se reescribe desde
 * la API, que es la fuente maestra. No hay merge ni resolución de conflictos
 * porque **no hay dos escritores**: el móvil nunca inventa un producto, solo
 * copia el que el servidor le dio.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Caché de productos. */
class CatalogoLocal(private val db: StockDatabase) {

    private val consultas get() = db.stockQueries

    fun guardar(producto: ProductoLocal) {
        consultas.insertarProducto(
            id = producto.id,
            codigo = producto.codigo,
            nombre = producto.nombre,
            descripcion = producto.descripcion,
            unidad_medida = producto.unidadMedida,
            external_id = producto.externalId,
            activo = if (producto.activo) 1L else 0L,
            stock_milesimas = producto.stock.value,
            actualizado_en = producto.actualizadoEn,
        )
    }

    /**
     * Reemplaza el catálogo completo.
     *
     * Es lo que se hace tras sincronizar: la API es la fuente maestra, así que
     * un producto que ya no viene **deja de existir** en la caché en lugar de
     * quedarse como un fantasma que el operador puede escanear.
     */
    fun reemplazarTodo(productos: List<ProductoLocal>) {
        db.transaction {
            consultas.borrarProductos()
            productos.forEach { guardar(it) }
        }
    }

    fun porId(id: Long): ProductoLocal? = consultas.productoPorId(id).executeAsOneOrNull()?.aModelo()

    fun porCodigo(codigo: String): ProductoLocal? =
        consultas.productoPorCodigo(codigo).executeAsOneOrNull()?.aModelo()

    /**
     * Búsqueda libre por código o nombre.
     *
     * Se resuelve en local y no en el servidor a propósito: es la búsqueda que
     * el operador hace **mientras camina** por el pasillo, y ahí la red es
     * justo lo que no hay.
     */
    fun buscar(texto: String, limite: Long = 50): List<ProductoLocal> {
        val patron = "%${texto.trim()}%"
        return consultas.buscarProductos(patron, limite).executeAsList().map { it.aModelo() }
    }

    fun listar(): List<ProductoLocal> = consultas.listarProductos().executeAsList().map { it.aModelo() }

    fun contar(): Long = consultas.contarProductos().executeAsOne()

    fun limpiar() {
        consultas.borrarProductos()
    }
}

/** Caché de bodegas y ubicaciones. */
class UbicacionesLocales(private val db: StockDatabase) {

    private val consultas get() = db.stockQueries

    fun guardarAlmacen(almacen: AlmacenLocal) {
        consultas.insertarAlmacen(
            id = almacen.id,
            codigo = almacen.codigo,
            nombre = almacen.nombre,
            es_predeterminada = if (almacen.esPredeterminada) 1L else 0L,
            activo = if (almacen.activo) 1L else 0L,
        )
    }

    fun guardar(ubicacion: UbicacionLocal) {
        consultas.insertarUbicacion(
            id = ubicacion.id,
            almacen_id = ubicacion.almacenId,
            almacen_codigo = ubicacion.almacenCodigo,
            nombre_visible = ubicacion.nombreVisible,
            tipo = ubicacion.tipo,
            activa = if (ubicacion.activa) 1L else 0L,
            actualizado_en = ubicacion.actualizadoEn,
        )
    }

    fun reemplazarTodo(
        almacenes: List<AlmacenLocal>,
        ubicaciones: List<UbicacionLocal>,
    ) {
        db.transaction {
            consultas.borrarAlmacenes()
            consultas.borrarUbicaciones()
            almacenes.forEach { guardarAlmacen(it) }
            ubicaciones.forEach { guardar(it) }
        }
    }

    fun almacenes(): List<AlmacenLocal> =
        consultas.listarAlmacenes().executeAsList().map { it.aModelo() }

    fun porId(id: Long): UbicacionLocal? =
        consultas.ubicacionPorId(id).executeAsOneOrNull()?.aModelo()

    fun listar(): List<UbicacionLocal> =
        consultas.listarUbicaciones().executeAsList().map { it.aModelo() }

    fun deAlmacen(almacenId: Long): List<UbicacionLocal> =
        consultas.listarUbicacionesDeAlmacen(almacenId).executeAsList().map { it.aModelo() }

    fun contar(): Long = consultas.contarUbicaciones().executeAsOne()

    /** Resuelve una ubicación por su etiqueta visible (`04-B-C`), que es lo que se escanea. */
    fun porNombreVisible(nombre: String): UbicacionLocal? =
        listar().firstOrNull { it.nombreVisible.equals(nombre, ignoreCase = true) }

    fun limpiar() {
        db.transaction {
            consultas.borrarAlmacenes()
            consultas.borrarUbicaciones()
        }
    }
}
