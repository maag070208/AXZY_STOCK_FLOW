# M03 — Recepción: facturas, captura física y discrepancias
> **Normativo.** Última verificación: 2026-10-10 (fase 6: facturas, captura y decisiones; falta el enganche de ubicar con la línea).

| | |
|---|---|
| **Código** | M03 |
| **Carpeta** | `docs/modulos/M03-recepcion/` |
| **Documentado** | Sí |
| **Terminado (backend)** | Sí |
| **Web** | `InvoicesPage`, `InvoiceDetailPage`, `PendingApprovalPage` |
| **App** | `features/invoices` |

## 1. Objetivo

Registrar lo que llega en una factura, contar físicamente cada línea, resolver las
diferencias con un supervisor y dejar la mercancía ubicada.

## 2. Alcance

| Dentro | Fuera |
|---|---|
| Alta de facturas y líneas | Facturación electrónica |
| Captura física de cada línea | Ubicación en estantería (M02, con `invoice.locate`) |
| Bandeja de discrepancias y aprobación por factura | Surtido (M04) |
| Cierre de factura | |

## 3. Reglas de negocio

| # | Regla | Dónde vive |
|---|---|---|
| R1 | Una línea con diferencia respecto a lo facturado queda en `DISCREPANCY` y no se puede ubicar | [ADR-0008](../../adr/0008-discrepancias-y-merma.md); `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/receiving/InvoiceLine.kt` |
| R2 | Toda diferencia requiere aprobación de un supervisor, sin umbral | [ADR-0008](../../adr/0008-discrepancias-y-merma.md) |
| R3 | La aprobación es por factura, no por línea | `ApproveInvoice` en `api/application/.../receiving/InvoiceUseCases.kt`, con una sola sentencia de escritura |
| R4 | Quien captura una línea no puede aprobar su discrepancia | `SEGREGATION_OF_DUTIES` (403) en `ApproveInvoice`, con `details.lines` |
| R5 | Una factura no se cierra con líneas en discrepancia | `INVOICE_HAS_PENDING_LINES` en `CloseInvoice`, con el criterio de `InvoiceLineStatus.blocksInvoiceClosing` |
| R6 | Si nada está pendiente de aprobar, responde `NO_PENDING_APPROVAL` (409) | `application/receiving/ReceivingUseCases.kt` |
| R7 | El listado de facturas rechaza `page` negativa en lugar de recortarla | `PageRequest.analyze` (regla transversal), comprobada en `ReceivingFlowTest` |

## 4. Modelo de datos

| Tabla | Migración | Qué guarda |
|---|---|---|
| `invoices` | `V3` | Facturas, con su estado |
| `invoice_lines` | `V3` | Líneas, con su estado y el `CHECK` de aprobación |

## 5. API

Contrato en [api/recepcion.md](../../api/recepcion.md). Rutas en
`infrastructure/inbound/http/receiving/ReceivingRoutes.kt`.

## 6. Permisos

`invoice.view`, `invoice.create`, `invoice.capture`, `invoice.approve`, `invoice.reject`,
`invoice.close`, `invoice.locate`. El operador ubica con alcance `OWN` (decisión R-77, en
`docs/historico/PLAN_ALMACEN_INTEGRAL.md`). Ver
[seguridad/roles-permisos.md](../../seguridad/roles-permisos.md).

## 7. Bitácora

Captura, aprobación, rechazo y cierre escriben en `audit_log`.

## 8. Pruebas

`InvoiceLineStateMachineTest` en `stock-domain`, y pruebas de rutas en el backend.

## 9. Criterios de aceptación

- [x] Una línea en discrepancia no se puede ubicar (`InvoiceLineStateMachineTest`).
- [ ] Prueba de navegador de captura, aprobación y cierre. No existe.
