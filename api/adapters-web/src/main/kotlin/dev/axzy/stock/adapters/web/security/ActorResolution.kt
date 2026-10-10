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
 * The identity left by an already verified access token: whose it is and with which credentials
 * stamp it was issued. It carries no permissions or roles; those are read from the database when resolving the [Actor].
 */
class TokenIdentity(val userId: Long, val credentialsStamp: Long) : AbstractAuthenticationToken(emptyList()) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any? = null
    override fun getPrincipal(): Any = userId
}

/**
 * A route that can be used even though the account has a pending password change. These are the
 * ones essential to get out of that state: changing it, seeing who I am and signing out.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AllowedWithPendingPasswordChange

/**
 * Delivers the [Actor] to the controllers that ask for it as a parameter.
 *
 * It is rebuilt from the database ON EVERY REQUEST from the token. This is where a token that is still
 * valid stops working if the account was deactivated or the password changed, and where an account with
 * a pending password change is left unable to do anything else.
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
