package dev.axzy.stock.adapters.web.security

import dev.axzy.stock.adapters.web.http.FailureException
import dev.axzy.stock.application.access.Actor
import dev.axzy.stock.application.identity.ResolveActor
import dev.axzy.stock.application.shared.Failure
import dev.axzy.stock.application.shared.FailureKind
import dev.axzy.stock.domain.common.Either
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * La identidad que deja un token de acceso ya verificado: de quién es y con qué sello de
 * credenciales se emitió. No lleva permisos ni roles; eso se lee de la base al resolver al [Actor].
 */
class TokenIdentity(val userId: Long, val credentialsStamp: Long) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = userId
}

/**
 * Una ruta que se puede usar aunque la cuenta tenga pendiente cambiar su contraseña. Son las
 * imprescindibles para salir de ese estado: cambiarla, ver quién soy y cerrar sesión.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AllowedWithPendingPasswordChange

/**
 * Entrega el [Actor] a los controladores que lo piden como parámetro.
 *
 * Lo reconstruye de la base EN CADA PETICIÓN a partir del token. Es aquí donde un token todavía
 * vigente deja de servir si la cuenta se desactivó o la contraseña cambió, y donde una cuenta con
 * el cambio de contraseña pendiente se queda sin poder hacer nada más.
 */
class ActorArgumentResolver(private val resolveActor: ResolveActor) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.parameterType == Actor::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val request = webRequest.getNativeRequest(HttpServletRequest::class.java)!!
        val actor = request.getAttribute(ATTRIBUTE_ACTOR) as Actor? ?: load().also { request.setAttribute(ATTRIBUTE_ACTOR, it) }

        if (actor.mustChangePassword && !parameter.hasMethodAnnotation(AllowedWithPendingPasswordChange::class.java)) {
            throw FailureException(
                Failure(
                    FailureKind.FORBIDDEN, "PASSWORD_CHANGE_REQUIRED",
                    "You must change your password before continuing.",
                ),
            )
        }
        return actor
    }

    private fun load(): Actor {
        val identity = SecurityContextHolder.getContext().authentication as? TokenIdentity
            ?: throw FailureException(Failure(FailureKind.UNAUTHENTICATED, "UNAUTHENTICATED", "The access token is missing or not valid."))
        return when (val outcome = resolveActor.execute(identity.userId, identity.credentialsStamp)) {
            is Either.Right -> outcome.value
            is Either.Left -> throw FailureException(outcome.error)
        }
    }

    private companion object {
        const val ATTRIBUTE_ACTOR = "stock.actor"
    }
}
