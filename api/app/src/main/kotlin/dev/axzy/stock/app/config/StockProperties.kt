package dev.axzy.stock.app.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId

/**
 * La configuración propia de la API. Los valores salen de variables de entorno
 * (`application.yml`); los secretos no tienen valor por defecto, así que sin ellos la API no arranca.
 */
@ConfigurationProperties("stock")
data class StockProperties(
    /** La versión desplegada; la informa `GET /v1/health`. */
    val version: String,
    /** La zona horaria de la operación: en ella se evalúan las políticas de horario. */
    val businessZone: ZoneId,
    val jwt: Jwt,
    val admin: Admin,
) {
    data class Jwt(
        val secret: String,
        val issuer: String,
        val audience: String,
    )

    data class Admin(
        val username: String,
        /** Solo se usa si la base no tiene usuarios: crea el primer administrador. */
        val bootstrapPassword: String?,
    )
}
