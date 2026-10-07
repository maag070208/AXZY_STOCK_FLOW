package dev.axzy.stock.database.model

import dev.axzy.stock.database.Almacen as FilaAlmacen
import dev.axzy.stock.database.Existencia as FilaExistencia
import dev.axzy.stock.database.Operacion_pendiente as FilaOperacion
import dev.axzy.stock.database.Producto as FilaProducto
import dev.axzy.stock.database.Ubicacion as FilaUbicacion
import dev.axzy.stock.domain.value.Quantity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * MODELOS LOCALES DEL MÓVIL
 *
 * Son **modelos de caché**, no DTOs ni modelos de dominio: existen para que el
 * móvil pueda operar sin señal. Se separan de las filas generadas por
 * SQLDelight por dos motivos:
 *
 *  1. Las filas generadas tienen nombres de columna (`stock_milesimas`,
 *     `unidad_medida`) y tipos de SQLite (`Long` para un booleano). Los modelos
 *     locales hablan el idioma del resto del código.
 *  2. Los modelos locales usan los **objetos de valor del dominio**
 *     ([Quantity], en milésimas enteras). Guardar un `Double` en SQLite y
 *     sumarlo en el móvil es exactamente de donde salían los descuadres de
 *     redondeo del legacy.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Un producto del catálogo, cacheado para poder buscar sin red. */
data class ProductoLocal(
    val id: Long,
    /** Código de negocio y también código de barras. */
    val codigo: String,
    val nombre: String,
    val descripcion: String?,
    val unidadMedida: String,
    val externalId: Long?,
    val activo: Boolean,
    /** Existencias totales derivadas, tal como las calculó el servidor. */
    val stock: Quantity,
    val actualizadoEn: Long,
)

/** Una bodega. */
data class AlmacenLocal(
    val id: Long,
    val codigo: String,
    val nombre: String,
    val esPredeterminada: Boolean,
    val activo: Boolean,
)

/**
 * Una ubicación física.
 *
 * `nombreVisible` es la etiqueta que está impresa en el estante (`04-B-C`): es
 * lo que el operador lee y lo que se usa para resolver la ubicación cuando no
 * hay red.
 */
data class UbicacionLocal(
    val id: Long,
    val almacenId: Long,
    val almacenCodigo: String?,
    val nombreVisible: String,
    /** `SHELF` o `RECEIVING`. */
    val tipo: String,
    val activa: Boolean,
    val actualizadoEn: Long,
)

/** Existencia de un producto en una ubicación concreta. */
data class ExistenciaLocal(
    val productoId: Long,
    val ubicacionId: Long,
    val codigoProducto: String,
    val nombreUbicacion: String,
    val almacenId: Long,
    val cantidad: Quantity,
    val actualizadoEn: Long,
)

// ── Cola de sincronización ──────────────────────────────────────────────────

/**
 * Estado de una operación encolada.
 *
 * `EN_CURSO` existe para que el worker no reenvíe lo que ya está enviando: sin
 * ese estado, un ciclo lento y otro rápido mandarían la misma operación dos
 * veces y dependeríamos solo de la idempotencia del servidor para no duplicar.
 */
enum class EstadoOperacion(val valor: String) {
    PENDIENTE("PENDIENTE"),
    EN_CURSO("EN_CURSO"),
    FALLIDA("FALLIDA"),
    COMPLETADA("COMPLETADA"),
    ;

    companion object {
        fun desde(valor: String): EstadoOperacion =
            entries.firstOrNull { it.valor == valor }
                ?: error("Estado de operación desconocido en la base local: '$valor'")
    }
}

/**
 * Tipo de operación encolada.
 *
 * Son exactamente las que el contrato (§1.5) marca como obligatorias de
 * idempotencia, porque son **las que mueven inventario o crean documentos**.
 * Tenerlas como enum evita el `String` suelto que en el legacy acababa con
 * cinco variantes del mismo nombre.
 */
enum class TipoOperacion(val valor: String, val descripcion: String) {
    CAPTURA_LINEA("CAPTURA_LINEA", "Captura física de una línea de factura"),
    UBICACION("UBICACION", "Ubicar mercancía de recepción a un estante"),
    AJUSTE("AJUSTE", "Ajuste de inventario (merma, conteo, faltante)"),
    EMPAQUE("EMPAQUE", "Empaque de una línea de orden"),
    CONFIRMACION_ORDEN("CONFIRMACION_ORDEN", "Confirmación de una orden de surtido"),
    EXPORTACION_ERP("EXPORTACION_ERP", "Exportación de una orden al ERP"),
    ;

    companion object {
        fun desde(valor: String): TipoOperacion =
            entries.firstOrNull { it.valor == valor }
                ?: error("Tipo de operación desconocido en la base local: '$valor'")
    }
}

/**
 * Una operación capturada sin señal, esperando a poder enviarse.
 *
 * Guarda **la petición HTTP ya construida** (`metodo`, `ruta`, `cuerpo`) en
 * lugar de los campos sueltos del caso de uso. El motivo: cuando cambie el
 * flujo (por ejemplo, un ajuste con un campo nuevo), el reenvío sigue mandando
 * exactamente lo que se capturó en el piso, sin reinterpretarlo con el código
 * de una versión posterior de la app.
 */
data class OperacionPendiente(
    val id: Long,
    /** La clave que viaja en la cabecera `Idempotency-Key` (contrato §1.5). */
    val idempotencyKey: String,
    val tipo: TipoOperacion,
    val metodo: String,
    val ruta: String,
    val cuerpo: String,
    val estado: EstadoOperacion,
    val intentos: Int,
    val ultimoError: String?,
    val creadaEn: Long,
    val ultimoIntentoEn: Long?,
)

// ── Conversión de fila generada a modelo local ──────────────────────────────

internal fun FilaProducto.aModelo(): ProductoLocal = ProductoLocal(
    id = id,
    codigo = codigo,
    nombre = nombre,
    descripcion = descripcion,
    unidadMedida = unidad_medida,
    externalId = external_id,
    activo = activo != 0L,
    // La columna guarda milésimas enteras: el dominio no ve un `Double` nunca.
    stock = Quantity.ofScaled(stock_milesimas),
    actualizadoEn = actualizado_en,
)

internal fun FilaAlmacen.aModelo(): AlmacenLocal = AlmacenLocal(
    id = id,
    codigo = codigo,
    nombre = nombre,
    esPredeterminada = es_predeterminada != 0L,
    activo = activo != 0L,
)

internal fun FilaUbicacion.aModelo(): UbicacionLocal = UbicacionLocal(
    id = id,
    almacenId = almacen_id,
    almacenCodigo = almacen_codigo,
    nombreVisible = nombre_visible,
    tipo = tipo,
    activa = activa != 0L,
    actualizadoEn = actualizado_en,
)

internal fun FilaExistencia.aModelo(): ExistenciaLocal = ExistenciaLocal(
    productoId = producto_id,
    ubicacionId = ubicacion_id,
    codigoProducto = codigo_producto,
    nombreUbicacion = nombre_ubicacion,
    almacenId = almacen_id,
    cantidad = Quantity.ofScaled(cantidad_milesimas),
    actualizadoEn = actualizado_en,
)

internal fun FilaOperacion.aModelo(): OperacionPendiente = OperacionPendiente(
    id = id,
    idempotencyKey = idempotency_key,
    tipo = TipoOperacion.desde(tipo),
    metodo = metodo,
    ruta = ruta,
    cuerpo = cuerpo,
    estado = EstadoOperacion.desde(estado),
    intentos = intentos.toInt(),
    ultimoError = ultimo_error,
    creadaEn = creada_en,
    ultimoIntentoEn = ultimo_intento_en,
)
