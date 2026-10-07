package dev.axzy.stock.datastore

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PREFERENCIAS DEL USUARIO
 *
 * Tres cosas y ninguna más:
 *
 *  · **Idioma**: la app la usa gente que trabaja en español, pero el día que
 *    entre alguien que no, cambiarlo no puede exigir recompilar.
 *  · **Último usuario**: en una bodega el iPad es compartido y el turno cambia.
 *    Recordar el nombre ahorra teclearlo en cada cambio de turno — y, a
 *    propósito, **no se recuerda la contraseña**.
 *  · **Desbloqueo biométrico**: es una comodidad, no un requisito, y por eso es
 *    una preferencia y no una imposición.
 *
 * Lo que **no** está aquí: nada de la sesión. Los tokens viven en
 * [SecureStore], y esa frontera es la razón de ser de este módulo.
 * ═══════════════════════════════════════════════════════════════════════════
 */
enum class AppLanguage(val code: String) {
    SPANISH("es"),
    ENGLISH("en"),
    ;

    companion object {
        /** El idioma por defecto es el español: es el idioma de la operación. */
        val DEFAULT: AppLanguage = SPANISH

        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
    }
}

/** Preferencias del usuario sobre el almacén de clave-valor. */
class UserPreferences(
    private val keyValue: KeyValueStore,
) {

    suspend fun language(): AppLanguage = AppLanguage.fromCode(keyValue.getString(CLAVE_IDIOMA))

    suspend fun setLanguage(language: AppLanguage) {
        keyValue.putString(CLAVE_IDIOMA, language.code)
    }

    suspend fun lastUsername(): String? = keyValue.getString(CLAVE_USUARIO)?.takeIf { it.isNotBlank() }

    suspend fun setLastUsername(username: String?) {
        keyValue.putString(CLAVE_USUARIO, username?.trim()?.takeIf { it.isNotBlank() })
    }

    suspend fun biometricEnabled(): Boolean = keyValue.getString(CLAVE_BIOMETRIA) == "true"

    suspend fun setBiometricEnabled(enabled: Boolean) {
        keyValue.putString(CLAVE_BIOMETRIA, enabled.toString())
    }

    /** Borra las preferencias de usuario **sin** tocar la configuración del servidor. */
    suspend fun clear() {
        keyValue.remove(CLAVE_IDIOMA)
        keyValue.remove(CLAVE_USUARIO)
        keyValue.remove(CLAVE_BIOMETRIA)
    }

    private companion object {
        const val CLAVE_IDIOMA = "language"
        const val CLAVE_USUARIO = "last_username"
        const val CLAVE_BIOMETRIA = "biometric_enabled"
    }
}
