# Salud
> **Referencia.** El estado del servicio: la ruta pública que consultan el
> orquestador y la monitorización. Última verificación: 2026-10-09.

La ruta está en `api/adapters-web/src/main/kotlin/dev/axzy/stock/adapters/web/health/HealthController.kt`,
el caso de uso en `api/application/.../health/CheckHealth.kt` y la consulta a la base en
`api/adapters-persistence/.../health/JdbcDatabaseProbe.kt`.

## `GET /v1/health` — público, sin autenticación

```json
{
  "status": "UP", "schemaVersion": "15", "version": "1.0.0",
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

**Además del health, la API comprueba el esquema al arrancar** (`SchemaGuard.kt`): si la base está
por detrás de la migración que el código necesita, no arranca. El health informa la versión; el
arranque la exige.

`database.error` lleva solo el tipo del fallo, nunca el mensaje del controlador, que puede incluir el
host o el usuario de la base.

## Verificación

| Archivo | Qué cubre |
|---|---|
| `api/app/src/test/kotlin/dev/axzy/stock/app/HttpContractTest.kt` | la ruta de salud, `X-Request-Id` y el contrato de error |
| `api/app/src/test/kotlin/dev/axzy/stock/app/SchemaVersionTest.kt` | que la versión exigida es la última migración |
