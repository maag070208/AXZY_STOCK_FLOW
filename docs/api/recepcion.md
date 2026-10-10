# Recepción
> **Referencia.** Facturas de recepción, captura del conteo físico, aprobación y
> rechazo de discrepancias, cierre y bandeja de pendientes.
> Última verificación: 2026-10-10 (fase 6, M03: facturas, captura y decisiones).

Implementado en capas, como el resto de la API (docs/arquitectura/api-spring.md §1):

| Capa | Archivo |
|---|---|
| Rutas | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/receiving/InvoiceController.kt` |
| Casos de uso y puertos | `api/application/src/main/kotlin/dev/axzy/stock/application/receiving/` |
| Reglas de la línea | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/receiving/InvoiceLine.kt` |
| Persistencia | `api/adapters-persistence/.../receiving/JdbcInvoices.kt` |
| Cableado | `api/app/src/main/kotlin/dev/axzy/stock/app/config/ReceivingConfig.kt` |

La paginación está en [convenciones.md](convenciones.md); los códigos, en
[errores.md](errores.md).

**Ubicar** es `POST /v1/inventory/locate` con `invoiceLineId`
([inventario.md](inventario.md#ubicar-mercancía-de-una-recepción)): es lo que completa
la regla B1 —una línea con diferencia sin aprobar no se puede ubicar— y lo que lleva la
línea a `LOCATED` con su `locationDisplayName`.

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

`unitsPerBox` es obligatorio: sin él no se puede comprobar que el empaque de la factura
cuadre (`BOXES_DO_NOT_DIVIDE_EVENLY` si 90 unidades no son cajas enteras de 50) ni que
un conteo sea coherente. `unitMeasure` es opcional: si no viene, se usa la del producto
del catálogo.

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

**Contar mete la mercancía en `RECEPCION`.** El conteo es el momento en que el almacén toma
posesión de lo que de verdad llegó —lo contado, no lo facturado—, y el movimiento es un
`RECEIPT_IN` por la **diferencia** entre lo contado y lo ya ingresado por esa línea. Si lo
contado es **menos** que lo ya ingresado, la diferencia sale con un ajuste por **error de
conteo**: el conteo anterior estaba alto, y decirlo con el motivo correcto permite auditar
por qué bajó el inventario.

Lo ya ingresado vive en la línea (`received_quantity`, V15). Es lo que hace que un recuento
no sume dos veces: una línea con discrepancia que el supervisor descarta vuelve a `PENDING`
y se recuenta.

**Y la captura se reclama antes de mover.** Como la entrada es la *diferencia* contra lo ya
ingresado, dos capturas simultáneas de la misma línea leerían el mismo saldo e ingresarían la
misma diferencia dos veces: el stock se duplicaría. La reclamación es una sentencia condicional
sobre `received_quantity` —la primera gana y la segunda cambia cero filas— y la que pierde
responde `409 LINE_CHANGED` sin escribir ningún movimiento.

Permiso: `invoice.capture`.

```json
{ "physicalQuantity": 80.000, "physicalBoxQuantity": 1.600 }
```

Calcula `difference` y decide el estado: `CAPTURED` si coincide, `DISCREPANCY` si
no. **200** con la **línea** capturada (no la factura).

`physicalBoxQuantity` es una **cuenta entera de cajas**, no el equivalente decimal del
legacy: contar 80 piezas con cajas de 50 son «1 caja y 30 sueltas», y `80/50 = 1.6` era
un número derivado, no un conteo. Un valor con fracción responde `422 INVALID_QUANTITY`
en lugar de redondearse a espaldas del cliente.

Errores: `422 INVALID_QUANTITY`, `422 INCONSISTENT_BOX_COUNT`, `422 FIELD_REQUIRED` si
falta la cantidad o las cajas contadas, `409 LINE_ALREADY_RESOLVED` si la línea ya está
aprobada o ubicada, `404 LINE_NOT_FOUND`, `400 INVALID_ID`.

## `PUT /v1/invoices/{id}/lines/{lineId}/assign`

Asigna la línea a quien la va a contar. Permiso: `invoice.assign`.

```json
{ "userId": 7 }
```

**`userId` es opcional**: sin él, quien llama **se asigna a sí mismo**, que es el caso diario del
capturista. Con él, un supervisor reparte el conteo de un camión entre varias personas.

- **Solo desde `PENDING`**: una línea ya contada responde `409 INVALID_TRANSITION`. El conteo
  tiene responsable (`capturedBy`) y cambiarlo después sería reescribir quién hizo el trabajo;
  una línea en `DISCREPANCY` espera una decisión, no un capturista.
- Si otra persona contó la línea entre la lectura y la escritura, responde `409 LINE_CHANGED` en
  vez de asignar trabajo ya hecho.
- `422 ASSIGNEE_NOT_FOUND` si el usuario no existe; `404 INVOICE_NOT_FOUND` o
  `404 INVOICE_LINE_NOT_FOUND` si la factura o la línea no existen.

> **Antes no existía.** La columna `assigned_user_id` está en la base desde la primera migración
> —y la consulta la leía— pero **ninguna ruta la escribía**: el estado `ASSIGNED` decía «alguien
> la tiene» sin decir quién. Corregido el 2026-10-10.

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
- **`403`** `SEGREGATION_OF_DUTIES` si quien aprueba fue quien capturó **alguna** de las
  líneas pendientes. El mensaje y `details.lines` (los identificadores, separados por
  comas) indican **cuáles**, para que el supervisor sepa qué reasignar. Es `403` y no
  `422` porque pertenece a la familia de autorización ([errores.md](errores.md)).

La base impone la regla con un `CHECK`: es imposible que una línea llegue a
`APPROVED` sin `approved_by`. No depende de que el código se acuerde de validarlo.

## `POST /v1/invoices/{id}/reject-discrepancy`

**La otra salida de la regla B1.** Permiso: `invoice.reject`. El cuerpo comparte
forma con la aprobación —motivo obligatorio y nota opcional— porque es la misma
decisión tomada al revés.

```json
{ "adjustmentReason": "SUPPLIER_ERROR", "note": "..." }
```

Aprobar **acepta** la diferencia y la mercancía se queda; rechazar **no la acepta**.
Sin esta salida, la única opción ante una discrepancia era aceptarla, y un supervisor
que no puede rechazar acaba aprobando para desbloquear la factura.

**Rechazar devuelve la línea a `PENDING` y limpia el conteo**, no la cancela: el
número que vio el supervisor puede ser el correcto y lo que falta es un reconteo.
Cancelar dejaría mercancía en el almacén que nadie contó y una línea muerta que ya no
se puede capturar. Es la decisión que implementa
`InvoiceLine.rejectDiscrepancy` en el dominio, y esta página decía «cancela las
líneas»: se corrigió aquí.

**200** con la factura y sus líneas de vuelta en `PENDING`. Errores: `409
NO_PENDING_APPROVAL`, `422 REASON_REQUIRED`, `403 SEGREGATION_OF_DUTIES`,
`400 INVALID_ID`.

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
| `api/app/src/test/kotlin/dev/axzy/stock/app/ReceivingFlowTest.kt` | R2–R6 y R8 por HTTP real: alta, captura con y sin diferencia, aprobación por factura, rechazo, cierre, bandeja y permisos por ruta |
| `stock-domain/src/commonTest/kotlin/dev/axzy/stock/domain/receiving/InvoiceLineStateMachineTest.kt` | las transiciones de la línea, sin base de datos |
| `api/app/src/test/kotlin/dev/axzy/stock/app/ReceivingFlowTest.kt` (R1 y R7) | que una discrepancia sin aprobar bloquee la ubicación sin tocar el inventario, y que ubicar deje la línea `LOCATED` con el total intacto |

Las ocho pruebas del flujo están en verde: R1 y R7 cruzan con el módulo de inventario
—la ruta que ubica es la suya— y las otras seis viven en las rutas de facturas.
