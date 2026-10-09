package dev.axzy.stock.backend.security

import dev.axzy.stock.backend.application.audit.AccessDenial
import dev.axzy.stock.backend.application.audit.AccessDenialLog
import dev.axzy.stock.backend.application.audit.AuditChannel
import dev.axzy.stock.domain.access.AccessDecision
import dev.axzy.stock.domain.access.AccessEngine
import dev.axzy.stock.domain.access.AccessUser
import dev.axzy.stock.domain.access.RequestContext
import dev.axzy.stock.domain.access.ResourceContext
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * CONTROL DE ACCESO DE LAS RUTAS — la costura con la identidad
 *
 * **Este archivo no implementa autenticación.** Define el punto exacto donde la
 * identidad se conecta, para que el CRUD de catálogo y ubicaciones pueda exigir
 * sus permisos de §11 sin esperar a que el sistema de auth esté terminado y sin
 * inventar un segundo modelo de permisos.
 *
 * La regla que lo sostiene todo es **DEFAULT DENY**: la implementación por
 * defecto ([DenyAllPermissionChecker]) deniega *todo*. Es deliberado. Una
 * implementación por defecto que permitiera todo convertiría un olvido de
 * configuración en un agujero de seguridad, y el legacy tenía exactamente ese
 * defecto: 7 permisos sembrados que ninguna ruta exigía.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** Resultado de comprobar un permiso. */
sealed interface PermissionDecision {

    /** El permiso está concedido para esta petición. */
    data object Permitted : PermissionDecision

    /**
     * Denegado, con el código del contrato (§1.3).
     *
     * El código importa: el cliente decide con él. `UNAUTHENTICATED` lleva a
     * iniciar sesión; `PERMISSION_DENIED`, a pedir el permiso;
     * `SCOPE_INSUFFICIENT`, a pedir acceso a esa bodega; `POLICY_DENIED`, a
     * cambiar cuándo o cómo se hace la operación.
     */
    data class Denied(val code: String, val message: String) : PermissionDecision
}

/**
 * Comprueba si la petición en curso puede ejecutar una acción.
 *
 * @param call la petición, para leer la cabecera `Authorization` y el canal.
 * @param permiso clave `recurso.accion` de §11, por ejemplo `product.view`.
 */
interface PermissionChecker {

    suspend fun check(call: ApplicationCall, permission: String): PermissionDecision
}

/**
 * Implementación por defecto: **deniega todo**.
 *
 * Responde `UNAUTHENTICATED` (401) y no `PERMISSION_DENIED` (403) porque, sin
 * identidad conectada, la verdad es que no hay identidad que autorizar: el
 * cliente debe iniciar sesión. Decir 403 haría que el usuario pidiera un
 * permiso que no le falta.
 */
object DenyAllPermissionChecker : PermissionChecker {

    override suspend fun check(call: ApplicationCall, permission: String): PermissionDecision =
        PermissionDecision.Denied(
            code = "UNAUTHENTICATED",
            message = "La identidad todavía no está conectada en este despliegue, " +
                "así que ninguna petición está autorizada. Permiso exigido: '$permission'.",
        )
}

/**
 * Permite todo. **Solo para tests y desarrollo local.**
 *
 * Está aquí, y no en el código de test, para que sea evidente que existe y que
 * no se debe usar en producción: un verificador que permite todo en un
 * despliegue real deja el catálogo abierto.
 */
object AllowAllPermissionChecker : PermissionChecker {

    override suspend fun check(call: ApplicationCall, permission: String): PermissionDecision =
        PermissionDecision.Permitted
}

/**
 * El puente con el motor de acceso del dominio.
 *
 * **Es la pieza que conecta la identidad**: el agente de identidad construye
 * este objeto pasándole (a) el [AccessEngine] con la matriz rol→permiso y las
 * políticas vigentes, y (b) una función que resuelve el usuario de la petición
 * —leyendo y validando el JWT— y devuelve `null` cuando no hay identidad.
 *
 * No se lee el token aquí a propósito: cómo se llama la cabecera, qué claims
 * lleva y cómo se cachea el usuario es competencia de la capa de identidad, y
 * duplicarlo sería tener dos verdades sobre quién es el usuario.
 */
class AccessEnginePermissionChecker(
    private val engine: AccessEngine,
    private val currentUser: suspend (ApplicationCall) -> AccessUser?,
    private val requestContext: (ApplicationCall) -> RequestContext = { RequestContext() },
    /**
     * El escritor de auditoría, o `null` para no registrar.
     *
     * **Registrar una denegación no es telemetría: es control de acceso.** Un
     * sistema que niega sin dejar constancia no puede responder a la única pregunta
     * que importa después de un incidente —«¿cuántas veces lo intentó y desde
     * dónde?»—, y un ataque de fuerza contra permisos es indistinguible de un
     * usuario que se equivocó de pantalla.
     *
     * Es `null` por defecto para que las pruebas de ruta, que usan verificadores
     * que permiten todo, no arrastren una dependencia de base de datos.
     */
    private val denialLog: AccessDenialLog? = null,
) : PermissionChecker {

    override suspend fun check(call: ApplicationCall, permission: String): PermissionDecision {
        val user = currentUser(call)

        // El recurso va vacío: los permisos del catálogo y de las ubicaciones
        // son de alcance `ALL` (§11), así que no hay bodega concreta que
        // comprobar aquí. Cuando una ruta necesite alcance por bodega, se pasa
        // su `ResourceContext` y el motor resuelve la escalera.
        val context = requestContext(call)
        val decision = engine.decide(
            user = user,
            permissionKey = permission,
            resource = ResourceContext(),
            context = context,
        )

        val outcome = decision.toPermissionDecision()

        // ── LA DENEGACIÓN SE REGISTRA ───────────────────────────────────────
        //
        // `recordDeniedAccessSafely` **ya existía y nadie la llamaba**: el
        // escritor tenía el método, la tabla tenía las columnas y la bitácora
        // quedaba en cero. Un método de auditoría sin llamadas es una fachada, y
        // este era el requisito central del proyecto sin cumplir.
        //
        // Las 6 dimensiones salen de aquí sin trabajo extra: quién (`user`), qué
        // (`access.denied` + el permiso), sobre qué (la ruta), cuándo (el
        // servidor), **desde dónde** (canal y IP, que es lo que el legacy no
        // guardaba) y con qué decisión.
        //
        // **Un fallo al registrar NO cambia la decisión**: `recordDenied...` usa
        // `runCatching` y devuelve `null`. Es deliberado y en la dirección
        // correcta: si la auditoría no puede escribir, el acceso se sigue
        // denegando. Que un fallo de auditoría ABRIERA el acceso sería convertir
        // un problema de registro en un agujero de seguridad.
        if (outcome is PermissionDecision.Denied && denialLog != null) {
            denialLog.recordDenied(
                AccessDenial(
                    actorUserId = user?.id,
                    actorUsername = user?.username,
                    channel = call.auditChannel(),
                    sourceIp = context.ip,
                    permissionKey = permission,
                    entityType = "http_route",
                    entityId = "${call.request.local.method.value} ${call.request.path()}",
                    reason = outcome.message,
                ),
            )
        }

        return outcome
    }
}

/**
 * El canal de la petición, según la cabecera que manda el cliente.
 *
 * **Un valor desconocido cae a `WEB`, y no es un descuido:** un canal inventado
 * **no se registra** como si fuera válido —eso llenaría la bitácora de canales que
 * no existen y haría imposible contar por canal— pero tampoco se pierde el
 * registro. `WEB` es el caso mayoritario y el cliente que no manda cabecera es,
 * casi siempre, la web.
 *
 * En el futuro, cuando la impresora y el ERP escriban su auditoría, sus canales
 * vendrán de la credencial de servicio y no de una cabecera que el cliente elige:
 * **una cabecera no es una identidad**.
 */
internal fun ApplicationCall.auditChannel(): AuditChannel =
    when (request.headers["X-Client-Channel"]?.trim()?.uppercase()) {
        "MOBILE" -> AuditChannel.MOBILE
        "MOBILE_OFFLINE" -> AuditChannel.MOBILE_OFFLINE
        else -> AuditChannel.WEB
    }

/** Traduce la decisión del dominio al contrato de error de la API (§1.3). */
private fun AccessDecision.toPermissionDecision(): PermissionDecision = when (this) {
    is AccessDecision.Allowed -> PermissionDecision.Permitted

    is AccessDecision.NotAuthenticated -> PermissionDecision.Denied(
        code = "UNAUTHENTICATED",
        message = reason,
    )

    // `ScopeDenied` ya distingue los dos casos en su `code`: sin el permiso
    // (`PERMISSION_DENIED`) o con el permiso pero sin alcance sobre el recurso
    // (`SCOPE_INSUFFICIENT`). Colapsarlos es el defecto del legacy.
    is AccessDecision.ScopeDenied -> PermissionDecision.Denied(
        code = code,
        message = "No tienes el permiso '$permission' con el alcance necesario " +
            "(tienes ${effectiveScope.name}, se exige ${requiredScope.name}).",
    )

    is AccessDecision.PolicyDenied -> PermissionDecision.Denied(
        code = "POLICY_DENIED",
        message = "Una política impide '$permission': ${decidedBy.policyName}.",
    )

    // Hay políticas vigentes sobre la acción y ninguna permitió: la acción queda
    // denegada por política, no por falta de permiso.
    is AccessDecision.PolicyRequired -> PermissionDecision.Denied(
        code = "POLICY_DENIED",
        message = "La acción '$permission' requiere una condición de política que no se cumple.",
    )

    is AccessDecision.SegregationOfDuties -> PermissionDecision.Denied(
        code = "SEGREGATION_OF_DUTIES",
        message = "No puedes autorizar tu propio trabajo: debe hacerlo otra persona.",
    )
}
