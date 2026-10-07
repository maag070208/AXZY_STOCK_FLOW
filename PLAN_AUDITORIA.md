# Diseño de Auditoría y Trazabilidad — Almacén

> Requisito de primer orden: **TODO debe ser auditable** — quién, cuándo, dónde, de dónde, a qué
> hora, y con qué permiso.
>
> Estado: **propuesta para aprobación**. Complementa a `PLAN_ALMACEN_INTEGRAL.md`,
> `PLAN_ROLES_PERMISOS_POLITICAS.md` y `PLAN_REFACTOR_MEJORAS.md`.
> No se instalaron dependencias ni se modificó código de los repos de origen.

---

## 0. Resumen

Pediste auditoría total. Este documento la diseña, y quiero ser claro sobre **dos cosas** desde el
principio:

**1. Auditar el cambio no es suficiente: hay que auditar la autorización.** Los permisos cambian
con el tiempo. Si dentro de seis meses alguien pregunta si una aprobación de 200 piezas fue
legítima, mirar los permisos **actuales** del usuario no responde nada. Hay que saber qué roles,
qué alcance, qué excepciones y **qué políticas estaban vigentes en el instante de la acción**. Por
eso cada registro de auditoría incluye **el trace de la decisión de acceso**, no solo el resultado.

**2. Auditoría total tiene un coste que hay que diseñar, no ignorar.** Registrar el trace completo
en cada petición —incluidas las lecturas— multiplica el volumen de escritura y puede degradar el
sistema. La solución no es auditar menos, sino **separar en dos registros**: un **ledger de eventos**
completo para lo que cambia estado, y un **registro de accesos** agregado y muestreado para las
lecturas. Así se conserva la trazabilidad sin frenar el sistema.

---

## 1. Las dimensiones de la auditoría

Tu requisito enumerado, convertido en campos concretos. Cada columna es obligatoria.

| Dimensión | Pregunta | Campos | Notas de diseño |
|---|---|---|---|
| **Quién** | ¿Quién lo hizo? | `actor_user_id` (FK), `actor_username` (snapshot), `actor_name` (snapshot), `actor_roles[]` (snapshot) | **Snapshot obligatorio**: si el usuario se renombra o se borra, el registro histórico debe seguir siendo legible. Un FK solo no basta para una auditoría |
| **Cuándo** | ¿Cuándo, en hora del servidor? | `occurred_at` (TIMESTAMPTZ, UTC), `server_tz` | UTC en el almacenamiento; la presentación es local. El legacy mezclaba `Z` con hora local |
| **Dónde** | ¿Sobre qué recurso? | `entity_type`, `entity_id`, `entity_label` (snapshot legible) | `entity_label` evita que un reporte de auditoría sea una lista de ids |
| **De dónde** | ¿Desde qué canal y origen? | `source_channel`, `source_device`, `source_ip`, `source_user_agent`, `source_service_key_id` | Es la dimensión que el legacy **no tenía en absoluto** |
| **A qué hora (negocio)** | ¿Cuándo según la operación? | `business_date`, `occurred_at`, `synced_at`, `offline_created_at` | El móvil puede capturar a las 22:00 y sincronizar a las 09:00 del día siguiente. **Hay que guardar las dos horas** |
| **Con qué permiso** | ¿Estaba autorizado? | `permission_key`, `decision`, `decision_stage`, `rbac_trace`, `abac_trace`, `policy_id_decided` | **El corazón del requisito** (§3) |
| **Qué cambió** | ¿Cuál es la diferencia? | `action`, `previous_state` (JSONB), `new_state` (JSONB), `changed_fields[]` | El diff es lo que hace legible un cambio |
| **Por qué** | ¿Con qué justificación? | `reason`, `note`, `adjustment_reason`, `approval_chain` | En inventario, **un cambio sin motivo es un agujero de auditoría** |
| **Resultado** | ¿Funcionó? | `result` (SUCCESS/FAILURE/DENIED), `error_code`, `duration_ms` | Los intentos **denegados y fallidos** son tan valiosos como los exitosos |
| **Integridad** | ¿Se puede alterar? | `sequence_no`, `prev_hash`, `record_hash` | **Cadena de hashes** (§5.3): alterar un registro rompe la cadena |
| **Correlación** | ¿Qué otras acciones son parte de lo mismo? | `correlation_id`, `causation_id`, `idempotency_key` | Reconstruir un flujo completo (una recepción con 30 líneas) |

### 1.1 Los canales de origen (`source_channel`)

Esta dimensión es crítica para interpretar una auditoría: **no es lo mismo una captura hecha por una
persona en el móvil que una importación del ERP**.

```txt
WEB            — persona desde el navegador
MOBILE         — persona desde el móvil
MOBILE_OFFLINE — persona en el móvil sin señal (sincronizada después)
SERVICE_ERP    — sistema externo (el antiguo kardo)
SERVICE_PRINTER— la impresora Zebra confirmando impresión
SERVICE_JOB    — proceso interno (reconciliación, vencimiento de excepciones)
MIGRATION      — carga inicial de datos (Fase 7)
SYSTEM         — el propio sistema (arranque, migración de esquema)
```

**Por qué importa:** si aparece un movimiento de inventario sin persona detrás, hay que poder
distinguir "lo hizo el ERP" de "lo hizo alguien con una API key filtrada" de "es un bug". Sin
`source_channel` esa pregunta es irresoluble.

---

## 2. Qué se audita: clasificación de eventos

**Registrar todo no es auditar: es acumular ruido que nadie leerá.** Se clasifica por criticidad, y
cada nivel tiene su tratamiento.

### Nivel 1 — Crítico (registro completo e inmutable, sin excepción)

Todo lo que **mueve inventario, dinero o acceso**. Cada evento guarda el trace de decisión completo.

| Área | Eventos |
|---|---|
| **Inventario** | Cualquier movimiento del ledger: recepción, ubicación, traslado, ajuste, salida, merma |
| **Dinero/valor** | Cambios que alteran el valor del inventario (`unit_cost`, ajustes de alto valor) |
| **Control de acceso** | Creación/edición/borrado de roles, cambios de matriz, políticas, excepciones, asignación de roles y bodegas |
| **Aprobaciones** | Aprobar o rechazar una discrepancia, aprobar un ajuste |
| **Documentos** | Crear, anular (no borrar), cerrar una factura u orden |
| **Seguridad** | Login fallido, intento de acceso denegado, uso de API key, revocación de sesión |
| **Catálogo** | Alta/edición/desactivación de productos, ubicaciones y bodegas |
| **Migración** | Cada cambio de la carga inicial, identificado como `MIGRATION` |

### Nivel 2 — Relevante (registro completo, retención media)

Cambios que **no mueven inventario pero sí el trabajo de las personas**.

- Asignar trabajo a un operador (factura, línea de orden).
- Cambios de estado de un documento **sin** movimiento de inventario.
- Impresión y **reimpresión** de etiquetas.
- Creación/edición de plantillas de etiqueta.
- Cambios de configuración del sistema.

### Nivel 3 — Acceso (registro agregado y muestreado)

**Aquí está la decisión de diseño que evita que el sistema se frene.** Auditar cada lectura con
trace completo es inviable: un listado de inventario paginado genera decenas de registros, y el
volumen de escritura supera al de lectura real.

Estrategia diferenciada:

| Qué | Tratamiento |
|---|---|
| Lecturas de **datos agregados o listados** | **Contador agregado** por (usuario, recurso, hora): "el usuario X consultó el listado de facturas 47 veces entre las 10 y las 11". No un registro por cada una |
| Lecturas de **datos sensibles** | Registro completo: `inventory.view_cost`, `report.financial`, `report.audit`, `kardex.export` |
| Lecturas **denegadas** | **Siempre registro completo**, aunque sea un listado. Un 403 es una señal |
| Exportaciones | Registro completo: qué se exportó, cuántas filas, qué filtros, a qué archivo |
| Acceso a **datos de otra bodega** (alcance `ALL` sobre `AREA`) | Registro completo |

**El criterio:** se audita con detalle **lo que alguien podría querer negar que hizo**. Consultar un
listado no lo es; exportar 10.000 productos con su costo, sí.

### Nivel 4 — Técnico (métricas, no auditoría)

Latencia, queries por request, errores 500, estado de jobs. Van a observabilidad (Micrometer), **no**
a la tabla de auditoría. Mezclarlos hace la auditoría ilegible.

---

## 3. Auditabilidad de la decisión de acceso (el requisito central)

Esto es lo que responde *"¿tenía permiso?"*, y es lo que el legacy **no podía responder ni
retroactivamente ni en el momento**.

### 3.1 Qué se guarda

```ts
interface AccessDecisionTrace {
  permissionKey: string;              // 'invoice.approve'
  decision: "ALLOWED" | "DENIED";
  decisionCode: string;               // 'OK' | 'PERMISSION_DENIED' | 'POLICY_DENIED' | …
  stage: "IDENTITY" | "RBAC" | "ABAC"; // en qué etapa se decidió

  // Etapa 1 — identidad
  identity: {
    userId: number;
    active: boolean;
    authenticatedAt: string;          // cuándo se emitió el token
  };

  // Etapa 2 — RBAC con el estado EXACTO del momento
  rbac: {
    scope: PermissionScope;           // alcance efectivo resultante
    byRole: Record<string, PermissionScope>;  // qué aportó cada rol
    rolesAtTime: string[];            // snapshot de los roles vigentes
    exceptionId: number | null;       // si hubo excepción por persona
    exceptionScope: PermissionScope | null;
    warehousesAtTime: number[];       // las bodegas del alcance AREA en ese momento
  };

  // Etapa 3 — ABAC con la evaluación de CADA condición
  abac: {
    evaluated: boolean;
    ok: boolean;
    decidedByPolicyId: number | null; // la política que decidió
    decidedByPolicyName: string | null;
    deniedByPolicyId: number | null;
    rules: Array<{
      policyId: number;
      policyName: string;
      effect: "ALLOW" | "DENY";
      priority: number;
      appliesToUser: boolean;
      matched: boolean;
      conditions: Array<{
        field: string;                // 'resource.difference'
        operator: string;             // '<='
        expected: unknown;            // 50
        actual: unknown;              // 200
        matches: boolean;             // false
      }>;
    }>;
  };

  // Contexto de la petición
  context: {
    hour: number;
    weekday: number;
    deviceType: "WEB" | "MOBILE" | "SERVICE";
    offlineSync: boolean;
    ip: string | null;
  };

  // La instantánea de configuración que hizo válida esta decisión
  configSnapshot: {
    matrixVersion: number;            // versión de la matriz rol→permiso
    policiesVersion: number;          // versión del conjunto de políticas
  };
}
```

### 3.2 Por qué se guarda el trace completo y no solo el resultado

Tres razones concretas, no teóricas:

1. **Los permisos cambian.** Sin el trace, dentro de seis meses no se puede reconstruir por qué
   algo se permitió. Con `matrixVersion` y `policiesVersion` se puede **reproducir la decisión
   exacta**.
2. **El "por qué no" es tan valioso como el "por qué sí".** Cuando alguien reclama *"no me dejó
   aprobar"*, el trace dice **qué condición de qué política** lo bloqueó. Sin eso, el soporte
   técnico es adivinación.
3. **Las políticas evolucionan.** Si mañana se cambia el umbral de 50 a 30 y alguien pregunta por
   qué una aprobación de 40 pasó en marzo, el trace lo responde.

### 3.3 El detalle que hace esto defendible

`configSnapshot` es la pieza clave: guarda **la versión** de la matriz y de las políticas. Eso
permite dos cosas:

- **Reproducir** una decisión histórica tal como se tomó.
- **Detectar** que una decisión histórica **no se habría tomado hoy** (porque las reglas cambiaron),
  lo que es información valiosa para auditar la evolución del control interno.

### 3.4 Decisión de acceso: se registra siempre o solo cuando importa

Registrar el trace completo en **cada** request es caro. La regla:

| Situación | Trace |
|---|---|
| Acción de Nivel 1 (crítica) | **Siempre** trace completo, en el mismo registro del evento |
| Acción de Nivel 2 | Trace completo |
| Acceso denegado | **Siempre** trace completo, aunque sea una lectura |
| Lectura de datos sensibles o de otra bodega | Trace completo |
| Lectura normal | Solo contador agregado (sin trace) |

Así el coste del trace se paga **solo donde el trace tiene valor probatorio**.

---

## 4. Esquema de datos

### 4.1 El ledger de eventos (auditoría principal)

```sql
audit_log (
  id                BIGSERIAL PK,

  -- ── CUÁNDO
  occurred_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  business_date     DATE NOT NULL,              -- fecha operativa (para turnos de noche)
  record_hash       TEXT NOT NULL,              -- hash del registro (integridad)
  prev_hash         TEXT,                       -- hash del anterior (cadena)

  -- ── QUIÉN (con snapshot, para que el histórico sea legible siempre)
  actor_user_id     BIGINT NULL REFERENCES users(id),
  actor_username    TEXT,
  actor_name        TEXT,
  actor_roles       TEXT[],                     -- snapshot de roles vigentes

  -- ── DE DÓNDE
  source_channel    audit_channel NOT NULL,     -- WEB | MOBILE | MOBILE_OFFLINE | SERVICE_ERP | …
  source_device     TEXT,                       -- identificador del dispositivo
  source_ip         INET,
  source_user_agent TEXT,
  source_service_key_id BIGINT NULL,            -- si fue una integración

  -- ── SOBRE QUÉ
  entity_type       TEXT NOT NULL,              -- 'invoice', 'kardex', 'role', 'policy'…
  entity_id         TEXT,
  entity_label      TEXT,                       -- snapshot legible: "Factura F-2024-0158"

  -- ── QUÉ
  action            TEXT NOT NULL,              -- 'INVOICE_LINE_DELETED', 'STOCK_MOVED'…
  action_level      SMALLINT NOT NULL,          -- 1 crítico | 2 relevante | 3 acceso
  previous_state    JSONB,
  new_state         JSONB,
  changed_fields    TEXT[],                     -- los campos que difieren

  -- ── POR QUÉ
  reason            TEXT,                       -- obligatorio si el dominio lo exige
  note              TEXT,

  -- ── CON QUÉ PERMISO
  permission_key    TEXT,                       -- 'invoice.approve'
  decision          audit_decision,             -- ALLOWED | DENIED | NOT_APPLICABLE
  decision_code     TEXT,                       -- 'OK' | 'POLICY_DENIED' | 'SEGREGATION_OF_DUTIES'…
  decision_stage    TEXT,                       -- IDENTITY | RBAC | ABAC
  access_trace      JSONB,                      -- el AccessDecisionTrace de §3.1

  -- ── RESULTADO
  result            audit_result NOT NULL,      -- SUCCESS | FAILURE | DENIED | PARTIAL
  error_code        TEXT,
  error_message     TEXT,
  duration_ms       INT,

  -- ── CORRELACIÓN
  correlation_id    UUID,                       -- agrupa un flujo completo
  causation_id      UUID,                       -- qué evento lo provocó
  idempotency_key   TEXT,                       -- para deduplicar reintentos
  request_id        TEXT,                       -- id de la petición HTTP

  -- ── TIEMPOS DEL MÓVIL OFFLINE
  offline_created_at TIMESTAMPTZ,               -- cuándo lo capturó el móvil
  synced_at          TIMESTAMPTZ,               -- cuándo llegó al servidor
  clock_skew_ms      BIGINT                     -- diferencia detectada con el reloj del servidor
);
```

**Índices obligatorios** (sin ellos, la auditoría es inútil porque no se puede consultar):

```sql
CREATE INDEX ON audit_log (occurred_at DESC);
CREATE INDEX ON audit_log (entity_type, entity_id, occurred_at DESC);  -- historia de un recurso
CREATE INDEX ON audit_log (actor_user_id, occurred_at DESC);           -- qué hizo una persona
CREATE INDEX ON audit_log (action, occurred_at DESC);
CREATE INDEX ON audit_log (correlation_id) WHERE correlation_id IS NOT NULL;
CREATE INDEX ON audit_log (decision, occurred_at DESC) WHERE decision = 'DENIED';
CREATE INDEX ON audit_log USING GIN (changed_fields);
```

### 4.2 Accesos agregados (para el Nivel 3)

```sql
access_counter (
  id           BIGSERIAL PK,
  bucket_hour  TIMESTAMPTZ NOT NULL,        -- truncado a la hora
  actor_user_id BIGINT NOT NULL,
  permission_key TEXT NOT NULL,
  entity_type  TEXT,
  scope        permission_scope,
  count        INT NOT NULL DEFAULT 0,
  denied_count INT NOT NULL DEFAULT 0,
  UNIQUE (bucket_hour, actor_user_id, permission_key, entity_type)
)
```

Un `UPSERT` que incrementa un contador es órdenes de magnitud más barato que un `INSERT` por
lectura, y responde la pregunta que de verdad se hace en una auditoría: **"¿qué consultó y cuánto,
y hubo denegaciones?"**.

### 4.3 Versionado de la configuración

Para que `configSnapshot` sea posible:

```sql
config_versions (
  id           BIGSERIAL PK,
  kind         TEXT NOT NULL,        -- 'permission_matrix' | 'policies'
  version      INT NOT NULL,
  snapshot     JSONB NOT NULL,       -- el estado completo en esa versión
  created_by   BIGINT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
  reason       TEXT,
  UNIQUE (kind, version)
)
```

Cada cambio de matriz o de políticas **incrementa la versión y guarda el snapshot completo**. El
coste de almacenamiento es trivial y a cambio se puede reproducir cualquier decisión histórica.

### 4.4 Inmutabilidad

La auditoría que se puede editar no es auditoría. Tres capas:

1. **Permisos de BD**: el rol de la aplicación puede `INSERT` y `SELECT` sobre `audit_log`, **nunca
   `UPDATE` ni `DELETE`**. Igual que el kardex.
2. **Trigger de bloqueo**: un trigger que rechaza `UPDATE`/`DELETE` incluso si alguien tiene permisos
   (defensa contra un `DELETE` accidental en un script de mantenimiento).
3. **Cadena de hashes**: cada registro lleva `record_hash = hash(prev_hash + campos_clave)`. Alterar
   un registro del medio **rompe la cadena** y se detecta con una verificación periódica.

```txt
record_hash = SHA256( prev_hash || id || occurred_at || actor || action || entity || new_state )
```

Esto convierte la auditoría en **tamper-evident**: no impide la alteración, pero la **hace
detectable**, que es lo máximo que se puede lograr sin hardware especializado. Es la misma lógica
que un libro contable foliado.

### 4.5 Retención

| Nivel | Retención | Justificación |
|---|---|---|
| Nivel 1 (crítico) | **Permanente** | Inventario y control de acceso: puede requerirse años después |
| Nivel 2 (relevante) | 3 años | Cubre auditorías y reclamaciones |
| Nivel 3 (accesos) | 1 año, luego agregado permanente | El detalle pierde valor; el agregado lo conserva |
| Nivel 4 (técnico) | 30 días | Observabilidad, no auditoría |

**La purga debe ser auditable.** Un job que borra registros de auditoría **genera un registro de
auditoría** diciendo cuántos y de qué rango. Si no, la purga es la forma perfecta de borrar pruebas.

**Particionado por mes** en `audit_log` desde el inicio: con el volumen esperado, una tabla sin
particionar se vuelve inmanejable en un año, y añadir el particionado después es una migración
dolorosa.

---

## 5. Cómo se implementa (y los errores a evitar)

### 5.1 Registrar en un solo sitio, no en cada controlador

El legacy no registra nada, y si se implementa a mano en cada controlador, en tres meses habrá 30
sitios que registran distinto y 10 que se olvidaron.

**La solución: intercepción transversal en dos niveles.**

```txt
Nivel 1 — Interceptor HTTP (Ktor plugin)
  · Captura automáticamente: actor, IP, user-agent, canal, request-id, duración, resultado,
    decisión de acceso (la tiene el plugin de autorización).
  · No sabe nada del dominio: no puede saber QUÉ cambió.

Nivel 2 — Anotación de caso de uso (declarativa en el DOMINIO)
  · Cada caso de uso declara qué audita, y el diff del cambio.
  · Se implementa una sola vez, no por controlador.
```

```kotlin
@Auditable(
    action = "INVOICE_LINE_CAPTURED",
    level = Level.CRITICAL,
    entity = "invoice_line",
    permission = Permission.InvoiceCapture,
    captureDiff = true,                    // genera previous_state / new_state
    reasonRequired = false
)
fun captureLine(command: CaptureLineCommand): InvoiceLine { … }
```

**Ventajas de la anotación:**

- **No se puede olvidar**: si un caso de uso crítico no tiene `@Auditable`, **un test falla**. Es la
  misma estrategia que el test de biyección de permisos ↔ rutas.
- El dominio no depende de la infraestructura de auditoría (es una anotación, no un logger).
- El diff se genera automáticamente, no campo por campo a mano (que es donde se olvidan campos).

### 5.2 El test que garantiza la cobertura

```kotlin
@Test
fun `todo caso de uso critico es auditable`() {
    val casosDeUsoCriticos = descubrirCasosDeUso()
        .filter { it.module in MODULOS_CRITICOS }

    val sinAuditar = casosDeUsoCriticos.filter { !it.tieneAnotacionAuditable() }

    assertTrue(sinAuditar.isEmpty(),
        "Casos de uso críticos sin auditoría: ${sinAuditar.map { it.name }}")
}
```

Es un test **estructural**: no verifica un comportamiento, verifica que **no falta un registro**. Es
la única forma de que "TODO debe ser auditable" siga siendo cierto en dos años.

### 5.3 Errores concretos a evitar

| # | Error | Por qué es grave | Prevención |
|---|---|---|---|
| E1 | Auditar solo lo que sale bien | Los intentos denegados y fallidos son la señal más valiosa (detección de abuso) | `result` incluye `DENIED` y `FAILURE`; las denegaciones se registran **siempre** |
| E2 | Guardar solo el `user_id` | Si el usuario se borra o renombra, el histórico queda ilegible | Snapshot de `username`, `name` y `roles` |
| E3 | Guardar solo la hora del servidor | El móvil puede capturar a las 22:00 y sincronizar a las 09:00 del día siguiente | `offline_created_at` + `synced_at` + `clock_skew_ms` |
| E4 | No registrar el origen | No se puede distinguir "lo hizo el ERP" de "alguien con una API key filtrada" | `source_channel` obligatorio |
| E5 | Registrar el permiso actual en la consulta, no el del momento | No responde si la acción fue legítima **entonces** | `access_trace` + `configSnapshot` |
| E6 | Auditoría editable | No es auditoría | Permisos de BD + trigger + cadena de hashes |
| E7 | Auditar todo con el mismo detalle | El volumen se vuelve inmanejable y nadie lee nada | 4 niveles de criticidad (§2) |
| E8 | Guardar el diff campo por campo a mano | Siempre se olvida un campo | `captureDiff` automático |
| E9 | Purgar sin auditar la purga | La purga se convierte en la forma de borrar pruebas | La purga genera su propio registro |
| E10 | Tabla sin particionar | En un año es inmanejable | Particionado mensual desde el inicio |
| E11 | Registrar datos sensibles en claro | La auditoría de contraseñas no debe contener contraseñas | Redacción de campos marcados como sensibles |
| E12 | Bloquear la operación si falla la auditoría | La auditoría no puede tumbar el almacén | Escritura **asíncrona** con buffer y alerta si se pierde |
| E13 | Auditar en el cliente | El cliente puede mentir | El servidor es la única fuente de auditoría; el móvil envía datos, **no registros de auditoría** |

**Sobre E12 y E13 hay un matiz importante.** La auditoría de una **acción crítica** (mover stock)
debe escribirse **en la misma transacción**: si el movimiento se confirma, su auditoría también. No
puede haber un movimiento sin rastro. Para las acciones **no críticas** (navegación, listados), la
escritura puede ser asíncrona para no penalizar la latencia.

---

## 6. Qué puede responder la auditoría

Esta es la prueba de fuego: si la auditoría no puede responder estas preguntas, no cumple el
requisito. Cada pregunta con la consulta que la responde.

| Pregunta | Cómo se responde |
|---|---|
| ¿Quién movió este producto y cuándo? | `audit_log` filtrando `entity_type='kardex'` + `entity_id`, con `actor_name` y `occurred_at` |
| ¿Por qué este producto tiene 200 unidades menos? | Cadena completa: el evento de ajuste con su `reason` y `adjustment_reason` |
| ¿Quién aprobó esta discrepancia y con qué autoridad? | El evento con `access_trace`: qué rol, qué alcance, **qué política decidió** |
| ¿Esa aprobación era legítima **en ese momento**? | `access_trace.configSnapshot` → se reproduce la decisión con la versión de la matriz y políticas de entonces |
| ¿Por qué ayer no me dejó aprobar? | El evento `DENIED` con `access_trace.abac.rules[].conditions[]`: **qué condición** falló |
| ¿Quién consultó los costos del inventario? | `access_counter` para `inventory.view_cost` + registros completos de Nivel 3 |
| ¿Quién exportó datos y cuántos? | Evento de exportación con filtros usados y número de filas |
| ¿De dónde vino este movimiento, del ERP o de una persona? | `source_channel` = `SERVICE_ERP` vs `MOBILE` |
| ¿Se capturó sin señal y se sincronizó después? | `offline_created_at` ≠ `occurred_at` + `clock_skew_ms` |
| ¿Alguien intentó algo que no debía? | Todos los `decision = 'DENIED'` agrupados por actor |
| ¿Quién cambió los permisos de este usuario? | `access_activity` con `previous_state`/`new_state` |
| ¿La auditoría fue alterada? | Verificación de la cadena de hashes: el primer `record_hash` que no cuadra indica el punto exacto |
| ¿Cuántos registros se purgaron y cuándo? | El propio registro de purga |

---

## 7. Pantallas de auditoría

Cuatro vistas, más el detalle. La auditoría que no se puede leer no se usa.

### 7.1 `/audit/log` — Bitácora general

- `ITDataTable` server-side sobre `audit_log`, con filtros por actor, acción, entidad, canal, nivel,
  decisión y rango de fechas.
- Columnas: fecha/hora, actor (nombre + usuario), acción, entidad (con su `entity_label`), canal,
  resultado, y un indicador de permiso (permitido/denegado).
- **Filtro rápido "solo denegados"** y "solo críticos" al alcance de un clic.

### 7.2 `/audit/entity/:type/:id` — Historia de un recurso

La vista más útil en el día a día: **la línea de tiempo de un producto, una factura o una línea**,
en orden, con el diff de cada cambio. Responde *"¿qué le pasó a esto?"* sin escribir SQL.

### 7.3 `/audit/trace/:correlationId` — Un flujo completo

Reconstruye una operación entera: la recepción con sus 30 líneas, quién hizo cada una, qué se
denegó por el camino y cuánto tardó. Es la vista que se usa para investigar un descuadre.

### 7.4 `/audit/actor/:userId` — Actividad de una persona

Qué hizo, qué le fue denegado, desde qué dispositivos, en qué horarios. Útil para
**detectar patrones** (alguien que consulta costos a las 3 de la mañana, o que acumula denegaciones).

### 7.5 Detalle de un registro

Panel lateral con:
- Las 6 dimensiones completas (quién, cuándo, dónde, de dónde, permiso, qué cambió).
- **El trace de acceso desplegable**: las 3 etapas y **cada condición evaluada** de cada política,
  con el valor esperado y el real.
- **Diff visual** del cambio (`previous_state` → `new_state`), campo por campo.
- El registro anterior y siguiente en la cadena de hashes, con su verificación.

### 7.6 `/audit/integrity` — Verificación de la cadena

Pantalla que corre la verificación de hashes y muestra: total de registros, primer registro inválido
(si lo hay), y el resultado del último job de verificación. **Es la pantalla que permite afirmar
"la auditoría no fue alterada"** — sin ella, la cadena de hashes es un detalle técnico invisible que
nadie comprueba.

---

## 8. Contrato de API

```txt
GET  /v1/audit/log                     audit.view   tabla server-side, todos los filtros
GET  /v1/audit/entity/:type/:id        audit.view   historia de un recurso
GET  /v1/audit/trace/:correlationId    audit.view   flujo completo
GET  /v1/audit/actor/:userId           audit.view   actividad de una persona
GET  /v1/audit/:id                     audit.view   un registro con su trace completo
GET  /v1/audit/:id/diff                audit.view   el diff estructurado
GET  /v1/audit/integrity               report.audit verificación de la cadena de hashes
POST /v1/audit/integrity/verify        report.audit disparar verificación manual
GET  /v1/audit/export                  report.audit exportación (registra el propio export)
GET  /v1/audit/access-summary          audit.view   accesos agregados por persona/permiso
GET  /v1/audit/denied                  audit.view   solo denegaciones, agrupadas por actor
```

**Nota:** `audit.view` y `report.audit` son permisos **sensibles**. Consultar la auditoría de otros
es en sí mismo un evento auditable — si no, quien tiene acceso a la auditoría puede espiar sin
rastro.

---

## 9. Pruebas de la auditoría

Sin tests, la auditoría se degrada silenciosamente: alguien añade un caso de uso y se olvida de
registrar. Estos tests lo impiden.

### 9.1 Cobertura estructural (los más importantes)

| # | Test | Qué garantiza |
|---|---|---|
| U1 | **Todo caso de uso crítico tiene `@Auditable`** | Que no falte un registro (§5.2). Falla el build si alguien añade un caso de uso sin auditar |
| U2 | **Toda acción con efecto en inventario genera un registro** | Ningún movimiento sin rastro |
| U3 | **Toda decisión de acceso denegada se registra** | Incluso en lecturas |
| U4 | **Todo permiso sensible usado genera registro de Nivel 3** | Consulta de costos, exportaciones |
| U5 | **Todo cambio de rol, política o matriz genera evento** | Control de acceso auditado |
| U6 | **Toda entidad del catálogo de dominio tiene `entity_label`** | Que los reportes de auditoría sean legibles |

### 9.2 Contenido del registro

| # | Test | Qué garantiza |
|---|---|---|
| U7 | Un movimiento registra **las 6 dimensiones** | Quién, cuándo, dónde, de dónde, permiso, qué |
| U8 | El `access_trace` contiene el estado **del momento**, no el actual | Se cambia un permiso después y el registro histórico no cambia |
| U9 | El `configSnapshot` permite **reproducir** la decisión | Se reproduce con la versión guardada y da el mismo resultado |
| U10 | Un `DENIED` guarda **las condiciones evaluadas** con valor esperado y real | El "por qué no" es accionable |
| U11 | El `previous_state`/`new_state` cubre **todos** los campos modificados | El diff automático no omite nada |
| U12 | Un ajuste sin `reason` **no se puede registrar** | El motivo es parte de la auditoría |
| U13 | Los campos sensibles salen **redactados** | La auditoría de contraseñas no contiene contraseñas |
| U14 | Un movimiento offline guarda **las dos horas** y el `clock_skew_ms` | Se distingue captura de sincronización |
| U15 | Un movimiento del ERP lleva `source_channel = SERVICE_ERP` | Se distingue persona de integración |

### 9.3 Inmutabilidad e integridad

| # | Test | Qué garantiza |
|---|---|---|
| U16 | `UPDATE` sobre `audit_log` **falla** | Inmutabilidad por permisos de BD |
| U17 | `DELETE` sobre `audit_log` **falla** | Inmutabilidad |
| U18 | Alterar un registro del medio **rompe la cadena** y la verificación lo detecta | Tamper-evidence |
| U19 | La purga **genera su propio registro** | La purga no es una forma de borrar pruebas |
| U20 | La cadena se verifica correctamente sobre 100.000 registros | Rendimiento aceptable |

### 9.4 Atomicidad y comportamiento bajo fallo

| # | Test | Qué garantiza |
|---|---|---|
| U21 | Si falla la transacción del movimiento, **no queda registro huérfano** | Consistencia |
| U22 | Si se registra el movimiento, **su auditoría está en la misma transacción** | No hay movimiento sin rastro |
| U23 | Si el buffer asíncrono se llena, **hay alerta** y no se pierde silenciosamente | No se pierde auditoría en silencio |
| U24 | La auditoría **no bloquea** la operación en acciones no críticas | El sistema no se frena |

### 9.5 Consultabilidad (una auditoría que no se consulta no sirve)

| # | Test | Qué garantiza |
|---|---|---|
| U25 | Las 13 preguntas de §6 se responden con las consultas documentadas | La auditoría responde lo que tiene que responder |
| U26 | Los listados de auditoría están **paginados** con `limit` máximo | No se cae el servidor al abrir la bitácora |
| U27 | Los índices cubren las consultas de §6 | Las consultas no hacen table scan |
| U28 | El particionado mensual se crea automáticamente | No se pierde un mes por olvido |

**U8 y U9 son los tests que hacen la diferencia.** Garantizan que la auditoría responda *"¿estaba
autorizado **entonces**?"* y no *"¿está autorizado **ahora**?"*, que es la diferencia entre una
auditoría real y una ilusión de auditoría.

---

## 10. Impacto en lo ya diseñado

Este requisito cambia cosas en los otros tres documentos. Los cambios concretos:

| Documento | Cambio |
|---|---|
| `PLAN_ALMACEN_INTEGRAL.md` | `audit_log` pasa de tabla menor a **ledger particionado con cadena de hashes**. El `stock-domain` gana la anotación `@Auditable` en sus casos de uso. La §2.2-quater gana la fila "sin auditoría alguna" |
| `PLAN_ROLES_PERMISOS_POLITICAS.md` | `access_activity` **no es suficiente**: se amplía con el `access_trace` de §3.1. Se añaden `config_versions` (matriz y políticas versionadas) y `access_counter`. `audit.view` y `report.audit` quedan como permisos **sensibles** cuya consulta se audita. Los tests pasan de 50 a incluir los 28 de auditoría |
| `PLAN_REFACTOR_MEJORAS.md` | Nueva clase de hallazgo: **"sin auditoría"**. El legacy no tiene *ninguna* tabla de auditoría, y su `Kardex` (el único rastro) **es mutable** por `removeUnitsFromKardex`. Se añade como defecto de severidad **Crítica** |
| Esquema de datos | 3 tablas nuevas: `audit_log` (particionada), `access_counter`, `config_versions` |
| Roadmap | La auditoría se implementa **en Fase 0/1**, no al final: si se deja para después, hay que instrumentar retroactivamente todo lo ya escrito y siempre se olvidan casos |

**El punto más importante del impacto:** el legacy **no tiene nada que auditar hoy**. Eso significa
que **no hay datos históricos que preservar** y el sistema nuevo puede nacer bien. Es una ventaja:
la auditoría se diseña desde el primer día en lugar de parchearse.

---

## 11. Decisiones abiertas

1. ~~¿Cuánto conservar el Nivel 1?~~ **CERRADA (R-78):** Nivel 1 **permanente** · Nivel 2 3 años ·
   Nivel 3 1 año · Nivel 4 30 días. Las particiones se crean con esta política desde el inicio.
2. **¿Alguien fuera de `ADMIN` y `AUDITOR` debe ver la auditoría?** Propongo que no, y que **ver la
   auditoría sea en sí mismo auditable**.
3. ~~¿Cadena de hashes o almacenamiento externo?~~ **CERRADA (R-79): cadena interna + verificación
   periódica.** 

   **Riesgo residual aceptado y documentado:** un administrador de base de datos con acceso total
   puede recalcular la cadena completa y alterar la auditoría sin que se detecte. Lo que **sí** se
   detecta es cualquier alteración accidental, cualquier cambio hecho a través de la aplicación, y
   cualquier `DELETE` o `UPDATE` que no recalcule la cadena entera. Si en el futuro el requisito
   cambia a *"ni el DBA puede alterar sin que se note"*, la mitigación es exportar los hashes a
   almacenamiento externo inmutable; el puerto de exportación queda **preparado y sin usar** en el
   diseño, para no rediseñar después.
4. **¿Se auditan las lecturas de listados con detalle o con contador?** Propongo **contador** (§2),
   salvo datos sensibles. Si el negocio quiere el detalle de toda lectura, el volumen crece ~20x y
   hay que dimensionar el almacenamiento en consecuencia.
5. **¿El personal de piso debe poder consultar su propia actividad?** Puede ser útil para resolver
   dudas ("no fui yo") y también puede ser problemático. Propongo que no.
6. **Conteo físico (R-73/R-81):** el conteo antes del corte lo ejecuta **personal de almacén** con
   una **pantalla de conteo nueva** (asignación de zonas por el jefe de bodega). Cada conteo genera
   un evento de auditoría con `source_channel = MOBILE` o `WEB` y su resultado.
7. **¿Qué pasa con la auditoría al migrar?** Los datos del legacy **no tienen auditoría**. ¿Se
   registra un evento de `MIGRATION` por cada registro migrado (1.398 productos + 10,9M de unidades),
   o un evento agregado por lote? Propongo **un evento por lote con el detalle en `metadata`**, y
   dejarlo documentado como "origen desconocido, migrado" en lugar de inventar un actor.
