package dev.axzy.stock.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.axzy.stock.database.model.AlmacenLocal
import dev.axzy.stock.database.model.EstadoOperacion
import dev.axzy.stock.database.model.ExistenciaLocal
import dev.axzy.stock.database.model.ProductoLocal
import dev.axzy.stock.database.model.TipoOperacion
import dev.axzy.stock.database.model.UbicacionLocal
import dev.axzy.stock.database.repo.ColaSincronizacion
import dev.axzy.stock.domain.value.Quantity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA COLA DE SINCRONIZACIÓN — LA PRUEBA QUE MÁS IMPORTA DEL MÓDULO
 *
 * La propiedad que se verifica, y por qué es la que importa: **reencolar la
 * misma operación no la duplica**. El móvil reintenta porque la red falla; si el
 * reintento creara una segunda operación, el servidor recibiría dos movimientos
 * de inventario y el descuadre se descubriría con un conteo físico.
 *
 * Se prueba contra un SQLite REAL (en memoria), no contra un doble: lo que se
 * está verificando es una restricción de la base (`UNIQUE`), y un mock no la
 * tiene.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class ColaSincronizacionTest {

    private var ahora = 1_700_000_000L

    private fun baseEnMemoria(): OfflineStockDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        asegurarEsquema(driver)
        return OfflineStockDatabase(driver)
    }

    private fun cola(base: OfflineStockDatabase): ColaSincronizacion =
        ColaSincronizacion(base.db, reloj = { ahora })

    // ── Idempotencia de la cola ─────────────────────────────────────────────

    @Test
    fun `encolar la misma clave dos veces no duplica la operacion`() {
        val base = baseEnMemoria()
        val cola = cola(base)

        val primera = cola.encolar(
            idempotencyKey = "uuid-1",
            tipo = TipoOperacion.CAPTURA_LINEA,
            metodo = "POST",
            ruta = "/v1/invoices/15/lines/88/capture",
            cuerpo = """{"physicalQuantity":80.000}""",
        )
        val segunda = cola.encolar(
            idempotencyKey = "uuid-1",
            tipo = TipoOperacion.CAPTURA_LINEA,
            metodo = "POST",
            ruta = "/v1/invoices/15/lines/88/capture",
            cuerpo = """{"physicalQuantity":80.000}""",
        )

        assertIs<ColaSincronizacion.Resultado.Nueva>(primera)
        // El segundo intento es un no-op: es exactamente lo que hace que el
        // móvil pueda reintentar sin miedo.
        assertIs<ColaSincronizacion.Resultado.YaEstaba>(segunda)
        assertEquals(1L, cola.contarPorEstado(EstadoOperacion.PENDIENTE))
        assertEquals(1, cola.listarTodas().size)
    }

    @Test
    fun `la unicidad la impone la base, no una comprobacion del codigo`() {
        val base = baseEnMemoria()
        val driver = base.driver

        // Se escriben dos veces con SQL directo, saltándose el repositorio: es la
        // prueba de que la garantía es estructural (índice UNIQUE) y no depende
        // de que el código se acuerde de comprobar nada.
        val insertar = """
            INSERT OR IGNORE INTO operacion_pendiente(
                idempotency_key, tipo, metodo, ruta, cuerpo, estado, intentos, creada_en
            ) VALUES ('clave-repetida', 'AJUSTE', 'POST', '/v1/inventory/adjustments', '{}', 'PENDIENTE', 0, 1)
        """.trimIndent()

        driver.execute(null, insertar, 0)
        driver.execute(null, insertar, 0)

        assertEquals(1L, cola(base).contarPorEstado(EstadoOperacion.PENDIENTE))
    }

    @Test
    fun `claves distintas si producen operaciones distintas`() {
        val base = baseEnMemoria()
        val cola = cola(base)

        cola.encolar("uuid-a", TipoOperacion.AJUSTE, "POST", "/v1/inventory/adjustments", "{}")
        cola.encolar("uuid-b", TipoOperacion.AJUSTE, "POST", "/v1/inventory/adjustments", "{}")

        assertEquals(2L, cola.contarPorSincronizar())
    }

    @Test
    fun `una operacion sin clave de idempotencia se rechaza`() {
        val cola = cola(baseEnMemoria())

        val fallo = runCatching {
            cola.encolar("", TipoOperacion.AJUSTE, "POST", "/v1/inventory/adjustments", "{}")
        }.exceptionOrNull()

        // Sin clave no hay idempotencia posible: encolarla sería aceptar que un
        // reintento puede duplicar un movimiento.
        assertNotNull(fallo)
        assertTrue(fallo.message!!.contains("idempotencia"), fallo.message)
    }

    // ── Ciclo de vida del worker ────────────────────────────────────────────

    @Test
    fun `el worker ve las operaciones por enviar y las mas antiguas primero`() {
        val base = baseEnMemoria()
        val cola = cola(base)

        ahora = 100
        cola.encolar("clave-vieja", TipoOperacion.AJUSTE, "POST", "/v1/inventory/adjustments", "{}")
        ahora = 200
        cola.encolar("clave-nueva", TipoOperacion.EMPAQUE, "POST", "/v1/orders/30/lines/55/package", "{}")

        val pendientes = cola.porEnviar()

        assertEquals(2, pendientes.size)
        assertEquals("clave-vieja", pendientes.first().idempotencyKey)
        assertEquals(100L, pendientes.first().creadaEn)
    }

    @Test
    fun `una operacion completada no se vuelve a enviar`() {
        val base = baseEnMemoria()
        val cola = cola(base)
        val creada = assertIs<ColaSincronizacion.Resultado.Nueva>(
            cola.encolar("clave", TipoOperacion.AJUSTE, "POST", "/v1/inventory/adjustments", "{}")
        ).operacion

        cola.marcarEnCurso(creada.id)
        cola.marcarCompletada(creada.id)

        assertTrue(cola.porEnviar().isEmpty())
        assertEquals(1L, cola.contarPorEstado(EstadoOperacion.COMPLETADA))
        // La fila NO se borra al completarse: hay que poder responder «¿esto se
        // envió?» si el operador pregunta.
        assertEquals(1, cola.listarTodas().size)
    }

    @Test
    fun `un fallo cuenta el intento y guarda cuando y por que`() {
        val base = baseEnMemoria()
        val cola = cola(base)
        val creada = assertIs<ColaSincronizacion.Resultado.Nueva>(
            cola.encolar("clave", TipoOperacion.UBICACION, "POST", "/v1/inventory/locate", "{}")
        ).operacion

        ahora = 500
        cola.registrarFallo(creada.id, error = "sin ruta al host", ahora = ahora)

        val fallida = assertNotNull(cola.porId(creada.id))
        assertEquals(EstadoOperacion.FALLIDA, fallida.estado)
        assertEquals(1, fallida.intentos)
        assertEquals("sin ruta al host", fallida.ultimoError)
        // La marca de tiempo es lo que permite el backoff exponencial: sin ella,
        // veinte móviles reintentarían a la vez contra un servidor caído.
        assertEquals(500L, fallida.ultimoIntentoEn)
        // Y sigue siendo reintentable: un fallo no descarta el trabajo del
        // operador.
        assertTrue(cola.porEnviar().any { it.id == creada.id })
    }

    @Test
    fun `un fallo recorta el mensaje para no llenar la base con un stacktrace`() {
        val base = baseEnMemoria()
        val cola = cola(base)
        val creada = assertIs<ColaSincronizacion.Resultado.Nueva>(
            cola.encolar("clave", TipoOperacion.AJUSTE, "POST", "/v1/inventory/adjustments", "{}")
        ).operacion

        cola.registrarFallo(creada.id, error = "x".repeat(5_000))

        val fallida = assertNotNull(cola.porId(creada.id))
        assertTrue(fallida.ultimoError!!.length <= 500, "longitud: ${fallida.ultimoError!!.length}")
    }

    @Test
    fun `una operacion en curso huerfana se recupera y se puede reintentar`() {
        val base = baseEnMemoria()
        val cola = cola(base)
        val creada = assertIs<ColaSincronizacion.Resultado.Nueva>(
            cola.encolar("clave", TipoOperacion.CONFIRMACION_ORDEN, "POST", "/v1/orders/30/confirm", "{}")
        ).operacion

        // El proceso murió a mitad del envío: queda EN_CURSO y nadie la mira.
        cola.marcarEnCurso(creada.id)
        assertTrue(cola.porEnviar().isEmpty())

        val recuperadas = cola.recuperarEnCurso()

        assertEquals(1, recuperadas)
        assertEquals(EstadoOperacion.FALLIDA, assertNotNull(cola.porId(creada.id)).estado)
        assertTrue(cola.porEnviar().any { it.id == creada.id })
    }

    @Test
    fun `limpiar las completadas no toca las pendientes`() {
        val base = baseEnMemoria()
        val cola = cola(base)
        val completada = assertIs<ColaSincronizacion.Resultado.Nueva>(
            cola.encolar("hecha", TipoOperacion.AJUSTE, "POST", "/v1/inventory/adjustments", "{}")
        ).operacion
        cola.encolar("pendiente", TipoOperacion.AJUSTE, "POST", "/v1/inventory/adjustments", "{}")

        cola.marcarCompletada(completada.id)
        cola.limpiarCompletadas()

        assertEquals(1, cola.listarTodas().size)
        assertEquals("pendiente", cola.listarTodas().single().idempotencyKey)
    }

    // ── Caché local ─────────────────────────────────────────────────────────

    @Test
    fun `el catalogo se guarda, se busca y se reemplaza entero`() {
        val base = baseEnMemoria()
        val catalogo = base.catalogo

        catalogo.guardar(
            ProductoLocal(
                id = 1, codigo = "ABRAA1B", nombre = "ABRAZADERA DE ARRANQUE",
                descripcion = null, unidadMedida = "PZA", externalId = 1L, activo = true,
                stock = Quantity.of(5193), actualizadoEn = 1,
            )
        )
        catalogo.guardar(
            ProductoLocal(
                id = 2, codigo = "TORNILLO1", nombre = "TORNILLO 1/4",
                descripcion = "hexagonal", unidadMedida = "PZA", externalId = null, activo = true,
                stock = Quantity.ZERO, actualizadoEn = 1,
            )
        )

        assertEquals(5193.0, assertNotNull(catalogo.porCodigo("ABRAA1B")).stock.toDoubleExample())
        assertEquals(1, catalogo.buscar("ABRA").size)
        assertEquals(2, catalogo.buscar("").size)
        assertEquals(2L, catalogo.contar())

        // La API es la fuente maestra: un producto que ya no viene desaparece de
        // la caché en lugar de quedarse como un fantasma escaneable.
        catalogo.reemplazarTodo(emptyList())
        assertEquals(0L, catalogo.contar())
        assertNull(catalogo.porCodigo("ABRAA1B"))
    }

    @Test
    fun `las existencias se guardan en milesimas y el total se deriva`() {
        val base = baseEnMemoria()
        val existencias = base.existencias

        existencias.guardar(
            ExistenciaLocal(1, 2, "ABRAA1B", "04-B-C", 1, Quantity.of(100), actualizadoEn = 1)
        )
        existencias.guardar(
            ExistenciaLocal(1, 3, "ABRAA1B", "05-A-A", 1, Quantity.parse("0.5").getOrThrow(), actualizadoEn = 1)
        )

        // 100 + 0.5, exacto: en milésimas enteras no hay error de redondeo.
        assertEquals(100_500L, existencias.totalDe(1).value)
        assertEquals(2, existencias.deProducto(1).size)
    }

    @Test
    fun `una existencia negativa no se guarda porque el dominio no la admite`() {
        val base = baseEnMemoria()

        base.existencias.reemplazarTodo(
            listOf(
                ExistenciaLocal(1, 2, "ABRAA1B", "04-B-C", 1, Quantity.ofScaled(-5_000), actualizadoEn = 1),
            )
        )

        // Guardarla haría que la pantalla mostrara un saldo imposible y el
        // operador dejaría de creer al sistema.
        assertEquals(0, base.existencias.listar().size)
    }

    @Test
    fun `las ubicaciones y almacenes se cachean y se resuelven por su etiqueta visible`() {
        val base = baseEnMemoria()

        base.ubicaciones.reemplazarTodo(
            almacenes = listOf(
                AlmacenLocal(1, "PRINCIPAL", "Bodega principal", esPredeterminada = true, activo = true)
            ),
            ubicaciones = listOf(
                UbicacionLocal(2, 1, "PRINCIPAL", "04-B-C", "SHELF", activa = true, actualizadoEn = 1),
                // La ubicación de recepción es REAL, con id real: el legacy
                // devolvía una pseudo-ubicación ADMIN con id ficticio que podía
                // colisionar con la ubicación 1.
                UbicacionLocal(9, 1, "PRINCIPAL", "RECEPCION", "RECEIVING", activa = true, actualizadoEn = 1),
            ),
        )

        assertEquals(2L, base.ubicaciones.contar())
        assertEquals("RECEIVING", assertNotNull(base.ubicaciones.porNombreVisible("recepcion")).tipo)
        assertEquals(2, base.ubicaciones.deAlmacen(1).size)
        assertEquals(1, base.ubicaciones.almacenes().size)
        assertTrue(base.ubicaciones.almacenes().single().esPredeterminada)
    }

    /** Convierte la cantidad a decimal para poder compararla en un test. */
    private fun Quantity.toDoubleExample(): Double = value / 1000.0
}
