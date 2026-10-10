# Errores de la API
> **Referencia.** El cuerpo de error común a todos los endpoints y el catálogo
> cerrado de códigos. Última verificación: 2026-10-09.

Las reglas de estado y de cabeceras están en [convenciones.md](convenciones.md).
El cuerpo de error lo implementa
`backend/src/main/kotlin/dev/axzy/stock/backend/error/ErrorResponse.kt` y lo
aplica el manejador de `Application.kt`.

## 1.3 El contrato de error — es el mismo en todos los endpoints

```json
{
  "code": "POLICY_DENIED",
  "message": "No puedes aprobar tu propio conteo (segregación de funciones)",
  "details": { "campo": "motivo" }
}
```

**El cliente decide con `code`, nunca con `message`.** El mensaje se traduce y se
reescribe; un cliente que compara texto se rompe en silencio. Los códigos son
estables y en `SCREAMING_SNAKE_CASE`. `details` es opcional y su forma depende del
código (`available`, `until`, `stock`, `pendingLines`, `allowed`…).

La agrupación por estado de las tablas siguientes es orientativa: el mismo `code`
puede acompañar estados distintos según la ruta (por ejemplo
`INVALID_PRODUCT_CODE` es `422` al crear un producto y `400` al ajustar
inventario). **La decisión del cliente se toma por el `code`; el estado indica la
acción inmediata.**

Códigos de autorización, que **el cliente debe distinguir** porque la acción del
usuario es distinta:

| `code` | Significado | Qué debe hacer el usuario |
|---|---|---|
| `UNAUTHENTICATED` | falta token o venció | iniciar sesión |
| `PERMISSION_DENIED` | no tiene el permiso | pedirlo a un administrador |
| `SCOPE_INSUFFICIENT` | tiene el permiso, pero no alcanza este recurso | pedir acceso a esa bodega |
| `POLICY_DENIED` | una política lo prohíbe | cambiar **cuándo** o **cómo** lo hace |
| `SEGREGATION_OF_DUTIES` | no puede aprobar lo que él mismo capturó | que lo apruebe otra persona |

Colapsar los cinco en un `403` genérico es el defecto del legacy: el usuario recibe
«no autorizado» y no sabe si pedir un permiso, esperar, o llamar a otra persona.

## 1.3-bis Catálogo de códigos de error

**Lista cerrada.** Un código nuevo se añade aquí antes de usarlo, y el cliente solo
necesita contemplar éstos. Escribirlos en el contrato evita el problema de
siempre: el backend inventa un código, el cliente no lo reconoce y cae en el
mensaje genérico.

Errores de la petición (§1.4):

| `code` | Situación |
|---|---|
| `INVALID_PAGINATION` | `page` o `size` fuera de rango. **Un `size` > 200 se rechaza, no se recorta** |
| `INVALID_SORT` | el campo o la dirección de `sort` no se admiten en ese endpoint |
| `INVALID_ID` | el identificador de la ruta no es un número |
| `INVALID_PARAMETER` | un parámetro de consulta tiene un valor inadmisible |
| `INVALID_IDEMPOTENCY_KEY` | la cabecera `Idempotency-Key` no es un UUID |
| `FILE_REQUIRED` | falta el archivo en un `multipart` |
| `FILE_TOO_LARGE` | el archivo supera el límite |
| `EMPTY_FILE` | el archivo no tiene filas |
| `IMPORT_FILE_FORMAT` | el formato no es CSV canónico ni XLSX |
| `INVALID_DRY_RUN` | `dryRun` no es un booleano |

Errores de validación de campo (§422, con `details` por campo):

| `code` | Situación |
|---|---|
| `FIELD_REQUIRED` | falta un campo obligatorio |
| `FIELD_TOO_LONG` | supera la longitud de la columna |
| `INVALID_PRODUCT_CODE` | no cumple el alfabeto `0-9A-Z` ni la longitud 6–14 |
| `UNKNOWN_UNIT_MEASURE` | la unidad no está en el catálogo |
| `INVALID_LEVELS` | el número de niveles no es positivo |
| `INVALID_LOCATION_KIND` | `kind` no es `SHELF` ni `RECEIVING` |
| `INVALID_LOCATION_PART` | `hall`/`code`/`position` no cumplen el formato |
| `UNKNOWN_WAREHOUSE` | la bodega no existe |
| `IMPORT_INVALID_EXTERNAL_ID` | `external_id` no es numérico. **Se rechaza en lugar de guardarlo nulo**: perdería la referencia cruzada con el ERP en silencio |

Errores de recurso inexistente (§404):

| `code` | Situación |
|---|---|
| `NOT_FOUND` | la ruta no existe. Lo emite Ktor, no un handler |
| `PRODUCT_NOT_FOUND` | el `{id}` de producto no corresponde a ningún producto |
| `LOCATION_NOT_FOUND` | el `{id}` de ubicación no corresponde a ninguna ubicación |

`PRODUCT_NOT_FOUND` y `LOCATION_NOT_FOUND` los emite el código
(`backend/src/main/kotlin/dev/axzy/stock/backend/catalog/CatalogValidation.kt`) y
pertenecen a la lista cerrada: un cliente que decide con `code` distingue «el
producto no existe» de «la ruta no existe», que para el usuario son dos mensajes
distintos. Un test unitario comprueba contra este catálogo que todo código emitido
está declarado.

Errores de conflicto con el estado actual (§409):

| `code` | Situación |
|---|---|
| `DUPLICATE_PRODUCT_CODE` | ya existe ese código |
| `DUPLICATE_EXTERNAL_ID` | ya existe ese identificador del ERP |
| `DUPLICATE_LOCATION` | ya existe esa ubicación en la bodega |
| `PRODUCT_CODE_IMMUTABLE` | se intentó cambiar el código de un producto. **La implementación lo devuelve como `422`** |
| `WAREHOUSE_IMMUTABLE` | se intentó mover una ubicación de bodega. **La implementación lo devuelve como `422`** |
| `PRODUCT_ALREADY_INACTIVE` | ya estaba desactivado |
| `LOCATION_ALREADY_INACTIVE` | ya estaba desactivada |
| `RECEIVING_LOCATION_PROTECTED` | no se puede desactivar la ubicación de recepción: el flujo de ubicación la necesita |
| `LOCATION_HAS_STOCK` | la ubicación tiene existencias: `details.quantity` |
| `PRODUCT_HAS_STOCK` | el producto tiene existencias: `details.stock` |
| `IMPORT_HAS_REJECTIONS` | el import real encontró filas inválidas y **no escribió ninguna** |

### Códigos emitidos por la implementación que la lista cerrada no enumeraba

La implementación emite los códigos siguientes; se incorporan al catálogo para que
el cliente pueda contemplarlos. Proceden de las rutas y servicios de cada módulo
(`application/receiving/ReceivingUseCases.kt`, `order/OrderService.kt`,
`application/transfer/TransferUseCases.kt`, `label/LabelService.kt`,
`audit/AuditRoutes.kt`) y de los errores de dominio
(`stock-domain/src/commonMain/kotlin/dev/axzy/stock/domain/error/DomainError.kt`).

| `code` | Estado | Situación |
|---|---|---|
| `INVALID_FILTER` | 400 | un filtro de consulta tiene un valor inadmisible; **se rechaza, no se ignora** |
| `INVALID_FILTER_RANGE` | 400 | `from` es posterior a `to`; un rango invertido no devuelve nada |
| `INVALID_FILTER_DATE` | 400 | la fecha no es ISO (`AAAA-MM-DD`) ni un instante ISO-8601 |
| `INVALID_LOCATION_CODE` | 400 | el código de ubicación no es válido |
| `DUPLICATE_KEY` | 409 | violación de unicidad detectada por la base |
| `DUPLICATE_WAREHOUSE_CODE` | 409 | ya existe una bodega con ese código |
| `DUPLICATE_INVOICE_NUMBER` | 409 | ya existe una factura con ese número |
| `INVOICE_ALREADY_CLOSED` | 409 | la factura ya estaba cerrada |
| `INVOICE_HAS_PENDING_LINES` | 422 | hay líneas que impiden cerrar: `details.pendingLines` |
| `LINE_ALREADY_RESOLVED` | 409 | la línea ya está aprobada o ubicada y no se puede volver a capturar |
| `NO_PENDING_APPROVAL` | 409 | la factura no tiene líneas en discrepancia que aprobar o rechazar |
| `NOT_PENDING_APPROVAL` | — | **Solo en `stock-domain`**: una línea que no está en `PENDING_APPROVAL` no puede aprobarse. La API de recepción no lo emite; el caso de factura sin pendientes es `NO_PENDING_APPROVAL` |
| `INVALID_TRANSITION` | 409 | el estado actual no admite la transición pedida |
| `INVALID_QUANTITY` | 422 | la cantidad no es válida o no es positiva |
| `INCONSISTENT_BOX_COUNT` | 422 | cajas y unidades no cuadran con `unitsPerBox` |
| `BOXES_DO_NOT_DIVIDE_EVENLY` | 422 | las cajas no dividen exactamente la cantidad |
| `EXCEEDS_LINE_QUANTITY` | 422 | lo capturado supera lo facturado |
| `CANNOT_DELETE_WITH_INVENTORY_EFFECT` | 422 | el borrado tendría efecto sobre el inventario. **Lo declara el error de dominio; ninguna ruta de esta API lo emite todavía** |
| `EMPTY_ORDER` | 422 | la orden no tiene líneas |
| `EXCEEDS_PENDING_QUANTITY` | 422 | se empaca más de lo pendiente de la línea |
| `LOCATION_REQUIRED` | 422 | la línea no tiene ubicación de la que salir |
| `ORDER_LINE_NOT_FOUND` | 404 | la línea no existe o no pertenece a esa orden |
| `ORDER_NOT_FOUND` | 404 | la orden no existe |
| `ORDER_ALREADY_CONFIRMED` | 409 | la orden ya estaba confirmada |
| `ORDER_NOT_CONVERTED` | 409 | se intentó exportar una orden sin confirmar |
| `EMPTY_TRANSFER` | 422 | el traslado no tiene líneas |
| `SAME_WAREHOUSE` | 422 | origen y destino son la misma bodega |
| `TRANSFER_NOT_FOUND` | 404 | el traslado no existe |
| `NO_RECEIVING_LOCATION` | 422 | la bodega de destino no tiene ubicación de recepción |
| `NOTHING_IN_TRANSIT` | 409 | el traslado no tiene mercancía en tránsito que recibir |
| `INVOICE_NOT_FOUND` | 404 | la factura no existe |
| `LINE_NOT_FOUND` | 404 | la línea de factura no existe |
| `LABEL_TEMPLATE_NOT_FOUND` | 404 | la plantilla no existe |
| `LABEL_TEMPLATE_INACTIVE` | 422 | se intentó imprimir con una plantilla desactivada |
| `LABEL_ALREADY_PRINTED` | 409 | el trabajo ya está impreso: un reintento no imprime dos veces |
| `PRINT_JOB_NOT_FOUND` | 404 | el trabajo de impresión no existe |
| `MISSING_LABEL_FIELD` | 422 | faltan datos obligatorios de la etiqueta |
| `INVALID_ZPL` | 422 | el ZPL no lleva `^XA` y `^XZ` |
| `INVALID_SERVICE_KEY` | 401 | falta la cabecera `X-Service-Key` o la clave no vale |
| `INTERNAL_ERROR` | 500 | error no controlado; el detalle **no** se filtra al cliente |
| `RATE_LIMITED` | 429 | demasiados intentos. **Declarado en la lista cerrada; la implementación aún no tiene limitador de tasa** |

## 1.3-ter El catálogo es cerrado

> **Normativo.** Los códigos están en esta lista y **no se inventan**. Un error nuevo se
> añade al catálogo **antes** de usarlo.

**Un `500` es una mentira.** Dice «el servidor falló» cuando el servidor está
bien. Un cliente que reintenta ante un `500` **reintenta para siempre**; ante un
`400`, corrige.

Un dato que el cliente escribió mal (una fecha, un UUID, un valor de enum de
PostgreSQL) se valida en Kotlin y se responde `400`; no se deja llegar al `CAST`
de la base, que lanzaría un `500` por una petición incorrecta.
