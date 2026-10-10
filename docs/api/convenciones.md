# Convenciones de la API
> **Normativo.** Cómo se construyen las rutas, los estados, la paginación, la
> idempotencia, las cabeceras y los formatos de esta API. Manda sobre el contrato
> de cada recurso. Última verificación: 2026-10-10.

Este documento define las reglas transversales. El contrato por recurso está en
[contrato.md](contrato.md); el cuerpo de error y su catálogo, en
[errores.md](errores.md).

## 1.1 Rutas

```
/v1/<recurso>                listado y creación
/v1/<recurso>/{id}           detalle, actualización y borrado
/v1/<recurso>/{id}/<accion>  acciones que no son CRUD
```

**Prefijo `/v1` obligatorio.** El legacy no versionaba sus rutas, así que no se
podía cambiar un contrato sin romper los clientes desplegados.

La única ruta que no cuelga de `/v1/<recurso>` con esa forma es
`PATCH /v1/print-jobs/{id}`, y aun así cuelga de un recurso: es la actualización
del estado de un trabajo de impresión (véase [etiquetas.md](etiquetas.md)).

## 1.2 Códigos de estado

| Código | Cuándo | Cuerpo |
|---|---|---|
| `200` | Éxito con cuerpo | el recurso o el listado |
| `201` | Recurso creado | el recurso, con su `id` |
| `202` | Aceptado, pendiente de confirmación | el trabajo o el ajuste con `status` |
| `204` | Éxito sin cuerpo | vacío (borrado, cierre de sesión) |
| `400` | Petición mal formada | `ErrorResponse` |
| `401` | Sin identidad válida | `ErrorResponse`, `code = UNAUTHENTICATED` |
| `403` | Identidad sin autorización | `ErrorResponse` con el motivo |
| `404` | El recurso no existe | `ErrorResponse` |
| `409` | El estado impide la operación | `ErrorResponse` |
| `422` | Viola una regla de negocio | `ErrorResponse` con `details` por campo |
| `429` | Demasiados intentos | `ErrorResponse`, `code = RATE_LIMITED` |
| `503` | Dependencia caída | `ErrorResponse` (health) |

La traducción de excepción a estado vive en
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/http/ErrorResponses.kt`, y el
manejador que la aplica, en `.../http/GlobalErrorHandler.kt`.
Un error inesperado se responde como `500` con `code = INTERNAL_ERROR` sin filtrar
el detalle interno.

**Distinguir `401` de `403` no es cosmético.** Ante un `401` el cliente manda al
usuario a iniciar sesión; ante un `403`, a pedir un permiso. Devolver `403` por
falta de token manda a iniciar sesión a quien ya tiene sesión abierta.

## 1.4 Paginación

Toda lista se pagina. El legacy devolvía **todos** los registros, y con un
catálogo de miles de productos y meses de kardex eso significa que el listado
cargaba la tabla entera.

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
- `size` por defecto **50**, máximo **200**. Un `size` mayor se rechaza con `400`,
  no se recorta en silencio: recortar hace que el cliente crea que tiene todos los
  datos.
- `sort` es `campo,direccion`. Los campos admitidos se documentan por endpoint.
- `q` es la búsqueda libre.
- **`from` y `to` son fechas operativas (`AAAA-MM-DD`) y se interpretan en la zona del
  negocio** (`stock.business-zone`), no en UTC: `to` incluye el día entero del almacén.
  Una fecha mal formada responde `400 INVALID_FILTER_DATE` y un rango invertido,
  `400 INVALID_FILTER_RANGE`.

Un `page` negativo o un `size` fuera de `1..200` responden `400 INVALID_PAGINATION`;
un `sort` no admitido, `400 INVALID_SORT`. La validación es una sola en toda la
API: `PageRequest.analyze` en
`api/application/src/main/kotlin/dev/axzy/stock/application/shared/Pagination.kt`.

**Excepciones deliberadas.** Devuelven **todas** sus filas, sin sobre de
paginación y sin límite de 200:

| Endpoint | Permiso | Motivo |
|---|---|---|
| `GET /v1/warehouses` | `warehouse.view` | catálogo cerrado y diminuto que la interfaz necesita completo |
| `GET /v1/label-templates` | `label.template_view` | conjunto de plantillas que crece solo cuando un administrador lo decide |
| `GET /v1/permissions` | `role.manage` | es la matriz de la pantalla de roles; paginarla obligaría a pedir todas las páginas para dibujar un selector |

El motivo es siempre el mismo: son **catálogos cerrados y pequeños**. Paginarlos
obligaría al cliente a pedir todas las páginas antes de poder dibujar un
`select`, que es trabajo peor y más lento que devolver las filas de una vez. El
límite que lo hace seguro es que los tres requieren permiso de administración
sobre un conjunto que no crece con la operación.

Además, `GET /v1/invoices/pending-approval` devuelve `{ "items": [...] }` y
`GET /v1/transfers/in-transit` devuelve un arreglo: son bandejas acotadas, no
listados paginables.

### El total se cuenta, no se adivina

```kotlin
Page(items, page.page, page.size, total)   // total de un SELECT count(*)
```

**Nunca `items.size`.** Ya hubo dos bugs por esto:

- En inventario: `totalItems` devolvía el tamaño de la página → «50 resultados»
  siempre.
- En kardex: el conteo **ignoraba los filtros** → un rango sin resultados daba
  `items: []` pero `totalItems: 1064`.

> **Si el conteo ignora los filtros, es peor que no tenerlo.**

El listado y el conteo comparten los filtros en cada endpoint, de modo que el
sobre no puede decir una cosa y los `items` otra. Ejemplos en el código:
`InventoryRoutes.kt` (`service.countStock` / `service.countKardex`),
`AuditRoutes.kt` y `ReportRoutes.kt`.

**`size` mayor de 200 se RECHAZA con `400`, no se recorta**: recortar hace que el
cliente crea que tiene todos los datos.

## 1.5 Idempotencia

Las operaciones que **mueven inventario** o **crean documentos** aceptan la
cabecera `Idempotency-Key`. Repetir la misma clave devuelve la **misma
respuesta** y no repite el efecto. El móvil sin señal reintenta; sin esto, un
reintento duplica un movimiento.

La clave viaja en la **cabecera**, no en el cuerpo. Si viene y no es un UUID, se
rechaza con `400 INVALID_IDEMPOTENCY_KEY`, no se deja al `CAST` de PostgreSQL
(que saldría como un `500` por un dato del cliente).

Estado en la implementación:

| Endpoint | Cómo se garantiza |
|---|---|
| `POST /v1/inventory/adjustments` | cabecera `Idempotency-Key` (`InventoryRoutes.kt`, `InventoryUseCases.kt`) |
| `POST /v1/inventory/locate` | cabecera `Idempotency-Key` |
| `POST /v1/inventory/move` | cabecera `Idempotency-Key` |
| `POST /v1/orders/{id}/export` | idempotente por el `orderNumber` convertido, no por cabecera (`OrderService.kt`) |
| `POST /v1/invoices/{id}/lines/{lineId}/capture` | **no lee la cabecera** en la implementación actual |
| `POST /v1/orders/{id}/lines/{lineId}/package` | **no lee la cabecera** en la implementación actual |
| `POST /v1/orders/{id}/confirm` | **no lee la cabecera** en la implementación actual |
| `POST /v1/products/import` | idempotente por `product_code`: importar dos veces el mismo archivo no duplica (K2) |

`POST /v1/auth/logout` también es idempotente por diseño: no exige token válido y
revocar dos veces no falla.

## 1.6 Cabeceras

| Cabecera | Dirección | Para qué |
|---|---|---|
| `Authorization: Bearer <jwt>` | petición | identidad |
| `Idempotency-Key: <uuid>` | petición | deduplicar reintentos |
| `X-Request-Id: <uuid>` | ambas | correlación con la auditoría |
| `X-Client-Channel` | petición | `WEB`, `MOBILE`, `MOBILE_OFFLINE` |
| `X-Client-Timezone` | petición | IANA, p. ej. `America/Mexico_City` |
| `X-Service-Key` | petición | credencial de una integración. **Solo en `PATCH /v1/print-jobs/{id}`** |

**`X-Client-Channel` no es decorativo**: la auditoría registra el canal, y
`MOBILE_OFFLINE` dispara el camino de sincronización diferida. El canal se lee en
`api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/http/RequestOrigins.kt` y se
escribe en `api/adapters-persistence/src/main/kotlin/dev/axzy/stock/adapters/persistence/audit/JdbcAuditRecorder.kt`.

`X-Service-Key` y no `Authorization` para la impresora: así la auditoría distingue
un trabajo marcado por una máquina (`SERVICE_PRINTER`) de uno hecho por una
persona. La clave se guarda hasheada en la tabla `service_api_keys` y su secreto
se muestra una sola vez al crearla (`backend/src/main/kotlin/dev/axzy/stock/backend/label/LabelService.kt`).

## 1.7 Fechas y números

- **Instantes**: ISO 8601 en UTC con `Z` → `"2026-10-07T21:24:37.688Z"`.
- **Fechas operativas** (sin hora): `"2026-10-07"`. Para el turno de noche, la
  fecha de negocio, no la del reloj.
- **Cantidades**: decimal con **3 decimales**. La mayoría de los DTO las
  serializan como **cadena** (`"100.000"`, `"0.500"`): `KardexDto`,
  `StockByLocationDto`, `InvoiceLineDto`, `OrderLineResponse`,
  `TransferResponse`, `InventoryReportItem`, `MovementsSummaryItem`,
  `DivergenceDto` y `MovementResponse` declaran sus cantidades como `String` y las
  escriben con `BigDecimal.toPlainString()`.
- **Excepción de transporte en el catálogo**: `ProductDto.stock` y
  `ImportReportDto.totalStock` se emiten como **número JSON sin comillas con 3
  decimales** (`5193.000`), con `Quantity.toJson()` en
  `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/catalog/CatalogDtos.kt`.

  ```json
  "stock": 5193.000
  ```

  **Por qué texto y no número en el resto, y es una decisión, no un descuido:**
  JSON no tiene decimales de precisión arbitraria. Un `number` de JavaScript es un
  `double` de 64 bits, y `0.1 + 0.2` no es `0.3`. Mandar `5193.000` como número
  invita a que el cliente lo sume en coma flotante y aparezcan centésimas
  fantasma. Como **cadena**, la precisión viaja intacta y el cliente decide cómo
  parsearla.

  **Consecuencia que hay que tener presente:** los tipos compartidos de `web/`
  (`StockItem`, `KardexEntry`) declaran estos campos como `number`, y **están
  mal**. Las pantallas que se conectaron tuvieron que declarar interfaces locales
  con la forma real. Es una deuda pendiente: alinear
  `web/src/shared/api/types.ts`.
- **Identificadores**: número entero. Las claves de negocio (`productCode`) son
  cadena.
