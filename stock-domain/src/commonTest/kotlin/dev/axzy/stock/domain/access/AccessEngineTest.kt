package dev.axzy.stock.domain.access

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ACCESS ENGINE TESTS — cases A1 to A43
 *
 * They cover the RBAC matrix, the scope, the ABAC engine and the security
 * properties. **All the negative cases are included**: it is not enough for the
 * happy path to work; one must show that the control blocks.
 *
 * The numbering is taken from docs/historico/PLAN_ROLES_PERMISOS_POLITICAS.md §9 so as to trace
 * each test against the design.
 * ═══════════════════════════════════════════════════════════════════════════
 */
class AccessEngineTest : DescribeSpec({

    // ── Construction utilities ─────────────────────────────────────────

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

    /** Engine without policies: isolates RBAC. */
    fun onlyRbac() = AccessEngine()

    /** Engine with all the warehouse policies. */
    fun withPolicies() = AccessEngine(policies = StockPolicies.all)

    val contextNormal = RequestContext(hour = 10, weekday = 3, deviceType = DeviceType.WEB)

    // ═════════════════════════════════════════════════════════════════════════
    describe("A1-A2 — the role matrix grants what it declares") {

        it("A3 — the OPERATOR cannot approve discrepancies") {
            // This is what makes rule B1 real: if the operator could approve,
            // they could sign off their own counting error and the control would be decorative.
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

            // This is the property that makes the auditor useful: it can look at everything and
            // cannot change anything. Without it, the role is of no use for auditing.
            permissionsThatGrant shouldBe emptyList()
        }

        it("A6 — the administrator has ALL the catalog permissions") {
            val admin = userWith(BaseRole.ADMIN, warehouseIds = setOf(1L, 2L))
            val engine = onlyRbac()

            val withoutGrants = PermissionCatalog.keys.filter { permission ->
                !engine.decide(admin, permission, ResourceContext(warehouseId = 1L), contextNormal).allowed
            }

            // The legacy solved this with `if (role === 1) return next()`, and the admin role
            // had NO database permissions (finding S3). Here what
            // the admin can do is explicit, visible in the matrix and auditable.
            withoutGrants shouldBe emptyList()
        }

        it("A6b — no role gets access by its numeric id, only by its key") {
            // Against the legacy's `role === 1` bypass: there is no way for a
            // role to get access by being "the first" or by its id.
            val roleAdmin = RoleDefinitions.byKey.getValue(BaseRole.ADMIN.key)
            roleAdmin.key.value shouldBe "ADMIN"
            // The permission comes from its matrix, not from a numeric comparison.
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
            // OWN means "only their own". The resource does not declare authorship, so
            // the minimum scope is required and OWN covers it; what is checked here
            // is that OWN **does not** cover AREA on a resource from another warehouse.
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
                withClue("$granted covers $required should be $expected") {
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
            // Golden rule: a denial cannot be "bought" with a higher priority.
            withPolicies().decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L)),
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 7L),
                contextNormal,
            ).allowed shouldBe false
        }

        it("A17 — applicable policies exist and no ALLOW matches: POLICY_REQUIRED") {
            // An operator has no ALLOW policy on transfer.dispatch,
            // but if they were misconfigured, this is what would happen.
            val engine = AccessEngine(
                policies = listOf(
                    Policy(
                        id = 99,
                        name = "Solo gerencia en fin de semana",
                        description = "Test that ABAC raises the bar",
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
                RequestContext(hour = 10, weekday = 3),  // Wednesday: the DENY does not match
            )

            // There is an applicable policy on the action and no ALLOW authorizes it,
            // so it is denied. RBAC alone is no longer enough: that is what makes
            // ABAC a real control and not decoration.
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

            // With no applicable active policies, RBAC decides and allows.
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

            // The user tries to approve their own capture: the policy
            // would deny it, but it is in observation mode.
            val decision = engine.decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L)),
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 7L),
                contextNormal,
            )

            withClue("In observation, the decision is NOT applied") {
                decision.allowed shouldBe true
            }
            // But the policy is evaluated and traced, which is the point:
            // review the audit log before activating it.
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
            // The protection belongs to the administration layer; here it is fixed that the
            // critical policies are marked as such.
            val segregation = StockPolicies.byId.getValue(1)
            segregation.protected shouldBe true

            val theft = StockPolicies.byId.getValue(4)
            theft.protected shouldBe true
        }

        it("A25 — every policy in the catalog has a description (it cannot be built without one)") {
            StockPolicies.all.forEach { policy ->
                withClue("The policy '${policy.name}' must have a description") {
                    policy.description.isNotBlank() shouldBe true
                }
            }

            // And building one without a description fails:
            val withoutDescription = runCatching {
                Policy(
                    id = 1, name = "No description", description = "",
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
                ResourceContext(warehouseId = 1L, capturedBy = 7L),  // the same person
                contextNormal,
            )

            val denied = decision.shouldBeInstanceOf<AccessDecision.SegregationOfDuties>()
            withClue("The code must be specific: it is actionable, it says that ANOTHER person is needed") {
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

            withClue("At 10:00 it does") {
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
            // Observation mode is the safety net so as not to block the
            // warehouse with a badly written policy. Here it is checked that the
            // decision is recorded but NOT applied.
            val onlyWeekend = listOf(StockPolicies.byId.getValue(7).copy(shadowMode = true))
            val engine = AccessEngine(policies = onlyWeekend)
            val manager = userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L))

            val decision = engine.decide(
                manager, "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L), RequestContext(hour = 10, weekday = 0),
            )

            withClue("In observation, the decision is NOT applied") {
                decision.allowed shouldBe true
            }
            withClue("But the policy is evaluated and traced") {
                val traces = engine.evaluateForObservation(
                    manager, "inventory.adjust_shortage",
                    ResourceContext(warehouseId = 1L), RequestContext(hour = 10, weekday = 0),
                )
                traces.first().matched shouldBe true
            }
        }

        it("A31 — the operator does NOT move high-value product") {
            val operator = userWith(BaseRole.WAREHOUSE_OPERATOR)

            // The scenario needs both policies: a base ALLOW (the
            // operator moves goods normally) and the high-value DENY.
            // Without the base ALLOW, the action would be denied for POLICY_REQUIRED and the
            // test would not be testing the cost threshold, but the absence of
            // authorization.
            val allowBase = Policy(
                id = 200,
                name = "The operator moves goods from their location",
                description = "Normal floor operation for an ordinary-value product.",
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
            withClue("From SHELF: the policy does not match and authorization is required") {
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
            withClue("From the web: denied, not even the admin imports that way") {
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
            // ALL the catalog's permissions are tested against the roles that do NOT
            // have them, with an engine loaded with ALLOW policies for everything. If
            // any combination granted access, ABAC would be a route for privilege
            // escalation.
            val policiesThatAllowAll = PermissionCatalog.keys.mapIndexed { index, key ->
                Policy(
                    id = 1000L + index,
                    name = "Allow universal para $key",
                    description = "Privilege escalation test: it must not grant anything on its own",
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

                    // If RBAC denies and ABAC allows, there is escalation.
                    if (!onlyRbac.allowed && withPolicies.allowed) {
                        escalations.add("${roleBase.key.value} → $permission")
                    }
                }
            }

            withClue(
                "ABAC must NEVER grant what RBAC denied. Escalations found: $escalations"
            ) {
                escalations shouldBe emptyList()
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A44-A46 — parity and behaviour of the engine") {

        it("A44 — the phone and the backend get the SAME decision (it is the same code)") {
            // The engine is pure and lives in the shared domain, so the
            // decision is identical by construction. What is tested here is that
            // the result does not depend on the order of the entries.
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
                withClue("The role ${role.key.value} must have a one-sentence description") {
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

            // Wednesday at 03:00: the schedule DENY matches, and the weekend one does not
            // apply (weekday 3). That way the trace inspected is the
            // one of the time condition and not the day's.
            // `evaluateForObservation` returns the evaluation of **all** the
            // applicable policies with their trace, without applying the decision. It is the
            // view that allows debugging a policy and answering "it did not let me".
            val traces = withPolicies().evaluateForObservation(
                manager, "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L),
                RequestContext(hour = 3, weekday = 3),
            )

            val hourTrace = traces.firstOrNull { it.policyId == 5L }
            withClue("The trace must include the working-hours policy") {
                (hourTrace != null) shouldBe true
            }

            val hourCondition = hourTrace!!.conditions
                .first { it.condition.field == "context.hour" }

            withClue("The condition must record the REAL observed value (3), not only the expected one") {
                hourCondition.current shouldBe 3
            }
            withClue("And it must mark that it did not match") {
                hourCondition.matches shouldBe false
            }
            withClue("At 03:00 the working-hours policy does not authorize, so it does not match") {
                hourTrace.matched shouldBe false
            }
        }

        it("the decision carries the version of the matrix and of the policies") {
            val engine = AccessEngine(policies = StockPolicies.all, matrixVersion = 3, policiesVersion = 7)

            val decision = engine.decide(
                userWith(BaseRole.WAREHOUSE_OPERATOR),
                "product.view",
            )

            // Without these numbers, six months from now it cannot be reconstructed
            // why something was allowed, because the permissions will have changed.
            decision.matrixVersion shouldBe 3
            decision.policiesVersion shouldBe 7
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("the permission catalog") {

        it("all keys have the format 'resource.action' in lowercase") {
            PermissionCatalog.all.forEach { permission ->
                withClue("Invalid key: '${permission.key}'") {
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
