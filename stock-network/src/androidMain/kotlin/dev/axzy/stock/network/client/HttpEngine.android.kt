package dev.axzy.stock.network.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * El motor de Android: **OkHttp**, el mismo que en la JVM.
 *
 * Es el motor que la app móvil usa de verdad, y el que respeta el proxy y los
 * certificados instalados en el dispositivo. En una bodega con red interna y
 * certificado corporativo, esto no es un detalle.
 */
internal actual fun platformHttpEngine(): HttpClientEngine = OkHttp.create()
