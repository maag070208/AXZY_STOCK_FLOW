package dev.axzy.stock.adapters.web.http

/**
 * La declaración de qué exige cada ruta, en la propia ruta.
 *
 * **Por qué existe.** El permiso se comprueba dentro del caso de uso, que es donde
 * se decide de verdad (docs/arquitectura/api-spring.md §6). Pero eso deja un hueco:
 * una ruta nueva que llame a un caso de uso **sin** autorización funciona y nadie
 * se entera hasta que alguien la usa. Estas anotaciones son la declaración
 * explícita que `RoutePermissionGuard` comprueba al arrancar: una ruta que no
 * declare nada **impide el arranque** (I5).
 *
 * La anotación no sustituye a la comprobación del caso de uso; la hace
 * verificable. Una anotación que dijera `product.view` sobre un caso de uso que no
 * autoriza seguiría siendo un agujero, y por eso las pruebas de flujo comprueban
 * el `403` de verdad.
 *
 * **Admite varios permisos** para el caso en que el permiso real dependa del cuerpo:
 * el ajuste de inventario exige uno distinto según la dirección y el motivo
 * (`inventory.adjust_increase`, `…_theft`, …). La ruta declara **el conjunto
 * cerrado** de los que pueden aplicar —todos tienen que existir en el catálogo— y
 * el caso de uso decide cuál de ellos. Declarar uno solo sería mentir en la
 * auditoría de rutas.
 */

/** La ruta exige este permiso, además de identidad. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class RequiresPermission(vararg val value: String)

/**
 * La ruta exige identidad pero **ningún permiso concreto**: ver quién soy y
 * cambiar mi propia contraseña no son permisos que se concedan.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class AuthenticatedOnly

/** La ruta es pública: no exige identidad. La lista de `SecurityConfig` manda. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PublicRoute
