package dev.axzy.stock.app.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.ZoneId

/**
 * The API's own configuration. The values come from environment variables
 * (`application.yml`); the secrets have no default value, so without them the API does not start.
 */
@ConfigurationProperties("stock")
data class StockProperties(
    /** The deployed version; `GET /v1/health` reports it. */
    val version: String,
    /** The operation's time zone: the schedule policies are evaluated in it. */
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
        /** Only used if the database has no users: it creates the first administrator. */
        val bootstrapPassword: String?,
    )
}
