# Estrategia de Refactor y Mejoras Arquitectónicas — Almacén

> Documento de trabajo del refactor. Registra **cada defecto encontrado en el legacy**, su causa
> raíz, la corrección con mejores prácticas, el riesgo de cambiarlo y **cómo saber si nos
> equivocamos**. Complementa a `PLAN_ALMACEN_INTEGRAL.md` y
> `PLAN_ROLES_PERMISOS_POLITICAS.md`.
>
> Estado: **propuesta para aprobación**. Nada de esto se implementa hasta cerrarlo.

---

## 0. El principio

> *"Estamos haciendo un refactor: si algo está mal, hay que corregirlo con las mejores prácticas.
> La idea es tener un mejor software."*

Correcto. Un refactor **no replica defectos**, y la fidelidad al legacy **no es un objetivo en sí
misma**: es una restricción temporal que existe para no romper el negocio mientras migramos. Donde
el legacy se contradice a sí mismo, gana la corrección.

**Pero hay una clase de cambio que necesita tu firma, y no es por burocracia:** cuando el sistema
nuevo va a afirmar algo **distinto** sobre *cuánto hay de algo* o *en qué estado está*. Ahí el
cambio mueve inventario real y afecta a personas que trabajan con él. No es una decisión técnica:
es una decisión de negocio disfrazada de técnica.

Por eso este documento separa todo en tres clases, y solo la tercera requiere tu confirmación
explícita.

| Clase | Qué es | Quién decide | Cómo se cierra |
|---|---|---|---|
| **A — Defecto técnico** | El código hace algo que ningún ingeniero defendería | Yo | Corrección directa + test de regresión |
| **B — Contradicción interna** | El sistema declara una intención y hace lo contrario | Yo | Corrección + test que fija la intención declarada |
| **C — Regla de negocio** | Comportamiento deliberado que podemos mejorar | **Tú** | Propuesta con datos + tu decisión + política de rollback |

---

## 1. Metodología: cómo se propone y se revierte un refactor

### 1.1 Plantilla de propuesta

Cada cambio de clase C (y los A/B que toquen inventario) se documenta así. El objetivo es que dentro
de un año alguien pueda entender **por qué** el sistema se comporta como se comporta.

```markdown
### R-<n>: <título corto>
- Clase:            A | B | C
- Severidad:        Crítica | Alta | Media | Baja
- Módulo:           recepción | inventario | surtido | acceso | …
- Evidencia:        archivo:línea + qué hace hoy
- Causa raíz:       por qué existe (no solo dónde está)
- Corrección:       qué se hace, con la mejor práctica aplicada
- Mejor práctica:   qué principio se aplica y de dónde viene
- Riesgo:           qué puede salir mal al cambiarlo
- No-reproducir:    qué NO debe pasar en el sistema nuevo
- Rollback:         cómo se revierte si el negocio dice que estaba bien
- Test:             el test que lo cubre y evita la regresión
```

### 1.2 Política de rollback

Un refactor sin rollback es una apuesta. Dos mecanismos:

1. **Feature flag por cambio de negocio.** Los cambios de clase C que alteran cantidades o estados
   se implementan detrás de un flag de configuración
   (`stock.legacy.locateDecrementsTotal = false`). Si el negocio descubre que el comportamiento
   viejo era el correcto, se activa el flag sin desplegar código. **El comportamiento viejo queda
   disponible y probado, no borrado.**
2. **Test de caracterización del comportamiento viejo.** Se escribe un test que fija lo que hacía
   PROD (`golden test`) **antes** de cambiarlo. Sirve para dos cosas: documenta el comportamiento
   histórico, y si algún día hay que volver atrás, el test ya existe.

Esto responde a tu preocupación original de forma concreta: **no reescribo un flujo sin dejar la
puerta abierta a volver atrás.**

### 1.3 Regla de una cosa a la vez

Un refactor que cambia 40 cosas simultáneamente es imposible de diagnosticar cuando algo falla. El
orden es: primero las correcciones que **no cambian comportamiento observable** (clase A y B),
después las que sí (clase C), y dentro de cada grupo, de mayor a menor severidad. Así, si el
inventario descuadra tras un despliegue, el espacio de búsqueda es pequeño.

---

## 2. Cambios de clase A — defectos técnicos (los arreglo sin preguntar)

Ninguno de estos requiere tu aprobación porque ninguno puede ser intencional. Van todos con test de
regresión.

### 2.1 Backend — inventario y datos

| # | Defecto | Evidencia | Corrección | Mejor práctica |
|---|---|---|---|---|
| R-01 | **Doble descuento al ubicar**: muta el kardex y además resta con un `LOCATION` | `kardex.service.ts` `addKardexLocation` | Ubicar = traslado entre ubicaciones (baja en recepción, sube en estante, total invariante) | Modelo de stock por ubicación; total derivado |
| R-02 | **El kardex se muta**: `kardex.update` sobre una fila histórica | `removeUnitsFromKardex` | Kardex **append-only**; permisos de BD sin `UPDATE` ni `DELETE` | Event sourcing / ledger inmutable |
| R-03 | **Sin transacción** entre kardex y proyección de stock | `addKardex` → `updateStoreStock` | Una transacción por movimiento; o ledger como única escritura y proyección derivada | Atomicidad; consistencia |
| R-04 | **Race condition**: el stock se lee, se calcula y se reescribe en JS | `updateStoreStock` | `UPDATE … SET quantity = quantity + ?` atómico, o lock optimista con versión | Operación atómica en BD |
| R-05 | **Fire-and-forget**: `.map()` sin `await` en el cierre de orden | `order.service.ts:350` | `await` explícito o corrutinas supervisadas | Nunca lanzar trabajo sin supervisar |
| R-06 | **`catch` que traga errores** devolviendo `undefined` | `sqlServer.ts:6-7`, `print.cron.ts` | Propagación explícita con error tipado | Fail fast |
| R-07 | **`products` no filtra `deleted`** → productos inactivos seleccionables | `product.service.ts:3` | Filtro `active` + FK real | Integridad del catálogo |
| R-08 | **`GET /invoices/pending` filtra por una columna inexistente** | `invoice.service.ts:271` (`Invoice.userId`) | Filtrar por `invoice_lines.assigned_user_id` | Consulta correcta |
| R-09 | **`update[0].id` sin comprobar** con lista vacía → `TypeError` | `invoice.service.ts:329` | Validación de entrada; lista vacía rechazada | Validación en el borde |
| R-10 | **Validación de stock inoperante en mover**: compara sobre un array | `moveProductFromLocation` | Tipar el retorno y validar de verdad | Type safety |
| R-11 | **`deleteStore` sin `await`** → responde `{}` | `kardex.service.ts:171` | `await` | Correctitud asíncrona |
| R-12 | **Mass assignment**: `update({...req.body})` | `PUT /orders/detail` | DTO cerrado con allowlist explícita | Nunca confiar en el cuerpo del cliente |
| R-13 | **N+1 sistemático**: un query por fila | `getInvoiceDetail`, `getOrderDetail`, `getLocationContent`, `getProductLocationsHaveStock`, `getStoreByIdRange` | Joins, proyecciones y `IN` por lote | O(1) queries por caso de uso |
| R-14 | **Sin paginación**; listados cargando la tabla entera en memoria | Todos los `GET` de colección | Paginación obligatoria con `limit` máximo | No cargar lo que no se va a mostrar |
| R-15 | **Zona horaria mezclada** en rangos de fecha (`Z` vs hora local) | `getKardexByDateRange` | `TIMESTAMPTZ` + UTC en todo el rango | Instantes en UTC, presentación local |
| R-16 | **SQL por concatenación** | `kardo.service.ts:25` | Queries parametrizadas | Prepared statements |
| R-17 | **Credenciales en el repositorio** (SA, AXZY, `admin/admin12345`) | `docker-compose.yml`, seeds | Secretos externos; `docker-compose` sin credenciales | Gestión de secretos |
| R-18 | **Password hashing débil** (bcrypt cost 10) | `security.ts` | argon2id con parámetros actuales | Estándar 2026 |
| R-19 | **Migraciones inconsistentes**: 36 legacy + 1 que recrea todo el esquema | `migrations/`, `prisma/migrations/` | **Baseline Flyway desde el esquema real** | Migraciones reproducibles desde cero |
| R-20 | **Errores de negocio con HTTP 200**; 500 con cuerpo `{}` | `addInvoice`, `addOrder`, `res.json(error)` | Códigos correctos (409/422) + `ApiError` tipado | Semántica HTTP |
| R-21 | **Sin rate limiting** en login; sin bloqueo por intentos | `user.controller.login` | Rate limit + bloqueo progresivo | Defensa contra fuerza bruta |
| R-22 | **`console.log` de datos sensibles** (config de SQL Server completa) | `sqlServer.ts:6-7` | Logger estructurado con redacción | No loguear secretos |
| R-23 | **`kardex.user` como nombre libre** (sin FK) | modelo `Kardex` | FK `user_id` | Integridad referencial |
| R-24 | **Transición silenciosa de la línea de captura**: crea kardex **antes** de la transacción de actualización | `updateInvoiceDetails` | Todo en una transacción; el ledger no puede quedar por delante del estado | Consistencia |
| R-24b | **No existe auditoría alguna**: ninguna tabla registra quién hizo qué | verificado en los 13 modelos del esquema | `audit_log` particionado, append-only, con trace de decisión y cadena de hashes. Ver **[PLAN_AUDITORIA.md](PLAN_AUDITORIA.md)** | Auditoría desde el primer día (§10 de ese documento) |
| R-24c | **El único rastro que existe es mutable**: `removeUnitsFromKardex` hace `kardex.update`, así que el "libro" se puede editar | `kardex.service.ts` | Kardex append-only + permisos de BD revocados + trigger | Un ledger que se puede editar no es un ledger |
| R-24d | **Ningún registro guarda el canal de origen**: no se puede distinguir una persona de una integración | — | `source_channel` obligatorio en cada evento | Trazabilidad del origen |
| R-24e | **Ningún registro guarda la decisión de acceso**: no se puede saber si la acción estaba autorizada **entonces** | — | `access_trace` + `config_versions` para reproducir la decisión histórica | Auditabilidad de la autorización |

### 2.2 Backend — control de acceso (detalle en `PLAN_ROLES_PERMISOS_POLITICAS.md`)

| # | Defecto | Corrección |
|---|---|---|
| R-25 | **Bypass `role === 1`** hardcodeado en 4 puntos | Permisos explícitos; el rol admin recibe el comodín `*` **sembrado y visible en la matriz** |
| R-26 | **El rol admin no tiene ni un permiso en BD** | Seed explícito; lo que puede hacer admin es auditable |
| R-27 | **3 rutas sin autenticación** (una por descuido) | Auth en todas; las 2 intencionales con **API key de servicio** |
| R-28 | **JWT de 24 h no revocable**, sin logout, `disabled` no se comprueba | Access token corto + refresh opaco revocable; `disabled` validado en cada request |
| R-29 | **`roleId` numérico** como referencia de negocio | `roles.key` estable e inmutable |
| R-30 | **`User.roleId` único** | `user_roles` N:M |
| R-31 | **4 permisos sembrados que nunca se exigen** | Test de biyección permisos ↔ rutas |

### 2.3 Web

| # | Defecto | Evidencia | Corrección |
|---|---|---|---|
| R-32 | **`axios` calcula el header una sola vez** al importar el módulo → token obsoleto tras login | `utils/axios.ts` | Interceptor de request que lee el token en cada llamada |
| R-33 | **El `catch` no propaga el error**: la promesa resuelve `undefined` | `utils/axios.ts` | Normalizador que **lanza** `ApiError`; nunca resolver en error |
| R-34 | **Permiso equivocado** en Locaciones (`invoice:create`) | `Locations.tsx:609` | Permisos tipados desde el catálogo |
| R-35 | **Menú hardcodeado y duplicado** en `Layout.tsx` y `Home.tsx`, sin filtrar por permisos | — | Fuente única `APP_SCREENS` derivada de permisos |
| R-36 | **`Table` tipa `UseSortBy` pero no invoca `useSortBy`** → sin ordenamiento | `components/atoms/Table` | Ordenamiento server-side real |
| R-37 | **Sin estados de carga/error** por página; doble fetch por `StrictMode` sin cancelación | todas las páginas | Estados explícitos + cancelación |
| R-38 | **Dos librerías de UI** conviviendo (Chakra + MUI) | `package.json` | Un solo sistema: `axzy_ui_system` + Tailwind |
| R-39 | **`mockData` como nombre de prop** para datos reales | `Table` | Nombres que describen la realidad |
| R-40 | **1.817 LOC de código muerto** (`types/catalog.ts`), `DataTable` sin uso, `enum Label` sin uso | — | Eliminar; lint de código no usado |
| R-41 | **Duplicación**: abecedario de ubicaciones en 3 archivos, lógica de "cajas" en 2, export XLSX en 4, `JSON.parse(localStorage)` en 5+ | — | Una sola implementación por regla |
| R-42 | **`PATCH /x/delete/:id`** mezclado con `DELETE /x/:id` | servicios | Un verbo por intención |
| R-43 | **i18n parcial**: ~60 cadenas hardcodeadas, validaciones inline | — | i18n completo o eliminarlo |

### 2.4 Móvil

| # | Defecto | Evidencia | Corrección |
|---|---|---|---|
| R-44 | **La sesión no se restaura al arrancar**: pide login siempre | `App.js` `bootstrapAsync` vacío | Restauración desde almacén seguro |
| R-45 | **`isTokenExpired` nunca se usa**; sin interceptor de respuesta | `utils/jwt.js` | Manejo de 401 y refresh |
| R-46 | **Máquina de estados del escáner rota**: la rama de ubicación es inalcanzable | 4 pantallas | `sealed interface ScanState` en el dominio compartido |
| R-47 | **Ventana de escaneo desalineada**: máscara 300×100, validación 250×50 | `CodeScanner.js` + 9 pantallas | Ventana definida **una sola vez** en `commonMain` |
| R-48 | **`Array(NaN)` → `RangeError`** si "unidades por caja" no es numérico | `handleToPrint` | Validación numérica fuerte (`toIntOrNull`) |
| R-49 | **Enum de estados incompleto**: faltan `discrepancy`, `done`, `assigned` | `data/status.js` | Enum completo compartido |
| R-50 | **Sin permisos en la UI**: el rol solo pinta una etiqueta | — | Evaluación local con el motor compartido |
| R-51 | **617 LOC muertas** (`MoveProductAdmin`), `ConfigModal` con placeholder "Caca" | — | Eliminar |
| R-52 | **Dependencias no declaradas** (`prop-types`, `@expo/vector-icons`) | `package.json` | Dependencias explícitas + CI que valide |
| R-53 | **Usuario enviado por nombre** en las mutaciones | servicios | `user_id` |
| R-54 | **`setCurrentNotification("msg","error")`** con firma incorrecta | `FreeLabelScreen:111,119` | Type safety (TS en el dominio) |
| R-55 | **`useEffect(async () => …)`** | `InvoiceDetailScreen:140` | Patrón correcto de efectos asíncronos |
| R-56 | **Paginación en cliente** con `page=1` inicial y llamada extra al montar | `MoveProductLocation` | Paginación server-side |

---

## 3. Cambios de clase B — contradicciones internas (las arreglo, fijando la intención declarada)

Aquí el sistema **declara** una intención y hace lo contrario. La corrección fija la intención
declarada, que es la que el propio autor del legacy quiso.

| # | Contradicción | Declara | Hace | Resolución |
|---|---|---|---|---|
| R-57 | **Borrado lógico vs físico** | `Invoice.deleted`, `InvoiceDetail.deleted`, `Location.deleted` en el esquema + migración `add_soft_delete_invoices` | `invoice.delete`, `location.delete` (**físico**); `deleted` **nunca** se escribe | **Borrado lógico real** (la intención estaba escrita en el esquema) con `deleted_at`, `deleted_by` y filtro por defecto. Para anular con efecto contable se usa un movimiento, no un borrado |
| R-58 | **`difference` existe pero nunca se calcula** | campo `Decimal` en el modelo, DTO, mapper y respuesta | siempre `0` | Columna generada `physical_quantity - quantity` |
| R-59 | **Los 4 permisos sembrados que nadie exige** | `user:*`, `order:approve`, `print:update`, `invoice:update` en el seed | ninguna ruta los pide | O se exigen, o se eliminan del catálogo. Test de biyección (R-31) |
| R-60 | **`PENDING_TO_OUT` "no afecta stock"** pero `MOVE` y `LOCATION` restan, sin razón documentada | comentario en el código | efectos por defecto del `else` | Efectos **declarados por movimiento**; el tránsito se modela explícito |
| R-61 | **`Store` = producto + stock**, con `productId` como PK no autoincremental | el nombre dice "almacén" | es la tabla de productos **y** de existencias | Separar `products` de `stock_by_location` |
| R-62 | **`user.mapper` no formatea fechas** pero todos los demás sí | `YYYY-MM-DD` en el resto | `Date` crudo en users | Contrato de fechas uniforme (ISO-8601 UTC) |
| R-63 | **`processReceiptLabelSm` es idéntico a `processReceiptLabel`** | dos etiquetas distintas | mismo cuerpo duplicado | Una implementación parametrizada por tamaño |
| R-64 | **`isLoading`/`isSignout` en el móvil** | el reducer los define | nadie los lee | O se usan, o se eliminan |

---

## 4. Cambios de clase C — reglas de negocio (necesito tu decisión)

Estos **no los toco sin tu confirmación**, y llevan feature flag + test de caracterización del
comportamiento viejo.

### R-65: Confirmar el fix del doble descuento al ubicar

- **Clase:** C (afecta cantidades)
- **Severidad:** **Crítica**
- **Evidencia:** `addKardexLocation` resta dos veces; verificado por simulación (§2.7 del plan
  principal): entra una caja de 100 y el total baja.
- **Comportamiento correcto propuesto:** ubicar es un **traslado** entre la ubicación de recepción y
  el estante. El total **no cambia**. El registro de recepción **conserva sus unidades originales**.
- **CERRADA (R-65): CONFIRMADO.** Ubicar **no baja el total**: es un traslado entre la ubicación de
  recepción y el estante. El registro de recepción **conserve sus unidades originales**. Se
  implementa con feature flag `stock.locate.legacyDecrements` (por defecto `false`) para poder
  revertir sin desplegar.
- **Rollback:** flag `stock.locate.legacyDecrements = true` restaura el comportamiento actual.
- **Impacto en datos existentes:** el stock migrado del legacy **está por debajo del real**. Hay que
  cuantificarlo (§2.7-bis del plan) y decidir qué hacer.

### R-66: `LOCATION` deja de restar del total

Consecuencia directa de R-65. Hoy `LOCATION` resta por la regla por defecto. En el modelo nuevo
**ninguna operación resta del total**, porque el total es una suma derivada.

- **Lo que necesito de ti:** nada nuevo si confirmas R-65. Solo que sepas que la matriz de
  movimientos cambia: `LOCATION` pasa de "resta" a "traslado".

### R-67: Algoritmo de contenido por ubicación

- **Clase:** C · **Severidad:** Alta
- **Evidencia:** `getLocationContent` calcula: si el último movimiento es `ADJUSTMENT` → `total =
  units`; si no, `Σ LOCATION − Σ OUT` desde el último ajuste.
- **El problema:** ese algoritmo **depende de `LOCATION` restando y de `ADMIN` existiendo**. Al
  arreglar R-65/R-66 el algoritmo pierde sentido: con stock por ubicación como fuente de verdad, el
  contenido de una ubicación es **una consulta directa**, no un cálculo.
- **Estado:** pendiente de confirmación explícita, pero **la decisión R-65 ya la condiciona**: el
  algoritmo actual depende de `LOCATION` restando, así que al confirmar R-65 el algoritmo pierde su
  base. Con `stock_by_location` como fuente de verdad, el contenido de una ubicación es una
  **consulta directa**.
- **Mitigación obligatoria antes de simplificar:** un test de caracterización que calcula el
  contenido con **los dos métodos** sobre datos reales y **reporta las diferencias**. Si las
  diferencias son todas explicables por el bug de R-65, se simplifica con confianza. Si no, hay una
  intención de negocio que no vimos.
- **Riesgo:** si el algoritmo tenía una intención contable que no veo, simplificarlo cambia los
  reportes históricos. Mitigación: test de caracterización que compara ambos métodos sobre datos
  reales y **reporta las diferencias** antes de cambiar nada.

### R-68: Borrado lógico con semántica contable

Relacionado con R-57. Si un documento con efecto en inventario (factura ya capturada, orden ya
surtida) **no debe borrarse ni lógicamente**, sino **anularse con un movimiento compensatorio**:

- **Propuesta (pendiente de confirmación):** un documento **con efecto en inventario** (factura ya
  capturada, orden ya surtida) **no se borra** — ni lógica ni físicamente —: se **anula** con un
  movimiento compensatorio, dejando el rastro en el kardex. El borrado lógico se reserva para
  documentos **sin efecto** en inventario. Es coherente con R-57 y con el principio de que el
  inventario se corrige **añadiendo movimientos**, nunca borrando.

### R-69: `ADMIN` como ubicación real de recepción

- **Clase:** C · **Severidad:** Media
- **Evidencia:** `getLocationByCode('ADMIN')` devuelve `{id: 1, …}` **hardcodeado**, sin tocar la BD.
  El `id: 1` es ficticio y puede colisionar con la ubicación real 1.
- **Corrección:** ubicación real con `id` real y `kind = RECEIVING` (ya aprobado por ti).
- **Lo que necesito de ti:** confirmar que **todo el stock de `ADMIN` se considera "recibido y no
  ubicado"**. Si hay stock en `ADMIN` que en realidad nunca pasó por recepción, hay que tratarlo
  distinto en la migración.

### R-70: `unit_measure` como catálogo

- **Clase:** C · **Severidad:** Baja
- **Evidencia:** campo de texto libre; hoy el 100% es `PZA`.
- **Corrección:** catálogo `units_of_measure` con `allows_fraction`.
- **Lo que necesito de ti:** confirmar que no hay unidades fuera de `PZA` en uso real. Si mañana
  entra `KG`, el catálogo evita `KG`/`Kg`/`KGS` como tres unidades distintas.

### R-82: **La conversión de órdenes al ERP no existe** (feature nueva, no corrección)

- **Clase:** C · **Severidad:** Alta
- **Evidencia:** `Order.converted` y `Order.sent` existen en el modelo y **nunca se escriben ni se
  leen** en todo el código. No hay endpoint, ni llamada HTTP al ERP, ni job. `TipoObjeto` se mapea
  en el import pero no se usa para decidir nada.
- **El problema:** el ERP **espera que le devuelvan las órdenes convertidas** (confirmado por ti).
  Ese flujo nunca se implementó. No es un defecto del código: **es una funcionalidad ausente**.
- **Corrección:** construir el **flujo de salida** completo. Se dispara al confirmar el empaque
  (decisión R-72), con job de reintentos, **idempotencia** (no enviar dos veces la misma orden) y
  auditoría del envío. Requiere definir el contrato con el ERP (endpoint o escritura en su base).
- **Riesgo:** sin idempotencia, un reintento duplica órdenes en el ERP y descuadra el inventario
  del otro lado. **La idempotencia no es opcional aquí.**
- **Test:** dos intentos de conversión de la misma orden → **un solo envío**. Fallo del ERP →
  reintento con backoff y la orden queda en estado `PENDING_EXPORT` visible.

### R-83: **La etiqueta se marca impresa sin confirmación** (defecto crítico)

- **Clase:** A · **Severidad:** **Crítica**
- **Evidencia:** `print.cron.ts` hace `axios.post(...)` **sin `await`** y a continuación marca
  `printed: true`. El cron usa `forEach(async …)`, que no espera.
- **Consecuencia:** si la impresora Zebra está apagada o sin red, **la etiqueta se pierde para
  siempre** y el sistema cree que salió. Además, dos ticks solapados pueden **doble imprimir**.
- **Corrección (R-76):** estado `PENDING` hasta que la impresora **confirme**; máquina de estados
  `PENDING → SENT → PRINTED | FAILED`; reintentos con backoff; `attempts` y `last_error`; el
  `forEach(async)` pasa a `await` supervisado. **Una etiqueta no se marca impresa hasta que la
  impresora lo dice.**
- **Test:** impresora caída → la etiqueta queda `FAILED` con `attempts` incrementado y **no** marcada
  como impresa; al volver la impresora, el reintento la imprime.

### R-84: **El catálogo de productos son dos tablas distintas** (defecto crítico)

- **Clase:** A · **Severidad:** Alta
- **Evidencia:** `GET /store/detail/:code` usa `store.findUnique({ productCode })` mientras
  `POST /invoices/detail` resuelve el producto con `store.findUnique({ productId })`. Y en
  `POST /store` el `productId` **lo envía el cliente**.
- **Consecuencia:** si un `productId` ya existe con otro código, la factura **apunta al producto
  equivocado**. Sin FK y sin validación. Es una vía directa a inventario mal imputado.
- **Corrección:** una sola tabla `products` con **PK sustituta** y `product_code` único; **el
  `productId` deja de venir del cliente**; todas las referencias por FK. El `external_id` del ERP se
  conserva como campo aparte (decisión sobre la importación).
- **Test:** intentar crear un producto con un `external_id` ya existente → **error de conflicto**, no
  un producto duplicado ni una factura mal imputada.

### R-85: **Validación de cajas** (decisión R-80)

- **Clase:** C · **Severidad:** Baja — **CERRADA: se valida**
- **Corrección:** si `cantidad / unidadesPorCaja` no es entero, la captura **rechaza con error**.
  Evita facturas de 100 piezas con cajas de 30, que son siempre un dato mal capturado.
- **Riesgo:** si `unitsPerBox` es 0 → **división por cero**. Ya hay una validación en el móvil; hay
  que llevarla al dominio.
- **Test:** `quantity = 100`, `unitsPerBox = 30` → error de validación con mensaje claro;
  `unitsPerBox = 0` → error, nunca excepción.

### R-71: Sufijo `N` en los códigos de producto

- **Clase:** C · **Severidad:** **Crítica para la migración**
- **Evidencia:** 201 productos con código `X` y `XN`; en 180 casos **mismo nombre**.
- **CERRADA (R-71): SON DOS SKUs DISTINTOS.** Se importan los 1.398 tal cual, sin fusionar.
- **Riesgo documentado:** es una **decisión de negocio**, no una verificación técnica. Nombrar los
  201 pares con el mismo nombre es sospechoso, y si resultara que son un producto partido, el stock
  quedaría **duplicado** en el sistema. Queda registrado como riesgo aceptado, con esta nota para
  que dentro de un año se sepa que fue una decisión y no una suposición.
- **Verificación barata de bajo coste:** al migrar, comparar para cada par `X`/`XN` si **ambos
  tienen stock > 0**. Si en los 201 pares casi nunca coinciden en stock positivo, refuerza que son
  SKUs distintos (uno suele estar en cero). Si **ambos tienen stock alto**, hay que sospechar.

---

## 5. Mejoras por mejores prácticas (sin defecto asociado)

Estas no corrigen nada: son el sistema nuevo haciendo las cosas bien desde el principio. No requieren
tu aprobación, pero conviene que sepas que están.

| # | Mejora | Por qué |
|---|---|---|
| M-01 | **Kardex append-only a nivel de BD** (permisos revocados) | Un ledger no es un ledger si se puede editar |
| M-02 | **`quantity_after` en cada movimiento** | El kardex se vuelve auto-auditable |
| M-03 | **Columna generada para `difference`** | Imposible desincronizar |
| M-04 | **Un `CHECK` por invariante** (`quantity >= 0`, ajuste con motivo, `APPROVED` con aprobador) | La BD defiende el dominio, no solo el código |
| M-05 | **Idempotencia por `Idempotency-Key`** | El móvil reintenta sin duplicar movimientos |
| M-06 | **`TIMESTAMPTZ` en todo el sistema** | Los instantes no tienen ambigüedad |
| M-07 | **Enum de estados en la BD, no strings** | Valores inválidos imposibles |
| M-08 | **Dominio puro compartido** entre backend y móvil | La lógica crítica se prueba en milisegundos y no puede divergir |
| M-09 | **Property-based tests** de invariantes de inventario | Cubre combinaciones que nadie escribiría a mano |
| M-10 | **Test de biyección permisos ↔ rutas** | Atrapa permisos muertos y rutas sin protección |
| M-11 | **Test de presupuesto de queries** | El N+1 no vuelve |
| M-12 | **Test que el operador completa el flujo sin un 403** | Un sistema de permisos que impide trabajar está roto |
| M-13 | **Escáner de secretos en CI** | Las credenciales no vuelven al repositorio |
| M-14 | **Guardia de arquitectura del dominio** | El `stock-domain` no se contamina de frameworks |
| M-15 | **Lint de fronteras FSD en web** | El grafo de imports no se degrada |

---

## 6. Priorización

El orden importa: primero lo que no cambia comportamiento observable, para que si algo falla el
espacio de búsqueda sea pequeño.

### Bloque 1 — Correcciones sin cambio de comportamiento (clase A + B)

Van primero porque **no pueden romper el negocio**: el sistema se comporta igual pero mejor.

1. R-01 a R-24 (backend: transacciones, atomicidad, N+1, paginación, validación, errores, secretos)
   — incluida **R-24b a R-24e: la auditoría**, que nace en Fase 0/1 (ver `PLAN_AUDITORIA.md`)
2. R-25 a R-31 (control de acceso)
3. R-32 a R-56 (web y móvil)
4. R-57 a R-64 (contradicciones internas)

**Riesgo de este bloque:** bajo. Cada corrección tiene test y no altera ninguna cantidad.
**Señal de éxito:** los tests de caracterización del comportamiento legacy **siguen pasando**.

### Bloque 2 — Correcciones que cambian comportamiento, con flag

Aquí el negocio ve diferencias. Van **una por una**, con feature flag y test del comportamiento viejo.

5. **R-65 + R-66** (doble descuento al ubicar) — el más importante, y el que más stock mueve
6. **R-67** (algoritmo de contenido por ubicación)
7. **R-68** (borrado vs anulación)
8. **R-69** (`ADMIN` como ubicación real)

**Riesgo de este bloque:** alto, y es inevitable: estamos corrigiendo algo que estaba mal.
**Señal de éxito:** el inventario cuadra (invariante I1–I10) y el personal de piso no reporta
fricción.

### Bloque 3 — Dependiente de decisiones de negocio

9. R-70 (unidades), R-71 (sufijo `N`), umbral de discrepancia, segregación de funciones

**Riesgo:** bloqueado hasta tener tu respuesta.

---

## 7. Cómo se verifica que un refactor no rompió nada

Cuatro redes, en capas:

| Red | Qué atrapa | Cuándo |
|---|---|---|
| **1. Tests de caracterización** (golden) | Cambios de comportamiento no intencionales en el legacy | En cada commit |
| **2. Tests de invariantes I1–I10** (property-based) | Descuadres de inventario bajo secuencias aleatorias | En cada commit |
| **3. Tests de contrato** | Divergencia entre backend, web y móvil | En cada commit |
| **4. Doble lectura en producción** | Diferencias reales con datos reales | Durante el corte (Fase 7) |

**La cuarta es la que da la confianza final:** durante un período, el sistema nuevo y el viejo
procesan la misma operación y se comparan los resultados. Cuando coinciden (o las diferencias están
explicadas y firmadas), se corta. Sin doble lectura, el corte es una apuesta.

Y la regla de proceso que cierra el círculo: **todo bug de producción entra primero como test que
falla, después como corrección.** Es la única forma de que la deuda de "cero tests" no se repita.

---

## 8. Lo que necesito de ti

**Confirmaciones de clase C (bloquean el bloque 2):**

1. **R-65** — ¿confirmas que ubicar **no debe bajar** el total? (Ya lo describiste así; lo dejo por
   escrito porque mueve stock.)
2. **R-67** — ¿el contenido de una ubicación es simplemente "lo que hay ahí", o el algoritmo actual
   tiene una intención de negocio que no veo?
3. **R-68** — ¿una factura ya capturada se puede borrar, o solo **anular** con movimiento
   compensatorio?
4. **R-69** — ¿todo el stock en `ADMIN` es "recibido y no ubicado"?

**Decisiones que bloquean la migración:**

5. **R-71** — el sufijo `N`: ¿dos SKUs o un producto con dos códigos?
6. **La discrepancia heredada** — el stock del Excel está por debajo del real por R-01. ¿Se carga
   fiel al legacy o se reconcilia contra conteo físico antes de cortar?

**Decisiones de configuración (no bloquean el diseño):**

7. Umbral de discrepancia real (propuse 50 piezas).
8. ¿Segregación de funciones en la aprobación? (política PB3)
9. ¿Unidades de medida fuera de `PZA`? (R-70)

**Y una pregunta de proceso:**

10. **¿Quieres revisar el bloque 1 completo antes de que empiece, o prefieres que ejecute las
    correcciones de clase A directamente** y te presente solo el bloque 2 (que cambia
    comportamiento) para aprobación? Lo segundo es más rápido y no cambia nada observable.
