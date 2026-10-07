package dev.axzy.stock.datastore

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ALMACÉN SEGURO — para lo único que de verdad hay que proteger: los tokens
 *
 * **Por qué no basta con «guardarlo cifrado a mano con una clave en el código»:**
 * una clave embebida en el APK no protege de nadie que tenga el APK, que es
 * cualquiera. La protección real la da el sistema operativo:
 *
 *  · **Android → AndroidKeyStore**, con una clave AES/GCM que **no se puede
 *    extraer del dispositivo** (vive en el TEE o en un elemento seguro). El
 *    descifrado solo puede ocurrir en ese teléfono. ROOT o no, la clave no sale.
 *  · **iOS → Keychain**, con `kSecAttrAccessibleAfterFirstUnlock`: disponible
 *    tras el primer desbloqueo (el operador enciende el iPad y trabaja) y
 *    **cifrado por el sistema** con la clave del dispositivo.
 *
 * Un refresh token de 30 días es el activo más valioso de la app: con él se
 * obtiene un access token nuevo sin credenciales. Guardarlo en un archivo de
 * preferencias legible —o en un JSON en `Documents`— convierte cualquier copia
 * de seguridad o cualquier app con acceso al almacenamiento en una sesión
 * abierta.
 *
 * **Qué NO es este almacén:** un lugar para datos grandes. El Keychain y
 * Keystore están pensados para secretos pequeños; meter un catálogo aquí es
 * abusar del sistema y acaba en lentitud o en fallos del proveedor.
 * ═══════════════════════════════════════════════════════════════════════════
 */
interface SecureStore {

    /** Guarda (o reemplaza) un secreto. */
    suspend fun put(key: String, value: String)

    /**
     * Lee un secreto.
     *
     * Devuelve `null` si no existe **o si no se puede descifrar**: una clave
     * rotada, una restauración de copia de seguridad en otro dispositivo o un
     * dato corrupto son casos reales, y ninguno debe tumbar la app. Se trata
     * como «no hay sesión», que es la respuesta segura.
     */
    suspend fun get(key: String): String?

    suspend fun remove(key: String)

    /** Borra todos los secretos de este almacén. */
    suspend fun clear()
}

/** Crea (o abre) el almacén seguro de una plataforma. */
expect fun createSecureStore(name: String): SecureStore

/** Nombre del almacén seguro de la sesión. */
const val DEFAULT_SECURE_STORE: String = "stock.session"
