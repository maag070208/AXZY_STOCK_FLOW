# Diseño de Roles, Permisos y Políticas — Almacén

> Documento de diseño del **control de acceso** del sistema nuevo: RBAC + ABAC híbrido,
> con pantallas de administración, motor de decisión y estrategia de migración desde el
> legacy de `BASE/backend`.
>
> Estado: **propuesta para aprobación**. Complementa a `PLAN_ALMACEN_INTEGRAL.md`.
> No se instalaron dependencias ni se modificó código de los repos de origen.

---

## 0. Resumen

Pediste *"modificar por completo el flujo de ROLES, con pantalla para administrar roles y
políticas, combinando ABAC con RBAC"*. Lo bueno es que **no hay que inventar el modelo**: tu repo
`~/DEV/PTNV/RESPONSIVA/web` ya tiene ABAC en producción, con consola completa (7 hooks, 30
componentes), simulación de acceso, bitácora y hasta un pipeline de decisión en tres etapas. Este
documento **adapta ese modelo probado al dominio de almacén**, que tiene una diferencia importante:
aquí el alcance no es "mi área" sino **bodega y ubicación**.

**Qué es RBAC y qué es ABAC, en una línea:**

- **RBAC** (Role-Based Access Control) responde **qué** puede hacer alguien: `invoice.approve`.
  Se administra asignando permisos a roles.
- **ABAC** (Attribute-Based Access Control) responde **bajo qué condiciones**: "solo si la
  diferencia es menor a 50 piezas", "solo si es su misma bodega", "solo entre 8 y 18 h".
  Se administra con **políticas** que evalúan atributos del usuario, del recurso y del contexto.

**Por qué los dos y no uno:** RBAC solo no puede expresar "aprobar discrepancias menores a 50
piezas" sin crear un rol por cada umbral (explosión de roles). ABAC solo es difícil de auditar,
porque no hay forma de responder "¿qué puede hacer este usuario?" sin simular. El híbrido usa RBAC
para la estructura visible y ABAC para las condiciones finas.

**El pipeline de decisión (tomado del modelo que ya funciona en `RESPONSIVA`):**

```txt
Petición: (usuario, acción, recurso, contexto)
   │
   ├─ 1. IDENTIDAD   ¿usuario existe, activo, no bloqueado?
   │                 ✗ → 401
   │
   ├─ 2. RBAC        ¿tiene el permiso `acción` con alcance suficiente para el recurso?
   │                 ✗ → 403 PERMISSION_DENIED
   │
   ├─ 3. ABAC        evaluar políticas activas que apliquen (acción + roles del usuario)
   │                 · alguna DENY que matchea  → 403 POLICY_DENIED   (deny gana siempre)
   │                 · alguna ALLOW que matchea → permitir
   │                 · solo DENY y ninguna ALLOW → 403 POLICY_REQUIRED
   │                 · sin políticas que apliquen → DEFAULT DENY      (403)
   │
   └─ 4. AUDITORÍA   registrar la decisión si es sensible o fue denegada
```

**La regla que sostiene todo: `DEFAULT DENY`.** Si ninguna política permite explícitamente, se
niega. El legacy hacía lo contrario en la práctica: tenía un bypass `if (role === 1) return next()`
y el rol admin **no tenía ni un permiso sembrado en base de datos**. Eso es *default allow*, y es lo
que hay que eliminar de raíz (hallazgos S2 y S3 de `PLAN_ALMACEN_INTEGRAL.md` §2.2-ter).

---

## 1. Nomenclatura de permisos: la corrección clave

### 1.1 El problema de tu ejemplo

Escribiste:

```txt
ROLE ALMACEN
ALMACEN:CREA
ALMACEN:SACA
```

El formato `ACCION:RECURSO` tiene **dos ambigüedades** que conviene resolver ahora, antes de
sembrar 80 permisos:

1. **Dirección inconsistente**: `ALMACEN:CREA` se lee "almacén : crea", o sea **recurso:acción**,
   pero el legacy usa lo contrario (`invoice:create` = **recurso:acción**). Si mezclamos ambas
   direcciones, nadie podrá leer el catálogo de un vistazo.
2. **Granularidad excesiva del recurso**: `ALMACEN` como recurso es demasiado amplio.
   `ALMACEN:SACA` no distingue entre **sacar para una orden** (surtido normal), **sacar por merma**
   (requiere motivo y aprobación) o **sacar por traslado a otra bodega**. Son tres operaciones con
   riesgo y permisos distintos.

Además, `CREA`/`SACA` no escalan: cuando aparezcan 20 acciones de almacén tendrás 20 verbos
inventados, en lugar de un vocabulario cerrado y predecible.

### 1.2 La convención que propongo

**Formato: `recurso.accion` — minúsculas, separado por punto.** Es la que ya usás en producción y
la que el editor de políticas necesita para agrupar por recurso y ordenar por ciclo de vida.

```txt
invoice.capture        order.package        inventory.move
invoice.approve        inventory.adjust_shortage
```

**Vocabulario de acciones cerrado** (el catálogo valida contra esta lista):

| Acción | Significado | Ejemplo en almacén |
|---|---|---|
| `view` | Ver, sin exportar | Ver la lista de facturas |
| `create` | Crear | Alta de factura |
| `edit` | Modificar antes de que tenga efecto | Corregir una línea antes de capturar |
| `delete` | Anular (siempre con trazabilidad) | Anular una línea |
| `capture` | **Registrar el conteo físico** | Capturar cantidades contadas |
| `locate` | **Asignar a una ubicación física** | Ubicar la caja en el estante |
| `move` | Mover entre ubicaciones de la misma bodega | Cambiar de estante |
| `transfer` | Mover entre bodegas | Enviar a otra bodega |
| `package` | Surtir y cerrar empaque | Completar una orden |
| `adjust` | **Ajustar inventario con motivo** | Merma, corrección de conteo |
| `approve` | Autorizar algo que otro hizo | Aprobar una discrepancia |
| `reject` | Rechazar y devolver a captura | Rechazar una discrepancia |
| `print` | Encolar impresión | Etiquetas |
| `reprint` | Reimprimir (con trazabilidad) | Reimprimir por daño |
| `export` | Extraer datos | Reporte a Excel |
| `assign` | Asignar trabajo a una persona | Asignar factura a un operador |

**Tus dos ejemplos quedan así:**

| Tu ejemplo | Permiso propuesto | Por qué |
|---|---|---|
| `ALMACEN:CREA` | `inventory.adjust_increase` | "Crear inventario" no existe en almacén; lo que existe es **ajustar hacia arriba** y necesita motivo |
| `ALMACEN:SACA` | `order.package` (sacar para surtir)<br>`inventory.adjust_shortage` (sacar por merma)<br>`transfer.dispatch` (sacar para otra bodega) | Son tres operaciones distintas, con permisos y riesgos distintos |

Esta descomposición es el corazón de la mejora: **"sacar" es el permiso más peligroso de un
almacén**, y el legacy tenía un solo `location:read` cubriendo mover, ajustar y consultar. Fíjate en
el legacy: `PATCH /locations/inventory` (mover) y `POST /locations/inventory` (ajustar) usan
**ambos** `location:read`. Eso significa que **consultar y ajustar inventario tienen el mismo
permiso**.

### 1.3 Alcance (scope): el segundo eje de RBAC

Un permiso no es solo sí/no. `inventory.view` puede significar "ver todo el almacén" o "ver solo mi
bodega". El modelo de referencia usa cuatro alcances; los adapto al dominio de almacén:

| Alcance | Significado en almacén | Ejemplo |
|---|---|---|
| `NONE` | Sin acceso | — |
| `OWN` | **Solo sus asignaciones / su ubicación** | Un operador ve solo las facturas asignadas a él |
| `AREA` | **Su bodega** (o las bodegas que tenga asignadas) | El jefe de bodega ve todas las ubicaciones de su bodega |
| `ALL` | **Todas las bodegas** | Gerente de operaciones |

```ts
export type PermissionScope = "NONE" | "OWN" | "AREA" | "ALL";
```

**Correspondencia con el negocio de almacén:**

| Nivel | Quién es | Alcance típico |
|---|---|---|
| `OWN` | Operador de piso | Sus capturas, su ubicación asignada |
| `AREA` | Jefe de bodega | Toda su bodega, todos sus pasillos |
| `ALL` | Gerente / admin | Todas las bodegas, reportes consolidados |

Esto **resuelve la multi-bodega gratis**: no hace falta crear roles por bodega. Un rol
`JEFE_BODEGA` con `inventory.view = AREA` sirve para cualquier bodega; qué bodega es "su area" se
determina por la asignación de la persona, no por el rol.

---

## 2. Catálogo de permisos para el dominio de almacén

Ocho módulos, **76 permisos**. Cada uno declara: clave, alcances admitidos, sensibilidad y estado.

```txt
── MÓDULO: identidad
user.view                 OWN AREA ALL      user.create        ALL
user.edit                 ALL                user.delete        ALL  (sensible)
user.reset_password       ALL  (sensible)    role.manage        ALL  (sensible)
role.assign               ALL  (sensible)    permission.exception ALL (sensible)
audit.view                OWN AREA ALL       session.revoke     ALL  (sensible)

── MÓDULO: catálogo
product.view              ALL                product.create     ALL
product.edit              ALL                product.delete     ALL  (sensible)
product.import            ALL  (sensible)    product.sync_barcode ALL (sensible)

── MÓDULO: almacén físico
warehouse.view            ALL                warehouse.create   ALL
warehouse.edit            ALL                location.view      OWN AREA ALL
location.create           AREA ALL           location.edit      AREA ALL
location.delete           ALL  (sensible)    location.print_labels AREA ALL

── MÓDULO: recepción
invoice.view              OWN AREA ALL       invoice.create     AREA ALL
invoice.edit              OWN AREA ALL       invoice.delete     ALL  (sensible)
invoice.capture           OWN AREA ALL       invoice.assign     AREA ALL
invoice.locate            OWN AREA ALL       invoice.approve    AREA ALL  (sensible)
invoice.reject            AREA ALL           invoice.close      AREA ALL
invoice.import_external   ALL  (sensible, de servicio: kardo)

── MÓDULO: surtido
order.view                OWN AREA ALL       order.create       AREA ALL
order.edit                OWN AREA ALL       order.delete       ALL  (sensible)
order.assign              AREA ALL           order.package      OWN AREA ALL
order.confirm             AREA ALL           order.convert      ALL  (sensible)

── MÓDULO: inventario
inventory.view            OWN AREA ALL       inventory.view_cost ALL  (sensible)
inventory.move            OWN AREA ALL       inventory.adjust_increase AREA ALL
inventory.adjust_shortage AREA ALL (sensible) inventory.adjust_damage   AREA ALL (sensible)
inventory.adjust_theft    ALL      (sensible) inventory.adjust_count_error AREA ALL
inventory.approve_adjustment AREA ALL (sensible) inventory.count   OWN AREA ALL
inventory.reconcile       ALL      (sensible) kardex.view        OWN AREA ALL
kardex.export             AREA ALL

── MÓDULO: transferencias y tránsito
transfer.view             OWN AREA ALL       transfer.create    AREA ALL
transfer.dispatch         AREA ALL           transfer.receive   AREA ALL
transfer.cancel           ALL  (sensible)    transit.view       AREA ALL

── MÓDULO: etiquetado
label.print               OWN AREA ALL       label.reprint      AREA ALL
label.template_view       AREA ALL           label.template_edit ALL  (sensible)
label.queue_view          AREA ALL           label.queue_manage  ALL  (sensible, de servicio: Zebra)

── MÓDULO: reportes
report.view               OWN AREA ALL       report.export      AREA ALL
report.financial          ALL  (sensible)    report.audit       ALL  (sensible)

── MÓDULO: sistema
system.config             ALL  (sensible)    system.health      ALL
print_device.manage       ALL  (sensible)    integration.manage ALL  (sensible)
```

**Notas de diseño importantes:**

1. **`inventory.adjust_*` está descompuesto por motivo.** No es lo mismo ajustar por error de
   conteo que por robo. Un jefe de bodega puede corregir un conteo; **declarar un robo debería
   requerir más autoridad** porque tiene implicaciones legales. Con el permiso único del legacy,
   ambas operaciones eran indistinguibles (y además compartían permiso con *consultar*).
2. **`inventory.view_cost` separado de `inventory.view`.** Ver el costo del inventario es
   información sensible que no todos los operadores deben tener.
3. **Los permisos de servicio están marcados.** `invoice.import_external` (ERP / `kardo`) y
   `label.queue_manage` (Zebra) son los que consumen sistemas externos. **No son de usuario: se
   autentican con API key de servicio** y quedan auditados por separado.
4. **Todo permiso `sensible` se audita al usarse**, no solo al denegarse.

---

## 3. Roles propuestos para el almacén

Cinco roles base. La regla: **cada rol debe poder explicarse en una frase.** Si no se puede, es que
está mal cortado.

| Rol (clave) | Nombre visible | Una frase | Alcance típico |
|---|---|---|---|
| `ADMIN` | Administrador | Puede todo, incluida la configuración | `ALL` |
| `GERENTE_OPERACIONES` | Gerente de operaciones | Ve y aprueba todo, sin administrar el sistema | `ALL` |
| `JEFE_BODEGA` | Jefe de bodega | Gobierna su bodega: captura, ubica, aprueba y ajusta | `AREA` |
| `OPERADOR_ALMACEN` | Operador de almacén | Ejecuta el trabajo que le asignan, sin aprobar nada | `OWN` |

> **R-77:** el operador **sí tiene `invoice.locate`**. En el legacy no lo tenía aunque ubicar es su
> trabajo principal: era un hueco del modelo de permisos, no una decisión. Se le concede con
> alcance `OWN` (ubica lo que él recibió).
| `AUDITOR` | Auditor | Solo mira: reportes, kardex y bitácora; no toca nada | `ALL` (lectura) |

### 3.1 Matriz de permisos por rol (extracto significativo)

Convención: `✓` = `ALL` · `A` = `AREA` · `O` = `OWN` · `—` = `NONE`

| Permiso | ADMIN | GERENTE | JEFE_BODEGA | OPERADOR | AUDITOR |
|---|---|---|---|---|---|
| `invoice.view` | ✓ | ✓ | A | O | ✓ |
| `invoice.create` | ✓ | ✓ | A | — | — |
| `invoice.capture` | ✓ | ✓ | A | O | — |
| `invoice.locate` | ✓ | ✓ | A | O | — |
| `invoice.assign` | ✓ | ✓ | A | — | — |
| **`invoice.approve`** | ✓ | ✓ | **A** | **—** | — |
| **`invoice.locate`** | ✓ | ✓ | A | **O** | — |
| `invoice.delete` | ✓ | — | — | — | — |
| `order.view` | ✓ | ✓ | A | O | ✓ |
| `order.package` | ✓ | ✓ | A | O | — |
| `order.confirm` | ✓ | ✓ | A | — | — |
| `inventory.view` | ✓ | ✓ | A | O | ✓ |
| `inventory.move` | ✓ | ✓ | A | **O** | — |
| `inventory.adjust_count_error` | ✓ | ✓ | A | — | — |
| `inventory.adjust_shortage` | ✓ | ✓ | A | — | — |
| `inventory.adjust_damage` | ✓ | ✓ | A | — | — |
| **`inventory.adjust_theft`** | ✓ | **✓** | **—** | — | — |
| `inventory.approve_adjustment` | ✓ | ✓ | A | — | — |
| `inventory.reconcile` | ✓ | — | — | — | — |
| `kardex.view` | ✓ | ✓ | A | O | ✓ |
| `transfer.dispatch` | ✓ | ✓ | A | — | — |
| `transfer.receive` | ✓ | ✓ | A | — | — |
| `label.print` | ✓ | ✓ | A | O | — |
| `report.view` | ✓ | ✓ | A | — | ✓ |
| `report.export` | ✓ | ✓ | A | — | ✓ |
| `report.financial` | ✓ | ✓ | — | — | ✓ |
| `report.audit` | ✓ | ✓ | — | — | ✓ |
| `product.view` | ✓ | ✓ | ✓ | ✓ | ✓ |
| `product.create` / `edit` | ✓ | — | — | — | — |
| `location.view` | ✓ | ✓ | A | O | ✓ |
| `location.create` / `edit` | ✓ | — | A | — | — |
| `user.view` | ✓ | ✓ | A | — | ✓ |
| `user.create` / `edit` | ✓ | — | — | — | — |
| `role.manage` | ✓ | — | — | — | — |
| `system.config` | ✓ | — | — | — | — |

### 3.2 Las decisiones discutibles, explicadas

Tres celdas de esta matriz son deliberadas y conviene que las revises:

1. **El OPERADOR no tiene `invoice.approve`.** Esto es lo que hace real la regla B1 del plan
   (discrepancia bloquea hasta aprobación). Si le diéramos `approve` al operador, **podría aprobar
   su propio error de conteo** y el control sería decorativo.
2. **`inventory.adjust_theft` es exclusivo de ADMIN y GERENTE.** Declarar un robo no es una
   operación de piso: mueve inventario *y* tiene implicaciones legales.
3. **El AUDITOR tiene `report.financial` pero no un solo permiso de escritura.** Es el rol que
   permite auditar el sistema sin poder alterarlo, y es la razón por la que el sistema nuevo
   necesita kardex append-only: **un auditor no puede confiar en un libro que se puede editar**, y
   hoy `removeUnitsFromKardex` hace `kardex.update`.

### 3.3 Lo que NO proponemos

- **No proponemos roles por bodega** (`JEFE_BODEGA_NORTE`, `JEFE_BODEGA_SUR`…). El alcance `AREA`
  más la asignación de la persona resuelve esto sin multiplicar roles.
- **No proponemos un rol `SUPERVISOR` genérico.** "Supervisor" no dice de qué; en la práctica
  termina con permisos de todo para no bloquear el trabajo. Es el rol que destruye un modelo de
  permisos.
- **No proponemos `OPERADOR_ALMACEN` con `inventory.adjust`.** Ajustar inventario sin aprobación
  es la vía más rápida a un descuadre no detectado.

---

## 4. ABAC: políticas para el almacén

### 4.1 Modelo de política

Tomado del que ya funciona en `RESPONSIVA`, con los mismos conceptos:

```ts
export type PolicyEffect = "ALLOW" | "DENY";

export interface PolicyCondition {
  field: string;                        // p.ej. "resource.difference"
  operator: string;                     // "<=", ">", "==", "in", "!=" ...
  value: string | null;
}

export interface Policy {
  id: string;
  key: string | null;
  name: string;                         // "Aprobación de discrepancias menores a 50 pzs"
  description: string | null;
  action: string;                       // permiso sobre el que actúa: "invoice.approve"
  effect: PolicyEffect;
  priority: number;                     // mayor gana en empates; DENY gana siempre
  active: boolean;
  roles: string[];                      // a qué roles aplica (vacío = a todos)
  conditions: PolicyCondition[];        // TODAS deben cumplirse (AND)
  createdAt: string;
}
```

**Reglas de evaluación (críticas, y hay que implementarlas exactamente así):**

1. Solo se evalúan políticas `active` cuya `action` coincida y cuyos `roles` incluyan alguno del
   usuario (o estén vacíos).
2. Las `conditions` se combinan con **AND**.
3. **Si alguna política `DENY` matchea, se deniega**, sin importar cuántas `ALLOW` matcheen.
4. Si no hay ninguna `DENY` y al menos una `ALLOW` matchea, se permite.
5. Si hay políticas que aplican **pero ninguna `ALLOW` matchea** (solo `DENY` que no matchearon),
   se deniega con `POLICY_REQUIRED`. Esto es lo que hace útil a ABAC: para acciones con políticas,
   **RBAC ya no basta**.
6. Si no aplica ninguna política: `DEFAULT DENY`.

La regla 5 es la que más se malinterpreta. Ejemplo: si existe la política *"Aprobación de
discrepancias menores a 50 pzs → ALLOW para JEFE_BODEGA"*, entonces un `JEFE_BODEGA` con
`invoice.approve` intentando aprobar una diferencia de **200** piezas **es denegado**, aunque su rol
tenga el permiso. La política **sube el listón**, no lo baja.

### 4.2 Catálogo de atributos disponibles en almacén

Esto es lo que hace que ABAC sea útil aquí y no decorativo. Los atributos deben ser **del dominio**,
no genéricos:

| Grupo | Atributo | Tipo | Para qué |
|---|---|---|---|
| **Recurso** | `resource.warehouseId` | enum | Limitar a la bodega del usuario |
| | `resource.locationId` | enum | Limitar a ubicaciones concretas |
| | `resource.locationKind` | enum (`SHELF`/`RECEIVING`) | Reglas distintas en recepción |
| | `resource.productId` | enum | Productos de alto valor |
| | `resource.difference` | number | **Umbral de discrepancia** |
| | `resource.quantity` | number | Umbral de cantidad |
| | `resource.adjustmentReason` | enum | Distinguir merma de robo |
| | `resource.unitCost` | number | Umbral de valor |
| | `resource.totalValue` | number | Umbral de valor total |
| **Usuario** | `user.role` | enum | Aplicar por rol |
| | `user.warehouseIds` | enum[] | Bodegas asignadas |
| | `user.hasApprovedToday` | boolean | Evitar exceso de aprobaciones |
| **Contexto** | `context.hour` | number | **Restringir a horario laboral** |
| | `context.weekday` | number | No permitir ciertas acciones en fin de semana |
| | `context.deviceType` | enum (`MOBILE`/`WEB`/`SERVICE`) | Piso vs oficina |
| | `context.offlineSync` | boolean | Operación sincronizada desde el móvil |
| | `context.ip` | string | Red interna |

### 4.3 Políticas concretas propuestas

Ejemplos reales, no teóricos. Cada una resuelve un problema operativo concreto:

| # | Nombre | Acción | Efecto | Condiciones | Problema que resuelve |
|---|---|---|---|---|---|
| PB1 | ~~Aprobación de discrepancias menores~~ **ELIMINADA** | — | — | — | **Decisión R-75: sin umbral.** Toda discrepancia requiere aprobación. Al no haber umbral, PB1 y PB2 desaparecen y `invoice.approve` queda gobernado solo por RBAC + PB3 |
| PB2 | ~~Discrepancias grandes requieren gerencia~~ **ELIMINADA** | — | — | — | Ver PB1 |
| PB3 | Prohibido aprobar lo propio | `invoice.approve` | `DENY` | `resource.capturedBy == user.id` | **Segregación de funciones**: nadie firma su propio error |
| PB4 | Ajuste por robo solo con gerencia | `inventory.adjust_theft` | `ALLOW` | `user.role == 'GERENTE_OPERACIONES'` | Mover el permiso de robo a un atributo verificable |
| PB5 | Ajustes solo en horario laboral | `inventory.adjust_shortage`, `inventory.adjust_damage` | `ALLOW` | `context.hour >= 8` **AND** `context.hour < 18` | Evita ajustes nocturnos sin supervisión |
| PB6 | Ajustes de fin de semana escalan | `inventory.adjust_*` | `DENY` | `context.weekday in [0, 6]` **AND** `user.role == 'JEFE_BODEGA'` | El fin de semana solo gerencia ajusta |
| PB7 | Productos de alto valor | `inventory.move` | `DENY` | `resource.unitCost > 5000` **AND** `user.role == 'OPERADOR_ALMACEN'` | Los productos caros los mueve el jefe de bodega |
| PB8 | Ubicar desde recepción sin restricción horaria | `invoice.locate` | `ALLOW` | `resource.locationKind == 'RECEIVING'` **AND** `user.role == 'OPERADOR_ALMACEN'` | Recibir y ubicar es la operación más frecuente: no se estorba |
| PB9 | Sincronización offline marcada | `invoice.capture` | `ALLOW` | `context.offlineSync == true` **AND** `user.role == 'OPERADOR_ALMACEN'` | Permitir captura diferida pero dejarla auditada |
| PB10 | Exportación masiva auditada | `report.export` | `ALLOW` | `resource.totalValue > 100000` **AND** `user.role in ['GERENTE_OPERACIONES','ADMIN']` | Sacar inventario de valor alto requiere autoridad |
| PB11 | Traslado entre bodegas | `transfer.dispatch` | `ALLOW` | `user.role in ['JEFE_BODEGA','GERENTE_OPERACIONES']` | Los traslados no son operación de piso |
| PB12 | Bloqueo de servicio en horario | `invoice.import_external` | `ALLOW` | `context.deviceType == 'SERVICE'` | La integración del ERP solo entra por el canal de servicio |

**Decisión cerrada R-74: segregación de funciones ACTIVA.** PB3 se mantiene: quien captura no
aprueba. Es el control interno más fuerte y fue confirmado explícitamente.

**Decisión cerrada R-75: SIN umbral.** Toda discrepancia requiere aprobación. Esto **elimina PB1 y
PB2** (ya no hay niveles) y concentra el control en PB3. **Consecuencia operativa que hay que
dimensionar:** con un contenedor de 40 líneas y 15 diferencias de una pieza, se generan 15
aprobaciones que no puede hacer el operador. Ver §2.9 de `PLAN_ALMACEN_INTEGRAL.md` para las medidas
propuestas (aprobación por factura en lugar de por línea).

**Fíjate en PB3**: es la respuesta a la decisión abierta sobre segregación de funciones. En vez de
un `CHECK` rígido en base de datos, se expresa como **política configurable**. Si mañana el negocio
decide que con poco personal sí puede autoaprobar, se cambia la política y queda auditado — sin
tocar código ni migrar.

**Y fíjate en las que son `DENY` sobre `inventory.adjust_*`**: son el mecanismo para que el permiso
grueso `inventory.adjust_shortage` (que sí tiene el jefe de bodega) no se pueda usar a las 3 de la
mañana. **RBAC dice qué; ABAC dice cuándo.**

### 4.4 Sobre el riesgo de ABAC: políticas mal configuradas

Hay que decirlo claro: **ABAC es potente y por eso peligroso.** Un `DENY` mal escrito puede bloquear
la operación del almacén en plena recepción de un contenedor. Mitigaciones obligatorias:

1. **Simulador antes de guardar** (§6.6): toda política se prueba contra casos reales antes de
   activarse.
2. **Modo observación**: una política nueva puede activarse en modo *shadow* que solo **registra**
   lo que habría decidido, sin aplicarlo. Se revisa la bitácora unos días y recién entonces se
   activa.
3. **Prohibido crear políticas sin `description`**: en 6 meses nadie recordará por qué existe.
4. **Las políticas no pueden conceder lo que RBAC no dio** (regla 5). Es la garantía de que ABAC
   nunca escala privilegios hacia arriba.
5. **Un conjunto mínimo de políticas no se puede desactivar** sin confirmación explícita (PB3 y
   PB4, por su implicación legal).

---

## 5. Esquema de datos

```sql
-- Catálogo de permisos (hoy son 25 filas sembradas; ahora es administrable)
permissions (
  id          BIGSERIAL PK,
  key         VARCHAR(80) NOT NULL UNIQUE,      -- 'invoice.approve'
  module      VARCHAR(40) NOT NULL,             -- 'recepción'
  resource    VARCHAR(40) NOT NULL,             -- 'invoice'
  action      VARCHAR(40) NOT NULL,             -- 'approve'
  name        VARCHAR(120) NOT NULL,            -- 'Aprobar discrepancias'
  description TEXT,
  scopes      permission_scope[] NOT NULL,      -- alcances admitidos
  sensitive   BOOLEAN NOT NULL DEFAULT FALSE,
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order  INT NOT NULL DEFAULT 0,
  UNIQUE (module, resource, action)
)

-- Roles (clave estable, nunca el id numérico)
roles (
  id          BIGSERIAL PK,
  key         VARCHAR(50) NOT NULL UNIQUE,      -- 'JEFE_BODEGA' (inmutable)
  name        VARCHAR(120) NOT NULL,            -- 'Jefe de bodega' (renombrable)
  description TEXT,
  module      VARCHAR(40),
  is_system   BOOLEAN NOT NULL DEFAULT FALSE,   -- no se puede borrar
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order  INT NOT NULL DEFAULT 0,
  created_at, updated_at
)

-- Matriz rol → permiso → alcance (una fila por celda concedida)
role_permissions (
  role_id       BIGINT FK,
  permission_id BIGINT FK,
  scope         permission_scope NOT NULL,      -- NONE | OWN | AREA | ALL
  PRIMARY KEY (role_id, permission_id)
)

-- Varios roles por persona (el legacy tenía UNO solo: roleId)
user_roles (
  user_id  BIGINT FK,
  role_id  BIGINT FK,
  is_primary BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (user_id, role_id)
)

-- Alcance de datos: qué bodegas "son suyas" (resuelve el AREA)
user_warehouse_scopes (
  user_id      BIGINT FK,
  warehouse_id BIGINT FK,
  PRIMARY KEY (user_id, warehouse_id)
)

-- Excepciones por persona: permiso puntual por encima o por debajo de su rol
user_permission_exceptions (
  id           BIGSERIAL PK,
  user_id      BIGINT FK,
  permission_id BIGINT FK,
  scope        permission_scope NOT NULL,
  reason       TEXT NOT NULL,                   -- obligatorio
  granted_by   BIGINT FK,
  expires_at   TIMESTAMPTZ,                     -- NULL = sin caducidad
  created_at,
  UNIQUE (user_id, permission_id)
)

-- Políticas ABAC
policies (
  id          BIGSERIAL PK,
  key         VARCHAR(80),
  name        VARCHAR(160) NOT NULL,
  description TEXT NOT NULL,                    -- obligatoria (mitigación §4.4)
  action      VARCHAR(80) NOT NULL,             -- permiso sobre el que actúa
  effect      policy_effect NOT NULL,           -- ALLOW | DENY
  priority    INT NOT NULL DEFAULT 0,
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  shadow_mode BOOLEAN NOT NULL DEFAULT FALSE,   -- solo registra, no aplica
  protected   BOOLEAN NOT NULL DEFAULT FALSE,   -- no desactivable sin confirmar
  created_by  BIGINT FK,
  created_at, updated_at
)

policies_roles (policy_id FK, role_id FK, PRIMARY KEY (policy_id, role_id))

policy_conditions (
  id         BIGSERIAL PK,
  policy_id  BIGINT FK ON DELETE CASCADE,
  field      VARCHAR(80) NOT NULL,              -- 'resource.difference'
  operator   VARCHAR(16) NOT NULL,              -- '<=', '>', 'in', '=='
  value      TEXT,                              -- serializado; el tipo lo da el campo
  sort_order INT NOT NULL DEFAULT 0             -- las condiciones se combinan con AND
)

-- Bitácora del control de acceso (append-only, como el kardex)
-- NOTA: se AMPLÍA con el trace de decisión de PLAN_AUDITORIA.md §3.1 y se versiona
--       la configuración en config_versions para poder REPRODUCIR decisiones históricas.
access_activity (
  id             BIGSERIAL PK,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
  action         VARCHAR(40) NOT NULL,          -- ROLE_CREATED, POLICY_UPDATED, ACCESS_DENIED…
  entity_type    VARCHAR(40) NOT NULL,
  entity_id      TEXT,
  actor_id       BIGINT FK,
  previous_state JSONB,
  new_state      JSONB,
  metadata       JSONB                          -- decisión, regla que decidió, condiciones
)

-- API keys de servicio (integración ERP y Zebra)
service_api_keys (
  id          BIGSERIAL PK,
  name        VARCHAR(80) NOT NULL,
  key_hash    TEXT NOT NULL,                    -- nunca en claro
  scopes      TEXT[] NOT NULL,                  -- permisos concedidos
  active      BOOLEAN NOT NULL DEFAULT TRUE,
  last_used_at TIMESTAMPTZ,
  expires_at   TIMESTAMPTZ,
  created_at, revoked_at
)

-- Enums
permission_scope : NONE | OWN | AREA | ALL
policy_effect    : ALLOW | DENY
```

**Tres decisiones de esquema que conviene destacar:**

1. **`roles.key` es inmutable y es la clave de negocio.** El legacy usaba `roleId === 1`
   hardcodeado en cuatro puntos del backend, dependiendo de que el seed insertara `admin` primero.
   Con `key` estable, añadir un rol o reordenar el seed no rompe nada. **Elimina el hallazgo S2.**
2. **`user_roles` es N:M**: una persona puede tener rol principal más roles adicionales. El legacy
   tenía `User.roleId` único, lo que obligaba a crear roles híbridos cuando alguien cubría dos
   funciones.
3. **`access_activity` y `kardex` son ambos append-only.** Tiene sentido: la bitácora de quién
   cambió los permisos es tan auditable como el libro de inventario.

---

## 6. Pantallas de administración

Cinco pantallas en el módulo `administración`, alineadas con lo que ya existe en `RESPONSIVA`
(`roles`, `matrix`, `policies`, `access`, `activity`, `catalog`) y adaptadas al almacén.

### 6.1 `/admin/roles` — Lista de roles

- `ITDataTable` server-side con: clave, nombre, módulo, nº de permisos concedidos, nº de personas,
  tipo (sistema/creado), activo.
- Acciones por fila: **editar**, **duplicar matriz desde otro rol** (clave para no partir de cero),
  **desactivar**, **eliminar** (solo roles no-sistema y sin personas asignadas).
- Botón *Nuevo rol*: el nombre visible genera la `key` automáticamente
  (`"Jefe de bodega"` → `JEFE_BODEGA`) con la misma función que ya usas (`roleKeyFromName`).
- **Guardia**: no se puede borrar un rol con personas asignadas ni un rol `is_system`.

### 6.2 `/admin/roles/:key` — Detalle de rol

- **Matriz de permisos agrupada por módulo** (`identidad`, `catálogo`, `almacén físico`,
  `recepción`, `surtido`, `inventario`, `transferencias`, `etiquetado`, `reportes`, `sistema`).
- Por cada permiso: un **control de alcance** (`Ninguno / Propio / Su bodega / Todas`) en lugar de
  un checkbox. El control solo ofrece los alcances que el permiso admite
  (`grantableScopes`): si un permiso no admite `OWN`, no aparece la opción.
- **Vista resumen por módulo** con nivel `FULL/PARTIAL/READ/NONE` y cobertura (`12/15`), que es lo
  que permite revisar un rol de un vistazo sin leer 82 filas.
- Los permisos `sensitive` van marcados visualmente.
- **Botón "Ver acceso efectivo"**: navega al probador con este rol precargado.

### 6.3 `/admin/roles/matrix` — Matriz global

- Tabla **roles (columnas) × permisos (filas)** con el alcance en cada celda. Es la vista para
  comparar roles y detectar inconsistencias (p. ej. `GERENTE` sin un permiso que sí tiene
  `JEFE_BODEGA`).
- **Edición por lotes**: se marcan varias celdas y se guarda con un solo `PUT /permissions/matrix`,
  como ya hace la implementación de referencia.
- Filtros por módulo, solo-sensibles, solo-diferencias.
- **Detección de riesgos**: resalta combinaciones peligrosas del mismo rol
  (`inventory.adjust_theft` + `audit.view`, o `role.manage` + `user.create`).

### 6.4 `/admin/policies` — Políticas ABAC

- Lista con: nombre, acción, efecto (`ALLOW`/`DENY`), prioridad, roles, nº de condiciones, estado,
  modo observación.
- **Editor de política** que es lo que hace usable ABAC. Debe guiar, no pedir JSON:
  1. Elegir **acción** (buscador sobre los 76 permisos).
  2. Elegir **efecto** con advertencia visual: `DENY` en rojo, con la explicación de que
     *"deny gana siempre"*.
  3. Elegir **roles** (vacío = todos).
  4. Construir **condiciones** con un constructor fila a fila: campo (desplegable de los atributos
     de §4.2), operador (según el tipo del campo), valor (input según el tipo; enum muestra opciones).
     **Los campos disponibles se filtran por la acción elegida** (patrón `PolicyActionDef`: cada
     acción declara qué campos aplican).
  5. **Vista previa de la frase en lenguaje natural**: *"Permitir aprobar discrepancias cuando la
     diferencia es menor o igual a 50 y la bodega está entre las del usuario"*. Esto es lo que
     permite que un jefe de operaciones revise una política sin entender el modelo.
  6. **Simulador embebido** antes de guardar.
- **Prioridad** visible y explicada: a igualdad de condiciones, gana la de mayor prioridad; `DENY`
  gana siempre.

### 6.5 `/admin/access` — Personas y acceso efectivo

- Lista de personas con: nombre, usuario, rol principal, roles adicionales (contador), nº de
  excepciones vigentes, activo.
- Detalle de una persona: **acceso efectivo explicado permiso por permiso**, mostrando
  `efectivo | qué rol lo aporta | excepción`. Es la vista que responde *"¿por qué este usuario puede
  hacer esto?"* sin adivinar.
- **Asignación de roles**: principal + adicionales, y asignación de **bodegas** (`AREA`).
- **Excepciones** (conceder o quitar un permiso puntual): **el motivo es obligatorio** y puede
  llevar fecha de caducidad. Una excepción sin caducidad debería revisarse periódicamente.

### 6.6 `/admin/access/tester` — Probador de acceso

La pantalla que evita los incidentes. Responde: *"¿este usuario puede hacer esto sobre este
recurso?"* mostrando las tres etapas por separado:

```txt
Usuario:      Juan Pérez (OPERADOR_ALMACEN)
Acción:       invoice.approve
Recurso:      { warehouseId: 2, difference: 30, capturedBy: juan }

1. IDENTIDAD   ✓ activo
2. RBAC        ✗ scope = NONE  (OPERADOR_ALMACEN no tiene invoice.approve)
               byRole: { OPERADOR_ALMACEN: NONE }
3. ABAC        no evaluado (RBAC ya denegó)

RESULTADO: DENEGADO — PERMISSION_DENIED
```

Y el caso interesante, donde ABAC decide:

```txt
Usuario:      Ana López (JEFE_BODEGA, bodegas: [2])
Acción:       invoice.approve
Recurso:      { warehouseId: 2, difference: 200, capturedBy: ana }

1. IDENTIDAD   ✓
2. RBAC        ✓ scope = AREA  (bodega 2 ∈ sus bodegas)
3. ABAC        ✗ decidido por PB2 "Discrepancias grandes requieren gerencia"
               · PB1 (ALLOW, difference <= 50): NO matchea (200 > 50)
              · PB2 (DENY, difference > 50):  MATCHEA
               · PB3 (DENY, capturedBy == user): MATCHEA

RESULTADO: DENEGADO — POLICY_DENIED
```

Además:
- **Probar un rol antes de asignarlo**: simular "¿qué podría hacer alguien con este rol?".
- **Probar un cambio de política** contra un conjunto de casos guardados (regresión).
- **Ver el impacto de un cambio**: cuántas personas y acciones afecta antes de guardar.

### 6.7 `/admin/activity` — Bitácora del control de acceso

- `ITDataTable` server-side con las acciones del catálogo ya definido en tu repo:
  `ROLE_CREATED`, `ROLE_UPDATED`, `ROLE_DELETED`, `ROLE_PERMISSIONS_UPDATED`, `PERMISSION_CREATED`,
  `PERMISSION_UPDATED`, `POLICY_CREATED`, `POLICY_UPDATED`, `POLICY_DELETED`,
  `PERMISSION_EXCEPTION_SET`, `PERMISSION_EXCEPTION_REMOVED`, `USER_ROLE_ADDED`,
  `USER_ROLE_REMOVED`, `ACCESS_DENIED`.
- **Diff visual** `previous_state` → `new_state`, que es lo que hace legible un cambio de permisos.
- Filtro por actor, acción, entidad y rango de fechas.
- Las denegaciones (`ACCESS_DENIED`) son valiosas: permiten detectar a alguien intentando algo que
  no debería, o **un permiso mal configurado que bloquea trabajo legítimo**.

---

## 7. Contrato de API

```txt
# ── Roles
GET    /v1/roles                          role.manage  lista con conteo de personas
POST   /v1/roles                          role.manage  crear (con copyFrom opcional)
GET    /v1/roles/:key                     role.manage  detalle + matriz del rol
PATCH  /v1/roles/:key                     role.manage  metadatos (el key NO se puede cambiar)
DELETE /v1/roles/:key                     role.manage  409 si tiene personas o es de sistema
GET    /v1/roles/:key/effective           role.manage  acceso efectivo del rol (unión de personas)

# ── Catálogo de permisos
GET    /v1/permissions                    sesión       catálogo activo (para pintar el menú)
GET    /v1/permissions/admin              role.manage  catálogo completo con inactivos
POST   /v1/permissions                    role.manage  alta de permiso
PATCH  /v1/permissions/:key               role.manage  editar metadatos/alcances/sensibilidad
GET    /v1/permissions/actions/:action/fields  role.manage  campos válidos para una acción (ABAC)

# ── Matriz
GET    /v1/permissions/matrix             role.manage  { roles, catalog, matrix }
PUT    /v1/permissions/matrix             role.manage  lote de celdas (transaccional)

# ── Políticas
GET    /v1/policies                       role.manage  políticas + catálogo de acciones/campos
POST   /v1/policies                       role.manage  crear (description obligatoria)
PATCH  /v1/policies/:id                   role.manage  editar
DELETE /v1/policies/:id                   role.manage  409 si es protected
POST   /v1/policies/:id/shadow            role.manage  activar/desactivar modo observación

# ── Personas y acceso
GET    /v1/access/members                 user.view    personas con roles y excepciones
GET    /v1/access/members/:userId         user.view    acceso efectivo explicado
PUT    /v1/access/members/:userId/roles   role.assign  reemplazar roles (principal + adicionales)
PUT    /v1/access/members/:userId/warehouses  role.assign  bodegas del alcance AREA
PUT    /v1/access/members/:userId/exceptions/:permission  role.assign  conceder excepción (motivo obligatorio)
DELETE /v1/access/members/:userId/exceptions/:permission  role.assign  revocar excepción

# ── Probador y bitácora
POST   /v1/access/simulate                user.view    Identidad → RBAC → ABAC (explicado)
POST   /v1/access/simulate/regression     role.manage  un lote de casos contra políticas propuestas
GET    /v1/access/activity/query          audit.view   bitácora (tabla server-side)

# ── API keys de servicio
GET    /v1/service-keys                   role.manage  listar (nunca devuelve el secreto)
POST   /v1/service-keys                   role.manage  crear (devuelve el secreto UNA vez)
DELETE /v1/service-keys/:id               role.manage  revocar
```

**Códigos de error del motor (contrato explícito, para que el móvil y la web sepan qué mostrar):**

| Código | HTTP | Cuándo | Qué debe mostrar la UI |
|---|---|---|---|
| `UNAUTHENTICATED` | 401 | Sin token o inválido | Ir a login |
| `ACCOUNT_DISABLED` | 401 | Usuario desactivado | "Cuenta desactivada, contacta al administrador" |
| `PERMISSION_DENIED` | 403 | RBAC no alcanza | "No tienes permiso para esta acción" |
| `SCOPE_INSUFFICIENT` | 403 | Tiene el permiso pero no para **ese** recurso | "No tienes permiso sobre esta bodega/ubicación" |
| `POLICY_DENIED` | 403 | ABAC denegó | **El motivo de la política**, que es lo accionable |
| `POLICY_REQUIRED` | 403 | Hay políticas y ninguna ALLOW matcheó | "Esta acción requiere autorización específica" |
| `SEGREGATION_OF_DUTIES` | 403 | Intentó aprobar su propio trabajo | "No puedes aprobar tu propia captura" |

**`SCOPE_INSUFFICIENT` con mensaje específico es importante**: "no tienes permiso sobre esta
bodega" es accionable (pedir acceso a esa bodega); "acceso denegado" no lo es.

---

## 8. Ubicación en la arquitectura

### 8.1 Dónde vive el motor (decisión de arquitectura)

El motor de acceso **tiene que ser el mismo en backend y móvil**, por la misma razón que el dominio
de inventario: si el móvil cree que algo está permitido y el backend lo niega, el operador captura
datos que se pierden al sincronizar.

```txt
stock-domain/                          # módulo KMP puro (sin Ktor, sin Exposed, sin Compose)
└── src/commonMain/kotlin/.../access/
    ├── Permission.kt                  # catálogo tipado de las 82 claves
    ├── PermissionScope.kt             # NONE | OWN | AREA | ALL
    ├── RoleKey.kt                     # claves estables + generación desde nombre
    ├── Policy.kt                      # Policy, PolicyCondition, PolicyEffect
    ├── AccessRequest.kt               # (usuario, acción, recurso, contexto)
    ├── AccessDecision.kt              # Allowed / Denied(code, reason, trace)
    ├── AccessEngine.kt                # el pipeline de 3 etapas — PURO
    └── AccessEngineTest.kt            # tests de la matriz y del motor (§9)
```

**El `AccessEngine` es una función pura:**

```kotlin
fun decide(
    user: AccessUser,          // roles, bodegas, excepciones, activo
    action: Permission,
    resource: ResourceContext, // bodega, ubicación, diferencia, motivo, costo…
    context: RequestContext,   // hora, día, dispositivo, offline
    policies: List<Policy>,
): AccessDecision
```

**Por qué puro e importa:** se prueba con miles de combinaciones en milisegundos, sin base de datos
ni servidor. Y como es el mismo código en los tres lugares, **la decisión es idéntica** en backend,
móvil y tests.

**Quién consume qué:**

| Consumidor | Uso |
|---|---|
| Backend Ktor | Plugin de autorización que llama a `decide()` antes de cada ruta, con los datos cargados de la BD |
| Móvil KMP | Llama a `decide()` **localmente** para habilitar/deshabilitar botones y validar antes de enviar |
| Web React | Consume los permisos efectivos del usuario desde `/v1/auth/me` y los evalúa con el mismo catálogo; **la UI nunca decide sola** |
| Tests | Verificación exhaustiva de la matriz y de las políticas |

**Regla de oro: la UI es una pista, el servidor es la autoridad.** El móvil puede ocultar un botón,
pero el backend **siempre** revalida. El legacy hacía lo contrario: la web tenía 5 usos de
`hasPermission` en todo el código y uno de ellos con el permiso equivocado.

### 8.2 Sustitución del `role === 1`

El legacy tiene el bypass hardcodeado en cuatro puntos. El reemplazo es mecánico y mecánicamente
verificable:

```kotlin
// ANTES (legacy):  if (role === 1) return next()
// DESPUÉS:
install(AuthorizationPlugin) {
    // ADMIN tiene el permiso '*' sembrado en role_permissions, no un bypass en código
    onCheck(Permission.InvoiceApprove) { user, resource ->
        accessEngine.decide(user, Permission.InvoiceApprove, resource, context, policies)
    }
}
```

El rol `ADMIN` recibe **el permiso comodín `*` en `role_permissions`**, explícito y visible en la
matriz de la UI. La diferencia con el legacy no es cosmética: **lo que el admin puede hacer se ve en
pantalla y se audita**, en lugar de estar escondido en un `if`.

---

## 9. Estrategia de pruebas del control de acceso

Parte de la estrategia general (`PLAN_ALMACEN_INTEGRAL.md` §3.6), con los casos propios de permisos.
Todo corre en **JVM puro**, sin base de datos.

### 9.1 Pruebas de la matriz RBAC

| # | Caso | Assertion |
|---|---|---|
| A1 | Cada uno de los 5 roles × cada uno de los 76 permisos | El alcance efectivo coincide **exactamente** con la matriz de §3.1 (test generado desde la tabla) |
| A2 | Permiso sensible sin rol autorizado | Ningún rol salvo los previstos lo tiene |
| A3 | `OPERADOR_ALMACEN` intenta `invoice.approve` | `Denied(PERMISSION_DENIED)` |
| A4 | `AUDITOR` intenta cualquier escritura (los 40 permisos de escritura) | `Denied` en **todos** |
| A5 | **Biyección permisos ↔ rutas** | Todo permiso del catálogo se exige en ≥1 ruta y toda ruta exige un permiso existente. **Atrapa los 4 permisos muertos del legacy** (`user:*`, `order:approve`, `print:update`, `invoice:update` que existían y nunca se usaban) |
| A6 | **Ninguna ruta sin autenticación** | Recorre la tabla de rutas y falla si alguna no exige permiso o API key. **Atrapa las 3 rutas abiertas del legacy** |
| A7 | Auditoría del catálogo | Ningún permiso `active=false` concede acceso |

### 9.2 Pruebas del alcance (scope)

| # | Caso | Assertion |
|---|---|---|
| A8 | `OPERADOR` con `invoice.view = OWN` pide una factura ajena | `Denied(SCOPE_INSUFFICIENT)` |
| A9 | `JEFE_BODEGA` con `AREA` y bodegas `[2]` pide un recurso de bodega 3 | `Denied(SCOPE_INSUFFICIENT)` |
| A10 | Ídem pero bodega 2 | Permitido |
| A11 | `ALL` sobre cualquier bodega | Permitido |
| A12 | `OWN` sin asignación y recurso sin dueño | Denegado (no se asume) |
| A13 | Escalera de alcances | `ALL ⊇ AREA ⊇ OWN`; conceder `AREA` **no** concede `ALL` |

### 9.3 Pruebas del motor ABAC (las más importantes)

| # | Caso | Assertion |
|---|---|---|
| A14 | Sin políticas para la acción | RBAC decide; **default deny** si RBAC tampoco |
| A15 | Una `ALLOW` matchea | Permitido |
| A16 | Una `ALLOW` matchea y otra `DENY` matchea | **Denegado** (deny gana) |
| A17 | Hay políticas pero **ninguna `ALLOW` matchea** | `Denied(POLICY_REQUIRED)` — **RBAC no basta** |
| A18 | Condiciones múltiples con una falsa | La política no matchea (AND) |
| A19 | Política inactiva | No se evalúa |
| A20 | Política con `roles: []` | Aplica a todos |
| A21 | Política con rol que el usuario no tiene | No aplica |
| A22 | **`shadow_mode`** | Registra la decisión pero **no la aplica** |
| A23 | Prioridad | A igual efecto gana la mayor; **`DENY` gana siempre**, incluso con prioridad menor |
| A24 | `protected` | No se puede desactivar sin confirmación explícita |
| A25 | Política con condición sobre campo inexistente | Error de validación al guardar, **no** al evaluar |

### 9.4 Pruebas de las políticas concretas (§4.3)

Cada una de las 12 políticas con un caso positivo y uno negativo:

| # | Política | Caso positivo | Caso negativo |
|---|---|---|---|
| A26 | PB1/PB2 umbral de discrepancia | `difference = 30` → permitido | `difference = 200` → `POLICY_DENIED` |
| A27 | **PB3 segregación de funciones** | Aprueba lo de otro → permitido | **Aprueba lo suyo → `SEGREGATION_OF_DUTIES`** |
| A28 | PB4 robo solo gerencia | GERENTE → permitido | JEFE_BODEGA → `POLICY_REQUIRED` |
| A29 | PB5 horario laboral | 10:00 → permitido | 03:00 → `POLICY_DENIED` |
| A30 | PB6 fin de semana | Lunes → permitido | Domingo con JEFE_BODEGA → denegado |
| A31 | PB7 productos de alto valor | `unitCost = 100` con OPERADOR → permitido | `unitCost = 9000` → denegado |
| A32 | PB8 ubicar desde recepción | OPERADOR ubica desde `RECEIVING` → permitido | Desde `SHELF` → denegado |
| A33 | PB9 sincronización offline | `offlineSync = true` → permitido y **con marca en auditoría** | — |
| A34 | PB12 servicio ERP | `deviceType = SERVICE` → permitido | Usuario web → `POLICY_DENIED` |

### 9.5 Pruebas de seguridad específicas

| # | Caso | Assertion |
|---|---|---|
| A35 | **Escalada por ABAC** | Ninguna combinación de políticas permite algo que RBAC negó. Test exhaustivo: para cada uno de los 76 permisos × 5 roles, añadir **todas** las políticas posibles con `ALLOW` y verificar que los que RBAC niega **siguen negados** |
| A36 | **Contra el `role === 1`** | Ningún rol obtiene acceso por su `id` numérico; solo por `key` y permisos |
| A37 | Excepción sin motivo | Rechazada por la BD |
| A38 | Excepción caducada | No concede acceso |
| A39 | Excepción no puede escalar por encima de los permisos del que la concede | Rechazada |
| A40 | API key revocada o caducada | 401 |
| A41 | API key con scope insuficiente | 403 |
| A42 | Secreto de API key | Se devuelve **una sola vez**; en BD solo el hash; nunca en logs |
| A43 | Toda denegación | Se registra en `access_activity` con la regla que decidió |

### 9.6 Pruebas de integración y E2E

| # | Caso | Nivel |
|---|---|---|
| A44 | El móvil **oculta** lo que el backend **niega** | Paridad: mismo `decide()` en ambos |
| A45 | Cambio de permisos aplica **inmediatamente**, sin relogin | Integración (el legacy guardaba el rol en el JWT: tardaba hasta 24 h) |
| A46 | Desactivar un usuario **invalida su sesión** | Integración (el legacy no lo comprobaba) |
| A47 | El operador completa el flujo de recepción sin ver nunca "acceso denegado" | E2E — **prueba de que los permisos no estorban el trabajo** |
| A48 | El jefe de bodega aprueba una discrepancia de 30 pzs end-to-end | E2E |
| A49 | Un cambio de matriz en la UI se refleja en el backend | E2E |
| A50 | La bitácora registra cada cambio de rol/política con su diff | E2E |

### 9.7 Puertas de calidad

| Puerta | Umbral |
|---|---|
| Cobertura de `stock-domain/access` | **≥ 95%** |
| Matriz rol × permiso | Test **generado** desde la tabla de §3.1 (si divergen, falla) |
| Biyección permisos ↔ rutas | 100% |
| Rutas sin autenticación | **0** |
| Escalada por ABAC | 0 casos posibles |
| Toda política | Debe tener `description` y ≥1 caso guardado en la suite de regresión |

**A47 es el test más importante de todos.** Es fácil construir un sistema de permisos que funcione y
que haga imposible trabajar. La métrica real no es "nadie accede a lo que no debe", sino
**"nadie legítimo se topa con un acceso denegado"**. Ese test se corre con el flujo completo de
recepción y de surtido, y si el operador recibe un 403 en medio del trabajo, **falla el build**.

---

## 10. Migración desde el legacy

El legacy tiene **3 roles** (`admin`, `operaciones`, `almacen`), **25 permisos**, el bypass
`role === 1` y `User.roleId` único. La migración es directa pero requiere decisiones:

| Legacy | Sistema nuevo | Nota |
|---|---|---|
| `admin` (sin permisos en BD, bypass en código) | `ADMIN` con permiso comodín `*` en `role_permissions` | Se acaba el bypass: lo que puede hacer es visible en la matriz |
| `operaciones` (21 permisos) | `JEFE_BODEGA` + `GERENTE_OPERACIONES` | Hay que revisar **persona por persona** cuál le corresponde; no se puede automatizar sin criterio |
| `almacen` (4 permisos: `invoice:read`, `invoice:capture`, `order:read`, `order:capture`) | `OPERADOR_ALMACEN` (+ `invoice.locate` y `order.package`) | El legacy **no le daba permiso para ubicar**, aunque en la práctica es su trabajo principal. Hay que confirmarlo |
| `roleId === 1` hardcodeado | `roles.key` estable | Eliminado |
| `User.roleId` (uno solo) | `user_roles` (N:M) | Permite roles adicionales sin crear roles híbridos |
| `RolePermission` (sin alcance) | `role_permissions.scope` | Migra como `ALL` por defecto, y luego se ajusta rol por rol |
| 25 permisos planos | 76 permisos jerárquicos | **Los permisos viejos no mapean 1:1.** `location:read` cubría consultar, mover y ajustar: hay que descomponerlo |

**El mapeo de los 25 permisos viejos es una tarea de la Fase 1** y necesita tu revisión, porque
implica decidir qué puede hacer cada persona concreta. Propongo hacerlo en una hoja de cálculo:
persona × rol nuevo × bodegas, y que la firmes antes de migrar.

**`location:read` es el caso más delicado.** En el legacy, mover y ajustar inventario usaban
`location:read` — el mismo permiso que consultar. Si migramos `almacen → OPERADOR_ALMACEN` con lo
que tenía, el operador **podría ajustar inventario sin motivo ni aprobación**. Eso es exactamente lo
que el sistema nuevo no debe permitir.

---

## 11. Riesgos y decisiones abiertas

### Riesgos

| Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|
| **Una política `DENY` mal escrita bloquea la operación** en plena recepción | Media | **Alto** | Simulador obligatorio + `shadow_mode` + `protected` en las críticas |
| **Explosión de políticas**: 80 políticas imposibles de auditar | Media | Alto | Toda política con `description`, y revisión periódica de las no usadas en 90 días |
| **Roles de conveniencia**: se crea un rol "SUPERVISOR" con todo | Alta | Medio | El lint del catálogo avisa si un rol tiene >70% de los permisos |
| Permisos que estorban el trabajo real | Media | **Alto** | **Test A47** (el operador completa el flujo sin un solo 403) |
| **La auditoría crece sin control** (toda decisión registrada) | Media | Medio | 4 niveles de criticidad + contador agregado para lecturas + particionado mensual. Ver `PLAN_AUDITORIA.md` §2 |
| Sobrecarga de ABAC en cada request | Baja | Medio | El motor es puro y en memoria; las políticas se cachean y se invalidan al cambiarlas |
| Pérdida de acceso al sistema por error de configuración | Baja | **Alto** | `ADMIN` protegido: no se le puede quitar `role.manage` desde la UI; solo por SQL directo |
| Las 12 políticas propuestas no reflejan el negocio real | Media | Medio | Se proponen como **punto de partida**, no como verdad; se ajustan contigo en Fase 1 |

### Decisiones abiertas

1. **¿Confirmas los 5 roles base** (`ADMIN`, `GERENTE_OPERACIONES`, `JEFE_BODEGA`,
   `OPERADOR_ALMACEN`, `AUDITOR`), o el almacén tiene una jerarquía distinta? Pregunto porque los
   roles deben salir de cómo trabajan las personas, no de cómo suena bien un organigrama.
2. ~~¿El operador puede aprobar sus propias discrepancias?~~ **CERRADA: no puede (R-74).** PB3 activa.
3. ~~¿Hay un umbral de discrepancia?~~ **CERRADA: sin umbral (R-75).** Toda discrepancia requiere aprobación.
4. **¿Los ajustes requieren horario laboral?** PB5 y PB6 asumen que sí. Si el almacén opera
   turnos de noche o fines de semana, estas políticas estorban.
5. **¿El `AUDITOR` es un rol del sistema o una persona concreta?** Si es una persona que además
   trabaja en almacén, no puede ser un rol puro de lectura.
6. **¿Se necesita aprobación en dos pasos** para ajustes de robo (jefe pide, gerente aprueba), o con
   que lo haga el gerente es suficiente?
7. **¿La bodega es un atributo de la persona o del rol?** Propongo que sea de la persona
   (`user_warehouse_scopes`); así un jefe de bodega que cambia de bodega no requiere rol nuevo.

---

## 12. Plan de implementación

Se integra en las fases de `PLAN_ALMACEN_INTEGRAL.md`, principalmente Fase 1 (backend núcleo).

| Fase | Entregable de roles/permisos |
|---|---|
| **Fase 0** | `stock-domain/access` con el motor de 3 etapas y sus tests puros. Catálogo de los 76 permisos tipado. **Los tests A1–A25 corren en JVM desde el primer día.** |
| **Fase 1** | Esquema de §5, seed de los 5 roles y la matriz de §3.1, plugin de autorización en Ktor, endpoints de §7. **Eliminación del `role === 1`.** Tests A26–A43. |
| **Fase 2** | Las políticas del dominio de almacén (§4.3) con sus tests. El probador de acceso. Tests A44–A46. |
| **Fase 5 (web)** | Las 7 pantallas de §6, empezando por `/admin/roles` y `/admin/roles/:key` (la matriz), luego `/admin/policies` (el editor), después el probador y la bitácora. |
| **Fase 6 (móvil)** | Evaluación local con el mismo `decide()` para habilitar botones; **nunca como única defensa**. |
| **Fase 7** | Migración de los 3 roles y las personas reales, con la hoja persona × rol × bodegas firmada. |

**Orden recomendado dentro de Fase 1:** motor y catálogo primero (con tests), luego esquema y seed,
luego el plugin de autorización, y las pantallas al final. **Al revés es el error clásico**: construir
la UI de administración antes de que el motor sea correcto produce una pantalla que administra algo
que no funciona.

---

## Anexo A — Fuentes

**Modelo ABAC de referencia** (`~/DEV/PTNV/RESPONSIVA/web`), leído y verificado:

- `src/entities/permission/api/permissionApi.ts` (340 líneas): `PolicyEffect`, `PolicyCondition`,
  `PolicyAdmin`, `PolicyFieldDef`, `PolicyActionDef`, `ConditionTrace`, `RuleTrace`,
  `PolicyExplanation`, `AccessSimulation` (con el desglose `identity` → `rbac` → `abac`),
  `AccessMember`, `UserAccessView`, `ACCESS_ACTIVITY_ACTIONS` (14 acciones), y los 18 endpoints del
  `permissionApi`.
- `src/entities/permission/model/screens.ts` (307 líneas): `APP_SCREENS`, `ScreenRequirement`
  (`anyOf`/`allOf`), `isScreenVisible`, `screenActions`, `screenLeaves`.
- `src/entities/user/model/types.ts`: `PermissionScope = "NONE" | "OWN" | "AREA" | "ALL"`.
- `src/entities/user/model/usePermission.ts`: `usePermission`, `useCan`, interfaz estructural local.
- `src/entities/user/model/roles-catalog.ts`: catálogo dinámico con `useSyncExternalStore`.
- `src/features/roles/roles-console/`: 7 hooks (`useRolesAdmin`, `useRolesWorkspace`, `usePolicies`,
  `useAccessMembers`, `useUserAccess`, `useAccessActivity`, `access-levels`, `role-key`) y 30
  componentes en `roles/`, `matrix/`, `policies/`, `access/`, `activity/`, `shared/`.
- `src/features/roles/roles-console/model/access-levels.ts`: `PermissionVerb`, `AccessLevel`,
  `isScopedPermission`, `grantableScopes`, `moduleLevel`, `moduleCoverage`, `groupByModule`.
- `src/features/roles/roles-console/model/role-key.ts`: `ROLE_KEY_REGEX`, `roleKeyFromName`.

**Legacy a migrar** (`~/DEV/STOCK/BASE/backend`): `prisma/schema.prisma` (`User.roleId`,
`Role`, `Permission`, `RolePermission`), `prisma/seeds/security.ts` (3 roles, 25 permisos, el rol
admin sin permisos), `src/middlewares/{token,role,admin-role,axzy}-validator.middleware.ts`,
`src/routes/*.ts` (los permisos exigidos por ruta), `src/service/role.service.ts`.

**Contexto**: `PLAN_ALMACEN_INTEGRAL.md` §2.2-ter (hallazgos S1–S8), §2.8 (política de cambios),
§3.2-bis (módulo de dominio compartido), §3.6 (estrategia de pruebas).
