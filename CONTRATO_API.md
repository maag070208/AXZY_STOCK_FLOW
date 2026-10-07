# CONTRATO DE API — Almacén Integral

**Este archivo es la fuente de verdad única del contrato HTTP.** Lo consumen el backend (que lo
implementa), la web (que genera su cliente) y el móvil (que declara sus DTOs). Si algo cambia aquí,
los tres lados se enteran.

> **Escribir este contrato antes de paralelizar el trabajo es lo que hace posible paralelizarlo.**
> Sin él, el backend, la web y el móvil inventan formas distintas de la misma respuesta, y el
> problema aparece en la integración, que es el peor sitio para descubrirlo.

---

## 1. Convenciones

### 1.1 Rutas

```
/v1/<recurso>              listado y creación
/v1/<recurso>/{id}         detalle, actualización y borrado
/v1/<recurso>/{id}/<accion>  acciones que no son CRUD
```

**Prefijo `/v1` obligatorio.** El legacy no versionaba sus rutas, así que no se podía cambiar un
contrato sin romper los clientes desplegados.

### 1.2 Códigos de estado

| Código | Cuándo | Cuerpo |
|---|---|---|
| `200` | Éxito con cuerpo | el recurso o el listado |
| `201` | Recurso creado | el recurso, con su `id` |
| `204` | Éxito sin cuerpo | vacío (borrado, cierre de sesión) |
| `400` | Petición mal formada | `ErrorResponse` |
| `401` | Sin identidad válida | `ErrorResponse`, `code = UNAUTHENTICATED` |
| `403` | Identidad sin autorización | `ErrorResponse` con el motivo |
| `404` | El recurso no existe | `ErrorResponse` |
| `409` | El estado impide la operación | `ErrorResponse` |
| `422` | Viola una regla de negocio | `ErrorResponse` con `details` por campo |
| `429` | Demasiados intentos | `ErrorResponse`, `code = RATE_LIMITED` |
| `503` | Dependencia caída | `ErrorResponse` (health) |

### 1.3 El contrato de error — es el mismo en todos los endpoints

```json
{
  "code": "POLICY_DENIED",
  "message": "No puedes aprobar tu propio conteo (segregación de funciones)",
  "details": { "campo": "motivo" }
}
```

**El cliente decide con `code`, nunca con `message`.** El mensaje se traduce y se reescribe; un
cliente que compara texto se rompe en silencio. Los códigos son estables y en `SCREAMING_SNAKE_CASE`.

Códigos de autorización, que **el cliente debe distinguir** porque la acción del usuario es distinta:

| `code` | Significado | Qué debe hacer el usuario |
|---|---|---|
| `UNAUTHENTICATED` | falta token o venció | iniciar sesión |
| `PERMISSION_DENIED` | no tiene el permiso | pedirlo a un administrador |
| `SCOPE_INSUFFICIENT` | tiene el permiso, pero no alcanza este recurso | pedir acceso a esa bodega |
| `POLICY_DENIED` | una política lo prohíbe | cambiar **cuándo** o **cómo** lo hace |
| `SEGREGATION_OF_DUTIES` | no puede aprobar lo que él mismo capturó | que lo apruebe otra persona |

Colapsar los cinco en un `403` genérico es el defecto del legacy: el usuario recibe «no autorizado»
y no sabe si pedir un permiso, esperar, o llamar a otra persona.

### 1.4 Paginación

Toda lista se pagina. El legacy devolvía **todos** los registros, y con 1.398 productos y meses de
kardex eso significa que el listado de inventario cargaba la tabla entera.

```
GET /v1/products?page=0&size=50&sort=productCode,asc&q=abra
```

```json
{
  "items": [ ... ],
  "page": 0,
  "size": 50,
  "totalItems": 1398,
  "totalPages": 28
}
```

- `page` empieza en **0**, como en Spring y en la mayoría de los clientes.
- `size` por defecto **50**, máximo **200**. Un `size` mayor se rechaza con `400`, no se recorta en
  silencio: recortar hace que el cliente crea que tiene todos los datos.
- `sort` es `campo,direccion`. Los campos admitidos se documentan por endpoint.
- `q` es la búsqueda libre.

**Dos excepciones, y son deliberadas:** `/v1/warehouses` y `/v1/label-templates` devuelven **todas**
sus filas sin sobre de paginación y sin límite de 200.

El motivo: son **catálogos cerrados y pequeños**. Habrá una o dos bodegas y ocho plantillas de
etiqueta, y la interfaz necesita las listas **completas** para poblar desplegables. Paginarlas
obligaría al cliente a pedir todas las páginas antes de poder dibujar un `select`, que es trabajo
peor y más lento que devolver ocho filas.

**El límite que lo hace seguro:** los dos requieren permisos de administración
(`location.view` / `label.template_view`) sobre un conjunto que crece solo cuando un administrador
lo decide. No son colecciones que crezcan con la operación, como el kardex o el inventario.

### 1.5 Idempotencia

Las operaciones que **mueven inventario** o **crean documentos** aceptan la cabecera
`Idempotency-Key`. Repetir la misma clave devuelve la **misma respuesta** y no repite el efecto.

Es obligatorio en: captura de línea, ubicación, ajustes, empaque, confirmación de orden y
exportación al ERP. El móvil sin señal reintenta; sin esto, un reintento duplica un movimiento.

### 1.6 Cabeceras

| Cabecera | Dirección | Para qué |
|---|---|---|
| `Authorization: Bearer <jwt>` | petición | identidad |
| `Idempotency-Key: <uuid>` | petición | deduplicar reintentos |
| `X-Request-Id: <uuid>` | ambas | correlación con la auditoría |
| `X-Client-Channel` | petición | `WEB`, `MOBILE`, `MOBILE_OFFLINE` |
| `X-Client-Timezone` | petición | IANA, p. ej. `America/Mexico_City` |

**`X-Client-Channel` no es decorativo**: la auditoría registra el canal, y `MOBILE_OFFLINE` dispara
el camino de sincronización diferida con sus dos horas.

### 1.7 Fechas y números

- **Instantes**: ISO 8601 en UTC con `Z` → `"2026-10-07T21:24:37.688Z"`.
- **Fechas operativas** (sin hora): `"2026-10-07"`. Para el turno de noche, la fecha de negocio, no
  la del reloj.
- **Cantidades**: número decimal con **3 decimales**, nunca cadena → `100.000`, `0.500`.
  El legacy mezclaba `Decimal` de Prisma con `number` de JSON, y de ahí venían los descuadres de
  redondeo.
- **Identificadores**: número entero. Las claves de negocio (`productCode`) son cadena.

---

## 2. Autenticación

### `POST /v1/auth/login`

```json
{ "username": "jperez", "password": "..." }
```

**200:**

```json
{
  "accessToken": "eyJ...",
  "refreshToken": "8Kd3...",
  "expiresIn": 900,
  "user": {
    "id": 7, "username": "jperez", "name": "Juan Pérez",
    "roles": ["JEFE_BODEGA"],
    "permissions": ["invoice.capture", "invoice.approve", "inventory.move"],
    "scopes": { "invoice.approve": "AREA", "inventory.move": "AREA" },
    "warehouses": [1],
    "mustChangePassword": false
  }
}
```

**Errores:**

| `code` | Situación |
|---|---|
| `INVALID_CREDENTIALS` | usuario o contraseña incorrectos — **el mismo código para ambos**, para no permitir enumerar usuarios |
| `ACCOUNT_DISABLED` | la cuenta está desactivada |
| `ACCOUNT_LOCKED` | bloqueada por intentos fallidos; `details.until` con el instante |
| `RATE_LIMITED` | demasiados intentos desde esta IP |

`expiresIn` en **segundos**, que es lo que esperan las bibliotecas de cliente.

### `POST /v1/auth/refresh`

```json
{ "refreshToken": "8Kd3..." }
```

**200:** el mismo cuerpo que el login, con tokens **nuevos** (rotación).

**401 con `code = TOKEN_REUSED`:** se detectó reuso y **se revocó toda la familia**. El cliente debe
descartar sus tokens y pedir credenciales. **Es un código distinto de `INVALID_CREDENTIALS`** porque
la interfaz debe decir algo diferente: «tu sesión se cerró por seguridad».

### `POST /v1/auth/logout`

**204.** Revoca el refresh token presentado y su familia.

### `GET /v1/auth/me`

Devuelve el objeto `user` de arriba. Es lo que la web usa al recargar para reconstruir los permisos
sin volver a autenticar.

### `POST /v1/auth/change-password`

```json
{ "currentPassword": "...", "newPassword": "..." }
```

**204.** **Invalida todas las sesiones**, incluida la actual: el cliente debe volver a entrar.

---

## 3. Catálogo

### `GET /v1/products`

Lista paginada. `sort` admitido: `productCode`, `name`, `createdAt`.

```json
{
  "items": [
    {
      "id": 1, "productCode": "ABRAA1B",
      "name": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "description": null,
      "unitMeasure": "PZA",
      "externalId": 1,
      "active": true,
      "stock": 5193.000
    }
  ],
  "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
}
```

`stock` es el **total derivado** de `stock_by_location` + `stock_in_transit`. Se incluye en el
listado porque es el dato que más se consulta y evitar una petición por producto importa con 1.398.

### `POST /v1/products`

```json
{ "productCode": "ABRAA1B", "name": "...", "description": null, "unitMeasure": "PZA" }
```

**201.** `409` con `code = DUPLICATE_PRODUCT_CODE` si ya existe.

### `GET|PUT|DELETE /v1/products/{id}`

El `DELETE` es **lógico** (`active = false`) y exige `reason` en el cuerpo. Un producto con stock no
se puede desactivar sin forzarlo explícitamente: `409` con `code = PRODUCT_HAS_STOCK` y
`details.stock`.

### `POST /v1/products/import`

`multipart/form-data` con `file` (CSV o XLSX) y `dryRun=true|false`.

**Siempre `200`, nunca `201`, incluso con `dryRun=false`.** No es una creación de recurso: es una
**operación sobre una colección** que produce un informe. El `201` implica que el cuerpo es el
recurso creado y que su `Location` apunta a él; aquí no hay un recurso único que devolver. Por eso
§1.2 no aplica y el ejemplo es correcto.

**200 con `dryRun=true`:** el informe, **sin escribir nada**.

```json
{
  "dryRun": true,
  "totalRows": 1398,
  "accepted": 1398,
  "rejected": 0,
  "totalStock": 10914130.000,
  "zeroStockCount": 334,
  "unitMeasures": ["PZA"],
  "rejections": [
    { "rowNumber": 42, "productCode": "mal", "reason": "Código de producto inválido" }
  ]
}
```

**`rejections` con el número de fila es obligatorio**: sin él, el usuario no puede corregir el
archivo. El importador del dominio ya lo produce (`CatalogImporter`).

### `GET|POST /v1/locations`, `GET|PUT|DELETE /v1/locations/{id}`

```json
{
  "id": 2, "warehouseId": 1, "warehouseCode": "PRINCIPAL",
  "hall": "04", "code": "B", "position": "C", "levels": 3,
  "kind": "SHELF", "active": true, "displayName": "04-B-C"
}
```

`kind` es `SHELF` o `RECEIVING`. **La ubicación de recepción es real, con `id` real** — no la
pseudo-ubicación `ADMIN` con identificador ficticio que el legacy devolvía hardcodeada y que podía
colisionar con la ubicación 1.

**`displayName` es DERIVADO, no un campo libre.** El servidor lo compone con
`hall-code-position` para las ubicaciones `SHELF` (`"04-B-C"`). Para la de recepción devuelve
**`"RECEPCION"`**, que es su `code`, no `"RECEPCION-RECEPCION-RECEPCION"`.

La regla: **el cliente nunca compone el nombre visible, lo recibe.** Así, si mañana cambia el formato
de las etiquetas de estante, no hay que tocar el backend, la web y el móvil a la vez. En el kardex,
`fromLocationDisplayName` y `toLocationDisplayName` usan la misma regla; es lo que explica que el
ejemplo muestre `"RECEPCION"`.

### `GET /v1/warehouses`

```json
{ "items": [{ "id": 1, "code": "PRINCIPAL", "name": "Bodega principal", "isDefault": true, "active": true }] }
```

---

## 4. Inventario

### `GET /v1/inventory/stock`

Existencias por producto y ubicación.

```
GET /v1/inventory/stock?productId=1&locationId=2&warehouseId=1&onlyWithStock=true
```

```json
{
  "items": [
    { "productId": 1, "productCode": "ABRAA1B", "locationId": 2,
      "locationDisplayName": "04-B-C", "warehouseId": 1, "quantity": 100.000 }
  ],
  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
}
```

### `GET /v1/inventory/kardex`

```
GET /v1/inventory/kardex?productCode=ABRAA1B&from=2026-10-01&to=2026-10-31&moveType=RECEIPT_IN
```

```json
{
  "items": [
    {
      "id": 501, "movedAt": "2026-10-07T21:24:37.688Z",
      "productCode": "ABRAA1B", "moveType": "RECEIPT_IN",
      "units": 100.000, "quantityAfter": 1100.000,
      "fromLocationId": null, "fromLocationDisplayName": null,
      "toLocationId": 2, "toLocationDisplayName": "RECEPCION",
      "container": "CONT-01", "orderNumber": null,
      "adjustmentReason": null, "userId": 7, "userName": "Juan Pérez",
      "note": null
    }
  ],
  "page": 0, "size": 50, "totalItems": 1, "totalPages": 1
}
```

**El kardex es append-only y no se puede modificar por API.** No existe `PUT` ni `DELETE`. Las
correcciones son movimientos nuevos.

### `POST /v1/inventory/adjustments`

**La regla única `AdjustStock`.** Los cuatro orígenes del legacy se unifican aquí.

```json
{
  "productId": 1,
  "locationId": 2,
  "quantity": 5.000,
  "direction": "OUT",
  "reason": "DAMAGE",
  "origin": "DAMAGE_REPORT",
  "note": "Caja rota en el pasillo 3",
  "idempotencyKey": "..."
}
```

- `direction`: `IN` | `OUT`.
- `reason`: `SHORTAGE` | `DAMAGE` | `THEFT` | `SUPPLIER_ERROR` | `COUNT_ERROR` |
  `RETURN_TO_SUPPLIER` | `EXPIRED` | `OTHER`. **Obligatorio siempre.**
- `origin`: `INVOICE_DISCREPANCY` | `FULFILLMENT_SHORTAGE` | `MANUAL_COUNT` | `DAMAGE_REPORT`.

**201** con el movimiento creado. Errores: `422` `REASON_REQUIRED` si falta el motivo,
`422` `INSUFFICIENT_STOCK` con `details.available` si no hay existencia.

**Un ajuste por encima del umbral exige aprobación** (`inventory.approve_adjustment`) y queda en
`PENDING_APPROVAL`: el `201` lo indica con `"status": "PENDING_APPROVAL"`.

### `POST /v1/inventory/locate`

Ubicar mercancía: traslado de la ubicación de recepción al estante.

```json
{ "invoiceLineId": 88, "toLocationId": 2, "quantity": 100.000, "idempotencyKey": "..." }
```

**El total NO cambia**: es un traslado. El legacy mutaba el registro de recepción del kardex y por eso
su libro dejaba de ser un libro.

### `POST /v1/inventory/move`

Traslado entre estantes de la misma bodega.

```json
{ "productId": 1, "fromLocationId": 2, "toLocationId": 3, "quantity": 10.000, "idempotencyKey": "..." }
```

`422` `SAME_SOURCE_AND_DESTINATION` si origen y destino coinciden, `422` `INSUFFICIENT_STOCK` si no
hay existencia en el origen.

---

## 5. Recepción

### `GET|POST /v1/invoices`

```json
{
  "id": 15, "invoiceNumber": "F-2024-0158", "receiptDate": "2026-10-07",
  "invoiceType": "NATIONAL", "status": "IN_PROGRESS",
  "customerCode": null, "concept": null,
  "totalLines": 12, "locatedLines": 8, "pendingLines": 4,
  "createdAt": "2026-10-07T21:24:37.688Z"
}
```

`status`: `PENDING` | `IN_PROGRESS` | `PENDING_TO_LOCATE` | `COMPLETED` | `CANCELLED`.

### `GET /v1/invoices/{id}`

Incluye las líneas:

```json
{
  "id": 15, "invoiceNumber": "F-2024-0158", "status": "IN_PROGRESS",
  "lines": [
    {
      "id": 88, "productId": 1, "productCode": "ABRAA1B",
      "productName": "ABRAZADERA DE ARRANQUE 1-5/8\"",
      "container": "CONT-01", "unitMeasure": "PZA",
      "quantity": 100.000, "boxQuantity": 2.000, "unitsPerBox": 50.000,
      "physicalQuantity": 80.000, "physicalBoxQuantity": 1.600,
      "locatedQuantity": 0.000,
      "difference": -20.000,
      "status": "DISCREPANCY",
      "capturedBy": 7, "capturedByName": "Juan Pérez",
      "capturedAt": "2026-10-07T21:00:00Z",
      "approvedBy": null, "approvedByName": null, "approvedAt": null,
      "adjustmentReason": null,
      "locationId": null, "locationDisplayName": null
    }
  ]
}
```

**`difference` la calcula el servidor** y es `physicalQuantity - quantity`. En el legacy estaba
declarada en el modelo, el DTO, el mapper y la respuesta, y **siempre valía 0**. Aquí es una columna
generada de la base: es imposible que se desincronice.

`status` de línea: `PENDING` | `CAPTURED` | `DISCREPANCY` | `PENDING_APPROVAL` | `APPROVED` |
`ASSIGNED` | `LOCATED` | `CANCELLED`.

### `POST /v1/invoices/{id}/lines/{lineId}/capture`

```json
{ "physicalQuantity": 80.000, "physicalBoxQuantity": 1.600, "idempotencyKey": "..." }
```

Calcula `difference` y decide el estado: `CAPTURED` si coincide, `DISCREPANCY` si no.

**Una línea en `DISCREPANCY` no se puede ubicar.** El `POST /inventory/locate` responde `409` con
`code = DISCREPANCY_NOT_APPROVED`. Es la regla B1.

### `POST /v1/invoices/{id}/approve`

**La ÚNICA ruta de aprobación. No existe una variante por línea.**

```json
{ "adjustmentReason": "SUPPLIER_ERROR", "note": "..." }
```

Requiere `invoice.approve`. **La aprobación es por factura, no por línea** (decisión R-86).

**Por qué no hay ruta por línea.** Una factura llega con varias líneas con discrepancia, y
aprobarlas de una en una obliga al supervisor a repetir el mismo juicio N veces y deja la factura en
un estado intermedio donde unas líneas están aprobadas y otras no. El supervisor revisa la factura
completa y decide una vez. Aprobar por línea era además lo que invitaba a **aprobar lo propio sin
darse cuenta**: con una sola acción sobre el documento, la segregación de funciones es evidente.

**Respuesta 200:** la factura completa con sus líneas ya en `APPROVED`.

**Errores:**
- `409` `NO_PENDING_APPROVAL` si no hay ninguna línea por aprobar.
- `422` `REASON_REQUIRED` si falta `adjustmentReason`.
- `422` `SEGREGATION_OF_DUTIES` si quien aprueba fue quien capturó **alguna** de las líneas
  pendientes. El mensaje indica **cuáles**, para que el supervisor sepa qué reasignar.

La base impone la regla con un `CHECK`: es imposible que una línea llegue a `APPROVED` sin
`approved_by`. No depende de que el código se acuerde de validarlo.

### `POST /v1/invoices/{id}/reject-discrepancy`, `POST /v1/invoices/{id}/close`

`close` responde `409` con `code = HAS_PENDING_LINES` y `details.pendingLines` si quedan líneas.

### `GET /v1/invoices/pending-approval`

Bandeja de pendientes. **Pantalla nueva: el legacy no la tenía**, y sin ella una discrepancia se
quedaba bloqueada sin que nadie supiera que había que aprobarla.

---

## 6. Surtido

### `GET|POST /v1/orders`

```json
{
  "id": 30, "orderNumber": "ORD-2024-0042", "packagingDate": "2026-10-07",
  "status": "IN_PROGRESS", "customerCode": null, "concept": null,
  "converted": false, "convertedAt": null,
  "exportStatus": null, "exportAttempts": 0, "exportLastError": null,
  "totalLines": 5, "packagedLines": 2
}
```

`status` de la **orden**: `PENDING` | `IN_PROGRESS` | `PACKAGED` | `PENDING_TO_OUT` | `COMPLETED` | `CANCELLED`.

`status` de la **línea** (`order_lines.status`), y es una unión **distinta** de la de factura:
`PENDING` | `ASSIGNED` | `SUPPLIED` | `VERIFIED` | `CANCELLED`.

**Antes reutilizaba los estados de `InvoiceLineStatus` y era un error**: `LOCATED` y `DISCREPANCY`
son del flujo de recepción y no significan nada en surtido. Compartir la unión obligaba a la interfaz
a contemplar estados imposibles, y a un `when` exhaustivo a tener ramas que nunca se ejecutan.

### `POST /v1/orders/{id}/lines/{lineId}/package`

```json
{ "quantity": 10.000, "locationId": 2, "idempotencyKey": "..." }
```

Descuenta stock y escribe `FULFILLMENT_OUT` en el kardex. Si no alcanza,
`422` `INSUFFICIENT_STOCK` con `details.available` — **y se ofrece la opción de faltante**, que es
un ajuste con origen `FULFILLMENT_SHORTAGE`, no un segundo flujo.

### `POST /v1/orders/{id}/confirm`

**Descuenta el stock de las líneas pendientes y dispara la exportación al ERP.**

`409` con `code = ORDER_ALREADY_CONFIRMED` si ya estaba confirmada.

### `POST /v1/orders/{id}/export`

Reintento manual. **Idempotente**: la clave es el `orderNumber` convertido.

```json
{ "attempts": 3, "status": "FAILED", "lastError": "El ERP no responde", "nextRetryAt": "..." }
```

`200` con el resultado del intento. `409` con `code = ORDER_NOT_CONVERTED` si aún no se confirmó.

### `POST /v1/orders/{id}/shortage`

Faltante durante el surtido: delega en la regla única de ajuste.

```json
{ "lineId": 55, "quantity": 3.000, "reason": "SHORTAGE", "note": "..." }
```

---

## 7. Traslados

Los tres estados de §2.6: `DRAFT` → `DISPATCHED` → `IN_TRANSIT` → `RECEIVED`, con `CANCELLED`.

```json
{
  "id": 9, "code": "TR-2026-0001",
  "fromWarehouseId": 1, "fromWarehouseCode": "PRINCIPAL",
  "toWarehouseId": 2, "toWarehouseCode": "SUCURSAL",
  "status": "IN_TRANSIT",
  "lineCount": 3, "totalUnits": 150.000,
  "dispatchedBy": 7, "dispatchedAt": "...", "receivedBy": null, "receivedAt": null
}
```

### `POST /v1/transfers`, `POST /v1/transfers/{id}/dispatch`, `POST /v1/transfers/{id}/receive`, `POST /v1/transfers/{id}/cancel`

**Invariante que se verifica por test:** `Σ stock_by_location + Σ stock_in_transit` **se conserva en
cada transición**. La mercancía en el camión **sigue contando** en el inventario: si se modelara como
dos movimientos instantáneos, el total bajaría durante el viaje y los reportes mentirían.

### `GET /v1/transfers/in-transit`

Traslados despachados y no recibidos, con su responsable y los días transcurridos.
**Un traslado nunca recibido es una deuda visible, no un dato que se pierde.**

---

## 8. Etiquetas

### `GET|POST /v1/label-templates`, `GET|PUT /v1/label-templates/{id}`

```json
{
  "id": 1, "code": "receipt", "name": "Recibo de mercancía",
  "description": null, "size": "4x6", "isActive": true,
  "fieldSchema": {
    "productCode": { "type": "string", "label": "Código", "required": true },
    "name": { "type": "string", "label": "Nombre", "required": true },
    "quantity": { "type": "number", "label": "Cantidad", "required": false }
  }
}
```

`zpl` **no se devuelve en el listado** (es grande y no se necesita): solo en el detalle.

### `POST /v1/labels/preview`

Devuelve el ZPL resuelto **sin imprimir**. Para web y móvil.

```json
{ "templateId": 1, "data": { "productCode": "ABRAA1B", "name": "...", "quantity": 100 } }
```

**200:**

```json
{ "zpl": "^XA...^XZ", "preview": { "widthMm": 101.6, "heightMm": 152.4, "copies": 1 } }
```

### `POST /v1/labels/print`

```json
{ "templateId": 1, "copies": 2, "data": [ { "productCode": "ABRAA1B" } ] }
```

**202 Accepted** con `jobIds`. **El trabajo queda `PENDING` hasta que la impresora confirma.**

```json
{ "jobIds": [501, 502], "status": "PENDING" }
```

### `GET /v1/labels/jobs/{id}`, `GET /v1/labels/jobs?status=FAILED`

```json
{
  "id": 501, "templateCode": "receipt", "copies": 2,
  "status": "PRINTED", "attempts": 1, "lastError": null,
  "printedAt": "2026-10-07T21:30:00Z", "createdAt": "2026-10-07T21:29:50Z"
}
```

`status`: `PENDING` | `SENT` | `PRINTED` | `FAILED`. **Una etiqueta no se marca impresa hasta que la
impresora confirma.** El legacy la marcaba sin esperar la respuesta, así que si la Zebra estaba
apagada, la etiqueta se perdía para siempre.

### `POST /v1/labels/jobs/{id}/reprint`

Exige motivo. Genera un **trabajo nuevo** enlazado al original, no reutiliza el registro: la
trazabilidad de una reimpresión exige saber quién la pidió y por qué.

### `PATCH /v1/print-jobs/{id}` — **integración con la impresora**

**Es la única ruta que no vive bajo `/v1/labels`**, y aun así cuelga de un recurso: es la
actualización del estado de UN trabajo de impresión.

Antes estaba como `PATCH /v1/prints`, que era **un error mío de diseño**: no colgaba de ningún
recurso, usaba un verbo que no describía el efecto, y era imposible de descubrir leyendo la
documentación de etiquetas.

Es la llamada que hace la Zebra. **No exige JWT de usuario, sino credencial de servicio**
(`service_api_keys`, cabecera `X-Service-Key`). El legacy tenía este endpoint **abierto sin ninguna
credencial**, así que cualquiera en la red podía marcar trabajos como impresos.

**Errores:** `401` `INVALID_SERVICE_KEY`, `404` si el trabajo no existe, `409` si el trabajo ya está
en `PRINTED` — una etiqueta no se imprime dos veces por un reintento de la impresora.

```json
{ "jobId": 501, "status": "PRINTED", "error": null }
```

---

## 9. Reportes

Todos devuelven el mismo sobre de paginación, salvo las exportaciones.

### `GET /v1/reports/inventory`

Existencias por producto y ubicación, con totales.

```json
{
  "items": [ { "productCode": "ABRAA1B", "name": "...", "locationDisplayName": "04-B-C",
               "quantity": 100.000, "warehouseCode": "PRINCIPAL" } ],
  "totals": { "distinctProducts": 1398, "totalUnits": 10914130.000 },
  "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
}
```

### `GET /v1/reports/kardex/export?format=xlsx|csv`

Devuelve el archivo con `Content-Disposition: attachment`. **Los filtros son los mismos que los del
listado**: si el usuario ve 200 filas filtradas y exporta, debe recibir esas 200.

### `GET /v1/reports/movements-summary?from=&to=&groupBy=day|week|month|product|location`

### `GET /v1/reports/kardex-vs-projection`

**El reporte de reconciliación.** Compara lo que dice el ledger con lo que dicen las proyecciones.

```json
{
  "checkedAt": "...", "productsChecked": 1398, "divergences": 0,
  "items": [ { "productCode": "ABRAA1B", "ledgerNet": 5193.000,
               "projected": 5193.000, "difference": 0.000 } ]
}
```

En el legacy esta comprobación no existía, y el descuadre solo se descubría con un conteo físico.

---

## 10. Salud

### `GET /v1/health` — público, sin autenticación

```json
{
  "status": "UP", "schemaVersion": "4", "version": "1.0.0",
  "database": { "status": "UP", "latencyMs": 3, "error": null }
}
```

`503` si `status = DOWN`. **`schemaVersion` es información de diagnóstico**: si no se puede leer, el
estado sigue siendo `UP` y se informa del motivo en `database.error`.

---

## 11. Permisos por endpoint

Tabla de referencia. La clave es `recurso.accion` en minúsculas; el alcance se resuelve por RBAC con
su `PermissionScope` (`NONE`/`OWN`/`AREA`/`ALL`).

| Endpoint | Permiso | Alcance mínimo |
|---|---|---|
| `POST /v1/auth/*` | — | público |
| `GET /v1/auth/me` | — | autenticado |
| `GET /v1/products` | `product.view` | `ALL` |
| `POST /v1/products` | `product.create` | `ALL` |
| `PUT /v1/products/{id}` | `product.edit` | `ALL` |
| `DELETE /v1/products/{id}` | `product.delete` | `ALL` |
| `POST /v1/products/import` | `product.import` | `ALL` |
| `GET /v1/locations` | `location.view` | `ALL` |
| `POST /v1/locations` | `location.create` | `ALL` |
| `GET /v1/inventory/stock` | `inventory.view` | `AREA` |
| `GET /v1/inventory/kardex` | `inventory.view` | `AREA` |
| `POST /v1/inventory/adjustments` | `inventory.adjust_*` | `AREA` |
| `POST /v1/inventory/locate` | `invoice.locate` | `AREA` |
| `POST /v1/inventory/move` | `inventory.move` | `AREA` |
| `GET /v1/invoices` | `invoice.view` | `AREA` |
| `POST /v1/invoices` | `invoice.create` | `AREA` |
| `POST /v1/invoices/{id}/lines/{lineId}/capture` | `invoice.capture` | `AREA` |
| `POST /v1/invoices/{id}/approve` | `invoice.approve` | `AREA` |
| `GET /v1/invoices/pending-approval` | `invoice.view` | `AREA` |
| `POST /v1/orders/{id}/confirm` | `order.confirm` | `AREA` |
| `POST /v1/orders/{id}/export` | `order.convert` | `AREA` |
| `POST /v1/transfers/{id}/dispatch` | `transfer.dispatch` | `AREA` |
| `POST /v1/labels/print` | `label.print` | `ALL` |
| `PATCH /v1/prints` | — | credencial de servicio |
| `GET /v1/reports/kardex/export` | `report.export` | `ALL` |
| `GET /v1/audit/log` | `audit.view` | `ALL` |

**Ningún endpoint queda sin permiso o sin credencial.** El legacy tenía 7 permisos sembrados que
ninguna ruta exigía: parecía que había una restricción y no la había.

---

## 12. Lo que NO existe en esta API, y por qué

| Ruta del legacy | Estado | Motivo |
|---|---|---|
| `PUT /kardex/:id`, `DELETE /kardex/:id` | **No existe** | El ledger es append-only. Las correcciones son movimientos nuevos. |
| `GET /get-location-content` | **No existe** | Se reemplaza por `GET /v1/inventory/stock`, que lee el saldo real en lugar de recalcularlo desde movimientos mutados. |
| Rutas sin `/v1` | **No existen** | El contrato debe poder evolucionar. |
| `POST /prints` (abierto, sin credencial) | **`PATCH /v1/prints` con credencial de servicio** | Mismo consumidor, sin endpoint abierto. |
| Escritura directa en `store.stock` | **No existe** | `product_stock` es una proyección derivada; nunca se escribe por API. |
