package dev.axzy.stock.adapters.persistence.service

import dev.axzy.stock.application.labeling.ServiceIdentity
import dev.axzy.stock.application.labeling.ServiceKeys
import org.springframework.jdbc.core.simple.JdbcClient
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Las credenciales de servicio (la Zebra, y mañana el ERP).
 *
 * El hash se calcula **aquí** y no en la capa de aplicación: la aplicación no hace
 * criptografía. Se usa **SHA-256** y no Argon2id —el que protege las contraseñas— por
 * una razón concreta: una contraseña de persona es corta y adivinable, y hay que hacer
 * cara cada prueba; un secreto de servicio son 32 bytes aleatorios, así que no hay nada
 * que adivinar, y en cambio hay que poder **buscar por hash** en cada llamada de la
 * impresora. Con Argon2id habría que probar fila por fila.
 */
class JdbcServiceKeys(private val jdbc: JdbcClient) : ServiceKeys {

    override fun findActive(secret: String, at: Instant): ServiceIdentity? {
        if (secret.isBlank()) return null
        return jdbc.sql(
            """
            SELECT id, name, scopes FROM service_api_keys
            WHERE key_hash = :hash AND active AND revoked_at IS NULL
              AND (expires_at IS NULL OR expires_at > CAST(:at AS timestamptz))
            """.trimIndent(),
        )
            .param("hash", sha256(secret))
            .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .query { rs, _ ->
                @Suppress("UNCHECKED_CAST")
                ServiceIdentity(
                    keyId = rs.getLong("id"),
                    name = rs.getString("name"),
                    scopes = (rs.getArray("scopes")?.array as? Array<String>)?.toList() ?: emptyList(),
                )
            }
            .optional()
            .orElse(null)
    }

    override fun touch(keyId: Long, at: Instant) {
        jdbc.sql("UPDATE service_api_keys SET last_used_at = CAST(:at AS timestamptz) WHERE id = :id")
            .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
            .param("id", keyId)
            .update()
    }

    private fun sha256(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    /*
     * El hash no se expone: quien siembra una credencial —una prueba, un script de
     * aprovisionamiento— calcula el SHA-256 en hexadecimal, que es el formato que esta
     * clase espera. Si el algoritmo cambiara, esas siembras fallarían y la prueba lo
     * diría, que es justo lo que se quiere de un formato de credencial.
     */
}
