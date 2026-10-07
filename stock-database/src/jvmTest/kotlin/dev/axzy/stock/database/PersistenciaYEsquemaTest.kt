package dev.axzy.stock.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.axzy.stock.database.model.EstadoOperacion
import dev.axzy.stock.database.model.TipoOperacion
import dev.axzy.stock.database.repo.ColaSincronizacion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PERSISTENCIA REAL Y MIGRACIONES
 *
 * Aquí no se usa el driver en memoria, y es a propósito: lo que se verifica es
 * que **la cola sobrevive a cerrar la app**. Un driver en memoria pasa ese test
 * siempre, porque nunca hubo nada que perder.
 *
 * La migración se prueba sobre una base de verdad en la versión 1, con una
 * operación encolada dentro. Es el escenario real: un móvil que lleva medio
 * turno sin señal y al que hay que actualizar la app **sin perder su trabajo**.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class PersistenciaYEsquemaTest {

    private val directorio = File("build/tmp/pruebas-base-local").apply { mkdirs() }

    private fun rutaLimpia(nombre: String): String =
        File(directorio, nombre).also { if (it.exists()) it.delete() }.absolutePath

    // ── La cola sobrevive al cierre ─────────────────────────────────────────

    @Test
    fun `la cola sobrevive a cerrar y reabrir la base`() {
        val ruta = rutaLimpia("sobrevive.db")

        val primera = abrirBaseLocal(ruta)
        val encolada = assertIs<ColaSincronizacion.Resultado.Nueva>(
            primera.cola.encolar(
                idempotencyKey = "uuid-del-turno",
                tipo = TipoOperacion.CAPTURA_LINEA,
                metodo = "POST",
                ruta = "/v1/invoices/15/lines/88/capture",
                cuerpo = """{"physicalQuantity":80.000,"idempotencyKey":"uuid-del-turno"}""",
            )
        ).operacion
        primera.cola.registrarFallo(encolada.id, error = "sin cobertura", ahora = 1_700_000_500L)
        primera.cerrar()

        // Reabrir es lo que hace la app al arrancar por la mañana.
        val segunda = abrirBaseLocal(ruta)
        val recuperadas = segunda.cola.listarTodas()

        assertEquals(1, recuperadas.size)
        assertEquals("uuid-del-turno", recuperadas.single().idempotencyKey)
        // La clave de idempotencia viaja intacta: es lo que hará que, si la
        // operación ya llegó al servidor antes de morir el proceso, el reenvío
        // no duplique el movimiento.
        assertEquals(TipoOperacion.CAPTURA_LINEA, recuperadas.single().tipo)
        assertEquals(EstadoOperacion.FALLIDA, recuperadas.single().estado)
        assertEquals(1_700_000_500L, recuperadas.single().ultimoIntentoEn)
        // Y sigue lista para reintentarse: el trabajo del operador no se pierde.
        assertTrue(segunda.cola.porEnviar().any { it.id == encolada.id })
        segunda.cerrar()
    }

    @Test
    fun `reabrir una base existente no vuelve a crear el esquema`() {
        val ruta = rutaLimpia("reabrir.db")

        abrirBaseLocal(ruta).apply {
            catalogo.guardar(
                dev.axzy.stock.database.model.ProductoLocal(
                    id = 1, codigo = "ABRAA1B", nombre = "ABRAZADERA", descripcion = null,
                    unidadMedida = "PZA", externalId = null, activo = true,
                    stock = dev.axzy.stock.domain.value.Quantity.of(10), actualizadoEn = 1,
                )
            )
            cerrar()
        }

        // Si `asegurarEsquema` volviera a crear el esquema, el CREATE TABLE
        // fallaría por tabla existente: este test lo detecta.
        val segunda = abrirBaseLocal(ruta)

        assertEquals(1L, segunda.catalogo.contar())
        assertEquals(2L, leerVersionDeEsquema(segunda.driver))
        segunda.cerrar()
    }

    // ── Migración v1 → v2 ───────────────────────────────────────────────────

    @Test
    fun `una base en la version 1 se migra a la 2 y conserva las operaciones encoladas`() {
        val ruta = rutaLimpia("migracion.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:$ruta")
        crearEsquemaVersion1(driver)

        // Una operación capturada sin señal ANTES de actualizar la app.
        driver.execute(
            null,
            """
            INSERT INTO operacion_pendiente(
                idempotency_key, tipo, metodo, ruta, cuerpo, estado, intentos, ultimo_error, creada_en
            ) VALUES ('clave-de-antes', 'AJUSTE', 'POST', '/v1/inventory/adjustments', '{}', 'PENDIENTE', 0, NULL, 100)
            """.trimIndent(),
            0,
        )
        assertEquals(1L, leerVersionDeEsquema(driver))

        // Lo que hace la app nueva al arrancar.
        asegurarEsquema(driver)

        assertEquals(2L, leerVersionDeEsquema(driver))
        val base = OfflineStockDatabase(driver)
        val operaciones = base.cola.listarTodas()

        // La cola sigue ahí: actualizar la app no puede costarle el trabajo al
        // operador.
        assertEquals(1, operaciones.size)
        assertEquals("clave-de-antes", operaciones.single().idempotencyKey)
        assertEquals(null, operaciones.single().ultimoIntentoEn)

        // Y la columna nueva existe y se escribe.
        base.cola.registrarFallo(operaciones.single().id, error = "timeout", ahora = 999L)
        assertEquals(999L, base.cola.porId(operaciones.single().id)?.ultimoIntentoEn)
        base.cerrar()
    }

    @Test
    fun `migrar dos veces es inofensivo`() {
        val ruta = rutaLimpia("migracion-doble.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:$ruta")
        crearEsquemaVersion1(driver)
        driver.execute(null, "PRAGMA user_version = 1", 0)

        asegurarEsquema(driver)
        asegurarEsquema(driver)

        assertEquals(2L, leerVersionDeEsquema(driver))
        driver.close()
    }

    @Test
    fun `una base mas nueva que el codigo no se degrada`() {
        val ruta = rutaLimpia("futuro.db")
        val driver = JdbcSqliteDriver("jdbc:sqlite:$ruta")
        asegurarEsquema(driver)
        driver.execute(null, "PRAGMA user_version = 99", 0)

        // Un usuario que vuelve a una versión anterior de la app no puede perder
        // sus datos por abrirla.
        asegurarEsquema(driver)

        assertEquals(99L, leerVersionDeEsquema(driver))
        driver.close()
    }

    /**
     * El esquema tal como era en la versión 1: el de `Stock.sq` **sin**
     * `ultimo_intento_en` y sin el índice por estado.
     */
    private fun crearEsquemaVersion1(driver: SqlDriver) {
        val ddl = listOf(
            """
            CREATE TABLE producto (
                id INTEGER NOT NULL PRIMARY KEY,
                codigo TEXT NOT NULL UNIQUE,
                nombre TEXT NOT NULL,
                descripcion TEXT,
                unidad_medida TEXT NOT NULL,
                external_id INTEGER,
                activo INTEGER NOT NULL DEFAULT 1,
                stock_milesimas INTEGER NOT NULL DEFAULT 0,
                actualizado_en INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE almacen (
                id INTEGER NOT NULL PRIMARY KEY,
                codigo TEXT NOT NULL,
                nombre TEXT NOT NULL,
                es_predeterminada INTEGER NOT NULL DEFAULT 0,
                activo INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent(),
            """
            CREATE TABLE ubicacion (
                id INTEGER NOT NULL PRIMARY KEY,
                almacen_id INTEGER NOT NULL,
                almacen_codigo TEXT,
                nombre_visible TEXT NOT NULL,
                tipo TEXT NOT NULL,
                activa INTEGER NOT NULL DEFAULT 1,
                actualizado_en INTEGER NOT NULL
            )
            """.trimIndent(),
            """
            CREATE TABLE existencia (
                producto_id INTEGER NOT NULL,
                ubicacion_id INTEGER NOT NULL,
                codigo_producto TEXT NOT NULL,
                nombre_ubicacion TEXT NOT NULL,
                almacen_id INTEGER NOT NULL,
                cantidad_milesimas INTEGER NOT NULL DEFAULT 0,
                actualizado_en INTEGER NOT NULL,
                PRIMARY KEY (producto_id, ubicacion_id)
            )
            """.trimIndent(),
            """
            CREATE TABLE operacion_pendiente (
                id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                idempotency_key TEXT NOT NULL,
                tipo TEXT NOT NULL,
                metodo TEXT NOT NULL,
                ruta TEXT NOT NULL,
                cuerpo TEXT NOT NULL,
                estado TEXT NOT NULL DEFAULT 'PENDIENTE',
                intentos INTEGER NOT NULL DEFAULT 0,
                ultimo_error TEXT,
                creada_en INTEGER NOT NULL
            )
            """.trimIndent(),
            "CREATE UNIQUE INDEX idx_operacion_pendiente_clave ON operacion_pendiente(idempotency_key)",
            "PRAGMA user_version = 1",
        )

        ddl.forEach { driver.execute(null, it, 0) }
    }
}
