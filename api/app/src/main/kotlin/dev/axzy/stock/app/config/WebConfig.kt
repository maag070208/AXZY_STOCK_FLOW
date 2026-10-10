package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.web.health.HealthController
import dev.axzy.stock.adapters.web.http.GlobalErrorHandler
import dev.axzy.stock.adapters.web.http.RequestIdFilter
import dev.axzy.stock.adapters.web.http.RequestOriginArgumentResolver
import dev.axzy.stock.adapters.web.identity.AuthController
import dev.axzy.stock.adapters.web.security.ActorArgumentResolver
import dev.axzy.stock.application.health.CheckHealth
import dev.axzy.stock.application.health.DatabaseProbe
import dev.axzy.stock.application.identity.ChangePassword
import dev.axzy.stock.application.identity.Login
import dev.axzy.stock.application.identity.Logout
import dev.axzy.stock.application.identity.RefreshSession
import dev.axzy.stock.application.identity.ResolveActor
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/** Los puertos de entrada por HTTP: los controladores y lo que necesitan para recibir sus argumentos. */
@Configuration(proxyBeanMethods = false)
class WebConfig(private val resolveActor: ResolveActor) : WebMvcConfigurer {

    /** Un controlador declara `Actor` y `RequestOrigin` como parámetros; estos resolutores se los dan. */
    override fun addArgumentResolvers(resolvers: MutableList<HandlerMethodArgumentResolver>) {
        resolvers += RequestOriginArgumentResolver()
        resolvers += ActorArgumentResolver(resolveActor)
    }

    /** Antes que la seguridad: así una respuesta `401` también lleva su `X-Request-Id`. */
    @Bean
    fun requestIdFilter(): FilterRegistrationBean<RequestIdFilter> =
        FilterRegistrationBean(RequestIdFilter()).apply { order = Ordered.HIGHEST_PRECEDENCE }

    @Bean
    fun globalErrorHandler() = GlobalErrorHandler()

    /**
     * I5: una ruta que no declare qué exige **impide el arranque**. Se ejecuta
     * cuando el contexto ya conoce todas las rutas, no antes.
     */
    @Bean
    fun routePermissionGuard(mapping: RequestMappingHandlerMapping) = ApplicationRunner {
        RoutePermissionAudit(mapping).verify()
    }

    @Bean
    fun checkHealth(probe: DatabaseProbe, properties: StockProperties) = CheckHealth(probe, properties.version)

    @Bean
    fun healthController(checkHealth: CheckHealth) = HealthController(checkHealth)

    @Bean
    fun authController(login: Login, refreshSession: RefreshSession, logout: Logout, changePassword: ChangePassword) =
        AuthController(login, refreshSession, logout, changePassword)
}
