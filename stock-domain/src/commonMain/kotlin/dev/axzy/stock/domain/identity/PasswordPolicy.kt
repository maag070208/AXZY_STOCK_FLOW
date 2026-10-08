package dev.axzy.stock.domain.identity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONTRASEÑAS — reglas y puerto de hash
 *
 * Dos piezas, y la separación importa:
 *
 *  · **[PasswordPolicy]** — qué se acepta como contraseña. Es **lógica pura**,
 *    así que vive en el dominio y se prueba en milisegundos. Además se aplica en
 *    el cliente con el mismo código, para que el usuario vea el requisito antes
 *    de enviar el formulario en lugar de después.
 *
 *  · **[PasswordHasher]** — el **puerto** del algoritmo. El dominio declara lo
 *    que necesita y no sabe qué algoritmo se usa. El adaptador con Argon2id vive
 *    en el backend, porque es una dependencia de plataforma.
 *
 * **Por qué esto está en el dominio y no en el backend.** El legacy comparaba
 * contraseñas en texto plano y guardaba el usuario `admin` con la contraseña
 * `admin12345`. Poner las reglas aquí, con tests, hace que el defecto no se
 * pueda reintroducir sin que falle un test.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Por qué se rechaza una contraseña. */
enum class PasswordRejection(val message: String) {
    TOO_SHORT("La contraseña debe tener al menos 12 caracteres"),
    TOO_LONG("La contraseña no puede superar los 128 caracteres"),
    ONLY_WHITESPACE("La contraseña no puede ser solo espacios"),
    COMMON("Esa contraseña aparece en las listas de contraseñas filtradas más usadas"),
    CONTAINS_USERNAME("La contraseña no puede contener el nombre de usuario"),
    TOO_SIMPLE("La contraseña debe combinar letras y números, o ser una frase larga"),
}

/** Resultado de validar una contraseña. */
sealed interface PasswordValidation {
    data object Valid : PasswordValidation
    data class Invalid(val rejections: List<PasswordRejection>) : PasswordValidation

    val isValid: Boolean get() = this is Valid
}

/**
 * Las reglas de contraseña.
 *
 * **Se sigue la recomendación NIST SP 800-63B, y eso significa ir en contra de
 * la intuición.** La regla que la gente espera —«al menos una mayúscula, una
 * minúscula, un número y un símbolo»— **empeora** la seguridad: empuja a la
 * gente a `Password1!`, que es fácil de adivinar y difícil de recordar.
 *
 * Lo que sí funciona es la **longitud** y el **bloqueo de contraseñas filtradas**.
 * Por eso: mínimo 12 caracteres, y rechazo de las contraseñas que aparecen en
 * las filtraciones conocidas.
 *
 * **No se exige rotación periódica** por el mismo motivo: obliga a cambiarla y
 * la gente la cambia por algo peor.
 */
object PasswordPolicy {

    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 128

    /**
     * Las contraseñas más usadas del mundo.
     *
     * Es una lista **deliberadamente corta** y de las que de verdad se ven en
     * producción. No pretende ser exhaustiva: eso es trabajo de un servicio de
     * contraseñas filtradas, y el puerto [CompromisedPasswordChecker] queda
     * preparado para conectarlo sin cambiar esta clase.
     *
     * Aquí están dos que importan especialmente: `admin12345` **era la
     * contraseña del usuario admin del legacy**, y `puertonuevo` por ser el
     * nombre del dominio del que viene este sistema.
     */
    val COMMON = setOf(
        "password", "password1", "password123", "contrasena", "contraseña",
        "123456789012", "1234567890", "qwertyuiop", "administrador",
        "admin12345", "admin123456", "administrator", "bienvenido", "bienvenido1",
        "puertonuevo", "axzy123456", "stock123456", "almacen12345",
        "abcdefghijkl", "111111111111", "aaaaaaaaaaaa", "000000000000",
    )

    /**
     * Valida una contraseña.
     *
     * @param password la contraseña en claro. **Se valida y se descarta**: no se
     *   registra ni se guarda en ningún sitio.
     * @param username el nombre de usuario, para rechazar contraseñas que lo
     *   contengan.
     */
    fun validate(password: String, username: String? = null): PasswordValidation {
        val rejections = mutableListOf<PasswordRejection>()

        // ── Longitud ────────────────────────────────────────────────────────
        if (password.length < MIN_LENGTH) rejections += PasswordRejection.TOO_SHORT
        if (password.length > MAX_LENGTH) rejections += PasswordRejection.TOO_LONG

        if (password.isBlank()) {
            rejections += PasswordRejection.ONLY_WHITESPACE
            // Sin esto, una contraseña de solo espacios larga pasaría la longitud.
            return PasswordValidation.Invalid(rejections.distinct())
        }

        // ── Contra la lista de filtradas ────────────────────────────────────
        // Se compara en minúsculas y sin espacios de los extremos, porque
        // `Password123 ` es la misma contraseña que `password123` para quien la
        // escribe, y `PASSWORD123` para quien la adivina.
        val normalized = password.trim().lowercase()
        if (normalized in COMMON) rejections += PasswordRejection.COMMON

        // ── Que no contenga el usuario ──────────────────────────────────────
        // `jperez` con la contraseña `jperez2026!` es de lo primero que se prueba.
        val user = username?.trim()?.lowercase()
        if (!user.isNullOrBlank() && user.length >= 4 && normalized.contains(user)) {
            rejections += PasswordRejection.CONTAINS_USERNAME
        }

        // ── Complejidad: laxa, y solo contra lo trivial ─────────────────────
        // NO se exige mayúscula, símbolo ni número por separado. Solo se rechaza
        // lo que es literalmente una sola clase de carácter repetida, que es
        // malo incluso siendo largo.
        if (password.length >= MIN_LENGTH && isSingleCharacterClass(password)) {
            rejections += PasswordRejection.TOO_SIMPLE
        }

        return if (rejections.isEmpty()) {
            PasswordValidation.Valid
        } else {
            PasswordValidation.Invalid(rejections.distinct())
        }
    }

    /**
     * Detecta `aaaaaaaaaaaa` o `123456789012`.
     *
     * Se comprueba que haya al menos dos clases de carácter distintas. Es una
     * regla débil a propósito: con 12 caracteres, exigir más clases molesta sin
     * aportar seguridad real.
     */
    private fun isSingleCharacterClass(password: String): Boolean {
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        // Si no tiene ni letras ni dígitos, es solo símbolos: también se rechaza.
        return !(hasLetter && hasDigit) && !(hasLetter && password.any { !it.isLetterOrDigit() })
    }

    /** Mensaje legible con todos los motivos del rechazo. */
    fun explain(validation: PasswordValidation): String = when (validation) {
        is PasswordValidation.Valid -> "La contraseña cumple los requisitos"
        is PasswordValidation.Invalid ->
            validation.rejections.joinToString(". ") { it.message }
    }
}

/**
 * Puerto para comprobar contraseñas filtradas contra un servicio externo.
 *
 * Existe aunque no haya implementación todavía, y es deliberado: la política
 * local cubre las contraseñas obvias, pero un servicio como Have I Been Pwned
 * cubre miles de millones. **El puerto permite conectarlo sin tocar la
 * política**, que es lo que se quiere evitar: que añadir esa capacidad obligue a
 * reescribir las reglas.
 */
interface CompromisedPasswordChecker {
    /**
     * @return `true` si la contraseña aparece en filtraciones conocidas.
     *
     * Se implementa con k-anonimato: se envían los **cinco primeros caracteres
     * del hash SHA-1**, nunca la contraseña. Así el servicio no aprende la
     * contraseña de nadie.
     */
    suspend fun isCompromised(password: String): Boolean
}

/**
 * El puerto del algoritmo de hash.
 *
 * El dominio declara lo que necesita —hashear y verificar— y **no sabe si por
 * debajo hay Argon2id, bcrypt o scrypt**. Eso permite cambiar de algoritmo sin
 * tocar la lógica de negocio, y probar la identidad con un doble de prueba en
 * milisegundos en lugar de pagar el coste real de Argon2 en cada test.
 */
interface PasswordHasher {

    /**
     * Genera el hash de una contraseña.
     *
     * **Se devuelve una cadena autocontenida**: incluye el algoritmo, sus
     * parámetros y la sal. Nunca se guardan por separado, porque entonces hay
     * que mantenerlos sincronizados y un cambio de parámetros invalida las
     * contraseñas existentes.
     */
    fun hash(password: String): String

    /**
     * Verifica una contraseña contra su hash.
     *
     * **Nunca lanza por una contraseña incorrecta**: devuelve `false`. Una
     * excepción ahí filtra información por temporización y complica el flujo.
     */
    fun verify(password: String, hash: String): Boolean

    /**
     * Indica si un hash usa parámetros obsoletos y conviene regenerarlo.
     *
     * Es lo que permite **endurecer los parámetros con el tiempo sin invalidar
     * nada**: el usuario entra con su contraseña, se verifica con el hash viejo,
     * y se re-hashea con los parámetros nuevos. Sin esto, subir el coste de
     * Argon2 obligaría a un cambio de contraseña masivo.
     */
    fun needsRehash(hash: String): Boolean
}
