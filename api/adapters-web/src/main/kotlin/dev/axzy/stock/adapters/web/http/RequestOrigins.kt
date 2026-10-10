package dev.axzy.stock.adapters.web.http

import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.application.shared.RequestOrigin
import dev.axzy.stock.domain.audit.AuditChannel
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.MethodParameter
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import java.util.UUID

const val HEADER_REQUEST_ID = "X-Request-Id"
const val HEADER_CLIENT_CHANNEL = "X-Client-Channel"
const val HEADER_DEVICE_ID = "X-Device-Id"
private const val ATTRIBUTE_REQUEST_ID = "stock.requestId"

/**
 * Da a cada petición un identificador (`X-Request-Id`): el que traiga el cliente si es un UUID, o
 * uno nuevo. Vuelve en la respuesta, entra en cada línea de log y se guarda en la bitácora, así
 * que un error que ve el usuario se puede seguir hasta su registro.
 */
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // Solo se acepta un UUID: el valor termina en el log y en la base, y no se confía en texto libre.
        val requestId = request.getHeader(HEADER_REQUEST_ID)
            ?.takeIf { runCatching { UUID.fromString(it) }.isSuccess }
            ?: UUID.randomUUID().toString()

        request.setAttribute(ATTRIBUTE_REQUEST_ID, requestId)
        response.setHeader(HEADER_REQUEST_ID, requestId)
        MDC.put("requestId", requestId)
        try {
            chain.doFilter(request, response)
        } finally {
            MDC.remove("requestId")
        }
    }
}

/** De dónde viene la petición, tal como lo necesita un caso de uso. */
fun HttpServletRequest.origin(): RequestOrigin {
    val declared = getHeader(HEADER_CLIENT_CHANNEL)?.trim()?.uppercase()
    val channel = when (declared) {
        null, "" -> AuditChannel.WEB
        // Un cliente solo puede declararse como persona. Los canales de servicio no se eligen con
        // una cabecera: los fija la credencial con la que se entra.
        "WEB", "MOBILE", "MOBILE_OFFLINE" -> AuditChannel.valueOf(declared)
        else -> throw FailureException(
            Failure(
                FailureKind.INVALID_REQUEST, "INVALID_PARAMETER",
                "$HEADER_CLIENT_CHANNEL must be WEB, MOBILE or MOBILE_OFFLINE.",
                mapOf("header" to HEADER_CLIENT_CHANNEL),
            ),
        )
    }
    return RequestOrigin(
        channel = channel,
        // Detrás de un proxy de confianza, el contenedor ya resolvió la dirección real del cliente
        // (`server.forward-headers-strategy`). Aquí NO se lee `X-Forwarded-For`: cualquiera puede enviarlo.
        ip = remoteAddr,
        userAgent = getHeader("User-Agent"),
        deviceId = getHeader(HEADER_DEVICE_ID)?.take(120),
        requestId = getAttribute(ATTRIBUTE_REQUEST_ID) as String?,
    )
}

/** Permite que un controlador reciba el [RequestOrigin] como un parámetro más. */
class RequestOriginArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == RequestOrigin::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any = webRequest.getNativeRequest(HttpServletRequest::class.java)!!.origin()
}
