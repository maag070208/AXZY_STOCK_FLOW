package dev.axzy.stock.network.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin

/**
 * El motor de iOS: **Darwin** (`NSURLSession`).
 *
 * No se implementa un cliente propio porque `NSURLSession` es el que conoce el
 * App Transport Security, el Keychain de confianza, el proxy del sistema y el
 * estado del modo avión. Un cliente HTTP propio en iOS es una fuente de
 * comportamientos distintos de los del sistema, y eso en un iPad de bodega se
 * nota.
 */
internal actual fun platformHttpEngine(): HttpClientEngine = Darwin.create()
