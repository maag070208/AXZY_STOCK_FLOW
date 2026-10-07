package dev.axzy.stock.network.client

import kotlinx.serialization.json.Json

/**
 * El JSON del contrato. **Uno solo para todo el módulo** (y expuesto a los
 * tests) para que no haya dos configuraciones distintas según quién llame.
 *
 * Cada opción está aquí por un motivo concreto del contrato:
 *
 *  · `ignoreUnknownKeys = true` — el servidor puede **añadir** un campo a un
 *    DTO y los clientes ya desplegados siguen funcionando. Sin esto, cada
 *    despliegue del backend rompe a todas las apps instaladas hasta que se
 *    actualicen, que en un móvil de bodega puede ser semanas.
 *
 *  · `coerceInputValues = true` — un enum con un valor que este cliente todavía
 *    no conoce (`IN_TRANSIT` añadido después) cae a su valor por defecto en vez
 *    de tumbar la lista entera. Un valor nuevo en un estado no debe impedir
 *    pintar la pantalla.
 *
 *  · `explicitNulls = false` — al serializar, un campo nulo **se omite**. Es la
 *    semántica correcta para cuerpos donde `null` significa «no lo toco» y el
 *    backend rechaza `"campo": null` en los campos no anulables. El precio
 *    honesto: no se puede «vaciar» un campo opcional enviando `null`; para eso
 *    está la cadena vacía, que el contrato sí acepta.
 *
 *  · `encodeDefaults = true` — los cuerpos llevan los valores por defecto de
 *    forma explícita. El contrato marca los valores por defecto (p. ej.
 *    `copies: 1`) y confiar en que el servidor aplique el mismo default obliga a
 *    que los dos lados coincidan para siempre.
 */
public val stockJson: Json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}
