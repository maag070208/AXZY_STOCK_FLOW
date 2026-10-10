package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.security.JwtAccessTokens
import dev.axzy.stock.adapters.web.http.ErrorBody
import dev.axzy.stock.adapters.web.security.TokenIdentity
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import tools.jackson.databind.json.JsonMapper

/**
 * HTTP security. It answers only one question: **who is calling?** The token is verified and
 * the identity is left in the context. What that identity can do is decided by each use case
 * (docs/arquitectura/api-spring.md §6), not by this filter chain.
 *
 * By default everything requires identity: a new route that nobody declares public is born protected.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, tokens: JwtAccessTokens, json: JsonMapper): SecurityFilterChain {
        val unauthenticated = AuthenticationEntryPoint { _, response, _ ->
            response.setHeader("WWW-Authenticate", "Bearer")
            write(response, json, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "The access token is missing or not valid.")
        }
        val forbidden = AccessDeniedHandler { _, response, _ ->
            write(response, json, HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "You do not have permission for this action.")
        }

        http
            // No cookies or server session: there is nothing that a foreign site could take advantage of, so
            // CSRF protection does not apply.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                PUBLIC_ROUTES.forEach { (method, path) -> it.requestMatchers(method, path).permitAll() }
                /*
                 * The printer's confirmation does not carry a user token: it carries a
                 * service credential. The chain lets it through and **the use case checks the
                 * credential**, which is the one that can do it before looking at
                 * anything else —not even whether the job exists, because a `404` to whoever has no
                 * credential would tell them which identifiers are valid—.
                 */
                SERVICE_ROUTES.forEach { (method, path) -> it.requestMatchers(method, path).permitAll() }
                it.anyRequest().authenticated()
            }
            .oauth2ResourceServer { server ->
                server.bearerTokenResolver(bearerTokenResolver())
                server.authenticationEntryPoint(unauthenticated)
                server.accessDeniedHandler(forbidden)
                server.jwt { jwt ->
                    jwt.decoder(tokens.decoder)
                    // From the token only who it is and its credentials stamp are taken. A well-signed one but
                    // without those two fields does not belong to this API: it is rejected like any other invalid token.
                    jwt.jwtAuthenticationConverter { token ->
                        val userId = token.subject?.toLongOrNull()
                        val credentialsStamp = token.claims[JwtAccessTokens.CLAIM_CREDENTIALS] as? Number
                        if (userId == null || credentialsStamp == null) {
                            throw InvalidBearerTokenException("The token does not identify a user.")
                        }
                        TokenIdentity(userId, credentialsStamp.toLong())
                    }
                }
            }
            .exceptionHandling { it.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden) }
        return http.build()
    }

    /**
     * On public routes the token is NOT read. Signing out with an already expired access token is
     * the normal case, and with the header present the filter would reject it with `401` before reaching the
     * route: the session would stay alive exactly when the user asks to close it.
     */
    private fun bearerTokenResolver(): BearerTokenResolver {
        val default = DefaultBearerTokenResolver()
        return BearerTokenResolver { request -> if (request.isPublic()) null else default.resolve(request) }
    }

    private fun HttpServletRequest.isPublic(): Boolean =
        PUBLIC_ROUTES.any { (method, path) -> method.matches(this.method) && servletPath == path }

    private fun write(response: HttpServletResponse, json: JsonMapper, status: HttpStatus, code: String, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.writer.write(json.writeValueAsString(ErrorBody(code, message)))
    }

    private companion object {
        /** The only routes without identity: health and the three that let a session be obtained or released. */
        /** Routes that authenticate with `X-Service-Key` and not with a user token. */
        val SERVICE_ROUTES = listOf(
            HttpMethod.PATCH to "/v1/print-jobs/*",
        )

        val PUBLIC_ROUTES = listOf(
            HttpMethod.GET to "/v1/health",
            HttpMethod.POST to "/v1/auth/login",
            HttpMethod.POST to "/v1/auth/refresh",
            HttpMethod.POST to "/v1/auth/logout",
        )
    }
}
