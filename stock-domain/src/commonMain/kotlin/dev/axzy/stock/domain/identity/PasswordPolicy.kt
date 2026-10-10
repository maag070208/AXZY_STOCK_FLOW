package dev.axzy.stock.domain.identity

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PASSWORDS — rules and hash port
 *
 * Two pieces, and the separation matters:
 *
 *  · **[PasswordPolicy]** — what is accepted as a password. It is **pure logic**,
 *    so it lives in the domain and is tested in milliseconds. It is also applied in
 *    the client with the same code, so that the user sees the requirement before
 *    sending the form instead of afterwards.
 *
 *  · **[PasswordHasher]** — the **port** of the algorithm. The domain declares what it
 *    needs and does not know which algorithm is used. The Argon2id adapter lives in the
 *    backend, because it is a platform dependency.
 *
 * **Why this is in the domain and not in the backend.** The legacy compared
 * plain-text passwords and stored the `admin` user with the password
 * `admin12345`. Putting the rules here, with tests, makes the defect impossible to
 * reintroduce without a test failing.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Why a password is rejected. */
enum class PasswordRejection(val message: String) {
    TOO_SHORT("The password must be at least 12 characters"),
    TOO_LONG("The password cannot exceed 128 characters"),
    ONLY_WHITESPACE("The password cannot be only spaces"),
    COMMON("That password appears in the most commonly leaked password lists"),
    CONTAINS_USERNAME("The password cannot contain the username"),
    TOO_SIMPLE("The password must combine letters and numbers, or be a long passphrase"),
}

/** Result of validating a password. */
sealed interface PasswordValidation {
    data object Valid : PasswordValidation
    data class Invalid(val rejections: List<PasswordRejection>) : PasswordValidation

    val isValid: Boolean get() = this is Valid
}

/**
 * The password rules.
 *
 * **NIST SP 800-63B's recommendation is followed, which means going against**
 * **intuition.** The rule people expect —"at least one uppercase, one lowercase, one digit
 * and one symbol"— **makes security worse**: it pushes people to
 * `Password1!`, which is easy to guess and hard to remember.
 *
 * What does work is the **length** and the **blocking of leaked passwords**.
 * So: at least 12 characters, and rejection of the passwords that appear in
 * known leaks.
 *
 * **Periodic rotation is not required** for the same reason: it forces changing it and
 * people change it to something worse.
 */
object PasswordPolicy {

    const val MIN_LENGTH = 12
    const val MAX_LENGTH = 128

    /**
     * The most used passwords in the world.
     *
     * It is a **deliberately short** list of the ones that really show up in
     * production. It does not aim to be exhaustive: that is the work of a leaked-passwords service, and
     * the port [CompromisedPasswordChecker] is
     * ready to connect it without changing this class.
     *
     * Two of them matter especially here: `admin12345` **was the password of the legacy's**
     * **admin user**, and `puertonuevo` because it is the name of the domain
     * this system comes from.
     */
    val COMMON = setOf(
        "password", "password1", "password123", "contrasena", "password",
        "123456789012", "1234567890", "qwertyuiop", "administrador",
        "admin12345", "admin123456", "administrator", "bienvenido", "bienvenido1",
        "puertonuevo", "axzy123456", "stock123456", "almacen12345",
        "abcdefghijkl", "111111111111", "aaaaaaaaaaaa", "000000000000",
    )

    /**
     * Validates a password.
     *
     * @param password the password in clear. **It is validated and discarded**: it is not
     *   logged nor stored anywhere.
     * @param username the username, to reject passwords that contain it.
     *   contengan.
     */
    fun validate(password: String, username: String? = null): PasswordValidation {
        val rejections = mutableListOf<PasswordRejection>()

        // ── Longitud ────────────────────────────────────────────────────────
        if (password.length < MIN_LENGTH) rejections += PasswordRejection.TOO_SHORT
        if (password.length > MAX_LENGTH) rejections += PasswordRejection.TOO_LONG

        if (password.isBlank()) {
            rejections += PasswordRejection.ONLY_WHITESPACE
            // Without this, a password of only spaces but long would pass the length check.
            return PasswordValidation.Invalid(rejections.distinct())
        }

        // ── Against the list of leaked passwords ───────────────────────────────
        // It is compared in lowercase and without spaces at the ends, because
        // `Password123 ` is the same password as `password123` for whoever types it, and `PASSWORD123`
        // for whoever guesses it.
        val normalized = password.trim().lowercase()
        if (normalized in COMMON) rejections += PasswordRejection.COMMON

        // ── Must not contain the username ──────────────────────────────────────
        // `jperez` with the password `jperez2026!` is one of the first things that gets tried.
        val user = username?.trim()?.lowercase()
        if (!user.isNullOrBlank() && user.length >= 4 && normalized.contains(user)) {
            rejections += PasswordRejection.CONTAINS_USERNAME
        }

        // ── Complexity: lax, and only against the trivial ─────────────────────
        // It does NOT require an uppercase letter, symbol or digit separately. It only rejects
        // what is literally a single character class repeated, which is
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
     * It checks that there are at least two distinct character classes. It is a
     * deliberately weak rule: with 12 characters, requiring more classes is annoying without
     * aportar seguridad real.
     */
    private fun isSingleCharacterClass(password: String): Boolean {
        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }
        // If it has neither letters nor digits, it is only symbols: it is rejected too.
        return !(hasLetter && hasDigit) && !(hasLetter && password.any { !it.isLetterOrDigit() })
    }

    /** Readable message with all the reasons for the rejection. */
    fun explain(validation: PasswordValidation): String = when (validation) {
        is PasswordValidation.Valid -> "The password meets the requirements"
        is PasswordValidation.Invalid ->
            validation.rejections.joinToString(". ") { it.message }
    }
}

/**
 * Port to check passwords against a leaked-passwords external service.
 *
 * It exists even though there is no implementation yet, and that is deliberate: the local
 * policy covers the obvious passwords, but a service like Have I Been Pwned
 * covers billions. **The port lets it be connected without touching the
 * policy**, which is what one wants to avoid: that adding that capability forces
 * rewriting the rules.
 */
interface CompromisedPasswordChecker {
    /**
     * @return `true` if the password appears in known leaks.
     *
     * It is implemented with k-anonymity: the **first five characters
     * of the SHA-1 hash**, never the password. This way the service does not learn the
     * password of anyone.
     */
    suspend fun isCompromised(password: String): Boolean
}

/**
 * The port of the hash algorithm.
 *
 * The domain declares what it needs —hashing and verifying— and **does not know whether underneath
 * there is Argon2id, bcrypt or scrypt**. That allows changing the algorithm without
 * touching the business logic, and testing the identity with a test double in
 * milliseconds instead of paying the real cost of Argon2 in every test.
 */
interface PasswordHasher {

    /**
     * Generates the hash of a password.
     *
     * **A self-contained string is returned**: it includes the algorithm, its
     * parameters and the salt. They are never stored separately, because then one has to keep
     * them in sync, and a change of parameters would invalidate
     * the existing passwords.
     */
    fun hash(password: String): String

    /**
     * Verifies a password against its hash.
     *
     * **It never throws for a wrong password**: it returns `false`. An exception there leaks
     * information through timing and complicates the flow.
     */
    fun verify(password: String, hash: String): Boolean

    /**
     * Indicates whether a hash uses obsolete parameters and should be regenerated.
     *
     * It is what allows **hardening the parameters over time without invalidating**: the user
     * anything**: the user signs in with their password, it is verified against the old hash,
     * and it is re-hashed with the new parameters. Without this, raising the cost of
     * Argon2 would force a massive password change.
     */
    fun needsRehash(hash: String): Boolean
}
