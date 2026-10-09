# Salud
> **Referencia.** El estado del servicio: la ruta pública que consultan el
> orquestador y la monitorización. Última verificación: 2026-10-09.

Implementado en
`backend/src/main/kotlin/dev/axzy/stock/backend/health/HealthRoutes.kt`, montado en
`Application.kt`.

## `GET /v1/health` — público, sin autenticación

```json
{
  "status": "UP", "schemaVersion": "4", "version": "1.0.0",
  "database": { "status": "UP", "latencyMs": 3, "error": null }
}
```

`503` si `status = DOWN`. El estado lo decide la **base de datos**: la ruta ejecuta
una consulta real y trivial, y solo un fallo de conexión o de consulta marca el
servicio como caído. Un health que responde «ok» sin comprobar nada dice que el
sistema está sano mientras la base está caída; un health con falsos negativos hace
que un orquestador reinicie instancias sanas.

**`schemaVersion` es información de diagnóstico**, no de salud: si no se puede leer,
el estado sigue siendo `UP` y se informa del motivo en `database.error`. Se
distinguen así dos preguntas que no son la misma: «¿la base responde?» y «¿qué
versión de esquema tiene?». La versión se lee de `flyway_schema_history`; si falta
el historial, `schemaVersion` es `null` y `database.error` lo explica.

`database.latencyMs` es la latencia de la comprobación, en milisegundos. `version`
sale de `STOCK_VERSION` y vale `"dev"` si no está definida.

Es una de las dos únicas rutas **públicas** de la API; la otra es el grupo de
autenticación (`POST /v1/auth/login`, `/refresh`, `/logout`).

## Verificación

| Archivo | Qué cubre |
|---|---|
| `backend/src/test/kotlin/dev/axzy/stock/backend/ApplicationTest.kt` | la ruta de salud y el contrato de error del `404` |
| `backend/src/test/kotlin/dev/axzy/stock/backend/RoutesWiringTest.kt` | que la ruta existe y no devuelve `404` |
