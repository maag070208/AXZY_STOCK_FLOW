# Surtido
> **Referencia.** Órdenes de surtido, empaque, confirmación, exportación al ERP y
> faltantes. Última verificación: 2026-10-10 (fase 7, M04 y M09 cerradas).

Implementado en capas, como el resto de la API (docs/arquitectura/api-spring.md §1):

| Capa | Archivo |
|---|---|
| Rutas | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/fulfillment/OrderController.kt` |
| Casos de uso y puertos | `api/application/src/main/kotlin/dev/axzy/stock/application/fulfillment/` |
| Reglas de la orden | `stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/fulfillment/Order.kt` |
| Persistencia | `api/adapters-persistence/.../fulfillment/JdbcOrders.kt` |
| Cableado | `api/app/src/main/kotlin/dev/axzy/stock/app/config/FulfillmentConfig.kt` |

La paginación está en [convenciones.md](convenciones.md); los códigos, en
[errores.md](errores.md).

**La confirmación se reclama con una sentencia condicional**: `UPDATE … WHERE status IN
(estados que admiten empaque)`. Solo una transacción se lleva la fila, así que dos
confirmaciones simultáneas no descuentan dos veces (S3); la que pierde recibe
`409 ORDER_ALREADY_CONFIRMED`. La lista de estados se **compone desde el dominio**
(`OrderStatus.allowsPackaging`).

## `GET|POST /v1/orders`

### Listado (`GET`)

Permiso: `order.view`. Filtros: `status` (uno de `PENDING`, `IN_PROGRESS`,
`PACKAGED`, `PENDING_TO_OUT`, `COMPLETED`, `CANCELLED`) y `q`. Paginación por
`page` y `size`.

Devuelve **solo la cabecera**. El detalle trae las líneas: la pantalla de empaque
no puede funcionar sin ellas y obligar a una segunda petición por línea sería
absurdo con órdenes de decenas de líneas.

```json
{
  "id": 30, "orderNumber": "ORD-2024-0042", "packagingDate": "2026-10-07",
  "status": "IN_PROGRESS", "customerCode": null, "concept": null,
  "converted": false, "convertedAt": null,
  "exportStatus": null, "exportAttempts": 0, "exportLastError": null,
  "totalLines": 5, "packagedLines": 2
}
```

`status` de la **orden**: `PENDING` | `IN_PROGRESS` | `PACKAGED` | `PENDING_TO_OUT`
| `COMPLETED` | `CANCELLED`.

`status` de la **línea** (`order_lines.status`), y es una unión **distinta** de la
de factura: `PENDING` | `ASSIGNED` | `SUPPLIED` | `VERIFIED` | `CANCELLED`.

**Reutilizar los estados de `InvoiceLineStatus` era un error**: `LOCATED` y
`DISCREPANCY` son del flujo de recepción y no significan nada en surtido.
Compartir la unión obligaba a la interfaz a contemplar estados imposibles, y a un
`when` exhaustivo a tener ramas que nunca se ejecutan.

### Creación (`POST`)

Permiso: `order.create`.

```json
{
  "orderNumber": "ORD-2024-0042",
  "packagingDate": "2026-10-07",
  "customerCode": null,
  "concept": null,
  "lines": [ { "productCode": "ABRAA1B", "quantity": "10.000" } ]
}
```

`orderNumber` y `packagingDate` son opcionales; si no se envían, el servidor genera
el número como `ORD-AAAA-NNNN` y usa la fecha de hoy. **201** con la orden y sus
líneas. La creación **no toca el inventario**: el stock sale al empacar y al
confirmar, que son actos con responsable.

Errores: `422 EMPTY_ORDER`, `422 PRODUCT_NOT_FOUND`, `422 INVALID_QUANTITY`.

## `GET /v1/orders/{id}`

Permiso: `order.view`. **Devuelve la orden CON sus líneas.** Es el único endpoint
del surtido que las trae.

```json
{
  "id": 30, "orderNumber": "ORD-2024-0042", "packagingDate": "2026-10-07",
  "status": "IN_PROGRESS", "customerCode": null, "concept": null,
  "converted": false, "convertedAt": null,
  "exportStatus": null, "exportAttempts": 0, "exportLastError": null,
  "totalLines": 5, "packagedLines": 2,
  "lines": [
    {
      "id": 55, "productId": 1, "productCode": "ABRAA1B",
      "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "unitMeasure": "PZA",
      "quantity": "10.000", "suppliedQuantity": "7.000",
      "supplied": false, "status": "ASSIGNED",
      "assignedUserId": 7, "assignedUserName": "Juan Pérez",
      "locationId": 2, "locationDisplayName": "04-B-C"
    }
  ]
}
```

`suppliedQuantity` es **lo ya descontado del inventario**, no «lo empacado»: es el
acumulado que el inventario ya refleja. Confirmar descuenta `quantity -
suppliedQuantity`.

**404** con `code = ORDER_NOT_FOUND`; `400 INVALID_ID`.

## `POST /v1/orders/{id}/lines/{lineId}/package`

Permiso: `order.package`.

```json
{ "quantity": "10.000", "locationId": 2 }
```

Descuenta stock y escribe `FULFILLMENT_OUT` en el kardex, en una sola transacción.
La cantidad se valida contra lo **pendiente** de la línea, no contra `quantity`:
empacar 7 de 10 dos veces sumaría 14. `locationId` es opcional; sin él se usa la
ubicación de la línea.

**200** con la orden actualizada. Si no alcanza, `422 INSUFFICIENT_STOCK` con
`details.available` — y se ofrece la opción de faltante, que es un ajuste con
origen `FULFILLMENT_SHORTAGE`, no un segundo flujo. Otros errores: `422
EXCEEDS_PENDING_QUANTITY`, `INVALID_QUANTITY`, `LOCATION_REQUIRED`,
`INVALID_TRANSITION`; `404 ORDER_LINE_NOT_FOUND`.

La idempotencia **no** se lee en esta ruta: va por cabecera en las operaciones de
inventario (véase [convenciones.md §1.5](convenciones.md)).

## `POST /v1/orders/{id}/confirm`

Permiso: `order.confirm`. **Descuenta `quantity - suppliedQuantity` de cada línea,
NO `quantity`**, y después dispara la exportación al ERP.

Lo empacado ya se descontó al empacar; volver a descontarlo completo dejaría el
inventario corto y el descuadre se confundiría con una merma. Una línea ya completa
tiene pendiente cero y no descuenta nada. Si el ERP no responde, **la confirmación
NO se revierte**: el stock ya salió y el intento queda registrado para el reintento
manual.

**200** con la orden. `409` con `code = ORDER_ALREADY_CONFIRMED` si ya estaba
confirmada: confirmar dos veces restaría dos veces y el total bajaría el doble.
`422 INSUFFICIENT_STOCK` o `INVALID_TRANSITION`.

## `POST /v1/orders/{id}/export`

Reintento manual de la exportación. Permiso: `order.convert`. **Idempotente**: la
clave es el `orderNumber` convertido; si la orden ya se exportó, esto no crea una
segunda salida y solo cuenta el intento.

```json
{ "attempts": 3, "status": "FAILED", "lastError": "El ERP no responde" }
```

`status`: `SENT` | `FAILED` | `PENDING`. **200** con el resultado del intento. `409`
con `code = ORDER_NOT_CONVERTED` si aún no se confirmó: exportar algo que no se ha
surtido mandaría al ERP una salida que no existe. `404 ORDER_NOT_FOUND`.

> **Estado de la integración.** La conexión real con el ERP **no existe**: el intento
> falla con un motivo que empieza por `ERP_NOT_CONNECTED`, el contador sube y el error
> queda guardado para el reintento (S5 y S6). No se registra como enviado —sería mentir
> en la bitácora y en el estado de la orden—; lo que falta es el adaptador
> ([lo-no-verificado.md](../guia/lo-no-verificado.md) y
> [ADR-0013](../adr/0013-ordenes-al-erp-con-reintentos.md)). La respuesta no incluye
> `nextRetryAt`.

> **Sin ruta de asignación.** `order.assign` está en el catálogo de permisos y la tabla
> tiene `assigned_user_id` y `location_id`, pero **ninguna ruta los escribe**: la
> ubicación de la línea la anota el empaque y el operador no tiene forma de asignarse
> una línea. Está anotado en el plan.

## `POST /v1/orders/{id}/shortage`

Faltante durante el surtido: delega en la regla única de ajuste, con origen
`FULFILLMENT_SHORTAGE`. Permiso: `order.package`.

```json
{ "lineId": 55, "quantity": "3.000", "reason": "SHORTAGE", "note": "..." }
```

`reason` **no tiene valor por defecto**: un faltante es un ajuste y el motivo es
obligatorio, igual que en cualquier merma (`422 REASON_REQUIRED` sin él, S8). Esta
página decía «`SHORTAGE` por defecto», y eso hacía imposible la prueba de aceptación:
el ajuste delegado nunca habría visto un motivo ausente. **200** con la orden
actualizada. Errores: `422 REASON_REQUIRED`, `422 INVALID_QUANTITY`, `422
LOCATION_REQUIRED`, `404 ORDER_LINE_NOT_FOUND`, `403 PERMISSION_DENIED` sin
`inventory.adjust_shortage`.

Duplicar la regla de ajuste aquí crearía una segunda forma de mover inventario, y
con el tiempo una exigiría aprobación y la otra no.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/FulfillmentFlowTest.kt` | S1–S8 por HTTP real: empaque, confirmación que descuenta solo lo que falta, doble confirmación, exportación sin ERP, reintento y faltante |
| `api/adapters-persistence/src/test/.../FulfillmentPersistenceTest.kt` | el presupuesto de consultas y las **dos confirmaciones simultáneas** contra la base |
| `stock-domain/src/commonTest/kotlin/dev/axzy/stock/domain/fulfillment/` | las transiciones de la orden y de la línea, sin base de datos (incluye S9: una orden exportada no se cancela) |
