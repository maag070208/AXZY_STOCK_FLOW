package dev.axzy.stock.network.api

import dev.axzy.stock.network.client.StockHttpClient
import dev.axzy.stock.network.client.decode
import dev.axzy.stock.network.dto.health.HealthDto
import dev.axzy.stock.network.error.ApiOutcome

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * `/v1/health` — salud
 *
 * Contrato: docs/historico/CONTRATO_API.md §10.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class HealthApi internal constructor(private val http: StockHttpClient) {

    /**
     * Comprueba el estado del servidor.
     *
     * Es **público, sin autenticación**, y por eso va sin token: es la llamada
     * que la pantalla de configuración usa para probar la URL antes de tener
     * credenciales.
     *
     * Es la **única** llamada que lee el cuerpo aunque el estado no sea `2xx`:
     * con `status = DOWN` el contrato responde `503` con el diagnóstico dentro
     * (`database.error`, `schemaVersion`), y descartarlo convertiría «la base
     * está caída» en «no se pudo conectar», que es un mensaje que no ayuda a
     * nadie.
     */
    suspend fun health(): ApiOutcome<HealthDto> =
        http.getAllowAnyStatus("/health").decode()
}
