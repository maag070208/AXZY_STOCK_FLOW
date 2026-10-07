package dev.axzy.stock.network.mapper

import dev.axzy.stock.domain.value.Quantity
import kotlin.math.round

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA CAPA DE MAPPERS — POR QUÉ EXISTE
 *
 * Los DTOs son **contratos de transporte**: describen exactamente lo que viaja
 * por el cable, y su forma la manda `CONTRATO_API.md`, no el modelo de negocio.
 * El dominio, en cambio, describe lo que el sistema **sabe**: objetos de valor
 * que no se pueden construir mal.
 *
 * Sin esta capa, los dos se mezclan, y esa mezcla es el origen de tres defectos
 * concretos y verificados del legacy:
 *
 *  1. **`Decimal` contra `number`.** Prisma devolvía `Decimal` y el JSON
 *     `number`; el mismo concepto tenía dos representaciones y ninguna era la
 *     del otro. De ahí los descuadres de redondeo. Aquí hay **una sola**
 *     representación de cantidad en el dominio ([Quantity], en milésimas
 *     enteras) y el `Double` del contrato se convierte en la frontera, con una
 *     única función, testeada.
 *
 *  2. **Estados como cadenas sueltas.** `'pending'`, `'captured'`,
 *     `'pending-to-locate'`… circulaban por el modelo y por los servicios, y el
 *     legacy llegó a filtrar por un estado que no existía. Aquí el estado del
 *     transporte es un enum del contrato y el del dominio es una máquina de
 *     estados; **la traducción es explícita y exhaustiva**, así que un estado
 *     nuevo del contrato no compila hasta decidir a qué estado del dominio
 *     corresponde.
 *
 *  3. **`difference: 0`.** Estaba declarada en el modelo, el DTO, el mapper y la
 *     respuesta, y siempre valía cero. Aquí el dominio la **deriva** y el mapper
 *     comprueba que la del servidor coincide: la discrepancia se ve en un test,
 *     no tres meses después en un conteo físico.
 *
 * Regla de la capa: **el dominio nunca ve un DTO y el DTO nunca ve un objeto de
 * valor**. Todo pasa por funciones de este paquete, que son las únicas que
 * conocen las dos formas.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Escala del contrato: **3 decimales**, ni uno más. */
const val CONTRACT_QUANTITY_DECIMALS: Int = 3

private const val SCALE_FACTOR: Double = 1000.0

/**
 * Convierte el decimal del contrato en la cantidad del dominio.
 *
 * El contrato dice «número decimal con 3 decimales, nunca cadena». El redondeo
 * es a milésimas enteras, que es lo que el dominio guarda: no hay ninguna
 * aritmética en coma flotante dentro del dominio, y por eso dos sumas del mismo
 * inventario dan el mismo resultado en Android, en iOS y en el backend.
 */
fun Double.toQuantity(): Quantity =
    Quantity.ofScaled(round(this * SCALE_FACTOR).toLong())

/** Igual que [toQuantity] pero tolera el campo ausente. */
fun Double?.toQuantityOrNull(): Quantity? = this?.toQuantity()

/** El camino de vuelta, para los cuerpos de petición. */
fun Quantity.toContractNumber(): Double = value / SCALE_FACTOR

/**
 * Convierte las unidades por caja, **rechazando el cero**.
 *
 * El legacy dividía por `unitsPerBox` y con un 0 producía `Array(NaN)` y de ahí
 * un `RangeError` en el móvil. El dominio no puede representar ese valor, así
 * que aquí se devuelve `null` en lugar de inventar un 1: un 1 silencioso
 * calcularía mal todas las cajas.
 */
fun Double?.toUnitsPerBoxOrNull(): dev.axzy.stock.domain.value.UnitsPerBox? {
    val units = this ?: return null
    val rounded = round(units).toLong()
    return if (rounded > 0) dev.axzy.stock.domain.value.UnitsPerBox.of(rounded) else null
}

/**
 * Comprueba que un decimal del contrato no trae más precisión de la pactada.
 *
 * Se usa en los tests: si el servidor empieza a mandar 3,14159, es que alguien
 * cambió la columna y el redondeo silencioso lo estaría tapando.
 */
fun Double.hasContractPrecision(): Boolean {
    val scaled = this * SCALE_FACTOR
    return round(scaled) == scaled
}
