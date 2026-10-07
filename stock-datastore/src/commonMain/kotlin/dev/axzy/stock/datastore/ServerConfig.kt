package dev.axzy.stock.datastore

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * LA CONFIGURACIÓN DEL SERVIDOR
 *
 * El legacy guardaba **una IP pelada** (`192.168.1.50`). El cliente tenía que
 * adivinar el resto: si era `http` o `https`, el puerto, y si el prefijo de la
 * API era `/api` o `/api/v1`. Cada adivinanza era un sitio donde el cliente y el
 * servidor podían no coincidir, y el síntoma era «no conecta» sin más.
 *
 * Aquí se guarda la configuración **completa y validada**: esquema, host, puerto
 * y ruta base. Un valor inválido no se puede construir, así que no puede llegar
 * a guardarse y no puede aparecer media hora después como un fallo de red.
 * ═══════════════════════════════════════════════════════════════════════════
 */
data class ServerConfig(
    val scheme: Scheme,
    val host: String,
    val port: Int,
    /** Prefijo de la API, con barra inicial. El contrato exige `/v1`. */
    val basePath: String = DEFAULT_BASE_PATH,
) {
    /** El esquema solo puede ser uno de los dos que habla HTTP, con su puerto por defecto. */
    enum class Scheme(val value: String, val defaultPort: Int) {
        HTTP("http", 80),
        HTTPS("https", 443),
    }

    init {
        require(host.isNotBlank()) { "El host no puede estar vacío" }
        require(!host.contains("://")) {
            "El host no lleva esquema: '$host'. El esquema es un campo aparte, " +
                "y mezclarlos es cómo el legacy acababa con 'http://http://…'."
        }
        require(!host.contains('/')) { "El host no lleva ruta: '$host'" }
        require(port in 1..MAX_PORT) { "El puerto debe estar entre 1 y $MAX_PORT, se recibió $port" }
        require(basePath.startsWith("/")) { "La ruta base debe empezar por '/': '$basePath'" }
        require(!basePath.endsWith("/")) { "La ruta base no debe acabar en '/': '$basePath'" }
    }

    /**
     * La URL base completa, sin barra final.
     *
     * El puerto se omite cuando es el del esquema (80/443): una URL
     * `https://almacen.interno:443/api/v1` funciona, pero es ruido en la
     * pantalla de configuración y en los registros.
     */
    fun toBaseUrl(): String = buildString {
        append(scheme.value).append("://").append(host)
        if (port != scheme.defaultPort) append(':').append(port)
        append(basePath)
    }

    /** La URL tal como se enseña en la pantalla de configuración, con el puerto siempre. */
    fun toDisplayString(): String = "${scheme.value}://$host:$port$basePath"

    companion object {
        const val DEFAULT_BASE_PATH: String = "/api/v1"
        const val MAX_PORT: Int = 65_535

        private const val HTTP_PORT = 80
        private const val HTTPS_PORT = 443

        /**
         * Interpreta lo que el usuario teclea.
         *
         * Acepta las dos formas que se usan de verdad en una bodega:
         *  · La URL completa: `https://almacen.interno:8443/api/v1`
         *  · El atajo del servidor interno: `10.0.2.2:8080` (se asume `http`,
         *    porque es lo que hay en la red local) o `almacen.interno`.
         *
         * Devuelve `Result` y no lanza: es una entrada de usuario, y un
         * formulario necesita el mensaje, no una excepción.
         */
        fun parse(raw: String): Result<ServerConfig> = runCatching {
            val texto = raw.trim()
            require(texto.isNotEmpty()) { "La dirección del servidor no puede estar vacía" }

            val (scheme, resto) = when {
                texto.startsWith("https://", ignoreCase = true) ->
                    Scheme.HTTPS to texto.substring("https://".length)

                texto.startsWith("http://", ignoreCase = true) ->
                    Scheme.HTTP to texto.substring("http://".length)

                // Sin esquema: es una IP o un nombre de la red interna. Se asume
                // HTTP, que es lo que hay ahí, y el usuario puede cambiarlo.
                else -> Scheme.HTTP to texto
            }

            val sinBarraFinal = resto.trimEnd('/')
            val barra = sinBarraFinal.indexOf('/')
            val autoridad = if (barra >= 0) sinBarraFinal.substring(0, barra) else sinBarraFinal
            val ruta = if (barra >= 0) sinBarraFinal.substring(barra) else DEFAULT_BASE_PATH

            require(autoridad.isNotEmpty()) { "Falta el host en '$raw'" }

            // El puerto va después del ÚLTIMO ':' para no romper con las
            // direcciones IPv6 entre corchetes, que no se admiten aquí a
            // propósito: en una bodega no hay IPv6 en el servidor interno.
            val dosPuntos = autoridad.lastIndexOf(':')
            val host: String
            val puerto: Int
            if (dosPuntos >= 0) {
                host = autoridad.substring(0, dosPuntos)
                val puertoTexto = autoridad.substring(dosPuntos + 1)
                require(puertoTexto.isNotEmpty()) { "Falta el puerto en '$raw'" }
                puerto = puertoTexto.toIntOrNull()
                    ?: throw IllegalArgumentException("El puerto '$puertoTexto' no es un número")
            } else {
                host = autoridad
                puerto = if (scheme == Scheme.HTTPS) HTTPS_PORT else HTTP_PORT
            }

            ServerConfig(
                scheme = scheme,
                host = host,
                port = puerto,
                basePath = if (ruta.isBlank()) DEFAULT_BASE_PATH else ruta,
            )
        }
    }
}

/**
 * Persiste la configuración del servidor en el almacén de preferencias.
 *
 * Se guarda como **un solo valor** (`esquema://host:puerto/ruta`) y no como
 * cuatro claves sueltas: así una escritura a medias no puede dejar un host con
 * el puerto de la configuración anterior, que es un fallo que se manifiesta como
 * «no conecta» y cuesta media tarde encontrar.
 */
class ServerConfigStore(
    private val keyValue: KeyValueStore,
    private val clave: String = CLAVE_POR_DEFECTO,
) {

    suspend fun save(config: ServerConfig) {
        keyValue.putString(clave, config.toDisplayString())
    }

    suspend fun load(): ServerConfig? =
        keyValue.getString(clave)?.let { ServerConfig.parse(it).getOrNull() }

    suspend fun clear() {
        keyValue.remove(clave)
    }

    private companion object {
        const val CLAVE_POR_DEFECTO = "server_config"
    }
}
