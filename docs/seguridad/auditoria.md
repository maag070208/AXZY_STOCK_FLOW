# Auditoría
> **Normativo.** Qué se registra, con qué garantías, y qué falta. Última verificación: 2026-10-09.

**Todo cambio de estado registra quién, qué, sobre qué, cuándo, desde dónde y con qué
permiso.** La tabla `audit_log` tiene las seis dimensiones, particiones mensuales, cadena de
hash y disparadores de inmutabilidad
(`backend/src/main/resources/db/migration/V2__audit_and_traceability.sql`).

## Cómo se escribe

El escritor es `backend/src/main/kotlin/dev/axzy/stock/backend/infrastructure/outbound/persistence/audit/AuditWriter.kt`.
`record` recibe **la conexión del llamador** y participa de su transacción.

**Si la auditoría falla, la operación se revierte con ella.** Es deliberado: si el movimiento
de inventario se aplica y su registro no, **el inventario cambió sin dejar rastro de quién lo
cambió**, y eso no se descubre hasta que alguien pregunta quién autorizó una diferencia. Un
sistema que a veces audita es más peligroso que uno que no audita, porque genera confianza
sin merecerla. El precio, dicho claro: si la tabla de auditoría no está disponible, el
almacén deja de poder operar.

**Excepción deliberada: las denegaciones de acceso.** `recordDeniedAccessSafely` usa
`runCatching` y su propia conexión. Si la auditoría no puede escribir, **el acceso se sigue
denegando**: negar el acceso es la decisión de seguridad y ya está tomada; el registro es
información para después. Que un fallo de registro **abriera** el acceso convertiría un
problema de auditoría en un agujero de seguridad.

La base impone garantías que no dependen de que el código se acuerde:

- `audit_human_has_actor`: una acción `WEB` sin usuario identificado no es auditable.
- `audit_critical_has_trace`: un registro `CRITICAL` exige correlación, petición o clave de
  idempotencia. Sin ella, el `INSERT` falla.
- `audit_log_record` (migración `V7__audit_write_and_verify.sql`) mantiene la **cadena de
  hash** y serializa la inserción con `pg_advisory_xact_lock(hashtext('audit_log_chain'))`
  para que no se bifurque.

## La auditoría es append-only

No hay `UPDATE` ni `DELETE`: los disparadores los hacen fallar **a propósito**, y el `GRANT`
de la aplicación solo da `SELECT, INSERT` sobre `audit_log`
(`V2__audit_and_traceability.sql`). Las correcciones son registros nuevos.

## Qué se audita hoy

| Ámbito | Estado |
|---|---|
| **Denegaciones de acceso** | **Se registran.** `AccessEnginePermissionChecker` llama a `AuditWriter.recordDeniedAccessSafely` (`backend/src/main/kotlin/dev/axzy/stock/backend/security/PermissionChecker.kt`): todo acceso denegado deja entrada con quién, qué permiso, sobre qué ruta, desde qué canal, con qué IP y con qué decisión. El e2e de la API lo fija (`backend/src/test/kotlin/dev/axzy/stock/backend/ApiE2ETest.kt`, «la auditoría registra los accesos denegados»): un permiso denegado deja una entrada con canal, permiso y resultado |
| **Inventario y recepción** | Ya escriben su auditoría |
| **Catálogo, surtido, traslados y etiquetas** | **Faltan.** Mueven inventario sin dejar rastro en `audit_log` |
| **Operaciones de lectura** | No se auditan; no cambian estado |

**Por qué se empezó por las denegaciones:** son el caso de seguridad. Un sistema que niega sin
dejar constancia no puede responder a lo único que importa después de un incidente —cuántas
veces se intentó y desde dónde—, y un ataque contra permisos es indistinguible de un usuario
que se equivocó de pantalla.

El detalle de qué falta está en
[lo-no-verificado.md](../guia/lo-no-verificado.md). Los permisos y la decisión de acceso
están en [roles-permisos.md](roles-permisos.md).
