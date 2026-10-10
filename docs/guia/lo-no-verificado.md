# Lo que no está verificado
> **Referencia.** Lo que no se ha comprobado contra el código, el motor o un dispositivo. Un
> documento que solo dice lo que va bien no sirve para decidir. Última verificación: 2026-10-09.

| Qué | Estado |
|---|---|
| **Migración a capas de la API** | **No hecha.** `catalog/` está partido por recurso; las capas `application/` e `infrastructure/` **no existen** y las demás features siguen en la raíz del paquete ([migracion-capas.md](../arquitectura/migracion-capas.md)) |
| **Regla de dependencias, sin guardia** | Nada impide hoy que `application/` importe Ktor, Exposed o JDBC. Se comprueba a mano con el `grep` de [api-ktor.md](../arquitectura/api-ktor.md) §1.3 |
| **`Dispatchers.IO` / `withContext`** | **0 usos** en `backend/src`. Todo el JDBC bloquea el hilo de Netty ([api-ktor.md](../arquitectura/api-ktor.md) §1.10) |
| **MDC, métricas y trazas** | **0 `MDC`, 0 Micrometer, 0 OpenTelemetry.** El logging es plano; el `X-Request-Id` está prometido en el documento OpenAPI y **no se propaga** ([api-ktor.md](../arquitectura/api-ktor.md) §1.14) |
| **Resiliencia** | **0** timeouts, circuit breaker, reintentos y rate limiter. El `429 RATE_LIMITED` existe **solo en el catálogo de errores**, sin nada que lo emita ([api-ktor.md](../arquitectura/api-ktor.md) §1.15) |
| **`domain/` propio del backend** | `domain/error/DomainFailure` **todavía no existe**; el catálogo de errores vive en `stock-domain` (`DomainError`) |
| **Auditoría de escrituras** | Inventario y recepción escriben su auditoría, y las **denegaciones de acceso** también. Faltan las de **catálogo, surtido, traslados y etiquetas**: hoy mueven inventario sin dejar rastro en `audit_log` ([auditoria.md](../seguridad/auditoria.md)) |
| **Administración de acceso por interfaz** | `GET /v1/users`, `/v1/roles` y `/v1/permissions` existen y la pantalla los consulta. **No hay rutas de escritura**: crear un usuario o cambiarle los roles se hace por SQL |
| **Claves de servicio** | La verificación por `X-Service-Key` está implementada y probada, pero **no hay endpoint para crear una clave**: hoy se inserta por SQL con el hash calculado a mano |
| **Integración con el ERP (`/v1/orders/{id}/export`)** | **No existe.** El intento se registra y la orden pasa a `COMPLETED`, pero **no hay ningún envío real**. Fingir un fallo aleatorio sería peor: haría pasar tests que no prueban nada |
| **Envío real a la impresora Zebra** | La **cola** está completa y probada (el trabajo nace `PENDING` y solo la impresora lo cierra), pero **nadie envía el ZPL a la impresora**: el `PATCH` lo simula el cliente. Falta el worker que habla con la Zebra |
| **iOS** | El proyecto Xcode de `mobile-app/iosApp/` **no está versionado** en el repositorio: solo hay fuentes Swift y `Config.xcconfig`, sin `.xcodeproj`. Nunca se ha abierto ni compilado desde Xcode |
| **Cámara del móvil** | Sin probar en dispositivo real |
| **Móvil ↔ API** | Los módulos `stock-network` / `stock-database` / `stock-datastore` **no están conectados a las pantallas** |
| **E2E del móvil** | No hay ninguno |
| **Animación de intro** | Los JSON de intro están en `web/src/shared/assets/lottie/`, pero ninguna pantalla los usa ([identidad-visual.md](identidad-visual.md)) |
| **Cobertura de `receiving/` e `identity/` por HTTP con RBAC real** | Parcial: `ApiE2ETest` cubre token, 401/403, catálogo, paginación y reconciliación; **no** el flujo de captura y aprobación de facturas |
| **E2E de navegador en CI** | `./scripts/e2e-web.sh` **no está en el workflow**: necesita navegador y dos servidores. Se ejecuta a mano ([estrategia-pruebas.md](../pruebas/estrategia-pruebas.md)) |
| **Swagger / OpenAPI** | El documento se construye en código (`backend/src/main/kotlin/dev/axzy/stock/backend/docs/OpenApiDocumentation.kt`) y se sirve; la generación desde el árbol de rutas no existe en Ktor 3.1.1 ([stack.md](../arquitectura/stack.md)) |

## Lo que sí está verificado

| Qué | Estado |
|---|---|
| **API completa** | El contrato entero, endpoint a endpoint contra `docs/historico/CONTRATO_API.md` |
| **Web conectada** | Todas las pantallas contra la API real. No queda ninguna maqueta ni placeholder; las altas de orden de surtido y de plantilla de etiqueta están en la interfaz |
| **Un mapper leído por nombre de columna tiene test contra la función real** | `backend/src/test/kotlin/dev/axzy/stock/backend/infrastructure/outbound/persistence/inventory/DivergenceMapperTest.kt` ejercita la función PL/pgSQL y su nombre de columna |
| **Un test unitario comprueba que todo código de error emitido está en el catálogo del contrato** | `backend/src/test/kotlin/dev/axzy/stock/backend/docs/OpenApiDocumentationTest.kt` |
| **Un e2e comprueba que la misma falta de token da el mismo estado en todas las rutas** | `backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt`: sin token, las rutas protegidas dan `401` con `UNAUTHENTICATED`, nunca `403` |
| **Un e2e de navegador comprueba que recargar conserva la sesión** | `web/e2e/flow.spec.ts`, con un test dedicado a recargar |

## Afirmaciones del documento original que no se pudieron verificar

Son afirmaciones sobre **procesos pasados** (que una prueba se puso en rojo, que un defecto se
provocó a propósito). El test existe y es verificable; el episodio no queda en el código, así
que no entra como hecho en un normativo.

- Que `DivergenceMapperTest` falle al revertir el nombre de la columna a `total_en_bd`.
- Que el test de denegaciones de acceso falle al desconectar el escritor de auditoría.
- Que se quitara la condición `quantity.isNegative` a propósito y los tests nuevos fallaran.
- Que el e2e de la falta de token encontrara un `403` donde tocaba `401`.
- Que la web se quedara en blanco al recargar antes de escribir el e2e de recarga.
- Los recuentos de tests, endpoints y pantallas del original, que estaban desfasados.

Las cifras concretas (número de tests, de endpoints, de pantallas) **no se escriben aquí**:
caducan. Se miden con los comandos de
[estrategia-pruebas.md](../pruebas/estrategia-pruebas.md) y con el informe XML de los tests.
