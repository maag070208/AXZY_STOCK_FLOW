package dev.axzy.stock.backend.identity

import com.sun.jna.Library
import com.sun.jna.Native
import dev.axzy.stock.backend.config.logger
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BINDINGS SOBRE LA LIBRERÍA NATIVA DE ARGON2
 *
 * **Por qué se escriben a mano en lugar de usar `argon2-jvm`.**
 *
 * `de.mkammerer:argon2-jvm:2.12` **no funciona en esta máquina**: cualquier
 * llamada a `hash` falla con `IllegalStateException: Output is too short (-2)`.
 * Se investigó hasta el fondo y el resultado es concluyente:
 *
 *  · Llamando a la librería nativa **directamente** con JNA,
 *    `argon2_hash` devuelve `0` (éxito) y produce un hash correcto:
 *    `$argon2id$v=19$m=4096,t=1,p=1$...`
 *  · `argon2_encodedlen` devuelve el tamaño correcto (94, 97 y 98 bytes para
 *    m=8, 4096 y 65536, que coinciden exactamente con la longitud real).
 *  · El fallo persiste con JNA 5.9 y con JNA 5.17, así que no es la versión.
 *
 * Es decir: **el binario está bien y el binding de Java está mal**. La librería
 * calcula el tamaño del búfer, lo asigna con ese tamaño y la llamada nativa
 * responde que el búfer es corto. Como el binario funciona, el error está en la
 * capa de Java.
 *
 * **La decisión:** se usan las librerías nativas que `argon2-jvm` empaqueta
 * —que están bien y cubren macOS, Linux y Windows, en x86-64 y ARM—
 * y se escriben los bindings JNA a mano. Así:
 *
 *  1. **Funciona**, que es lo primero.
 *  2. Se controla exactamente qué se llama, sin una capa intermedia opaca.
 *  3. Se obtiene el **código de error numérico** de Argon2, traducido a texto con
 *     `argon2_error_message`, en lugar de una excepción genérica.
 *  4. Se elimina la dependencia del binding defectuoso, aunque se conserva su
 *     artefacto por las librerías nativas.
 *
 * Alternativa descartada: BouncyCastle. Es Java puro y muy portable, pero
 * **no produce el formato estándar PHC** (`$argon2id$v=19$m=...`) y habría que
 * implementar el parseo y el formateo a mano, que es precisamente donde se
 * cometen los errores sutiles. La librería nativa ya hace ese trabajo bien.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/**
 * La interfaz con la librería nativa `libargon2`.
 *
 * Se declaran **solo** las cuatro funciones que se usan. Declarar más superficie
 * es superficie que puede estar mal.
 */
internal interface Argon2Native : Library {

    /**
     * Calcula el hash y además su representación codificada.
     *
     * @param tCost iteraciones.
     * @param mCost memoria en KiB.
     * @param parallelism hilos.
     * @param pwd contraseña en bytes.
     * @param pwdLen longitud de la contraseña.
     * @param salt sal en bytes.
     * @param saltLen longitud de la sal.
     * @param hash búfer de salida para el hash en crudo. Puede ser `null`.
     * @param hashLen longitud del hash en crudo.
     * @param encoded búfer para la cadena codificada en formato PHC.
     * @param encodedLen longitud del búfer codificado.
     * @param type `0` = Argon2d, `1` = Argon2i, `2` = Argon2id.
     * @param version `0x13` (19) es la versión actual.
     * @return `0` si tuvo éxito, o un código de error negativo.
     */
    fun argon2_hash(
        tCost: Int,
        mCost: Int,
        parallelism: Int,
        pwd: ByteArray,
        pwdLen: Long,
        salt: ByteArray,
        saltLen: Long,
        hash: ByteArray?,
        hashLen: Long,
        encoded: ByteArray,
        encodedLen: Long,
        type: Int,
        version: Int,
    ): Int

    /**
     * Verifica una contraseña contra su hash codificado.
     *
     * **La función lee los parámetros del propio hash**, así que un hash
     * generado con parámetros antiguos sigue verificando correctamente. Es lo
     * que permite endurecer el coste con el tiempo sin invalidar nada.
     */
    fun argon2_verify(
        encoded: ByteArray,
        pwd: ByteArray,
        pwdLen: Long,
        type: Int,
    ): Int

    /** Longitud exacta que necesita el búfer para el hash codificado. */
    fun argon2_encodedlen(
        tCost: Int,
        mCost: Int,
        parallelism: Int,
        saltLen: Int,
        hashLen: Int,
        type: Int,
    ): Long

    /** Traduce un código de error a un mensaje legible. */
    fun argon2_error_message(errorCode: Int): String
}

/**
 * Acceso a la librería nativa, cargada **una sola vez**.
 *
 * La carga es perezosa: si nadie pide un hash, no se carga el binario. Y es
 * perezosa a propósito para que el arranque del backend no dependa de que la
 * librería nativa esté disponible si la identidad no se usa.
 */
internal object Argon2Library {

    /** `2` = Argon2id, la variante recomendada para contraseñas. */
    const val TYPE_ARGON2ID = 2

    /** Versión 19 del algoritmo (`0x13`), la actual de la RFC 9106. */
    const val VERSION = 0x13

    /** Longitud del hash en crudo, en bytes. 32 es el valor recomendado. */
    const val HASH_LENGTH = 32

    /** Longitud de la sal, en bytes. 16 es el valor recomendado por la RFC. */
    const val SALT_LENGTH = 16

    val instance: Argon2Native by lazy {
        try {
            Native.load("argon2", Argon2Native::class.java)
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException(
                "No se pudo cargar la librería nativa de Argon2. " +
                    "En el servidor asegúrate de que la dependencia " +
                    "`de.mkammerer:argon2-jvm` está en el classpath (aporta los " +
                    "binarios para macOS, Linux y Windows en x86-64 y ARM). " +
                    "Causa: ${e.message}",
                e,
            )
        }
    }

    /** Generador de sal. Se usa el CSPRNG de la plataforma, no `Random`. */
    val random = SecureRandom()

    /** Traduce un código de error nativo a texto legible. */
    fun describe(code: Int): String = try {
        instance.argon2_error_message(code)
    } catch (_: Exception) {
        "código $code"
    }
}

/**
 * El hasher de Argon2id sobre la librería nativa.
 *
 * Ver [Argon2Native] para por qué no se usa `argon2-jvm` directamente.
 */
class NativeArgon2PasswordHasher(
    private val config: NativeArgon2Config = NativeArgon2Config.DEFAULT,
) : dev.axzy.stock.domain.identity.PasswordHasher {

    /**
     * Parámetros del algoritmo.
     *
     * Se usa una clase propia en lugar de la de `argon2-jvm` para no depender de
     * la librería cuyo binding está roto: solo se quiere su binario.
     */
    data class NativeArgon2Config(
        /** Iteraciones. */
        val iterations: Int,
        /**
         * Memoria en KiB.
         *
         * **Es el parámetro que de verdad encarece el ataque.** 64 MiB por hash
         * significa que un atacante con 8 GB de RAM solo puede probar 128 hashes
         * en paralelo, aunque tenga una GPU con miles de núcleos.
         */
        val memoryKib: Int,
        /** Hilos. Fijado en 1 para que el coste sea predecible en el servidor. */
        val parallelism: Int,
    ) {
        companion object {
            /**
             * Parámetros de producción: **64 MiB, 3 iteraciones**, que es la
             * segunda opción recomendada por la RFC 9106. El coste medido en
             * esta máquina es de unas decenas de milisegundos: imperceptible
             * para quien inicia sesión una vez, prohibitivo para quien prueba
             * millones.
             */
            val DEFAULT = NativeArgon2Config(
                iterations = 3,
                memoryKib = 64 * 1024,
                parallelism = 1,
            )

            /**
             * Parámetros reducidos, **solo para tests**.
             *
             * Con los de producción, 30 tests de identidad tardarían un minuto y
             * medio. Con estos, milisegundos. El algoritmo y el formato del hash
             * son idénticos: lo único que cambia es el coste.
             *
             * Se mantiene un test aparte que comprueba que `DEFAULT` tiene los
             * valores esperados, para que una reducción accidental no pase
             * inadvertida.
             */
            val FOR_TESTS = NativeArgon2Config(iterations = 1, memoryKib = 8, parallelism = 1)
        }
    }

    /**
     * Calcula el hash.
     *
     * El búfer del hash codificado se dimensiona con `argon2_encodedlen`, que se
     * **verificó que devuelve el valor correcto** (94, 97 y 98 bytes para m=8,
     * 4096 y 65536). No se usa un tamaño fijo «generoso»: si los parámetros
     * crecen, un tamaño fijo se queda corto y el fallo aparece en producción.
     */
    override fun hash(password: String): String {
        val pwd = password.toByteArray(StandardCharsets.UTF_8)
        val salt = ByteArray(Argon2Library.SALT_LENGTH).also { Argon2Library.random.nextBytes(it) }

        val encodedLength = Argon2Library.instance.argon2_encodedlen(
            config.iterations,
            config.memoryKib,
            config.parallelism,
            salt.size,
            Argon2Library.HASH_LENGTH,
            Argon2Library.TYPE_ARGON2ID,
        ).toInt()

        val encoded = ByteArray(encodedLength)

        val code = Argon2Library.instance.argon2_hash(
            config.iterations,
            config.memoryKib,
            config.parallelism,
            pwd,
            pwd.size.toLong(),
            salt,
            salt.size.toLong(),
            null,
            Argon2Library.HASH_LENGTH.toLong(),
            encoded,
            encodedLength.toLong(),
            Argon2Library.TYPE_ARGON2ID,
            Argon2Library.VERSION,
        )

        // Se borran la contraseña y la sal de memoria. La sal no es secreta, pero
        // no cuesta nada y evita dejarla en el montón.
        pwd.fill(0)
        salt.fill(0)

        if (code != 0) {
            throw IllegalStateException(
                "Argon2 falló al calcular el hash: ${Argon2Library.describe(code)} (código $code)",
            )
        }

        // La cadena codificada termina en NUL: se recorta hasta ahí.
        val fin = encoded.indexOf(0.toByte()).let { if (it < 0) encoded.size else it }
        return String(encoded, 0, fin, StandardCharsets.US_ASCII)
    }

    /**
     * Verifica una contraseña.
     *
     * **No lanza nunca por una contraseña incorrecta: devuelve `false`.** Una
     * excepción ahí complica el flujo y puede filtrar información por
     * temporización si el camino de error es más rápido que el de éxito.
     *
     * Un código `-35` (`ARGON2_VERIFY_MISMATCH`) es el caso normal de
     * contraseña incorrecta, no un error: se trata como `false` sin registrarlo,
     * porque registrar cada intento fallido de contraseña llenaría los logs.
     */
    override fun verify(password: String, hash: String): Boolean {
        if (hash.isBlank()) return false

        val pwd = password.toByteArray(StandardCharsets.UTF_8)
        val encoded = hash.toByteArray(StandardCharsets.US_ASCII)

        return try {
            val code = Argon2Library.instance.argon2_verify(
                encoded,
                pwd,
                pwd.size.toLong(),
                Argon2Library.TYPE_ARGON2ID,
            )

            when (code) {
                0 -> true
                // `ARGON2_VERIFY_MISMATCH`: la contraseña es distinta. Es el
                // camino normal, no un incidente.
                ARGON2_VERIFY_MISMATCH -> false
                else -> {
                    // Cualquier otro código sí es un problema: hash corrupto,
                    // parámetros fuera de rango, memoria insuficiente.
                    logger.warn(
                        "Argon2 no pudo verificar la contraseña: {} (código {})",
                        Argon2Library.describe(code),
                        code,
                    )
                    false
                }
            }
        } catch (e: Exception) {
            // Un hash mal formado en la base no debe provocar un error 500 en el
            // endpoint de login. Bloquear a ESE usuario es correcto; tumbar el
            // servicio, no.
            logger.warn("Error al verificar la contraseña: {}", e::class.simpleName)
            false
        } finally {
            pwd.fill(0)
        }
    }

    /**
     * Indica si el hash usa parámetros distintos de los actuales.
     *
     * **No se delega en `argon2-jvm`** —cuyo binding está roto— sino que se leen
     * los parámetros del propio hash, que los lleva en formato PHC. Es un parseo
     * de tres campos y evita depender de la librería defectuosa.
     *
     * Un hash ilegible se considera que necesita rehash: es la respuesta segura,
     * porque fuerza a regenerarlo en el próximo inicio de sesión.
     */
    override fun needsRehash(hash: String): Boolean {
        val parametros = parsearParametros(hash) ?: return true

        // Se compara también el tipo: si el hash no es Argon2id, hay que
        // regenerarlo. Cubre el caso de una migración desde Argon2i o Argon2d.
        return parametros.tipo != "argon2id" ||
            parametros.memoryKib != config.memoryKib ||
            parametros.iterations != config.iterations ||
            parametros.parallelism != config.parallelism
    }

    /** Los parámetros que un hash PHC lleva dentro. */
    internal data class ParametrosPhc(
        val tipo: String,
        val version: Int,
        val memoryKib: Int,
        val iterations: Int,
        val parallelism: Int,
    )

    /**
     * Lee los parámetros de un hash PHC.
     *
     * Formato: `$argon2id$v=19$m=65536,t=3,p=1$<sal>$<hash>`
     *
     * @return los parámetros, o `null` si el hash no tiene el formato esperado.
     */
    internal fun parsearParametros(hash: String): ParametrosPhc? {
        val partes = hash.split('$')
        // La cadena empieza por `$`, así que la primera parte es vacía:
        // ["", "argon2id", "v=19", "m=65536,t=3,p=1", sal, hash]
        if (partes.size < 5 || partes[0].isNotEmpty()) return null

        val tipo = partes[1]
        val version = partes[2].removePrefix("v=").toIntOrNull() ?: return null

        val valores = partes[3].split(',').associate { par ->
            val (clave, valor) = par.split('=', limit = 2).let {
                if (it.size != 2) return null
                it[0] to it[1]
            }
            clave to (valor.toIntOrNull() ?: return null)
        }

        return ParametrosPhc(
            tipo = tipo,
            version = version,
            memoryKib = valores["m"] ?: return null,
            iterations = valores["t"] ?: return null,
            parallelism = valores["p"] ?: return null,
        )
    }

    private companion object {
        /**
         * `ARGON2_VERIFY_MISMATCH`.
         *
         * Se define como constante con nombre en lugar de usar `-35` en línea,
         * porque un número suelto en una comparación no dice nada a quien lo lee.
         */
        const val ARGON2_VERIFY_MISMATCH = -35
    }
}
