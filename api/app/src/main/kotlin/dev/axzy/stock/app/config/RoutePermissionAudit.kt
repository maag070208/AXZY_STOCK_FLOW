package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.web.http.AuthenticatedOnly
import dev.axzy.stock.adapters.web.http.PublicRoute
import dev.axzy.stock.adapters.web.http.ServiceKeyOnly
import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.domain.access.PermissionCatalog
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Comprueba al arrancar que **toda ruta declara qué exige** (I5).
 *
 * El permiso se decide dentro del caso de uso, que es donde se sabe qué recurso se
 * está tocando. El hueco que eso deja es una ruta nueva que llame a un caso de uso
 * sin autorización: funciona, y nadie se entera hasta que alguien la usa. Esta
 * comprobación cierra el hueco por el otro lado: si la ruta no declara permiso,
 * identidad o que es pública, **la API no arranca**.
 *
 * Además, un permiso escrito con un error de tecleo dejaría una ruta que responde
 * `403` a todo el mundo, incluido el administrador, y parecería un problema de
 * roles. Se comprueba que el permiso exista en el catálogo.
 */
class RoutePermissionAudit(private val mapping: RequestMappingHandlerMapping) {

    fun verify() {
        val undeclared = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        mapping.handlerMethods.forEach { (_, handler) ->
            val method = handler.method
            // Solo las rutas propias: Spring Boot registra además su controlador de
            // error por defecto, que no es una ruta de negocio y no depende de nosotros.
            if (!method.declaringClass.name.startsWith("dev.axzy.stock")) return@forEach

            val requires = method.getAnnotation(RequiresPermission::class.java)
            val authenticated = method.getAnnotation(AuthenticatedOnly::class.java) != null
            val public = method.getAnnotation(PublicRoute::class.java) != null
            // Una ruta de servicio no es pública ni autenticada: la llama la impresora con
            // su credencial, y decir «autenticada» haría creer que vale un token de usuario.
            val service = method.getAnnotation(ServiceKeyOnly::class.java) != null

            if (requires == null && !authenticated && !public && !service) {
                undeclared += "${method.declaringClass.simpleName}.${method.name}"
            }
            requires?.value?.forEach { permission ->
                if (PermissionCatalog.find(permission) == null) {
                    unknown += "${method.declaringClass.simpleName}.${method.name} → '$permission'"
                }
            }
        }

        check(undeclared.isEmpty()) {
            "Rutas sin declarar qué exigen (I5). Añade @RequiresPermission, @AuthenticatedOnly, " +
                "@PublicRoute o @ServiceKeyOnly: ${undeclared.sorted().joinToString(", ")}"
        }
        check(unknown.isEmpty()) {
            "Rutas que declaran un permiso que no existe en el catálogo: ${unknown.sorted().joinToString(", ")}"
        }
    }
}
