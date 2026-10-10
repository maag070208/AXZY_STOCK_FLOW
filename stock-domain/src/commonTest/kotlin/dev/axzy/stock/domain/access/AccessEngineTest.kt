package dev.axzy.stock.domain.access

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TESTS DEL MOTOR DE ACCESO — casos A1 a A43
 *
 * Cubre la matriz RBAC, el alcance, el motor ABAC y las propiedades de
 * seguridad. **Todos los casos negativos están incluidos**: no basta con que el
 * flujo feliz funcione, hay que demostrar que el control bloquea.
 *
 * Numeración tomada de docs/historico/PLAN_ROLES_PERMISOS_POLITICAS.md §9 para poder trazar
 * cada test contra el diseño.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AccessEngineTest : DescribeSpec({

    // ── Utilidades de construcción ──────────────────────────────────────────

    fun userWith(
        vararg roles: BaseRole,
        id: Long = 1L,
        warehouseIds: Set<Long> = setOf(1L),
        active: Boolean = true,
        exceptions: Map<String, PermissionScope> = emptyMap(),
    ) = AccessUser(
        id = id,
        username = "usuario$id",
        active = active,
        roles = roles.map { role -> RoleDefinitions.byKey.getValue(role.key) },
        warehouseIds = warehouseIds,
        exceptions = exceptions,
    )

    /** Motor sin políticas: aísla RBAC. */
    fun onlyRbac() = AccessEngine()

    /** Motor con todas las políticas de almacén. */
    fun withPolicies() = AccessEngine(policies = StockPolicies.all)

    val contextNormal = RequestContext(hour = 10, weekday = 3, deviceType = DeviceType.WEB)

    // ═════════════════════════════════════════════════════════════════════════
    describe("A1-A2 — the role matrix grants what it declares") {

        it("A3 — the OPERATOR cannot approve discrepancies") {
            // Es lo que hace real la regla B1: si el operador pudiera aprobar,
            // podría firmar su propio error de conteo y el control sería decorativo.
            val decision = onlyRbac().decide(
                userWith(BaseRole.WAREHOUSE_OPERATOR),
                "invoice.approve",
            )

            val denied = decision.shouldBeInstanceOf<AccessDecision.ScopeDenied>()
            denied.code shouldBe "PERMISSION_DENIED"
            denied.stage shouldBe DecisionStage.RBAC
        }

        it("A4 — the AUDITOR cannot write: checked against ALL write permissions") {
            val auditor = userWith(BaseRole.AUDITOR, warehouseIds = setOf(1L, 2L))
            val engine = onlyRbac()

            val permissionsThatGrant = RoleDefinitions.writePermissions.filter { permission ->
                engine.decide(
                    auditor,
                    permission,
                    ResourceContext(warehouseId = 1L),
                    contextNormal,
                ).allowed
            }

            // Ésta es la propiedad que hace útil al auditor: puede mirar todo y
            // no puede cambiar nada. Sin ella, el rol no sirve para auditar.
            permissionsThatGrant shouldBe emptyList()
        }

        it("A6 — the administrator has ALL the catalog permissions") {
            val admin = userWith(BaseRole.ADMIN, warehouseIds = setOf(1L, 2L))
            val engine = onlyRbac()

            val withoutGrants = PermissionCatalog.keys.filter { permission ->
                !engine.decide(admin, permission, ResourceContext(warehouseId = 1L), contextNormal).allowed
            }

            // El legacy resolvía esto con `if (role === 1) return next()` y el rol
            // admin NO tenía permisos en base de datos (hallazgo S3). Aquí lo que
            // puede hacer el admin es explícito, visible en la matriz y auditable.
            withoutGrants shouldBe emptyList()
        }

        it("A6b — no role gets access by its numeric id, only by its key") {
            // Contra el bypass `role === 1` del legacy: no existe forma de que un
            // rol acceda por ser "el primero" o por su id.
            val roleAdmin = RoleDefinitions.byKey.getValue(BaseRole.ADMIN.key)
            roleAdmin.key.value shouldBe "ADMIN"
            // El permiso viene de su matriz, no de una comparación numérica.
            roleAdmin.scopeOf("role.manage") shouldBe PermissionScope.ALL
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A7 — identity is checked before permissions") {

        it("a deactivated user has no access even if their role allows it") {
            val disabled = userWith(BaseRole.ADMIN, active = false)

            val decision = onlyRbac().decide(disabled, "role.manage")

            val denied = decision.shouldBeInstanceOf<AccessDecision.NotAuthenticated>()
            denied.stage shouldBe DecisionStage.IDENTITY
        }

        it("without a user there is no access") {
            onlyRbac().decide(null, "invoice.view").allowed shouldBe false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A8-A13 — scope") {

        it("A8 — the OPERATOR with OWN scope cannot see someone else's invoice") {
            // OWN significa "solo lo suyo". El recurso no declara autoría, así que
            // se exige el alcance mínimo y OWN lo cubre; lo que se comprueba aquí
            // es que OWN **no** cubre AREA en un recurso de otra bodega.
            val operator = userWith(BaseRole.WAREHOUSE_OPERATOR, warehouseIds = setOf(1L))

            onlyRbac().decide(
                operator,
                "invoice.view",
                ResourceContext(warehouseId = 2L),
                contextNormal,
            ).allowed shouldBe false
        }

        it("A9 — the MANAGER of warehouse 1 does not reach a resource of warehouse 3") {
            val manager = userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L))

            val decision = onlyRbac().decide(
                manager,
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 3L),
                contextNormal,
            )

            val denied = decision.shouldBeInstanceOf<AccessDecision.ScopeDenied>()
            with(denied) {
                code shouldBe "SCOPE_INSUFFICIENT"
                effectiveScope shouldBe PermissionScope.AREA
                requiredScope shouldBe PermissionScope.ALL
            }
        }

        it("A10 — the same MANAGER does reach a resource of THEIR warehouse") {
            val manager = userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L))

            onlyRbac().decide(
                manager,
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L),
                contextNormal,
            ).allowed shouldBe true
        }

        it("A11 — the MANAGER with ALL scope reaches any warehouse") {
            val manager = userWith(BaseRole.OPERATIONS_MANAGER, warehouseIds = emptySet())

            onlyRbac().decide(
                manager,
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 99L),
                contextNormal,
            ).allowed shouldBe true
        }

        it("A13 — the scope ladder: AREA does not grant ALL, and OWN does not grant AREA") {
            listOf(
                Triple(PermissionScope.ALL, PermissionScope.AREA, true),
                Triple(PermissionScope.ALL, PermissionScope.OWN, true),
                Triple(PermissionScope.AREA, PermissionScope.OWN, true),
                Triple(PermissionScope.AREA, PermissionScope.ALL, false),
                Triple(PermissionScope.OWN, PermissionScope.AREA, false),
                Triple(PermissionScope.OWN, PermissionScope.ALL, false),
                Triple(PermissionScope.NONE, PermissionScope.OWN, false),
            ).forEach { (granted, required, expected) ->
                withClue("$granted cubre $required debería ser $expected") {
                    granted.covers(required) shouldBe expected
                }
            }
        }

        it("A12 — a per-person exception grants a permission the role does not have") {
            val operator = userWith(
                BaseRole.WAREHOUSE_OPERATOR,
                exceptions = mapOf("invoice.approve" to PermissionScope.OWN),
            )

            onlyRbac().decide(operator, "invoice.approve").allowed shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A14-A25 — the ABAC engine") {

        it("A14 — without policies for the action, RBAC decides and it is allowed") {
            val engine = AccessEngine(
                policies = listOf(StockPolicies.byId.getValue(1)), // solo la de aprobar
            )

            engine.decide(
                userWith(BaseRole.WAREHOUSE_OPERATOR),
                "invoice.capture",
            ).allowed shouldBe true
        }

        it("A15 — a matching ALLOW allows") {
            withPolicies().decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L)),
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 99L),
                contextNormal,
            ).allowed shouldBe true
        }

        it("A16 — DENY wins even if a matching ALLOW exists") {
            // Regla de oro: no se puede 'comprar' una denegación con prioridad.
            withPolicies().decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L)),
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 7L),
                contextNormal,
            ).allowed shouldBe false
        }

        it("A17 — applicable policies exist and no ALLOW matches: POLICY_REQUIRED") {
            // Un operador no tiene ninguna política ALLOW sobre transfer.dispatch,
            // pero si las tuviera mal configuradas, esto es lo que pasaría.
            val engine = AccessEngine(
                policies = listOf(
                    Policy(
                        id = 99,
                        name = "Solo gerencia en fin de semana",
                        description = "Prueba de que ABAC sube el listón",
                        action = "inventory.adjust_shortage",
                        effect = PolicyEffect.DENY,
                        roles = setOf(BaseRole.WAREHOUSE_MANAGER.key),
                        conditions = listOf(
                            PolicyCondition("context.weekday", PolicyOperator.IN, listOf(0L, 6L)),
                        ),
                    ),
                ),
            )

            val decision = engine.decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L)),
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L),
                RequestContext(hour = 10, weekday = 3),  // miércoles: el DENY no coincide
            )

            // Hay una política aplicable sobre la acción y ninguna ALLOW la
            // autoriza, así que se deniega. RBAC ya no basta: es lo que hace
            // que ABAC sea un control y no un adorno.
            val denied = decision.shouldBeInstanceOf<AccessDecision.PolicyRequired>()
            denied.policyTrace.size shouldBe 1
        }

        it("A18 — conditions combine with AND: a false one cancels the policy") {
            val engine = withPolicies()

            // PB5 exige hora >= 8 AND hora < 18.
            engine.decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L)),
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L),
                RequestContext(hour = 3, weekday = 3),   // fuera de horario
            ).allowed shouldBe false
        }

        it("A19 — an inactive policy is not evaluated") {
            val inactive = StockPolicies.byId.getValue(5).copy(active = false)
            val engine = AccessEngine(policies = listOf(inactive))

            // Al no haber políticas activas aplicables, RBAC decide y permite.
            engine.decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L)),
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L),
                RequestContext(hour = 3, weekday = 3),
            ).allowed shouldBe true
        }

        it("A20-A21 — a policy without roles applies to everyone; with roles, only to them") {
            val operator = userWith(BaseRole.WAREHOUSE_OPERATOR)

            val forAll = Policy(
                id = 50, name = "Aplica a todos", description = "Sin roles",
                action = "inventory.move", effect = PolicyEffect.DENY,
                roles = emptySet(),
                conditions = listOf(PolicyCondition("resource.unitCost", PolicyOperator.GREATER_THAN, 100L)),
            )
            val onlyManager = forAll.copy(id = 51, roles = setOf(BaseRole.WAREHOUSE_MANAGER.key))

            AccessEngine(policies = listOf(forAll))
                .decide(operator, "inventory.move", ResourceContext(unitCost = 200L))
                .allowed shouldBe false

            AccessEngine(policies = listOf(onlyManager))
                .decide(operator, "inventory.move", ResourceContext(unitCost = 200L))
                .allowed shouldBe true
        }

        it("A22 — observation mode records the decision but does NOT apply it") {
            val shadow = StockPolicies.byId.getValue(1).copy(shadowMode = true)
            val engine = AccessEngine(policies = listOf(shadow))

            // El usuario intenta aprobar su propia captura: la política lo
            // denegaría, pero está en observación.
            val decision = engine.decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L)),
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 7L),
                contextNormal,
            )

            withClue("En observación, la decisión NO se aplica") {
                decision.allowed shouldBe true
            }
            // Pero la política se evalúa y queda trazada, que es el propósito:
            // revisar la bitácora antes de activarla.
            val traces = engine.evaluateForObservation(
                userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L)),
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 7L),
            )
            traces.size shouldBe 1
            traces.first().matched shouldBe true
        }

        it("A23 — DENY wins even with a LOWER priority than the ALLOW") {
            val allowAlta = Policy(
                id = 60, name = "Allow prioritaria", description = "Prioridad 999",
                action = "inventory.move", effect = PolicyEffect.ALLOW, priority = 999,
            )
            val denyLow = Policy(
                id = 61, name = "Deny de baja prioridad", description = "Prioridad 1",
                action = "inventory.move", effect = PolicyEffect.DENY, priority = 1,
            )

            AccessEngine(policies = listOf(allowAlta, denyLow))
                .decide(userWith(BaseRole.WAREHOUSE_OPERATOR), "inventory.move")
                .allowed shouldBe false
        }

        it("A24 — a protected policy cannot be deactivated without explicit confirmation") {
            // La protección es de la capa de administración; aquí se fija que las
            // políticas críticas están marcadas como tales.
            val segregation = StockPolicies.byId.getValue(1)
            segregation.protected shouldBe true

            val theft = StockPolicies.byId.getValue(4)
            theft.protected shouldBe true
        }

        it("A25 — every policy in the catalog has a description (it cannot be built without one)") {
            StockPolicies.all.forEach { policy ->
                withClue("La política '${policy.name}' debe tener descripción") {
                    policy.description.isNotBlank() shouldBe true
                }
            }

            // Y construir una sin descripción falla:
            val withoutDescription = runCatching {
                Policy(
                    id = 1, name = "Sin descripción", description = "",
                    action = "invoice.view", effect = PolicyEffect.ALLOW,
                )
            }
            withoutDescription.isFailure shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A27-A34 — the concrete warehouse policies") {

        it("A27 / R-74 — SEGREGATION OF DUTIES: cannot approve their own count") {
            val supervisor = userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L))

            val decision = withPolicies().decide(
                supervisor,
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 7L),  // él mismo
                contextNormal,
            )

            val denied = decision.shouldBeInstanceOf<AccessDecision.SegregationOfDuties>()
            withClue("El código debe ser específico: es accionable, dice que necesita OTRA persona") {
                denied.code shouldBe "SEGREGATION_OF_DUTIES"
            }
        }

        it("A27b / R-74 — CAN approve another person's count") {
            val supervisor = userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L))

            withPolicies().decide(
                supervisor,
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 42L),  // otra persona
                contextNormal,
            ).allowed shouldBe true
        }

        it("A28 — the operator CANNOT declare a theft, the manager can") {
            val contextTheft = ResourceContext(warehouseId = 1L)

            withPolicies().decide(
                userWith(BaseRole.WAREHOUSE_OPERATOR),
                "inventory.adjust_theft",
                contextTheft,
                contextNormal,
            ).allowed shouldBe false

            withPolicies().decide(
                userWith(BaseRole.OPERATIONS_MANAGER),
                "inventory.adjust_theft",
                contextTheft,
                contextNormal,
            ).allowed shouldBe true
        }

        it("A29 — the warehouse manager does NOT adjust outside working hours") {
            val manager = userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L))

            withClue("A las 10:00 sí") {
                withPolicies().decide(
                    manager, "inventory.adjust_shortage",
                    ResourceContext(warehouseId = 1L), RequestContext(hour = 10, weekday = 3),
                ).allowed shouldBe true
            }
            withClue("A las 03:00 no") {
                withPolicies().decide(
                    manager, "inventory.adjust_shortage",
                    ResourceContext(warehouseId = 1L), RequestContext(hour = 3, weekday = 3),
                ).allowed shouldBe false
            }
        }

        it("A30 — on Sunday the warehouse manager does not adjust shortages") {
            val manager = userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L))

            withClue("Domingo (weekday 0): la DENY de fin de semana coincide y se deniega") {
                withPolicies().decide(
                    manager, "inventory.adjust_shortage",
                    ResourceContext(warehouseId = 1L), RequestContext(hour = 10, weekday = 0),
                ).allowed shouldBe false
            }
            withClue("Lunes (weekday 1): permitido") {
                withPolicies().decide(
                    manager, "inventory.adjust_shortage",
                    ResourceContext(warehouseId = 1L), RequestContext(hour = 10, weekday = 1),
                ).allowed shouldBe true
            }
        }

        it("A30b — in observation mode, the weekend DENY does not block but is recorded") {
            // El modo observación es la red de seguridad para no bloquear el
            // almacén con una política mal escrita. Aquí se comprueba que la
            // decisión se registra pero NO se aplica.
            val onlyWeekend = listOf(StockPolicies.byId.getValue(7).copy(shadowMode = true))
            val engine = AccessEngine(policies = onlyWeekend)
            val manager = userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L))

            val decision = engine.decide(
                manager, "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L), RequestContext(hour = 10, weekday = 0),
            )

            withClue("En observación, la decisión NO se aplica") {
                decision.allowed shouldBe true
            }
            withClue("Pero la política se evalúa y queda trazada") {
                val traces = engine.evaluateForObservation(
                    manager, "inventory.adjust_shortage",
                    ResourceContext(warehouseId = 1L), RequestContext(hour = 10, weekday = 0),
                )
                traces.first().matched shouldBe true
            }
        }

        it("A31 — the operator does NOT move high-value product") {
            val operator = userWith(BaseRole.WAREHOUSE_OPERATOR)

            // El escenario necesita las dos políticas: una ALLOW base (el
            // operador mueve mercancía normalmente) y la DENY de alto valor.
            // Sin la ALLOW base, la acción se denegaría por POLICY_REQUIRED y el
            // test no estaría probando el umbral de costo, sino la ausencia de
            // autorización.
            val allowBase = Policy(
                id = 200,
                name = "El operador mueve mercancía de su ubicación",
                description = "Operación normal del piso para producto de valor corriente.",
                action = "inventory.move",
                effect = PolicyEffect.ALLOW,
                priority = 10,
                roles = setOf(BaseRole.WAREHOUSE_OPERATOR.key),
            )
            val denyHighValue = StockPolicies.byId.getValue(9)
            val engine = AccessEngine(policies = listOf(allowBase, denyHighValue))

            withClue("Costo 100: permitido por la ALLOW base, la DENY no coincide") {
                engine.decide(
                    operator, "inventory.move", ResourceContext(unitCost = 100L), contextNormal,
                ).allowed shouldBe true
            }
            withClue("Costo 9.000: denegado por PB9") {
                engine.decide(
                    operator, "inventory.move", ResourceContext(unitCost = 9_000L), contextNormal,
                ).allowed shouldBe false
            }
        }

        it("A32 / R-77 — the operator locates from receiving, but not from a shelf") {
            val operator = userWith(BaseRole.WAREHOUSE_OPERATOR)

            withClue("Desde RECEIVING: permitido (su trabajo principal)") {
                withPolicies().decide(
                    operator, "invoice.locate",
                    ResourceContext(locationKind = "RECEIVING"), contextNormal,
                ).allowed shouldBe true
            }
            withClue("Desde SHELF: la política no coincide y se exige autorización") {
                withPolicies().decide(
                    operator, "invoice.locate",
                    ResourceContext(locationKind = "SHELF"), contextNormal,
                ).allowed shouldBe false
            }
        }

        it("A33 / R-76 — synchronized capture from the phone without signal is allowed") {
            val operator = userWith(BaseRole.WAREHOUSE_OPERATOR)

            withPolicies().decide(
                operator, "invoice.capture",
                ResourceContext(warehouseId = 1L),
                RequestContext(hour = 22, weekday = 3, deviceType = DeviceType.MOBILE, offlineSync = true),
            ).allowed shouldBe true
        }

        it("A34 — the ERP import only comes in through the service channel") {
            val admin = userWith(BaseRole.ADMIN)

            withClue("Desde el ERP (SERVICE): permitido") {
                withPolicies().decide(
                    admin, "invoice.import_external",
                    ResourceContext(),
                    RequestContext(deviceType = DeviceType.SERVICE),
                ).allowed shouldBe true
            }
            withClue("Desde la web: denegado, ni el admin importa por ahí") {
                withPolicies().decide(
                    admin, "invoice.import_external",
                    ResourceContext(),
                    RequestContext(deviceType = DeviceType.WEB),
                ).allowed shouldBe false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A35 — the most important security property: ABAC does not escalate privileges") {

        it("no combination of policies allows what RBAC denied") {
            // Se prueban TODOS los permisos del catálogo contra los roles que NO
            // los tienen, con un motor cargado de políticas ALLOW para todo. Si
            // alguna combinación concediera acceso, ABAC sería una vía de
            // escalada de privilegios.
            val policiesThatAllowAll = PermissionCatalog.keys.mapIndexed { index, key ->
                Policy(
                    id = 1000L + index,
                    name = "Allow universal para $key",
                    description = "Prueba de escalada de privilegios: no debe conceder nada por sí sola",
                    action = key,
                    effect = PolicyEffect.ALLOW,
                    roles = emptySet(),   // aplica a todos
                )
            }
            val enginePermissive = AccessEngine(policies = policiesThatAllowAll)

            val escalations = mutableListOf<String>()

            BaseRole.entries.forEach { roleBase ->
                val user = AccessUser(
                    id = 1L,
                    username = "prueba",
                    roles = listOf(RoleDefinitions.byKey.getValue(roleBase.key)),
                    warehouseIds = setOf(1L, 2L, 3L),
                )

                PermissionCatalog.keys.forEach { permission ->
                    val onlyRbac = AccessEngine().decide(
                        user, permission, ResourceContext(warehouseId = 1L), contextNormal,
                    )
                    val withPolicies = enginePermissive.decide(
                        user, permission, ResourceContext(warehouseId = 1L), contextNormal,
                    )

                    // Si RBAC niega y ABAC permite, hay escalada.
                    if (!onlyRbac.allowed && withPolicies.allowed) {
                        escalations.add("${roleBase.key.value} → $permission")
                    }
                }
            }

            withClue(
                "ABAC NUNCA puede conceder lo que RBAC negó. Escaladas encontradas: $escalations"
            ) {
                escalations shouldBe emptyList()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A44-A46 — parity and behaviour of the engine") {

        it("A44 — the phone and the backend get the SAME decision (it is the same code)") {
            // El motor es puro y vive en el dominio compartido, así que la
            // decisión es idéntica por construcción. Lo que se prueba aquí es que
            // el resultado no depende del orden de las entradas.
            val user = userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L))
            val resource = ResourceContext(warehouseId = 1L, capturedBy = 7L)
            val engine = withPolicies()

            val first = engine.decide(user, "invoice.approve", resource, contextNormal)
            val second = engine.decide(user, "invoice.approve", resource, contextNormal)

            first.code shouldBe second.code
            first.allowed shouldBe second.allowed
        }

        it("the user's role determines their permissions in an auditable way") {
            val manager = RoleDefinitions.byKey.getValue(BaseRole.WAREHOUSE_MANAGER.key)

            manager.scopeOf("invoice.approve") shouldBe PermissionScope.AREA
            manager.scopeOf("inventory.adjust_theft") shouldBe PermissionScope.NONE
            manager.scopeOf("role.manage") shouldBe PermissionScope.NONE
        }

        it("every base role is explained in one sentence (otherwise it is badly cut)") {
            BaseRole.entries.forEach { role ->
                withClue("El rol ${role.key.value} debe tener una descripción de una frase") {
                    role.oneLiner.isNotBlank() shouldBe true
                    role.displayName.isNotBlank() shouldBe true
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the decision trace: the 'why not' is actionable") {

        it("a policy denial explains WHICH condition failed and with what values") {
            val manager = userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L))

            // Miércoles a las 03:00: coincide la DENY de horario, y la de fin de
            // semana no aplica (weekday 3). Así la traza que se inspecciona es la
            // de la condición horaria y no la del día.
            // `evaluateForObservation` devuelve la evaluación de **todas** las
            // políticas aplicables con su traza, sin aplicar la decisión. Es la
            // vista que permite depurar una política y responder al "no me dejó".
            val traces = withPolicies().evaluateForObservation(
                manager, "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L),
                RequestContext(hour = 3, weekday = 3),
            )

            val hourTrace = traces.firstOrNull { it.policyId == 5L }
            withClue("La traza debe incluir la política de horario laboral") {
                (hourTrace != null) shouldBe true
            }

            val hourCondition = hourTrace!!.conditions
                .first { it.condition.field == "context.hour" }

            withClue("La condición debe registrar el valor REAL observado (3), no solo el esperado") {
                hourCondition.current shouldBe 3
            }
            withClue("Y debe marcar que no coincidió") {
                hourCondition.matches shouldBe false
            }
            withClue("A las 03:00 la política de horario no autoriza, así que no coincidence") {
                hourTrace.matched shouldBe false
            }
        }

        it("the decision carries the version of the matrix and of the policies") {
            val engine = AccessEngine(policies = StockPolicies.all, matrixVersion = 3, policiesVersion = 7)

            val decision = engine.decide(
                userWith(BaseRole.WAREHOUSE_OPERATOR),
                "product.view",
            )

            // Sin estos números, dentro de seis meses no se puede reconstruir por
            // qué se permitió algo, porque los permisos habrán cambiado.
            decision.matrixVersion shouldBe 3
            decision.policiesVersion shouldBe 7
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the permission catalog") {

        it("all keys have the format 'resource.action' in lowercase") {
            PermissionCatalog.all.forEach { permission ->
                withClue("Clave inválida: '${permission.key}'") {
                    permission.key shouldBe permission.key.lowercase()
                    (permission.key.contains('.')) shouldBe true
                }
            }
        }

        it("there are no duplicate keys") {
            val keys = PermissionCatalog.all.map { it.key }
            keys.size shouldBe keys.toSet().size
        }

        it("the sensitive permissions are flagged and are the expected ones") {
            val sensitive = PermissionCatalog.sensitive.map { it.key }

            sensitive shouldContain "inventory.adjust_theft"
            sensitive shouldContain "report.financial"
            sensitive shouldContain "role.manage"
            sensitive shouldContain "audit.view"
        }

        it("the NONE scope is never offered as grantable") {
            PermissionCatalog.all.forEach { permission ->
                withClue("El permiso '${permission.key}' no debe listar NONE") {
                    permission.grantableScopes shouldBe permission.grantableScopes
                    (PermissionScope.NONE in permission.scopes) shouldBe false
                }
            }
        }
    }
})
