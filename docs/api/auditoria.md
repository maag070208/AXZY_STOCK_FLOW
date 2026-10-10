# Auditoría
> **Referencia.** La bitácora: quién hizo qué, cuándo y por qué. **Solo lectura.**
> Última verificación: 2026-10-10 (fase 9, M08 cerrado).

Implementado en capas, como el resto de la API:

| Capa | Archivo |
|---|---|
| Ruta | `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/audit/AuditController.kt` |
| Caso de uso y puertos | `api/application/src/main/kotlin/dev/axzy/stock/application/audit/AuditQueries.kt` |
| Consulta | `api/adapters-persistence/.../audit/JdbcAuditQueries.kt` |
| Escritura (desde la fase 3) | `api/adapters-persistence/.../audit/JdbcAuditRecorder.kt` |
| Cableado | `api/app/src/main/kotlin/dev/axzy/stock/app/config/AuditConfig.kt` |

Las reglas del módulo están en [M08](../modulos/M08-auditoria/README.md) y el flujo en
[flujos/auditoria.md](../flujos/auditoria.md). Esta página documenta **la consulta**.

## `GET /v1/audit/log`

Permiso: `audit.view`. Paginación por `page` y `size`.

| Parámetro | Qué filtra |
|---|---|
| `entityType`, `entityId` | sobre qué se actuó (`product`, `invoice`, `print_job`…) |
| `actorUserId` | quién lo hizo |
| `action` | la acción (`PRODUCT_CREATED`, `INVENTORY_ADJUSTED`…) |
| `level` | `CRITICAL`, `RELEVANT`, `ACCESS`, `TECHNICAL` |
| `result` | `SUCCESS`, `FAILURE`… |
| `channel` | `WEB`, `MOBILE`, `SERVICE_PRINTER`… |
| `from`, `to` | rango de **fechas de negocio** (`AAAA-MM-DD`), ambas incluidas |

```json
{
  "items": [
    {
      "id": 5012, "occurredAt": "2026-10-10T04:12:33.512Z", "businessDate": "2026-10-10",
      "actorUserId": 7, "actorUsername": "jperez", "actorName": "Juan Pérez",
      "actorRoles": ["JEFE_BODEGA"],
      "channel": "WEB", "entityType": "product", "entityId": "1032",
      "entityLabel": "ABRAA1B",
      "action": "PRODUCT_CREATED", "level": "RELEVANT",
      "previousState": null, "newState": "{\"productCode\":\"ABRAA1B\"}",
      "reason": null, "note": null, "permissionKey": "product.create",
      "decision": "ALLOW", "result": "SUCCESS",
      "errorCode": null, "errorMessage": null, "serviceKeyId": null,
      "requestId": "0f8…", "idempotencyKey": null
    }
  ],
  "page": 0, "size": 50, "totalItems": 1398, "totalPages": 28
}
```

**`previousState` y `newState` viajan como JSON en texto**, no como objeto: son documentos
libres y cada acción tiene los suyos. Un tipo fijo obligaría a inventar un esquema común
que ninguna acción respeta.

**El total se calcula con el mismo filtro que la página.** Si el conteo los ignorara, el
sobre diría una cosa y las filas otra (U5).

**Las fechas son de negocio, no instantes.** La tabla ya guarda `business_date`: un cierre
se explica por día de trabajo, y filtrar por instantes obligaría a cada cliente a decidir
en qué huso horario empieza el día. Un rango invertido responde `400 INVALID_FILTER_RANGE`
y una fecha mal formada, `400 INVALID_FILTER_DATE`.

## Lo que no existe, a propósito

- **No hay `PUT` ni `DELETE`.** La tabla es inmutable por disparadores: un `UPDATE` o un
  `DELETE` fallan con «audit_log es append-only». Para corregir un registro se añade uno
  nuevo que lo compensa, que es lo único que mantiene la bitácora creíble.
- No hay exportación a sistemas externos (fuera del alcance de M08).

## La consulta también se registra

Consultar la bitácora **escribe su propio registro**, con nivel `ACCESS`, acción
`AUDIT_QUERIED` y los filtros en el estado nuevo. Quien mira la bitácora está viendo quién
hizo qué: es información sensible, y el módulo dice que su consulta se registra como
acceso.

Se escribe **después** de leer: si el registro fallara, la consulta ya se hizo y lo que no
puede quedar es el hecho sin rastro.

## Permisos y errores

| Código | HTTP | Cuándo |
|---|---|---|
| `PERMISSION_DENIED` | 403 | consulta sin `audit.view` |
| `INVALID_FILTER_RANGE` | 400 | `from` posterior a `to` |
| `INVALID_FILTER_DATE` | 400 | una fecha que no es `AAAA-MM-DD` |
| `INVALID_PAGINATION` | 400 | `page` o `size` fuera de rango |

## Verificación

| Archivo | Qué cubre |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/AuditFlowTest.kt` | U5 (filtros y paginación con el total del mismo filtro), los filtros por acción, actor y nivel, que el registro lleva quién/qué/cuándo/por qué, que un `CRITICAL` lleva su permiso y su traza, que la consulta se registra como acceso, los errores de filtro, el permiso y la inmutabilidad de la tabla (U2) |
| `stock-domain/src/commonTest/.../audit/` | las reglas del registro, sin base de datos |
