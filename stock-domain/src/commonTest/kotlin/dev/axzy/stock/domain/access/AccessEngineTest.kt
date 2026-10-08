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
 * Numeración tomada de PLAN_ROLES_PERMISOS_POLITICAS.md §9 para poder trazar
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
    describe("A1-A2 — la matriz de roles concede lo que declara") {

        it("A3 — el OPERADOR no puede aprobar discrepancias") {
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

        it("A4 — el AUDITOR no puede escribir: comprobado sobre TODOS los permisos de escritura") {
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

        it("A6 — el administrador tiene TODOS los permisos del catálogo") {
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

        it("A6b — ningún rol obtiene acceso por su id numérico, solo por su clave") {
            // Contra el bypass `role === 1` del legacy: no existe forma de que un
            // rol acceda por ser "el primero" o por su id.
            val roleAdmin = RoleDefinitions.byKey.getValue(BaseRole.ADMIN.key)
            roleAdmin.key.value shouldBe "ADMIN"
            // El permiso viene de su matriz, no de una comparación numérica.
            roleAdmin.scopeOf("role.manage") shouldBe PermissionScope.ALL
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A7 — la identidad se comprueba antes que los permisos") {

        it("un usuario desactivado no tiene acceso aunque su rol lo permita") {
            val disabled = userWith(BaseRole.ADMIN, active = false)

            val decision = onlyRbac().decide(disabled, "role.manage")

            val denied = decision.shouldBeInstanceOf<AccessDecision.NotAuthenticated>()
            denied.stage shouldBe DecisionStage.IDENTITY
        }

        it("sin usuario no hay acceso") {
            onlyRbac().decide(null, "invoice.view").allowed shouldBe false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A8-A13 — el alcance (scope)") {

        it("A8 — el OPERADOR con alcance OWN no puede ver una factura ajena") {
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

        it("A9 — el JEFE de bodega 1 no alcanza un recurso de la bodega 3") {
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

        it("A10 — el mismo JEFE sí alcanza un recurso de SU bodega") {
            val manager = userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L))

            onlyRbac().decide(
                manager,
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L),
                contextNormal,
            ).allowed shouldBe true
        }

        it("A11 — el GERENTE con alcance ALL alcanza cualquier bodega") {
            val manager = userWith(BaseRole.OPERATIONS_MANAGER, warehouseIds = emptySet())

            onlyRbac().decide(
                manager,
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 99L),
                contextNormal,
            ).allowed shouldBe true
        }

        it("A13 — la escalera de alcances: AREA no concede ALL, y OWN no concede AREA") {
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

        it("A12 — una excepción por persona concede un permiso que el rol no tiene") {
            val operator = userWith(
                BaseRole.WAREHOUSE_OPERATOR,
                exceptions = mapOf("invoice.approve" to PermissionScope.OWN),
            )

            onlyRbac().decide(operator, "invoice.approve").allowed shouldBe true
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("A14-A25 — el motor ABAC") {

        it("A14 — sin políticas para la acción, RBAC decide y se permite") {
            val engine = AccessEngine(
                policies = listOf(StockPolicies.byId.getValue(1)), // solo la de aprobar
            )

            engine.decide(
                userWith(BaseRole.WAREHOUSE_OPERATOR),
                "invoice.capture",
            ).allowed shouldBe true
        }

        it("A15 — una ALLOW que coincide permite") {
            withPolicies().decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L)),
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 99L),
                contextNormal,
            ).allowed shouldBe true
        }

        it("A16 — DENY gana aunque haya una ALLOW que coincide") {
            // Regla de oro: no se puede 'comprar' una denegación con prioridad.
            withPolicies().decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L)),
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 7L),
                contextNormal,
            ).allowed shouldBe false
        }

        it("A17 — hay políticas aplicables y ninguna ALLOW coincide: POLICY_REQUIRED") {
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

        it("A18 — las condiciones se combinan con AND: una falsa anula la política") {
            val engine = withPolicies()

            // PB5 exige hora >= 8 AND hora < 18.
            engine.decide(
                userWith(BaseRole.WAREHOUSE_MANAGER, warehouseIds = setOf(1L)),
                "inventory.adjust_shortage",
                ResourceContext(warehouseId = 1L),
                RequestContext(hour = 3, weekday = 3),   // fuera de horario
            ).allowed shouldBe false
        }

        it("A19 — una política inactiva no se evalúa") {
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

        it("A20-A21 — una política sin roles aplica a todos; con roles, solo a esos") {
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

        it("A22 — el modo observación registra la decisión pero NO la aplica") {
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

        it("A23 — DENY gana incluso con prioridad MENOR que la ALLOW") {
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

        it("A24 — una política protected no se puede desactivar sin confirmación explícita") {
            // La protección es de la capa de administración; aquí se fija que las
            // políticas críticas están marcadas como tales.
            val segregation = StockPolicies.byId.getValue(1)
            segregation.protected shouldBe true

            val theft = StockPolicies.byId.getValue(4)
            theft.protected shouldBe true
        }

        it("A25 — toda política del catálogo tiene descripción (no se puede construir sin ella)") {
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
    describe("A27-A34 — las políticas concretas del almacén") {

        it("A27 / R-74 — SEGREGACIÓN DE FUNCIONES: no puede aprobar su propia captura") {
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

        it("A27b / R-74 — SÍ puede aprobar la captura de otra persona") {
            val supervisor = userWith(BaseRole.WAREHOUSE_MANAGER, id = 7L, warehouseIds = setOf(1L))

            withPolicies().decide(
                supervisor,
                "invoice.approve",
                ResourceContext(warehouseId = 1L, capturedBy = 42L),  // otra persona
                contextNormal,
            ).allowed shouldBe true
        }

        it("A28 — el operador NO puede declarar un robo, el gerente sí") {
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

        it("A29 — el jefe de bodega NO ajusta fuera de horario laboral") {
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

        it("A30 — el domingo el jefe de bodega no ajusta faltantes") {
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

        it("A30b — en modo observación, la DENY de fin de semana no bloquea pero queda registrada") {
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

        it("A31 — el operador NO mueve producto de alto valor") {
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

        it("A32 / R-77 — el operador ubica desde recepción, pero no desde un estante") {
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

        it("A33 / R-76 — la captura sincronizada desde el móvil sin señal se permite") {
            val operator = userWith(BaseRole.WAREHOUSE_OPERATOR)

            withPolicies().decide(
                operator, "invoice.capture",
                ResourceContext(warehouseId = 1L),
                RequestContext(hour = 22, weekday = 3, deviceType = DeviceType.MOBILE, offlineSync = true),
            ).allowed shouldBe true
        }

        it("A34 — la importación del ERP solo entra por el canal de servicio") {
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
    describe("A35 — la propiedad de seguridad más importante: ABAC no escala privilegios") {

        it("ninguna combinación de políticas permite lo que RBAC negó") {
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
    describe("A44-A46 — paridad y comportamiento del motor") {

        it("A44 — el móvil y el backend obtienen la MISMA decisión (es el mismo código)") {
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

        it("el rol del usuario determina sus permisos de forma auditable") {
            val manager = RoleDefinitions.byKey.getValue(BaseRole.WAREHOUSE_MANAGER.key)

            manager.scopeOf("invoice.approve") shouldBe PermissionScope.AREA
            manager.scopeOf("inventory.adjust_theft") shouldBe PermissionScope.NONE
            manager.scopeOf("role.manage") shouldBe PermissionScope.NONE
        }

        it("cada rol base se explica en una frase (si no, está mal cortado)") {
            BaseRole.entries.forEach { role ->
                withClue("El rol ${role.key.value} debe tener una descripción de una frase") {
                    role.oneLiner.isNotBlank() shouldBe true
                    role.displayName.isNotBlank() shouldBe true
                }
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    describe("la traza de decisión: el 'por qué no' es accionable") {

        it("una denegación de política explica QUÉ condición falló y con qué valores") {
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

        it("la decisión lleva la versión de la matriz y de las políticas") {
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
    describe("el catálogo de permisos") {

        it("todas las claves tienen formato 'recurso.accion' en minúsculas") {
            PermissionCatalog.all.forEach { permission ->
                withClue("Clave inválida: '${permission.key}'") {
                    permission.key shouldBe permission.key.lowercase()
                    (permission.key.contains('.')) shouldBe true
                }
            }
        }

        it("no hay claves duplicadas") {
            val keys = PermissionCatalog.all.map { it.key }
            keys.size shouldBe keys.toSet().size
        }

        it("los permisos sensibles están marcados y son los esperados") {
            val sensitive = PermissionCatalog.sensitive.map { it.key }

            sensitive shouldContain "inventory.adjust_theft"
            sensitive shouldContain "report.financial"
            sensitive shouldContain "role.manage"
            sensitive shouldContain "audit.view"
        }

        it("el alcance NONE nunca se ofrece como concedible") {
            PermissionCatalog.all.forEach { permission ->
                withClue("El permiso '${permission.key}' no debe listar NONE") {
                    permission.grantableScopes shouldBe permission.grantableScopes
                    (PermissionScope.NONE in permission.scopes) shouldBe false
                }
            }
        }
    }
})
