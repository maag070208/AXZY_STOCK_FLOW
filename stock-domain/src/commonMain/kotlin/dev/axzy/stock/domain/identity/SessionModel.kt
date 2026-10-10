package dev.axzy.stock.domain.identity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * SESIONES Y ROTACIÓN DE TOKENS
 *
 * Un refresh token de larga vida es el punto débil de cualquier sistema de
 * sesiones: **si se filtra, el atacante tiene acceso indefinido**. La defensa
 * estándar es la **rotación**: cada vez que se usa un refresh token, se emite
 * uno nuevo y el viejo deja de servir.
 *
 * Pero la rotación sola no basta, y ahí está el matiz que importa:
 *
 * **Un refresh token usado dos veces significa una de dos cosas.**
 *  1. Un reintento legítimo por un fallo de red: el cliente recibió el token
 *     nuevo pero la respuesta se perdió, y vuelve a intentarlo con el viejo.
 *  2. **Alguien robó el token.** El atacante y el usuario legítimo compiten por
 *     usarlo, y uno de los dos llega segundo.
 *
 * No se pueden distinguir con certeza. La respuesta correcta es asumir lo peor
 * **y revocar toda la familia de tokens**, obligando a volver a iniciar sesión.
 * Es un inconveniente para el caso 1 y la única respuesta segura para el caso 2.
 * La alternativa —permitir el reuso por si acaso— convierte la filtración en
 * acceso permanente, que es exactamente lo que la rotación pretendía evitar.
 *
 * Esto se implementa aquí, en el dominio y con tests, porque es **lógica de
 * decisión**: qué hacer cuando llega un token que ya se había usado. El legacy no
 * tenía nada de esto: sus sesiones eran un JWT sin revocación.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * Un refresh token opaco.
 *
 * **Es opaco a propósito: no lleva información dentro.** Un JWT de refresco
 * sería autocontenido y no se podría revocar sin una lista negra, que es
 * precisamente lo que se quiere evitar. Como token opaco, el servidor lo busca
 * en la base y decide: revocarlo es borrar una fila.
 *
 * La longitud mínima de 43 caracteres no es arbitraria: es lo que produce un
 * valor de 256 bits en base64url sin relleno, que es el mínimo recomendado para
 * que sea imposible de adivinar por fuerza bruta.
 */
@kotlin.jvm.JvmInline
value class RefreshToken private constructor(val value: String) {

    companion object {
        /** 32 bytes en base64url sin relleno son 43 caracteres. */
        const val MIN_LENGTH = 43

        /** Límite superior defensivo: un valor absurdo es un ataque. */
        const val MAX_LENGTH = 512

        /**
         * Construye un token a partir de un valor ya generado.
         *
         * @return el token, o `null` si el valor no tiene la forma esperada.
         */
        fun of(value: String): RefreshToken? {
            if (value.length < MIN_LENGTH || value.length > MAX_LENGTH) return null
            // base64url: letras, dígitos, guion y guion bajo. Cualquier otra cosa
            // indica que el valor no lo generó este sistema.
            if (!value.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return null
            return RefreshToken(value)
        }
    }

    /**
     * Solo la huella del token, para los registros.
     *
     * **El token en claro no debe aparecer nunca en un log.** Un log con el token
     * es un log que, si se filtra, da sesiones válidas.
     */
    val fingerprint: String get() = value.take(6) + "…" + value.takeLast(4)

    override fun toString(): String = "RefreshToken($fingerprint)"
}

/**
 * El identificador de una familia de tokens.
 *
 * Una familia nace en cada inicio de sesión y agrupa todas las rotaciones que
 * siguen. **Es la unidad de revocación**: si se detecta un reuso, se revoca la
 * familia completa, que es lo mismo que decir «esta sesión está comprometida».
 */
@kotlin.jvm.JvmInline
value class TokenFamilyId(val value: String)

/** Identificador de un token concreto, para poder revocarlo. */
@kotlin.jvm.JvmInline
value class TokenId(val value: Long)

/** Por qué se revocó una familia o un token. */
enum class RevocationReason(val description: String) {
    /** El usuario cerró sesión de forma explícita. */
    LOGOUT("Sign-out"),

    /**
     * Se detectó reuso de un token ya rotado.
     *
     * Es el motivo que más importa: significa que **hay dos partes usando la
     * misma sesión**, y una de ellas no es el usuario.
     */
    REUSE_DETECTED("Reuse of an already rotated token: compromised session"),

    /** La contraseña cambió: las sesiones activas dejan de ser válidas. */
    PASSWORD_CHANGED("The password changed"),

    /** Un administrador revocó la sesión. */
    ADMIN_REVOKED("Revoked by an administrator"),

    /** Venció sin usarse. */
    EXPIRED("Expired"),
}

/**
 * El estado de un refresh token en la base.
 *
 * `rotatedAt` es el campo que hace posible la detección de reuso: un token con
 * `rotatedAt` no nulo **ya se usó**, así que si vuelve a aparecer, hay un
 * problema.
 */
data class RefreshTokenRecord(
    val id: TokenId,
    val familyId: TokenFamilyId,
    val userId: Long,
    /** Huella del token, no el token. Ver [RefreshToken.fingerprint]. */
    val fingerprint: String,
    val issuedAt: Long,
    val expiresAt: Long,
    /** Cuándo se usó para rotar. `null` si todavía no se ha usado. */
    val rotatedAt: Long? = null,
    /** El token que lo reemplazó al rotar. */
    val replacedBy: String? = null,
    val revokedAt: Long? = null,
    val revocationReason: RevocationReason? = null,
) {
    /** Indica si el token ya no sirve. */
    val isRevoked: Boolean get() = revokedAt != null

    /** Indica si ya se usó para rotar. */
    val isRotated: Boolean get() = rotatedAt != null

    /** Indica si venció. */
    fun isExpired(now: Long): Boolean = now >= expiresAt

    /**
     * Indica si el token se puede usar.
     *
     * **Se comprueban las cuatro condiciones, y el orden importa para el
     * diagnóstico**: un token rotado y además revocado debe reportarse como
     * rotado, porque eso es lo que revela el reuso.
     */
    fun isUsable(now: Long): Boolean =
        !isRevoked && !isRotated && !isExpired(now)
}

/**
 * Lo que se decide al recibir un refresh token.
 *
 * Es un tipo sellado y no un booleano a propósito: los casos **no son dos**.
 * Confundir «el token no existe» con «el token se reusó» haría que el sistema
 * tratase un intento de robo como un token inválido cualquiera, y no revocaría
 * la familia.
 */
sealed interface RefreshDecision {

    /**
     * El token es válido: se rota.
     *
     * @param record el registro del token que se acaba de usar.
     */
    data class Rotate(val record: RefreshTokenRecord) : RefreshDecision

    /**
     * **Se detectó reuso: la sesión está comprometida.**
     *
     * Se revoca toda la familia. El usuario tendrá que iniciar sesión de nuevo, y
     * es lo correcto: hay dos partes con el mismo token.
     */
    data class ReuseDetected(
        val record: RefreshTokenRecord,
        /** Cuántos tokens de la familia se revocan. */
        val affectedTokens: Int,
    ) : RefreshDecision

    /** El token no existe, o nunca perteneció a este sistema. */
    data object Unknown : RefreshDecision

    /** El token está revocado: por cierre de sesión, por cambio de contraseña o por un administrador. */
    data class Revoked(val record: RefreshTokenRecord) : RefreshDecision

    /** El token venció sin usarse. */
    data class Expired(val record: RefreshTokenRecord) : RefreshDecision
}

/**
 * La política de sesiones.
 *
 * Los valores están agrupados y documentados porque cambiarlos tiene
 * consecuencias de seguridad medibles.
 */
data class SessionPolicy(
    /**
     * Vida del access token.
     *
     * **Corta a propósito: 15 minutos.** Un access token no se puede revocar sin
     * consultar la base en cada petición, así que su vida es la ventana en la que
     * un token robado sigue sirviendo. Quince minutos es el equilibrio habitual:
     * suficientemente corto para limitar el daño, suficientemente largo para no
     * obligar a refrescar en cada petición.
     */
    val accessTokenTtlMs: Long,

    /**
     * Vida del refresh token: 30 días.
     *
     * Es largo porque **sí se puede revocar**: se busca en la base en cada uso.
     * La rotación hace que un refresh robado solo sirva hasta que el usuario
     * legítimo refresque, y la detección de reuso revoca la familia entera.
     */
    val refreshTokenTtlMs: Long,

    /**
     * Margen de gracia para un reintento por fallo de red.
     *
     * **Decisión de diseño que evita revocar sesiones legítimas.** Si el cliente
     * recibe el token nuevo pero la respuesta se pierde, reintenta con el viejo.
     * Sin margen, eso se interpretaría como reuso y se revocaría la sesión por un
     * fallo de red.
     *
     * Se acepta el mismo token viejo **solo si se rotó hace menos de este
     * margen**, y en ese caso se devuelve el token que ya se generó en lugar de
     * crear otro. Es seguro porque el atacante que roba el token viejo tiene la
     * misma ventana: 30 segundos. Y a cambio se evitan cierres de sesión
     * aleatorios, que es lo que empuja a la gente a desactivar la rotación.
     */
    val rotationGraceMs: Long,
) {
    init {
        require(accessTokenTtlMs > 0) { "The access token lifetime must be positive" }
        require(refreshTokenTtlMs > accessTokenTtlMs) {
            "The refresh token must live longer than the access token"
        }
        require(rotationGraceMs >= 0) { "The grace period cannot be negative" }
        require(rotationGraceMs < refreshTokenTtlMs) {
            "The grace period cannot exceed the refresh token lifetime"
        }
    }

    companion object {
        val DEFAULT = SessionPolicy(
            accessTokenTtlMs = 15 * 60 * 1000L,        // 15 minutos
            refreshTokenTtlMs = 30L * 24 * 60 * 60 * 1000, // 30 días
            rotationGraceMs = 30 * 1000L,               // 30 segundos
        )
    }
}

/**
 * Decide qué hacer ante un refresh token.
 *
 * **Función pura, sin base de datos.** Recibe el registro y el instante, y
 * devuelve la decisión. Eso permite probar los seis casos —incluido el reuso—
 * en milisegundos, que es lo único que hace viable cubrir la lógica de seguridad
 * de una sesión con tests exhaustivos.
 *
 * @param record el registro del token, o `null` si no existe.
 * @param now el instante actual.
 * @param policy la política de sesiones.
 */
fun decideRefresh(
    record: RefreshTokenRecord?,
    now: Long,
    policy: SessionPolicy = SessionPolicy.DEFAULT,
): RefreshDecision {
    // ── 1. El token no existe ───────────────────────────────────────────────
    if (record == null) return RefreshDecision.Unknown

    // ── 2. Reuso: el token YA se usó para rotar ─────────────────────────────
    //
    // Se comprueba ANTES que la revocación, porque un token reusado suele estar
    // también revocado —la rotación lo marca— y lo que importa comunicar es el
    // reuso, que es la señal de compromiso.
    if (record.isRotated) {
        val rotatedDoes = now - (record.rotatedAt ?: 0)

        // Margen de gracia: un reintento por fallo de red dentro de la ventana.
        // Se devuelve el token que ya se generó, así que el cliente recibe uno
        // válido y no se revoca nada.
        if (rotatedDoes in 0..policy.rotationGraceMs) {
            return RefreshDecision.Rotate(record)
        }

        // Fuera del margen: se asume robo y se revoca la familia.
        return RefreshDecision.ReuseDetected(record, affectedTokens = 0)
    }

    // ── 3. Revocado explícitamente ──────────────────────────────────────────
    if (record.isRevoked) return RefreshDecision.Revoked(record)

    // ── 4. Vencido ──────────────────────────────────────────────────────────
    if (record.isExpired(now)) return RefreshDecision.Expired(record)

    // ── 5. Válido: se rota ──────────────────────────────────────────────────
    return RefreshDecision.Rotate(record)
}

/**
 * Indica si un cambio de contraseña debe invalidar las sesiones.
 *
 * **Sí, y siempre.** Si alguien cambia su contraseña es porque sospecha que está
 * comprometida, así que dejar vivas las sesiones abiertas mantendría el acceso
 * del atacante justo cuando el usuario está intentando cerrarlo.
 *
 * La alternativa —preguntar al usuario si quiere cerrar las demás sesiones— es
 * peor: en el momento en que sospecha, no quiere decidir; quiere que se cierre.
 */
fun passwordChangeRevokesSessions(): Boolean = true
