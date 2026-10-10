package dev.axzy.stock.adapters.web.http

/**
 * The declaration of what each route requires, on the route itself.
 *
 * **Why it exists.** The permission is checked inside the use case, which is where
 * it is really decided (docs/arquitectura/api-spring.md §6). But that leaves a gap:
 * a new route that calls a use case **without** authorization works and nobody
 * notices until someone uses it. These annotations are the explicit declaration
 * that `RoutePermissionGuard` checks at startup: a route that
 * declares nothing **prevents startup** (I5).
 *
 * The annotation does not replace the use case's check; it makes it
 * verifiable. An annotation that said `product.view` on a use case that does not
 * authorize would still be a hole, which is why the flow tests check
 * the real `403`.
 *
 * **It accepts several permissions** for the case where the real permission depends on the body:
 * the inventory adjustment requires a different one depending on the direction and the reason
 * (`inventory.adjust_increase`, `…_theft`, …). The route declares **the closed
 * set** of those that may apply —all of them have to exist in the catalog— and
 * the use case decides which one. Declaring only one would be lying in the
 * route audit.
 */

/** The route requires this permission, in addition to identity. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresPermission(vararg val value: String)

/**
 * The route requires identity but **no specific permission**: seeing who I am and
 * changing my own password are not permissions that are granted.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuthenticatedOnly

/** The route is public: it does not require identity. The `SecurityConfig` list decides. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PublicRoute

/**
 * The route is not called by a person: it requires a **service credential** (`X-Service-Key`).
 *
 * It exists so that the route can be declared in the route audit: without it, it would have to be
 * marked "authenticated", and that would say something false —a request with a user token is not valid
 * here, and one with a service credential is not a person—. The credential is checked
 * by the use case, which is the one that can do it before looking at anything else.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ServiceKeyOnly
