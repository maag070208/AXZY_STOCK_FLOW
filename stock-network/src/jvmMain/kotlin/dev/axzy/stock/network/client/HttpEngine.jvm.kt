package dev.axzy.stock.network.client

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp

/**
 * El motor de JVM: **OkHttp**.
 *
 * En la JVM lo usa el backend para hablar con servicios externos y, sobre todo,
 * los tests del módulo cuando no se inyecta `MockEngine`.
 *
 * Está duplicado en `androidMain` porque `androidMain` y `jvmMain` son source
 * sets **hermanos** en la jerarquía por defecto: Android no hereda de JVM. La
 * alternativa era un source set intermedio `jvmSharedMain`, que añade
 * configuración de Gradle para ahorrar siete líneas. No compensa.
 */
internal actual fun platformHttpEngine(): HttpClientEngine = OkHttp.create()
