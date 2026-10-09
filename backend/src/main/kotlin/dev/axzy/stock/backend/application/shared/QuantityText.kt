package dev.axzy.stock.backend.application.shared

import dev.axzy.stock.domain.value.Quantity
import java.math.BigDecimal

/**
 * La cantidad como texto con sus 3 decimales (`"5193.000"`), que es el formato del contrato
 * (§1.7): las cantidades viajan como cadena decimal y nunca como número JSON.
 *
 * Parte del entero escalado y no pasa por `Double`: el `Decimal` contra `number` del legacy es
 * el origen de sus descuadres de redondeo.
 */
fun Quantity.unitsText(): String = BigDecimal.valueOf(value).movePointLeft(Quantity.SCALE).toPlainString()
