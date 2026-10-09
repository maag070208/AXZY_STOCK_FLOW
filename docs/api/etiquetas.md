# Etiquetas
> **Referencia.** Plantillas ZPL, previsualización, impresión, cola de trabajos y
> reimpresión. Última verificación: 2026-10-09.

Implementado en
`backend/src/main/kotlin/dev/axzy/stock/backend/label/LabelRoutes.kt` y
`LabelService.kt`, con los DTO en `LabelDtos.kt`. La paginación está en
[convenciones.md](convenciones.md); los códigos, en [errores.md](errores.md).

## `GET|POST /v1/label-templates`, `GET|PUT /v1/label-templates/{id}`

El listado y el detalle exigen `label.template_view`; el alta (`POST`) y la
edición (`PUT`), `label.template_edit`.

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

`zpl` **no se devuelve en el listado** (es grande y no se necesita): solo en el
detalle. El listado admite `onlyActive` (`false` por defecto) y devuelve un
arreglo, **sin sobre de paginación** (es un catálogo cerrado que la interfaz
necesita completo; véase [convenciones.md §1.4](convenciones.md)).

El alta usa `LabelTemplateCreateRequest` (`code`, `name`, `zpl` obligatorios;
`description`, `size`, `fieldSchema`, `isActive` opcionales) y responde **201** con
el detalle. La edición usa `LabelTemplateUpdateRequest`: los campos que no se
envían quedan como estaban.

**El ZPL se valida AL GUARDAR**: tiene que llevar `^XA` y `^XZ`, o responde `422
INVALID_ZPL`. Sin el cierre, la impresora no da error — deja la etiqueta en blanco
o el trabajo en la cola, y eso se descubre gastando papel. Detalle: `404
LABEL_TEMPLATE_NOT_FOUND`, `400 INVALID_ID`.

## `POST /v1/labels/preview`

Permiso: `label.print`. Devuelve el ZPL resuelto **sin imprimir**. Para web y móvil.

```json
{ "templateId": 1, "data": { "productCode": "ABRAA1B", "name": "...", "quantity": "100" } }
```

**200:**

```json
{ "zpl": "^XA...^XZ", "preview": { "widthMm": 101.6, "heightMm": 152.4, "copies": 1 } }
```

**La resolución la hace el DOMINIO**, la misma función que usa el envío a la
impresora: si se resolviera aparte, la previsualización podría enseñar algo distinto
de lo que se imprime, y se aprobaría lo que se ve.

Un campo obligatorio que falta **devuelve `422 MISSING_LABEL_FIELD`, no una
etiqueta a medias**: dejar el marcador imprime `{{productCode}}` en el estante, y
sustituirlo por vacío imprime una etiqueta que parece correcta y no lo está.

El tamaño en milímetros lo calcula el servidor: si lo hiciera cada cliente, la vista
previa de la web y la del móvil mostrarían tamaños distintos para la misma
plantilla. `404 LABEL_TEMPLATE_NOT_FOUND` si la plantilla no existe.

## `POST /v1/labels/print`

Permiso: `label.print`.

```json
{ "templateId": 1, "copies": 2, "data": [ { "productCode": "ABRAA1B" } ] }
```

**202 Accepted** con `jobIds`. **El trabajo queda `PENDING` hasta que la impresora
confirma.**

```json
{ "jobIds": [501, 502], "status": "PENDING" }
```

`copies` va de 1 a 100 y por defecto es 1. Se resuelve el ZPL **antes** de encolar:
un trabajo con datos incompletos se crearía igual y habría que descubrirlo al
imprimir, con la etiqueta ya en la cola. Errores: `422 MISSING_LABEL_FIELD`,
`422 LABEL_TEMPLATE_INACTIVE`, `422 FIELD_REQUIRED`, `400 INVALID_PARAMETER`,
`404 LABEL_TEMPLATE_NOT_FOUND`.

## `GET /v1/labels/jobs/{id}`, `GET /v1/labels/jobs?status=FAILED`

Permiso: `label.queue_view`. El listado filtra por `status` y se pagina con `page` y
`size`.

```json
{
  "id": 501, "templateCode": "receipt", "copies": 2,
  "status": "PRINTED", "attempts": 1, "lastError": null,
  "printedAt": "2026-10-07T21:30:00Z", "createdAt": "2026-10-07T21:29:50Z"
}
```

`status`: `PENDING` | `SENT` | `PRINTED` | `FAILED`. **Una etiqueta no se marca
impresa hasta que la impresora confirma.** El legacy la marcaba sin esperar la
respuesta, así que si la Zebra estaba apagada, la etiqueta se perdía para siempre.
`?status=FAILED` es la consulta que hace útil la cola: una etiqueta que no salió
tiene que poder encontrarse.

Detalle: `404 PRINT_JOB_NOT_FOUND`, `400 INVALID_ID`.

## `POST /v1/labels/jobs/{id}/reprint`

Permiso: `label.reprint`. Exige motivo (`{"reason": "..."}`) y genera un **trabajo
nuevo** enlazado al original, no reutiliza el registro: la trazabilidad de una
reimpresión exige saber quién la pidió y por qué. **202** con el trabajo nuevo, que
también nace `PENDING`.

`label.reprint` y no `label.print`: reimprimir gasta una segunda etiqueta, y el
catálogo los separa a propósito. Errores: `422 REASON_REQUIRED`, `404
PRINT_JOB_NOT_FOUND`.

## `PATCH /v1/print-jobs/{id}` — integración con la impresora

**Es la única ruta que no vive bajo `/v1/labels`**, y aun así cuelga de un recurso:
es la actualización del estado de UN trabajo de impresión. La ruta cuelga del
recurso que modifica; una ruta sin recurso (`PATCH /v1/prints`) no describiría el
efecto ni sería descubrible leyendo la documentación de etiquetas.

Es la llamada que hace la Zebra. **No exige JWT de usuario, sino credencial de
servicio en la cabecera `X-Service-Key`** (tabla `service_api_keys`; el secreto se
guarda hasheado y se muestra una sola vez al crearlo). El legacy tenía este endpoint
**abierto sin ninguna credencial**, así que cualquiera en la red podía marcar
trabajos como impresos.

**`X-Service-Key` y no `Authorization`** para que quede claro en los registros que
no es una persona: la auditoría guarda `SERVICE_PRINTER` como canal y el id de la
clave, y así un trabajo marcado como impreso sin usuario detrás se distingue de uno
hecho por alguien.

```json
{ "status": "PRINTED", "error": null }
```

`status`: `PRINTED` | `FAILED`. El `jobId` va en la ruta, no en el cuerpo: es el
recurso que se actualiza, y repetir la llamada no debe imprimir dos veces.

**200** con la confirmación:

```json
{ "jobId": 501, "status": "PRINTED", "error": null }
```

**Errores:** `401 INVALID_SERVICE_KEY`, `404 PRINT_JOB_NOT_FOUND` si el trabajo no
existe, `409 LABEL_ALREADY_PRINTED` si el trabajo ya está en `PRINTED` — una
etiqueta no se imprime dos veces por un reintento de la impresora.

**Aquí es donde una etiqueta pasa a impresa, y solo aquí.** Ningún endpoint de
usuario lo hace: el sistema no se autocertifica.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `backend/src/test/kotlin/dev/axzy/stock/backend/label/LabelPrintQueueTest.kt` | la cola de impresión y el paso a `PRINTED` |
| `backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt` | las rutas de etiquetas contra la API montada |
