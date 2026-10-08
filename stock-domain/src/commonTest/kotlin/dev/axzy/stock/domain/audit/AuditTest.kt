package dev.axzy.stock.domain.audit

import dev.axzy.stock.domain.access.PermissionCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DE AUDITORÍA — casos U1 a U28
 *
 * El test más importante de este archivo es **U1: el estructural**. No comprueba
 * un comportamiento: comprueba que **no falta un registro**. Es lo único que
 * hace que "TODO debe ser auditable" siga siendo cierto cuando alguien añada un
 * caso de uso con prisa.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AuditTest : DescribeSpec({

    fun event(
        action: String = "STOCK_MOVED_WITHIN_WAREHOUSE",
        level: AuditLevel = AuditLevel.CRITICAL,
        permissionKey: String? = "inventory.move",
        channel: AuditChannel = AuditChannel.MOBILE,
        actorId: Long? = 7L,
        occurredAt: Long = 1_700_000_000_000,
        businessDate: String = "2026-10-07",
        entityType: String = "kardex",
        offlineCreatedAt: Long? = null,
        newState: Map<String, Any?>? = mapOf("quantity" to 100),
        reason: String? = null,
    ) = AuditEvent(
        timing = AuditTiming(
            occurredAt = occurredAt,
            businessDate = businessDate,
            offlineCreatedAt = offlineCreatedAt,
            syncedAt = offlineCreatedAt?.let { occurredAt },
        ),
        actor = AuditActor(userId = actorId, username = "operador", displayName = "Operador"),
        source = AuditSource(channel = channel),
        entity = AuditEntity(type = entityType, id = "1", label = "Kardex #1"),
        action = action,
        level = level,
        permissionKey = permissionKey,
        newState = newState,
        reason = reason,
    )

    // ═════════════════════════════════════════════════════════════════════════
    describe("U1 — COBERTURA ESTRUCTURAL: el test que evita que la auditoría se degrade") {

        it("toda acción declarada tiene permiso, entidad, nivel y categoría") {
            // Un caso de uso auditable SIN permiso no puede responder "con qué
            // permiso se hizo", que es el requisito central.
            CriticalUseCases.all.forEach { declaration ->
                withClue("La declaración '${declaration.useCase}' está incompleta") {
                    declaration.action.isNotBlank() shouldBe true
                    declaration.permission.isNotBlank() shouldBe true
                    declaration.entity.isNotBlank() shouldBe true
                }
            }
        }

        it("todas las acciones declaradas son únicas") {
            val actions = CriticalUseCases.all.map { it.action }
            withClue("Acciones duplicadas: ${actions.groupBy { it }.filter { it.value.size > 1 }.keys}") {
                actions.size shouldBe actions.toSet().size
            }
        }

        it("todos los casos de uso declarados son únicos") {
            val cases = CriticalUseCases.all.map { it.useCase }
            withClue("Casos duplicados: ${cases.groupBy { it }.filter { it.value.size > 1 }.keys}") {
                cases.size shouldBe cases.toSet().size
            }
        }

        it("BIYECCIÓN: todo permiso declarado existe en el catálogo") {
            // Si una declaración de auditoría referencia un permiso que no
            // existe, el evento no se puede auditar correctamente. Es el análogo
            // del test de biyección permisos ↔ rutas del plan.
            val nonexistent = CriticalUseCases.all
                .map { it.permission }
                .filterNot { it in PermissionCatalog.keys }

            withClue(
                "Estos permisos se declaran en la auditoría pero NO existen en el catálogo: " +
                    "$nonexistent. O la declaración está mal, o falta el permiso."
            ) {
                nonexistent shouldBe emptyList()
            }
        }

        it("toda operación que MUEVE INVENTARIO está declarada como crítica") {
            // La propiedad que de verdad importa: ningún cambio de inventario
            // puede quedar sin auditoría.
            val inventoryOperations = listOf(
                "moveWithinWarehouse",
                "adjustInventoryIncrease",
                "adjustInventoryShortage",
                "adjustInventoryDamage",
                "adjustInventoryTheft",
                "adjustInventoryCountError",
                "approveAdjustment",
                "locateInvoiceLine",
                "packageOrderLine",
                "dispatchTransfer",
                "receiveTransfer",
                "confirmOrder",
                "reconcileInventory",
            )

            val declared = CriticalUseCases.all.map { it.useCase }
            val missing = inventoryOperations.filterNot { it in declared }

            withClue("Estas operaciones mueven inventario y no están auditadas: $missing") {
                missing shouldBe emptyList()
            }

            inventoryOperations.forEach { case ->
                val declaration = CriticalUseCases.all.first { it.useCase == case }
                withClue("'$case' mueve inventario y debe ser CRITICAL, no ${declaration.level}") {
                    declaration.level shouldBe AuditLevel.CRITICAL
                }
            }
        }

        it("todo ajuste de inventario exige motivo") {
            // Invariante I6: un ajuste sin motivo es un agujero de auditoría.
            val adjustments = CriticalUseCases.all.filter { it.useCase.startsWith("adjustInventory") }

            withClue("Debe haber ajustes declarados") { adjustments.isEmpty() shouldBe false }

            val withoutReason = adjustments.filterNot { it.reasonRequired }
            withClue("Estos ajustes no exigen motivo: ${withoutReason.map { it.useCase }}") {
                withoutReason shouldBe emptyList()
            }
        }

        it("todos los cambios de control de acceso se auditan") {
            val accessControlCases = CriticalUseCases.byCategory(AuditCategory.ACCESS_CONTROL)
            val expected = listOf(
                "createRole", "updateRole", "deleteRole", "updateRolePermissions",
                "createPolicy", "updatePolicy", "deletePolicy",
                "assignRoleToUser", "removeRoleFromUser",
                "setPermissionException", "removePermissionException",
            )
            val declared = accessControlCases.map { it.useCase }
            expected.forEach { case ->
                withClue("El cambio de acceso '$case' debe estar auditado") {
                    declared shouldContain case
                }
            }
        }

        it("los cambios de control de acceso y las políticas exigen motivo") {
            val requiresReason = listOf(
                "deleteRole", "createPolicy", "updatePolicy", "deletePolicy",
                "setPermissionException", "removePermissionException",
            )
            requiresReason.forEach { case ->
                val declaration = CriticalUseCases.all.first { it.useCase == case }
                withClue("'$case' modifica el control de acceso y debe exigir motivo") {
                    declaration.reasonRequired shouldBe true
                }
            }
        }

        it("la declaración produce una anotación coherente consigo misma") {
            CriticalUseCases.all.forEach { declaration ->
                val annotationInfo = declaration.toAnnotation()
                withClue("La anotación de '${declaration.useCase}' no coincide con su declaración") {
                    annotationInfo.action shouldBe declaration.action
                    annotationInfo.level shouldBe declaration.level
                    annotationInfo.entity shouldBe declaration.entity
                    annotationInfo.permission shouldBe declaration.permission
                }
            }
        }

        it("construir una declaración sin permiso es imposible") {
            // El tipo impide el error, no un validador que hay que recordar llamar.
            val withoutPermission = runCatching {
                AuditDeclaration(
                    useCase = "prueba", action = "PRUEBA", level = AuditLevel.CRITICAL,
                    entity = "prueba", permission = "", category = AuditCategory.DOMAIN,
                )
            }
            withoutPermission.isFailure shouldBe true
        }

        it("construir una declaración con entidad en mayúsculas es imposible") {
            val uppercase = runCatching {
                AuditDeclaration(
                    useCase = "prueba", action = "PRUEBA", level = AuditLevel.CRITICAL,
                    entity = "InvoiceLine", permission = "invoice.view", category = AuditCategory.DOMAIN,
                )
            }
            uppercase.isFailure shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("U7-U15 — las seis dimensiones del evento") {

        it("U7 — un evento completo tiene las seis dimensiones") {
            val complete = event()

            withClue("Un evento crítico debe estar completo en sus seis dimensiones") {
                complete.hasAllDimensions shouldBe true
            }
        }

        it("un evento crítico SIN permiso está incompleto") {
            // Sin permiso no se puede responder "con qué permiso se hizo".
            val withoutPermission = event(permissionKey = null)

            withoutPermission.hasAllDimensions shouldBe false
        }

        it("U14 — un movimiento offline guarda las dos horas y la diferencia de reloj") {
            val offline = AuditEvent(
                timing = AuditTiming(
                    occurredAt = 1_700_000_000_000,
                    businessDate = "2026-10-07",
                    offlineCreatedAt = 1_699_990_000_000,   // capturado 10.000 s antes
                    syncedAt = 1_700_000_000_000,
                    clockSkewMs = 1_500,
                ),
                actor = AuditActor(userId = 7L, username = "operador", displayName = "Operador"),
                source = AuditSource(channel = AuditChannel.MOBILE_OFFLINE, deviceId = "tablet-04"),
                entity = AuditEntity(type = "kardex", id = "1"),
                action = "INVOICE_LINE_CAPTURED",
                level = AuditLevel.CRITICAL,
                permissionKey = "invoice.capture",
            )

            withClue("Debe distinguirse que el evento llegó por sincronización diferida") {
                offline.timing.isDeferred shouldBe true
            }
            withClue("Ambas horas deben estar presentes y ser distintas") {
                offline.timing.offlineCreatedAt shouldBe 1_699_990_000_000
                offline.timing.syncedAt shouldBe 1_700_000_000_000
                (offline.timing.offlineCreatedAt != offline.timing.occurredAt) shouldBe true
            }
            withClue("El desajuste de reloj queda registrado") {
                offline.timing.clockSkewMs shouldBe 1_500
            }
        }

        it("U15 — un movimiento del ERP se distingue de uno hecho por una persona") {
            val fromErp = event(
                channel = AuditChannel.SERVICE_ERP,
                actorId = null,
                action = "INVOICE_IMPORTED_FROM_ERP",
                permissionKey = "invoice.import_external",
                entityType = "invoice",
            )

            withClue("Sin canal de origen no se puede saber si lo hizo el ERP o una persona") {
                fromErp.source.channel.isService shouldBe true
                fromErp.source.channel.isHuman shouldBe false
            }
            withClue("Un evento de servicio sin usuario sigue siendo válido: el origen es la integración") {
                fromErp.hasAllDimensions shouldBe true
            }
        }

        it("un evento HUMANO sin usuario identificado está incompleto") {
            val anonymous = event(channel = AuditChannel.MOBILE, actorId = null)

            withClue("Si lo hizo una persona, tiene que estar identificada") {
                anonymous.hasAllDimensions shouldBe false
            }
        }

        it("U13 — el actor conserva un snapshot de nombre y roles, no solo el id") {
            // Si el usuario se renombra o se borra, un FK solo deja el histórico
            // ilegible. El snapshot es lo que hace que la auditoría siga siendo
            // útil dentro de dos años.
            val withSnapshot = event().copy(
                actor = AuditActor(
                    userId = 7L,
                    username = "jperez",
                    displayName = "Juan Pérez",
                    roles = listOf("JEFE_BODEGA", "AUDITOR"),
                ),
            )

            withClue("El snapshot debe incluir nombre y roles del momento") {
                withSnapshot.actor.displayName shouldBe "Juan Pérez"
                withSnapshot.actor.roles.size shouldBe 2
            }
        }

        it("U11 — el diff se calcula y detecta los campos que cambiaron") {
            val withDiff = event(
                newState = mapOf("status" to "APPROVED", "quantity" to 80),
            ).copy(
                previousState = mapOf("status" to "DISCREPANCY", "quantity" to 100),
            )

            val changed = withDiff.computeChangedFields()

            withClue("Deben detectarse ambos campos modificados") {
                changed shouldBe listOf("quantity", "status")
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("U16-U20 — la cadena de hashes y la inmutabilidad") {

        it("U18 — alterar un evento del medio rompe la cadena y se detecta") {
            // La cadena no impide la alteración: la HACE DETECTABLE. Es el máximo
            // que se puede lograr sin hardware especializado.
            val string = DefaultAuditHashChain
            var previous: String? = null

            val events = (1..10).map { index ->
                val base = event(action = "ACCION_$index", newState = mapOf("valor" to index))
                    .copy(sequence = index.toLong())
                val hash = string.hash(previous, base)
                previous = hash
                base.copy(recordHash = hash, previousHash = previous)
            }

            withClue("La cadena intacta debe verificar sin errores") {
                string.verify(events) shouldBe null
            }

            // Se altera el evento 5 sin recalcular su hash: como si alguien
            // hubiera hecho un UPDATE directo en la base de datos.
            val altered = events.toMutableList()
            altered[4] = altered[4].copy(newState = mapOf("valor" to 999))

            val breakpoint = string.verify(altered)
            withClue("La verificación debe señalar EXACTAMENTE el evento alterado (índice 4)") {
                breakpoint shouldBe 4
            }
        }

        it("borrar un evento del medio también rompe la cadena") {
            val string = DefaultAuditHashChain
            var previous: String? = null
            val events = (1..5).map { index ->
                val base = event(action = "ACCION_$index").copy(sequence = index.toLong())
                val hash = string.hash(previous, base)
                previous = hash
                base.copy(recordHash = hash)
            }

            val withGap = events.filterIndexed { index, _ -> index != 2 }

            withClue("Quitar un evento intermedio debe romper la verificación") {
                (string.verify(withGap) != null) shouldBe true
            }
        }

        it("el hash es determinista y estable") {
            val string = DefaultAuditHashChain
            val firstEvent = event()

            val hashA = string.hash(null, firstEvent)
            val hashB = string.hash(null, firstEvent)

            withClue("El mismo evento debe producir el mismo hash siempre") {
                hashA shouldBe hashB
            }
        }

        it("dos eventos distintos producen hashes distintos") {
            val string = DefaultAuditHashChain

            val hashA = string.hash(null, event(newState = mapOf("quantity" to 100)))
            val hashB = string.hash(null, event(newState = mapOf("quantity" to 101)))

            withClue("Cambiar un valor debe cambiar el hash") {
                (hashA != hashB) shouldBe true
            }
        }

        it("U20 — la cadena verifica correctamente sobre un volumen alto") {
            val string = DefaultAuditHashChain
            var previous: String? = null

            val events = (1..5_000).map { index ->
                val base = event(action = "ACCION_${index % 20}").copy(sequence = index.toLong())
                val hash = string.hash(previous, base)
                previous = hash
                base.copy(recordHash = hash)
            }

            withClue("5.000 eventos deben verificar en tiempo razonable") {
                string.verify(events) shouldBe null
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("U8-U10 — la traza de la decisión: auditar la AUTORIZACIÓN") {

        fun traceAllowed() = AccessDecisionTrace(
            permissionKey = "invoice.approve",
            decision = "ALLOWED",
            decisionCode = "OK",
            stage = "ABAC",
            identity = IdentityTrace(userId = 20L, active = true),
            rbac = RbacTrace(
                effectiveScope = "AREA",
                byRole = mapOf("JEFE_BODEGA" to "AREA"),
                rolesAtTime = listOf("JEFE_BODEGA"),
                warehousesAtTime = listOf(1L),
            ),
            abac = AbacTrace(
                evaluated = true,
                allowed = true,
                decidedByPolicyId = 2L,
                decidedByPolicyName = "El jefe de bodega aprueba discrepancias de su bodega",
                rules = listOf(
                    AuditedPolicy(
                        policyId = 2L,
                        policyName = "El jefe de bodega aprueba discrepancias de su bodega",
                        effect = "ALLOW",
                        priority = 10,
                        appliesToUser = true,
                        matched = true,
                        shadowMode = false,
                        conditions = emptyList(),
                    ),
                ),
            ),
            context = AuditedContext(hour = 10, weekday = 3, deviceType = "WEB", offlineSync = false),
            snapshot = ConfigSnapshot(matrixVersion = 3, policiesVersion = 7),
        )

        it("U8 — la traza guarda el estado DEL MOMENTO, no el actual") {
            val trace = traceAllowed()

            withClue("Los roles vigentes entonces deben quedar registrados") {
                trace.rbac.rolesAtTime shouldBe listOf("JEFE_BODEGA")
            }
            withClue("Y las bodegas del alcance AREA en ese momento") {
                trace.rbac.warehousesAtTime shouldBe listOf(1L)
            }
        }

        it("U9 — la instantánea permite reproducir la decisión histórica") {
            val trace = traceAllowed()

            // Sin estos números, dentro de seis meses no se puede reconstruir por
            // qué se permitió algo, porque los permisos habrán cambiado.
            withClue("La versión de la matriz debe quedar registrada") {
                trace.snapshot.matrixVersion shouldBe 3
            }
            withClue("Y la versión de las políticas") {
                trace.snapshot.policiesVersion shouldBe 7
            }
        }

        it("U10 — una denegación explica QUÉ condición falló y con qué valor real") {
            val traceDenied = traceAllowed().copy(
                decision = "DENIED",
                decisionCode = "POLICY_DENIED",
                abac = AbacTrace(
                    evaluated = true,
                    allowed = false,
                    deniedByPolicyId = 1L,
                    rules = listOf(
                        AuditedPolicy(
                            policyId = 1L,
                            policyName = "Prohibido aprobar lo propio (segregación de funciones)",
                            effect = "DENY",
                            priority = 100,
                            appliesToUser = true,
                            matched = true,
                            shadowMode = false,
                            conditions = listOf(
                                AuditedCondition(
                                    field = "resource.capturedBy",
                                    operator = "==",
                                    expected = 20L,
                                    current = 20L,
                                    matches = true,
                                ),
                            ),
                        ),
                    ),
                ),
            )

            val explanation = traceDenied.explain()

            withClue("La explicación debe nombrar la política que denegó") {
                (explanation.contains("segregación de funciones")) shouldBe true
            }
            withClue("Y debe ser construida desde la traza, no contradecirla") {
                traceDenied.wasDenied shouldBe true
                (explanation.isNotBlank()) shouldBe true
            }
        }

        it("la explicación de una denegación por alcance dice a qué bodega no alcanza") {
            val byScope = traceAllowed().copy(
                decision = "DENIED",
                decisionCode = "SCOPE_INSUFFICIENT",
                stage = "RBAC",
            )

            val explanation = byScope.explain()

            withClue("El mensaje debe ser accionable: 'no alcanza esta bodega', no 'acceso denegado'") {
                (explanation.contains("no alcanza este recurso")) shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el requisito del legacy: no existe ninguna auditoría") {

        it("el sistema nuevo declara cobertura sobre TODOS los flujos críticos") {
            val flows = listOf(
                AuditCategory.RECEIVING,
                AuditCategory.FULFILLMENT,
                AuditCategory.INVENTORY,
                AuditCategory.TRANSFER,
                AuditCategory.ACCESS_CONTROL,
            )

            flows.forEach { category ->
                val declarations = CriticalUseCases.byCategory(category)
                withClue(
                    "El flujo '${category.displayName}' debe tener casos de uso auditados. " +
                        "El legacy no tenía NINGUNA tabla de auditoría: su único rastro era el " +
                        "kardex, y era mutable."
                ) {
                    (declarations.size >= 3) shouldBe true
                }
            }
        }

        it("el resumen de cobertura es suficiente para un informe") {
            val critical = CriticalUseCases.byLevel(AuditLevel.CRITICAL)
            val relevant = CriticalUseCases.byLevel(AuditLevel.RELEVANT)

            withClue("Debe haber una base amplia de casos críticos auditados") {
                (critical.size >= 25) shouldBe true
            }
            withClue("Y casos relevantes que no mueven inventario") {
                (relevant.size >= 5) shouldBe true
            }

            val withReason = CriticalUseCases.reasonRequired
            withClue("Los cambios sensibles deben exigir motivo") {
                (withReason.size >= 10) shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("el nivel determina el tratamiento") {

        it("los cuatro niveles tienen la semántica documentada") {
            AuditLevel.CRITICAL.numeric shouldBe 1
            AuditLevel.RELEVANT.numeric shouldBe 2
            AuditLevel.ACCESS.numeric shouldBe 3
            AuditLevel.TECHNICAL.numeric shouldBe 4
        }

        it("todo evento crítico declarado es de nivel CRITICAL o superior") {
            CriticalUseCases.all
                .filter { it.useCase.startsWith("adjust") || it.useCase.startsWith("locate") }
                .forEach { declaration ->
                    withClue("'${declaration.useCase}' mueve inventario: debe ser CRITICAL") {
                        declaration.level shouldBe AuditLevel.CRITICAL
                    }
                }
        }
    }
})
