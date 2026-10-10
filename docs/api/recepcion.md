# Recepción
> **Referencia.** Facturas de recepción, captura del conteo físico, aprobación y
> rechazo de discrepancias, cierre y bandeja de pendientes.
> Última verificación: 2026-10-09.

Implementado en
`backend/src/main/kotlin/dev/axzy/stock/backend/infrastructure/inbound/http/receiving/ReceivingRoutes.kt` y
`ReceivingUseCases.kt`, con los mapeos en `ReceivingMappers.kt`. La paginación está
en [convenciones.md](convenciones.md); los códigos, en [errores.md](errores.md).
La idempotencia va en la cabecera `Idempotency-Key`, según
[convenciones.md §1.5](convenciones.md).

## `GET|POST /v1/invoices`

### Listado (`GET`)

Permiso: `invoice.view`. Filtros: `status` (uno de `PENDING`, `IN_PROGRESS`,
`PENDING_TO_LOCATE`, `COMPLETED`, `CANCELLED`) y `q`. Paginación por `page` y
`size`.

Devuelve **solo la cabecera**, con los contadores calculados en la base:

```json
{
  "items": [
    {
      "id": 15, "invoiceNumber": "F-2024-0158", "receiptDate": "2026-10-07",
      "invoiceType": "NATIONAL", "status": "IN_PROGRESS",
      "customerCode": null, "concept": null,
      "totalLines": 12, "pendingApprovalLines": 4,
      "createdAt": "2026-10-07T21:24:37.688Z"
    }
  ],
  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
}
```

`pendingApprovalLines` cuenta las líneas que **impiden cerrar** —sin capturar, con
diferencia sin aprobar o asignadas a medias—, que es el mismo criterio que aplica
el cierre: así la lista y la acción no pueden discrepar.

`status` de la factura: `PENDING` | `IN_PROGRESS` | `PENDING_TO_LOCATE` |
`COMPLETED` | `CANCELLED`.

### Creación (`POST`)

Permiso: `invoice.create`.

```json
{
  "invoiceNumber": "F-2024-0158",
  "receiptDate": "2026-10-07",
  "invoiceType": "NATIONAL",
  "lines": [
    {
      "productCode": "ABRAA1B", "container": "CONT-01", "unitMeasure": "PZA",
      "quantity": 100.000, "boxQuantity": 2.000, "unitsPerBox": 50.000
    }
  ]
}
```

**201** con la referencia, no con la factura completa: para el detalle hay que
pedir `GET /v1/invoices/{id}`.

```json
{ "id": 15, "invoiceNumber": "F-2024-0158" }
```

Una factura necesita **al menos una línea**: `400 FIELD_REQUIRED` si viene vacía,
porque una factura sin líneas no se puede capturar ni ubicar y bloquea el flujo.
El número es obligatorio y la fecha debe ser `AAAA-MM-DD` (`400 INVALID_PARAMETER`).
Errores: `422 UNKNOWN_UNIT_MEASURE`, `422 INVALID_PRODUCT_CODE`, `409
DUPLICATE_INVOICE_NUMBER`.

## `GET /v1/invoices/{id}`

Permiso: `invoice.view`. Incluye las líneas:

```json
{
  "id": 15, "invoiceNumber": "F-2024-0158", "receiptDate": "2026-10-07",
  "invoiceType": "NATIONAL", "status": "IN_PROGRESS",
  "customerCode": null, "concept": null,
  "totalLines": 1, "pendingApprovalLines": 1,
  "lines": [
    {
      "id": 88, "productId": 1, "productCode": "ABRAA1B",
      "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "container": "CONT-01",
      "quantity": "100.000", "boxQuantity": "2.000", "unitsPerBox": "50.000",
      "physicalQuantity": "80.000", "physicalBoxQuantity": "1.600",
      "locatedQuantity": "0.000",
      "difference": "-20.000",
      "status": "DISCREPANCY",
      "capturedBy": 7, "capturedByName": "Juan Pérez",
      "capturedAt": "2026-10-07T21:00:00Z",
      "approvedBy": null, "approvedByName": null, "approvedAt": null,
      "adjustmentReason": null,
      "locationDisplayName": null
    }
  ]
}
```

**`difference` la calcula el servidor** y es `physicalQuantity - quantity`. En el
legacy estaba declarada en el modelo, el DTO, el mapper y la respuesta, y **siempre
valía 0**. Aquí es una columna generada de la base: es imposible que se
desincronice.

`status` de línea: `PENDING` | `CAPTURED` | `DISCREPANCY` | `PENDING_APPROVAL` |
`APPROVED` | `ASSIGNED` | `LOCATED` | `CANCELLED`.

**404** con `code = INVOICE_NOT_FOUND` si la factura no existe; `400 INVALID_ID` si
el identificador no es un número.

## `POST /v1/invoices/{id}/lines/{lineId}/capture`

Permiso: `invoice.capture`.

```json
{ "physicalQuantity": 80.000, "physicalBoxQuantity": 1.600 }
```

Calcula `difference` y decide el estado: `CAPTURED` si coincide, `DISCREPANCY` si
no. **200** con la **línea** capturada (no la factura).

Errores: `422 INVALID_QUANTITY`, `422 INCONSISTENT_BOX_COUNT`, `409
LINE_ALREADY_RESOLVED` si la línea ya está aprobada o ubicada, `404 LINE_NOT_FOUND`,
`400 INVALID_ID`.

## `POST /v1/invoices/{id}/approve`

**La ÚNICA ruta de aprobación. No existe una variante por línea.** Permiso:
`invoice.approve`.

```json
{ "adjustmentReason": "SUPPLIER_ERROR", "note": "..." }
```

**La aprobación es por factura, no por línea.** Una factura llega con varias líneas
con discrepancia, y aprobarlas de una en una obliga al supervisor a repetir el
mismo juicio N veces y deja la factura en un estado intermedio donde unas líneas
están aprobadas y otras no. El supervisor revisa la factura completa y decide una
vez. Aprobar por línea era además lo que invitaba a **aprobar lo propio sin darse
cuenta**: con una sola acción sobre el documento, la segregación de funciones es
evidente.

**Respuesta 200:** la factura completa con sus líneas ya en `APPROVED`.

**Errores:**
- `409` `NO_PENDING_APPROVAL` si no hay ninguna línea por aprobar.
- `422` `REASON_REQUIRED` si falta `adjustmentReason`.
- `422` `SEGREGATION_OF_DUTIES` si quien aprueba fue quien capturó **alguna** de las
  líneas pendientes. El mensaje y `details.lines` indican **cuáles**, para que el
  supervisor sepa qué reasignar.

La base impone la regla con un `CHECK`: es imposible que una línea llegue a
`APPROVED` sin `approved_by`. No depende de que el código se acuerde de validarlo.

## `POST /v1/invoices/{id}/reject-discrepancy`

**La otra salida de la regla B1.** Permiso: `invoice.reject`. El cuerpo comparte
forma con la aprobación —motivo obligatorio y nota opcional— porque es la misma
decisión tomada al revés.

```json
{ "adjustmentReason": "SUPPLIER_ERROR", "note": "..." }
```

Aprobar **acepta** la diferencia y la mercancía se queda; rechazar **no la acepta**
y **cancela las líneas**. Sin esta salida, la única opción ante una discrepancia era
aceptarla, y un supervisor que no puede rechazar acaba aprobando para desbloquear
la factura.

**200** con la factura y sus líneas canceladas. Errores: `409 NO_PENDING_APPROVAL`,
`422 REASON_REQUIRED`, `422 SEGREGATION_OF_DUTIES`, `400 INVALID_ID`.

## `POST /v1/invoices/{id}/close`

Permiso: `invoice.close`. **200** con la factura cerrada y sus líneas.

**No se cierra una factura con líneas que bloquean**: una línea sin capturar, con
diferencia sin aprobar o con trabajo asignado a medias. El criterio lo define el
dominio (`InvoiceLineStatus.blocksInvoiceClosing`) y no una lista escrita en la
ruta.

- `422` con `code = INVOICE_HAS_PENDING_LINES` y `details.pendingLines` si quedan
  líneas bloqueantes.
- `409` `INVOICE_ALREADY_CLOSED` si ya estaba cerrada; `409 INVALID_TRANSITION` si
  está anulada.

## `GET /v1/invoices/pending-approval`

Bandeja de pendientes. Permiso: `invoice.view`. **Pantalla nueva: el legacy no la
tenía**, y sin ella una discrepancia se quedaba bloqueada sin que nadie supiera que
había que aprobarla. La respuesta **no** usa el sobre de paginación: es
`{ "items": [...] }`.

```json
{
  "items": [
    {
      "invoiceId": 15, "invoiceNumber": "F-2024-0158", "receiptDate": "2026-10-07",
      "status": "IN_PROGRESS", "pendingLines": 4, "totalDifference": "20.000"
    }
  ]
}
```

`totalDifference` es la suma de las **diferencias absolutas** pendientes: va
positiva aunque la discrepancia sea un faltante. No confundirla con el `difference`
de la línea, que sí lleva signo.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `backend/src/test/kotlin/dev/axzy/stock/backend/application/receiving/InvoiceCloseTest.kt` | el cierre y las líneas que lo bloquean |
| `backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt` | las rutas de facturas contra la API montada |
