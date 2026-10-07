package dev.axzy.stock.network

import dev.axzy.stock.network.api.AuthApi
import dev.axzy.stock.network.api.HealthApi
import dev.axzy.stock.network.api.InventoryApi
import dev.axzy.stock.network.api.InvoicesApi
import dev.axzy.stock.network.api.LabelsApi
import dev.axzy.stock.network.api.LocationsApi
import dev.axzy.stock.network.api.OrdersApi
import dev.axzy.stock.network.api.ProductsApi
import dev.axzy.stock.network.api.ReportsApi
import dev.axzy.stock.network.api.TransfersApi
import dev.axzy.stock.network.client.InMemoryTokenStore
import dev.axzy.stock.network.client.StockApiConfig
import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.TokenStorePort
import io.ktor.client.engine.HttpClientEngine

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `StockApi` — LA API PÚBLICA QUE CONSUME EL MÓVIL
 *
 * ```kotlin
 * // Cableado en la app (una vez):
 * val api = StockApi(
 *     config = StockApiConfig(baseUrl = "https://almacen.interno/api/v1"),
 *     tokens = SecureTokenStoreAdapter(datastore.secureStore),   // de stock-datastore
 * )
 * api.onSessionExpired = { navegarAlLogin() }
 *
 * // Uso:
 * when (val resultado = api.auth.login(usuario, contrasena)) {
 *     is Either.Right -> ...
 *     is Either.Left -> cuando(resultado.error) { ... }   // `when` exhaustivo sobre ApiError
 * }
 *
 * api.close()   // al cerrar la app
 * ```
 *
 * Decisiones que conviene conocer antes de usarla:
 *
 *  1. **Nada lanza excepciones por un error HTTP.** Todo devuelve
 *     `ApiOutcome<T>` = `Either<ApiError, T>`. El `when` es exhaustivo, así que
 *     no se puede olvidar un caso de error.
 *  2. **El refresh es automático.** Un `401` dispara un refresh y **un solo**
 *     reintento. Si el refresh falla de forma definitiva, se limpia la sesión y
 *     se llama a [onSessionExpired].
 *  3. **`Idempotency-Key` es obligatoria en lo que mueve inventario** y va en el
 *     cuerpo de la petición que la lleva (ajustes, ubicación, movimiento,
 *     captura, empaque). Genera la clave **una vez**, guárdala en la cola de
 *     sincronización y reutilízala en cada reintento: es lo que convierte un
 *     reintento sin señal en un no-op en lugar de en un movimiento duplicado.
 *  4. **`stock-network` no decide reglas de negocio.** Para esas decisiones
 *     (matriz de movimientos, discrepancia, segregación de funciones) están los
 *     mappers DTO→dominio y `stock-domain`.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class StockApi(
    val config: StockApiConfig,
    tokens: TokenStorePort = InMemoryTokenStore(),
    engine: HttpClientEngine? = null,
) {

    /** El cliente HTTP. Público para casos avanzados (descargas, cabeceras propias). */
    val http: StockHttpClient = StockHttpClient(config = config, tokens = tokens, engine = engine)

    val auth: AuthApi = AuthApi(http)
    val products: ProductsApi = ProductsApi(http)
    val locations: LocationsApi = LocationsApi(http)
    val inventory: InventoryApi = InventoryApi(http)
    val invoices: InvoicesApi = InvoicesApi(http)
    val orders: OrdersApi = OrdersApi(http)
    val transfers: TransfersApi = TransfersApi(http)
    val labels: LabelsApi = LabelsApi(http)
    val reports: ReportsApi = ReportsApi(http)
    val health: HealthApi = HealthApi(http)

    /**
     * La sesión dejó de ser recuperable (refresh rechazado o reuso detectado).
     * La app debe limpiar su estado en memoria y volver al login.
     */
    var onSessionExpired: () -> Unit
        get() = http.onSessionExpired
        set(value) {
            http.onSessionExpired = value
        }

    /** Libera el motor HTTP. Conviene llamarlo al cerrar la app. */
    fun close() = http.close()
}
