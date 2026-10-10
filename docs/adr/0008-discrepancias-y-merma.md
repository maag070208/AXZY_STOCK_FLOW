# ADR-0008 — Discrepancias y merma: una sola regla de ajuste, aprobada por factura
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 (decisiones B1, B2, B3, R-74, R-75, R-86 del plan original) |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

En el legacy, la diferencia entre lo facturado y lo contado no tenía un control claro: el
descuadre se corregía sin un registro con motivo ni un segundo firmante. El plan de
reescritura fijó tres reglas: bloquear hasta aprobar, nunca borrar, y una sola regla de
ajuste para todos los orígenes.

## Decisión

1. **Bloqueo.** Una línea cuya cantidad física no coincide con la facturada queda en
   `DISCREPANCY` y no se puede ubicar. Solo llega a `APPROVED` tras la aprobación de un
   supervisor con `invoice.approve` (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/receiving/InvoiceLine.kt`).
2. **Sin umbral.** Toda diferencia requiere aprobación (R-75).
3. **Aprobación por factura, no por línea** (R-86). El supervisor resuelve todas las
   discrepancias de la factura en una acción: `POST /v1/invoices/{id}/approve`
   (`receiving/ReceivingRoutes.kt`).
4. **Segregación de funciones.** Quien capturó una línea no puede aprobarla
   (`SEGREGATION_OF_DUTIES`, `security/PermissionChecker.kt`; R-74).
5. **La base lo impone.** Una línea no llega a `APPROVED` sin `approved_by`
   (`CHECK` en `V3__inventory_domain.sql`).
6. **Nunca borrar.** Toda diferencia es un movimiento de ajuste con motivo obligatorio y
   usuario (B2). El kardex es append-only: corregir es añadir.
7. **Una sola regla.** Ajuste de inventario, faltante en surtido, conteo y daño son
   `AdjustStock` con distinto `origin` (B3): `INVOICE_DISCREPANCY`, `FULFILLMENT_SHORTAGE`,
   `MANUAL_COUNT`, `DAMAGE_REPORT` (`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/inventory/Adjustment.kt`).

## Consecuencias

- Un supervisor que aprueba debe ver la factura completa; no puede aprobar por línea
  aunque la API lo permitiría, porque no existe esa ruta.
- Una factura con una discrepancia no se cierra hasta resolverla
  (`INVOICE_HAS_PENDING_LINES`, `application/receiving/ReceivingUseCases.kt`).
- Si nada está pendiente de aprobar, la API responde `409 NO_PENDING_APPROVAL`
  (`application/receiving/ReceivingUseCases.kt`).
- Se paga en tiempo del supervisor: cada discrepancia pasa por una persona. Es el coste
  asumido para no tener un descuadre sin firma.

## Alternativas descartadas

- **Aprobar por línea** (R-86): obliga a repetir el mismo juicio N veces y deja la factura en
  un estado intermedio.
- **Umbral de tolerancia** (R-75): cualquier umbral es una decisión de merma sin firma.
- **Dos reglas separadas** (merma y discrepancia): dos sitios donde equivocarse.
