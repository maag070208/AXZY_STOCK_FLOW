# OWASP y los hallazgos del legacy
> **Referencia.** Qué hallazgo de seguridad tenía el legacy, qué control lo reemplaza en el sistema
> nuevo y qué está verificado. Lo que no se ha comprobado figura como tal. Última verificación: 2026-10-09.
> **Nota de reconstrucción (2026-10-10).** Revisión del código anterior. Las vulnerabilidades halladas se comprueban de nuevo en el código nuevo. Las reglas siguen vigentes; las rutas de código que cita pertenecen al sistema anterior y se remapean al código nuevo (ver [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

## Hallazgos del legacy y su estado

Hallazgos de [historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md) §2.2-ter.

| # | Hallazgo en `BASE/` | Control en el sistema nuevo | Estado |
|---|---|---|---|
| S1 | Tres rutas sin autenticación (`GET /kardo`, `PATCH /prints`, `DELETE /invoices/detail/:id`) | Las rutas de sistema usan clave de servicio ([ADR-0010](../adr/0010-rutas-de-servicio-con-api-key.md)); el borrado de líneas no existe | Verificado por lectura de rutas. Sin barrido automático de 401/403 documentado |
| S2 | `role === 1` como bypass de permisos en cuatro puntos | Denegar por defecto; el permiso sale del catálogo, no de un número de rol ([ADR-0006](../adr/0006-rbac-mas-abac-denegar-por-defecto.md)) | Verificado: el texto queda solo en comentarios de `identity/IdentityTables.kt` e `identity/IdentityRepository.kt` |
| S3 | JWT de 24 h, sin logout ni revocación | Refresh rotado con detección de reúso (`TOKEN_REUSED`); la familia se revoca | Verificado por nombre de prueba: `SessionModelTest` |
| S4 | SQL por concatenación (`kardo.service.ts:25`) | Acceso a datos con Exposed sobre la base; sin SQL construido por texto | No verificado con una búsqueda completa de SQL crudo en el backend |
| S5 | Credenciales en el código (seed, validador externo, contraseña de SQL Server en `docker-compose.yml`) | El administrador inicial se genera o se pasa por variable; no hay contraseña en el código | Ver [operacion/entornos.md](../historico/OPERACION-ENTORNOS-ANTERIOR.md). El `infra/` del repo nuevo no contiene la contraseña del legacy |
| S6 | `console.log` con configuración y cuerpos de petición | Registro estructurado de la API | No verificado |
| S7 | Errores de autorización devueltos como 401 | 401 solo para identidad no válida; 403 para permisos | Verificado por el catálogo de errores (`docs/api/errores.md`) |
| S8 | Sin límite de intentos en el login | Bloqueo por intentos (`login_attempts`, `ACCOUNT_LOCKED`) y `RATE_LIMITED` | Verificado por existencia en `identity/AuthRoutes.kt` y `V4__sessions.sql`. Los umbrales no están documentados |

## Controles que no son hallazgos del legacy

- **Auditoría inmutable.** La bitácora no admite `UPDATE` ni `DELETE` ([seguridad/auditoria.md](auditoria.md)).
- **Segregación de funciones.** Quien captura una discrepancia no la aprueba ([ADR-0008](../adr/0008-discrepancias-y-merma.md)).
- **Secretos fuera del repo.** La clave de firma y la contraseña inicial se generan o vienen de variables de entorno ([operacion/entornos.md](../historico/OPERACION-ENTORNOS-ANTERIOR.md)).

## Pendiente

- Barrido automático de autenticación sobre todas las rutas de `/v1`, con su prueba.
- Búsqueda completa de SQL construido por texto en `backend/src/main`.
- Revisión de los registros para confirmar que no imprimen datos sensibles (S6).
- Umbrales de bloqueo y de límite de peticiones, documentados con sus valores.
