package dev.axzy.stock.database.repo

import dev.axzy.stock.database.StockDatabase
import dev.axzy.stock.database.model.ExistenciaLocal
import dev.axzy.stock.database.model.aModelo
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CACHÉ DE EXISTENCIAS
 *
 * El total de un producto **se deriva de sus ubicaciones**, igual que en el
 * backend y que en el dominio (`Σ stock_by_location + Σ stock_in_transit`).
 * Guardarlo como un número aparte sería la forma de que un día no cuadre: es
 * exactamente el `store.stock` del legacy, que se escribía por API y del que se
 * intentaba deducir la ubicación.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ExistenciasLocales(private val db: StockDatabase) {

    private val consultas get() = db.stockQueries

    fun guardar(existencia: ExistenciaLocal) {
        consultas.insertarExistencia(
            producto_id = existencia.productoId,
            ubicacion_id = existencia.ubicacionId,
            codigo_producto = existencia.codigoProducto,
            nombre_ubicacion = existencia.nombreUbicacion,
            almacen_id = existencia.almacenId,
            cantidad_milesimas = existencia.cantidad.value,
            actualizado_en = existencia.actualizadoEn,
        )
    }

    fun reemplazarTodo(existencias: List<ExistenciaLocal>) {
        db.transaction {
            consultas.borrarExistencias()
            existenciasValidas(existencias).forEach { guardar(it) }
        }
    }

    /**
     * Descarta las existencias negativas antes de guardarlas.
     *
     * Es la invariante I2 del dominio («la operación no puede dejar stock
     * negativo») aplicada en la frontera de la caché: si llegara un negativo,
     * guardarlo haría que el móvil mostrara un saldo imposible y el operador
     * perdería la confianza en la pantalla.
     */
    private fun existenciasValidas(existencias: List<ExistenciaLocal>): List<ExistenciaLocal> =
        existencias.filter { it.cantidad.isPositive || it.cantidad.isZero }

    fun deProducto(productoId: Long): List<ExistenciaLocal> =
        consultas.existenciasDeProducto(productoId).executeAsList().map { it.aModelo() }

    fun deUbicacion(ubicacionId: Long): List<ExistenciaLocal> =
        consultas.existenciasDeUbicacion(ubicacionId).executeAsList().map { it.aModelo() }

    /** Total del producto, derivado de la suma de sus ubicaciones. */
    fun totalDe(productoId: Long): Quantity =
        Quantity.ofScaled(consultas.totalDeProducto(productoId).executeAsOne())

    fun conStock(): List<ExistenciaLocal> =
        consultas.existenciasConStock().executeAsList().map { it.aModelo() }

    fun listar(): List<ExistenciaLocal> =
        consultas.listarExistencias().executeAsList().map { it.aModelo() }

    fun limpiar() {
        consultas.borrarExistencias()
    }
}
