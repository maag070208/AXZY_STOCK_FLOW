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
 * Gives each request an identifier (`X-Request-Id`): the one the client sends if it is a UUID, or
 * a new one. It comes back in the response, goes into every log line and is stored in the audit log, so
 * that an error the user sees can be followed to its record.
 */
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // Only a UUID is accepted: the value ends up in the log and the database, and free text is not trusted.
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

/** Where the request comes from, as a use case needs it. */
fun HttpServletRequest.origin(): RequestOrigin {
    val declared = getHeader(HEADER_CLIENT_CHANNEL)?.trim()?.uppercase()
    val channel = when (declared) {
        null, "" -> AuditChannel.WEB
        // A client can only declare itself as a person. Service channels are not chosen with
        // a header: the credential used to enter fixes them.
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
        // Behind a trusted proxy, the container has already resolved the client's real address
        // (`server.forward-headers-strategy`). `X-Forwarded-For` is NOT read here: anyone can send it.
        ip = remoteAddr,
        userAgent = getHeader("User-Agent"),
        deviceId = getHeader(HEADER_DEVICE_ID)?.take(120),
        requestId = getAttribute(ATTRIBUTE_REQUEST_ID) as String?,
    )
}

/** Lets a controller receive the [RequestOrigin] as one more parameter. */
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
