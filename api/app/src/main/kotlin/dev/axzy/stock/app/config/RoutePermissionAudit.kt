package dev.axzy.stock.app.config

import dev.axzy.stock.adapters.web.http.AuthenticatedOnly
import dev.axzy.stock.adapters.web.http.PublicRoute
import dev.axzy.stock.adapters.web.http.ServiceKeyOnly
import dev.axzy.stock.adapters.web.http.RequiresPermission
import dev.axzy.stock.domain.access.PermissionCatalog
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * Checks at startup that **every route declares what it requires** (I5).
 *
 * The permission is decided inside the use case, which is where one knows which resource
 * is being touched. The gap that leaves is a new route that calls a use case
 * without authorization: it works, and nobody notices until someone uses it. This
 * check closes the gap from the other side: if the route declares no permission,
 * identity or that it is public, **the API does not start**.
 *
 * Also, a permission with a typo would leave a route that answers
 * `403` to everyone, including the administrator, and would look like a roles problem.
 * roles. It is checked that the permission exists in the catalog.
 */
class RoutePermissionAudit(private val mapping: RequestMappingHandlerMapping) {

    fun verify() {
        val undeclared = mutableListOf<String>()
        val unknown = mutableListOf<String>()

        mapping.handlerMethods.forEach { (_, handler) ->
            val method = handler.method
            // Only our own routes: Spring Boot also registers its default error controller, which is
            // not a business route and does not depend on us.
            if (!method.declaringClass.name.startsWith("dev.axzy.stock")) return@forEach

            val requires = method.getAnnotation(RequiresPermission::class.java)
            val authenticated = method.getAnnotation(AuthenticatedOnly::class.java) != null
            val public = method.getAnnotation(PublicRoute::class.java) != null
            // A service route is neither public nor authenticated: the printer calls it with
            // its credential, and saying "authenticated" would make it seem a user token is valid.
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
            "Routes without a declared requirement (I5). Add @RequiresPermission, @AuthenticatedOnly, " +
                "@PublicRoute or @ServiceKeyOnly: ${undeclared.sorted().joinToString(", ")}"
        }
        check(unknown.isEmpty()) {
            "Routes that declare a permission that does not exist in the catalog: ${unknown.sorted().joinToString(", ")}"
        }
    }
}
