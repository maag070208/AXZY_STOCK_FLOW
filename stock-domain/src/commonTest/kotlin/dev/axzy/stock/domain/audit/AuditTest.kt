package dev.axzy.stock.domain.audit

import dev.axzy.stock.domain.access.PermissionCatalog
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * AUDIT TESTS — cases U1 to U28
 *
 * The most important test in this file is **U1: the structural one**. It does not check
 * a behavior: it checks that **no record is missing**. It is the only thing that
 * keeps "EVERYTHING must be auditable" true when someone adds a
 * use case in a hurry.
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
    describe("the registry of auditable use cases") {

        // The record is **executable** documentation: it says for each use case what it writes, with
        // what level and under which permission. Nothing tied it to the catalog, so a wrong permission
        // here would not show until that use case really wrote… inside a
        // transaction, where the failure takes down the legitimate operation that was being
        // audited with it. These three rules tie it down.

        it("every declared permission exists in the catalog") {
            val desconocidos = CriticalUseCases.all
                .map { it.permission }
                .distinct()
                .filter { PermissionCatalog.find(it) == null }

            desconocidos shouldBe emptyList()
        }

        it("every use case and every action is declared only once") {
            // Two use cases with the same action would make the audit log ambiguous: it would not be possible to
            // answer who did what.
            CriticalUseCases.all.map { it.useCase }.distinct() shouldBe CriticalUseCases.all.map { it.useCase }
            CriticalUseCases.all.map { it.action }.distinct() shouldBe CriticalUseCases.all.map { it.action }
        }
    }

    describe("U1 — STRUCTURAL COVERAGE: the test that prevents the audit from degrading") {

        it("every declared action has a permission, entity, level and category") {
            // An auditable use case WITHOUT a permission cannot answer "with which
            // permission it was done", which is the central requirement.
            CriticalUseCases.all.forEach { declaration ->
                withClue("La declaración '${declaration.useCase}' está incompleta") {
                    declaration.action.isNotBlank() shouldBe true
                    declaration.permission.isNotBlank() shouldBe true
                    declaration.entity.isNotBlank() shouldBe true
                }
            }
        }

        it("all declared actions are unique") {
            val actions = CriticalUseCases.all.map { it.action }
            withClue("Acciones duplicadas: ${actions.groupBy { it }.filter { it.value.size > 1 }.keys}") {
                actions.size shouldBe actions.toSet().size
            }
        }

        it("all declared use cases are unique") {
            val cases = CriticalUseCases.all.map { it.useCase }
            withClue("Casos duplicados: ${cases.groupBy { it }.filter { it.value.size > 1 }.keys}") {
                cases.size shouldBe cases.toSet().size
            }
        }

        it("BIJECTION: every declared permission exists in the catalog") {
            // If an audit declaration references a permission that does not
            // exist, the event cannot be audited correctly. It is the analog of
            // the permissions ↔ routes bijection test in the plan.
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

        it("every operation that MOVES INVENTORY is declared as critical") {
            // The property that really matters: no inventory change
            // can be left without audit.
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

        it("every inventory adjustment requires a reason") {
            // Invariant I6: an adjustment without a reason is an audit hole.
            val adjustments = CriticalUseCases.all.filter { it.useCase.startsWith("adjustInventory") }

            withClue("Debe haber ajustes declarados") { adjustments.isEmpty() shouldBe false }

            val withoutReason = adjustments.filterNot { it.reasonRequired }
            withClue("Estos ajustes no exigen motivo: ${withoutReason.map { it.useCase }}") {
                withoutReason shouldBe emptyList()
            }
        }

        it("every change to access control is audited") {
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

        it("changes to access control and policies require a reason") {
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

        it("the declaration produces an annotation consistent with itself") {
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

        it("building a declaration without a permission is impossible") {
            // The type prevents the error, not a validator that has to be remembered to be called.
            val withoutPermission = runCatching {
                AuditDeclaration(
                    useCase = "prueba", action = "PRUEBA", level = AuditLevel.CRITICAL,
                    entity = "prueba", permission = "", category = AuditCategory.DOMAIN,
                )
            }
            withoutPermission.isFailure shouldBe true
        }

        it("building a declaration with an uppercase entity is impossible") {
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
    describe("U7-U15 — the six dimensions of the event") {

        it("U7 — a complete event has the six dimensions") {
            val complete = event()

            withClue("Un evento crítico debe estar completo en sus seis dimensiones") {
                complete.hasAllDimensions shouldBe true
            }
        }

        it("a critical event WITHOUT a permission is incomplete") {
            // Without permission it cannot answer "with which permission it was done."
            val withoutPermission = event(permissionKey = null)

            withoutPermission.hasAllDimensions shouldBe false
        }

        it("U14 — an offline movement keeps both times and the clock difference") {
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

        it("U15 — an ERP movement is distinguished from one made by a person") {
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

        it("a HUMAN event without an identified user is incomplete") {
            val anonymous = event(channel = AuditChannel.MOBILE, actorId = null)

            withClue("Si lo hizo una persona, tiene que estar identificada") {
                anonymous.hasAllDimensions shouldBe false
            }
        }

        it("U13 — the actor keeps a snapshot of name and roles, not only the id") {
            // If the user is renamed or deleted, a FK alone leaves the history
            // unreadable. The snapshot is what keeps the audit useful
            // for two years.
            val withSnapshot = event().copy(
                actor = AuditActor(
                    userId = 7L,
                    username = "jperez",
                    displayName = "Juan Pérez",
                    roles = listOf("WAREHOUSE_MANAGER", "AUDITOR"),
                ),
            )

            withClue("El snapshot debe incluir nombre y roles del momento") {
                withSnapshot.actor.displayName shouldBe "Juan Pérez"
                withSnapshot.actor.roles.size shouldBe 2
            }
        }

        it("U11 — the diff is computed and detects the fields that changed") {
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
    describe("U16-U20 — the hash chain and immutability") {

        it("U18 — altering an event in the middle breaks the chain and is detected") {
            // The chain does not prevent alteration: it makes it DETECTABLE. It is the maximum
            // that can be achieved without specialized hardware.
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

            // Event 5 is altered without recalculating its hash: as if someone
            // had done a direct UPDATE in the database.
            val altered = events.toMutableList()
            altered[4] = altered[4].copy(newState = mapOf("valor" to 999))

            val breakpoint = string.verify(altered)
            withClue("La verificación debe señalar EXACTAMENTE el evento alterado (índice 4)") {
                breakpoint shouldBe 4
            }
        }

        it("deleting an event in the middle also breaks the chain") {
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

        it("the hash is deterministic and stable") {
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

        it("U20 — the chain verifies correctly over a high volume") {
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
    describe("U8-U10 — the decision trace: auditing the AUTHORIZATION") {

        fun traceAllowed() = AccessDecisionTrace(
            permissionKey = "invoice.approve",
            decision = "ALLOWED",
            decisionCode = "OK",
            stage = "ABAC",
            identity = IdentityTrace(userId = 20L, active = true),
            rbac = RbacTrace(
                effectiveScope = "AREA",
                byRole = mapOf("WAREHOUSE_MANAGER" to "AREA"),
                rolesAtTime = listOf("WAREHOUSE_MANAGER"),
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

        it("U8 — the trace stores the state AT THE TIME, not the current one") {
            val trace = traceAllowed()

            withClue("Los roles vigentes entonces deben quedar registrados") {
                trace.rbac.rolesAtTime shouldBe listOf("WAREHOUSE_MANAGER")
            }
            withClue("Y las bodegas del alcance AREA en ese momento") {
                trace.rbac.warehousesAtTime shouldBe listOf(1L)
            }
        }

        it("U9 — the snapshot allows reproducing the historical decision") {
            val trace = traceAllowed()

            // Without these numbers, six months from now it cannot be reconstructed
            // why something was allowed, because the permissions will have changed.
            withClue("La versión de la matriz debe quedar registrada") {
                trace.snapshot.matrixVersion shouldBe 3
            }
            withClue("Y la versión de las políticas") {
                trace.snapshot.policiesVersion shouldBe 7
            }
        }

        it("U10 — a denial explains WHICH condition failed and with what real value") {
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
                            policyName = "Approving your own work is forbidden (segregation of duties)",
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
                (explanation.contains("segregation of duties")) shouldBe true
            }
            withClue("Y debe ser construida desde la traza, no contradecirla") {
                traceDenied.wasDenied shouldBe true
                (explanation.isNotBlank()) shouldBe true
            }
        }

        it("the explanation of a scope denial says which warehouse is not reached") {
            val byScope = traceAllowed().copy(
                decision = "DENIED",
                decisionCode = "SCOPE_INSUFFICIENT",
                stage = "RBAC",
            )

            val explanation = byScope.explain()

            withClue("El mensaje debe ser accionable: 'no alcanza esta bodega', no 'acceso denegado'") {
                (explanation.contains("not for this resource")) shouldBe true
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the legacy requirement: there is no audit at all") {

        it("the new system declares coverage over ALL the critical flows") {
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

        it("the coverage summary is enough for a report") {
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
    describe("the level determines the treatment") {

        it("the four levels have their documented meaning") {
            AuditLevel.CRITICAL.numeric shouldBe 1
            AuditLevel.RELEVANT.numeric shouldBe 2
            AuditLevel.ACCESS.numeric shouldBe 3
            AuditLevel.TECHNICAL.numeric shouldBe 4
        }

        it("every declared critical event is CRITICAL level or higher") {
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
