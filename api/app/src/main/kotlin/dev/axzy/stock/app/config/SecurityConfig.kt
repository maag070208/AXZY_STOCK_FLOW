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
 * La seguridad HTTP. Aquí solo se responde una pregunta: **¿quién llama?** Se verifica el token y
 * se deja la identidad en el contexto. Qué puede hacer esa identidad lo decide cada caso de uso
 * (docs/arquitectura/api-spring.md §6), no esta cadena de filtros.
 *
 * Por defecto todo exige identidad: una ruta nueva que nadie declare pública nace protegida.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity, tokens: JwtAccessTokens, json: JsonMapper): SecurityFilterChain {
        val unauthenticated = AuthenticationEntryPoint { _, response, _ ->
            response.setHeader("WWW-Authenticate", "Bearer")
            write(response, json, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Falta el token de acceso o no es válido.")
        }
        val forbidden = AccessDeniedHandler { _, response, _ ->
            write(response, json, HttpStatus.FORBIDDEN, "PERMISSION_DENIED", "No tienes permiso para esta acción.")
        }

        http
            // Sin cookies ni sesión de servidor: no hay nada que un sitio ajeno pueda aprovechar, así
            // que la protección CSRF no aplica.
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                PUBLIC_ROUTES.forEach { (method, path) -> it.requestMatchers(method, path).permitAll() }
                /*
                 * La confirmación de la impresora no lleva token de usuario: lleva una
                 * credencial de servicio. En la cadena se deja pasar y **la credencial la
                 * comprueba el caso de uso**, que es quien puede hacerlo antes de mirar
                 * nada más —ni siquiera si el trabajo existe, porque un `404` a quien no
                 * tiene credencial le diría qué identificadores son válidos—.
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
                    // Del token solo se toma quién es y su sello de credenciales. Uno bien firmado pero
                    // sin esos dos datos no es de esta API: se rechaza como cualquier otro token inválido.
                    jwt.jwtAuthenticationConverter { token ->
                        val userId = token.subject?.toLongOrNull()
                        val credentialsStamp = token.claims[JwtAccessTokens.CLAIM_CREDENTIALS] as? Number
                        if (userId == null || credentialsStamp == null) {
                            throw InvalidBearerTokenException("El token no identifica a un usuario.")
                        }
                        TokenIdentity(userId, credentialsStamp.toLong())
                    }
                }
            }
            .exceptionHandling { it.authenticationEntryPoint(unauthenticated).accessDeniedHandler(forbidden) }
        return http.build()
    }

    /**
     * En las rutas públicas el token NO se lee. Cerrar sesión con un token de acceso ya vencido es
     * lo normal, y con la cabecera presente el filtro lo rechazaría con `401` antes de llegar a la
     * ruta: la sesión seguiría viva justo cuando el usuario pide cerrarla.
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
        /** Las únicas rutas sin identidad: la salud y las tres que permiten obtener o soltar una sesión. */
        /** Rutas que se autentican con `X-Service-Key` y no con un token de usuario. */
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
