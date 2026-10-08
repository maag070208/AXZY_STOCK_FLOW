# Plan de Almacén Integral — Almacén (Cascar / STOCK)

> Documento de arquitectura y roadmap para el rediseño completo del sistema de almacén:
> **backend Ktor (Kotlin) + web React (axzy_ui_system) + mobile KMP (Compose Multiplatform)**,
> sobre **PostgreSQL 16 en Docker**.
>
> Estado: **EN IMPLEMENTACIÓN**. El plan se aprobó y hay código funcionando.
>
> **Resumen a fecha de la última actualización:**
>
> | Fase | Estado |
> |---|---|
> | 0 · Fundamentos y dominio | ✅ completa — 334 tests, guardias de arquitectura |
> | 1 · Backend núcleo | ✅ completa — identidad, catálogo, ubicaciones |
> | 2 · Recepción e inventario | ✅ completa — motor, ajustes, kardex, facturas, **regla B1** |
> | 3 · Surtido + ERP | ⬜ sin empezar |
> | 3-bis · Traslados | 🔶 motor y esquema listos, sin rutas |
> | 4 · Etiquetas | 🔶 esquema listo, sin máquina de estados |
> | 5 · Web | 🔶 en curso — las 18 pantallas existen, se están conectando al API |
> | 6 · Móvil | 🔶 build KMP + escáner + APK, sin conectar al API |
> | 7 · Migración | ✅ 1.398 productos y 10.914.130 unidades cargados |
>
> **627 tests, 0 fallos.** Backend en 16 rutas, todas respondiendo.
>
> Las decisiones que se cerraron durante la implementación están en §7 y en las
> bitácoras de los commits; las que más cambiaron el diseño fueron: el stock inicial va
> a una ubicación `SIN-UBICAR` propia, la aprobación de discrepancias es **por factura**
> (R-86), y el ERP será REST con `Idempotency-Key` (contrato definido por nosotros,
> porque en el legacy no existía).
>
> **Lo que NO está verificado**: el proyecto Xcode de iOS nunca se abrió (el `pbxproj`
> se escribió a ciegas) y la cámara del móvil no se ha probado en un dispositivo real.

---

## 0. Resumen ejecutivo

Los tres repos en `~/DEV/STOCK/BASE` son la versión productiva de un sistema de almacén
("Cascar") con tres clientes sobre una sola API:

| Repo | Stack actual | Tamaño | Rol |
|---|---|---|---|
| `backend` | Node 16 + Express 4 + Prisma 4 + MySQL (+ SQL Server para sync) | ~4.000 LOC TS, 9 routers, 60 rutas | API única |
| `frontend` | React 18 + Vite 3 + Chakra 2 (+ MUI declarado sin usar) + Redux | 4.833 LOC TSX, 13 páginas | Web administrativa |
| `mobile-app` | Expo 47 / RN 0.70 (JS puro) + Paper 5 | 8.754 LOC JS, 26 pantallas | Operación en piso (escáner) |

El objetivo es **reescribir los tres** manteniendo el dominio y los flujos reales, pero:

- **Backend → Ktor 3 + Exposed + PostgreSQL 16 (Docker)**, con migraciones versionadas (Flyway),
  JWT con refresh, permisos por rol, OpenAPI generado desde el código y tests de dominio.
- **Web → React 19 + Vite + Tailwind v4 + `@axzydev/axzy_ui_system`**, replicando exactamente la
  arquitectura **FSD** del repo de referencia `~/DEV/PTNV/RESPONSIVA/web`.
- **Mobile → Kotlin Multiplatform + Compose Multiplatform** (UI compartida Android/iOS),
  Ktor Client, SQLDelight local, escáner de código de barras nativo, sincronización resiliente.

**Decisión estructural — un solo dominio compartido.** El corazón del sistema (matriz
movimiento→stock, máquinas de estado, reglas de captura y ubicación) vive en **`stock-domain`,
un módulo Kotlin Multiplatform puro** consumido por el backend Ktor y por el app KMP a la vez
(§3.2-bis). No depende de Ktor, Exposed, SQLDelight ni Compose, y una **guardia de arquitectura en
CI** impide que eso cambie. Consecuencia práctica: esas reglas se prueban en JVM puro en menos de un
segundo, sin base de datos ni emulador, y **el backend y el móvil no pueden divergir** porque
ejecutan el mismo código. Es la mitigación más fuerte contra el riesgo de "stock mal calculado",
que es el peor fallo posible en este sistema.

**Hallazgo clave 1 — el modelo de inventario está partido.** El backend mantiene a la vez datos
**desnormalizados sin integridad** (`Store.stock`, `Store.productId` como PK sin ser identity,
`invoice_lines.productId` sin FK, `kardex.user` como nombre libre) y un **ledger** (`Kardex`,
`location_content`) que sí tiene la verdad del inventario. Ambos se actualizan **en pasos separados
y sin transacción**, por lo que pueden divergir (y el cálculo de stock es read-modify-write en JS,
vulnerable a concurrencia). El plan unifica esto en un **modelo único de inventario por ubicación**
donde el `Kardex` es un libro **append-only** y `stock_by_location` la proyección consultable,
actualizados en la misma transacción.

**Hallazgo clave 2 — hay defectos funcionales reales, no sólo de estilo.** La auditoría de código
encontró, entre otros: el campo `difference` (discrepancias) **nunca se calcula**; los borrados
"lógicos" de facturas y ubicaciones son en realidad **físicos**; hay **3 rutas sin autenticación**;
el bypass de permisos es `role === 1` hardcodeado y el rol admin **no tiene permisos sembrados**;
el `JWT` no es revocable; hay **SQL injection por concatenación**; la app móvil **no restaura la
sesión** al arrancar y su **máquina de estados de escaneo de 2 pasos está rota**; la web tiene un
**axios que no propaga errores** y un permiso equivocado en Locaciones. El detalle completo está en
§2.2-bis, §2.2-ter, §1.3 y §1.3-bis, y **cada punto tiene su corrección asignada** en el roadmap.

**Hallazgo clave 3 — el defecto del legacy no es un descuadre de inventario, y me equivoqué dos
veces al analizarlo.** Reporté primero que ubicar restaba dos veces del total y después que restaba
una. **Las dos versiones eran falsas**: verifiqué las cinco llamadas a `updateStoreStock` y ninguna
de las funciones de ubicar lo invoca, así que **el total del legacy queda correcto** (§2.7).

Los defectos reales son otros dos, y siguen justificando la reescritura:
1. **El libro se muta**: `removeUnitsFromKardex` hace `kardex.update` sobre un registro histórico, así
   que tras ubicar el registro de recepción dice 0 y **no se puede reconstruir qué se recibió**.
2. **El modelo no puede decir dónde está el inventario**: hay un solo número por producto. El legacy
   cuadra en total y no sabe dónde está nada.
3. Y hay un **defecto latente**: `updateStoreStock` con tipo `LOCATION` sí resta, pero nadie la
   invoca con ese tipo. Es la trampa para un rewrite que "reutilice" esa función.

Implicación corregida para la migración: las **10.914.130 unidades del Excel son fiables**. Lo que
falta no es el número, es el **desglose por ubicación**, que el archivo no trae y que hay que
descubrir con conteo físico (decisión R-73).

**Hallazgo clave 4 — el catálogo real está perfilado y es de buena calidad.** El Excel
`INVENTARIO REAL LUNES 10 DE JUN 2024` trae **1.398 productos** con `product_code` único, `PZA` al
100%, **10.914.130 unidades** y cero nulos (§3.5). Eso permite un importador **estricto** que
rechaza filas en vez de adivinar. Salvo un punto que requiere tu decisión: **201 productos tienen
un código hermano con sufijo `N` y 180 comparten el mismo nombre** — si son un solo producto con
dos códigos, hay stock partido en dos y hay que decidirlo antes de importar.

**Hallazgo clave 5 — la deuda de "cero tests" es el mayor riesgo vivo.** Ninguno de los tres repos
tiene un solo test, así que hoy la única especificación del comportamiento es el código. El plan
incluye una **estrategia de pruebas de 10 niveles con 10 invariantes property-based** (§3.6) y un
**catálogo de 36 malas prácticas del código BASE, cada una con su corrección y el test que impide
que vuelva** (§2.2-quater).

**Alcance de este entregable:** plan completo + arquitectura + contratos de API + modelo de datos +
estrategia de pruebas + roadmap por fases. No incluye scaffold de código.

---

## 1. Inventario funcional actual (lo que hay que reconstruir)

### 1.1 Backend — superficie de API real

Montado en `src/index.ts` (puerto 4000), con `helmet`, `cors`, `morgan`, `bodyParser` (50 MB),
Swagger en `/api-docs` y un validador OpenAPI (`apiValidator`) montado sobre toda la app.

| Prefijo | Router | Notas |
|---|---|---|
| `/users` | `user.routes.ts` | login, CRUD usuarios, roles |
| `/products` | `product.routes.ts` | catálogo |
| `/invoices` | `invoice.routes.ts` | recepción de facturas |
| `/orders` | `order.routes.ts` | empaque / surtido |
| `/roles` | `role.routes.ts` | permisos del rol |
| `/locations` | `location.routes.ts` | ubicaciones + inventario por ubicación |
| `/prints` | `print.routes.ts` | cola de impresión de etiquetas |
| `/store` | `kardex.routes.ts` | store + kardex + reportes |
| `/kardo` | `kardo.routes.ts` | sync con SQL Server (`DataTransfers`) |

**Contrato detallado (método · ruta · permiso requerido):**

```txt
# users
GET    /users                    admin          -> lista usuarios
GET    /users/list               token          -> lista de nombres (para selectores)
POST   /users                    admin          -> crear usuario
PATCH  /users                    admin          -> actualizar usuario
PATCH  /users/password           admin          -> cambiar password
POST   /users/login              público        -> { token } + datos de usuario
GET    /users/roles              admin          -> roles disponibles

# roles
GET    /roles                    token          -> rol actual + permisos

# products
GET    /products                 token          -> catálogo
GET    /products/:code           token          -> producto por código de barras

# invoices
POST   /invoices                        invoice:create   -> crear factura
GET    /invoices                        invoice:read     -> listar
PATCH  /invoices                        invoice:assign   -> asignar factura a usuario
POST   /invoices/detail                 invoice:create   -> agregar detalle
GET    /invoices/detail/:id             invoice:read     -> detalle por factura
GET    /invoices/detail/:id/:userId     invoice:read     -> detalle por factura+usuario
PATCH  /invoices/detail/:id             invoice:capture  -> actualizar línea
PATCH  /invoices/detail/capture         invoice:capture  -> captura física en lote
DELETE /invoices/detail/:id             (sin permiso)    -> borrar línea (soft)
GET    /invoices/ready/:id              invoice:read     -> listas para trabajar
GET    /invoices/pending                invoice:read     -> pendientes
GET    /invoices/to-work/:id            invoice:read     -> asignadas al usuario
PATCH  /invoices/assign                 invoice:assign   -> asignar línea a ubicación
PATCH  /invoices/delete/:id             invoice:delete   -> soft delete
GET    /invoices/:id                    invoice:read     -> una factura
PATCH  /invoices/:id                    invoice:approve  -> ajustar/cerrar factura

# orders
POST   /orders                          order:create     -> crear orden
GET    /orders                          order:read       -> listar
GET    /orders/ready/:id                order:read       -> listas
POST   /orders/detail                   order:create     -> agregar línea
GET    /orders/detail/:id               order:read       -> detalle
GET    /orders/detail/:id/:user         order:read       -> detalle por usuario
DELETE /orders/detail/:id               order:delete     -> borrar línea
PATCH  /orders/... (assign, update, material, confirm)  -> ver §1.4

# locations
GET    /locations                       location:read    -> listar
GET    /locations/:code                 location:read    -> por código
GET    /locations/inventory/:code       location:read    -> contenido de ubicación
PATCH  /locations/inventory             location:read    -> mover producto entre ubicaciones
POST   /locations/inventory             location:read    -> ajustar inventario de ubicación
POST   /locations                       location:create  -> crear
PATCH  /locations                       location:update  -> actualizar
PATCH  /locations/delete/:id            location:delete  -> soft delete

# store (kardex/inventario global)
POST   /store                           token  -> crear store (+kardex INITIAL)
POST   /store/many                      token  -> alta masiva (import)
PATCH  /store                           token  -> actualizar store
DELETE /store/:productCode              token  -> borrar
GET    /store/:productCode              token  -> store por código
GET    /store/detail/:productCode       token  -> store + kardex del producto
GET    /store/report/product/:startId/:endId     token -> reporte por rango de id
GET    /store/report/location/:start/:end        token  -> reporte por rango de ubicación
GET    /store/report/:startDate/:endDate         token  -> reporte por rango de fechas

# prints
GET    /prints            print:read    -> cola
POST   /prints            print:create  -> encolar etiquetas
PATCH  /prints            (sin permiso) -> marcar impresa

# kardo (integración legacy)
GET    /kardo             público -> lee DataTransfers de SQL Server
```

**Autenticación y permisos (estado actual):**

- `POST /users/login` devuelve un **JWT** firmado con `secretKey`; payload `{ id, roleId }`.
- `token-validator.middleware.ts` verifica el JWT y deja `req.user = { id, role }`.
- `role-validator.middleware.ts` exige un permiso concreto (`validateRolePermissions('invoice:read')`).
  **Atajo inseguro vigente:** si `role === 1` (admin) se salta toda validación por hardcode.
- `admin-role-validator.middleware.ts` restringe a admin.
- `axzy-validator.middleware.ts` hace login contra un servicio externo AXZY con **credenciales
  hardcodeadas** (`maag070208@gmail.com`). Está **comentado** en `index.ts`. → eliminar.
- Catálogo de permisos (seed `prisma/seeds/security.ts`):
  `user:*`, `invoice:{create,read,update,delete,approve,assign,capture}`,
  `order:{create,read,update,delete,approve,assign,capture}`,
  `location:{create,read,update,delete}`, `print:{create,read,update}`.
- Roles seed: `admin`, `operaciones` (todos los de invoice/order/location/print),
  `almacen` (solo `invoice:read`,`invoice:capture`,`order:read`,`order:capture`).
- Usuario admin por defecto: `admin` / `admin12345`.

**Estados usados en la práctica** (strings libres, sin enum en BD):
`pending`, `assigned`, `captured`, `discrepancy`, `done`, `verified`,
`pending-to-locate`, `pending-to-out`, y `converted` (orden: 0/1).

**Movimientos de kardex** (`KardexType` en `dto/kardex.dto.ts`):
`INITIAL`, `IN`, `OUT`, `ADJUSTMENT`, `LOCATION`, `MOVE`, `PENDING-TO-LOCATE`, `PENDING-TO-OUT`.
En `service/` sólo se emiten `INITIAL`, `IN`, `OUT`, `ADJUSTMENT`, `LOCATION`.

**Impresión (Zebra ZPL):** `print.cron.ts` corre cada minuto, lee `Print` con `printed = false`,
y según `labelId` (1..8) arma el ZPL y hace `POST` a la impresora Zebra
(`zebraPrinter` en `config/config.ts`). Etiquetas seed: `receipt`, `locations`, `assortment`,
`mesh`, `free`, `locationsLG`, `freeSM`, `receiptSM` (plantillas ZPL con placeholders
`{{productCode}}`, etc.).

**Integración SQL Server (`kardo`):** consulta `SELECT DataTransferId, TipoObjeto, ObjetoJSON
FROM DataTransfers WHERE Estatus = 0` y marca `Estatus = 1`. Es un bus de integración con un
sistema externo. **Decisión pendiente:** conservar como módulo `integration` o descartar.

### 1.2 Web (frontend actual) — rutas y pantallas

`src/App.tsx` define las rutas (react-router v6, todas bajo `ProtectedRoute`):

| Ruta | Página | Contenido real |
|---|---|---|
| `/login` | `Login.tsx` | Formik + yup, guarda `user` en localStorage |
| `/` | `Home.tsx` | Grid de `HomeActionCard` con accesos |
| `/invoices` | `Invoices.tsx` | Listado + alta de factura |
| `/invoices/:id` | `InvoiceDetail.tsx` | Detalle / captura |
| `/orders` | `Orders.tsx` | Listado de órdenes |
| `/orders/:id` | `OrderDetail.tsx` | Detalle de orden |
| `/locations` | `Locations.tsx` (645 LOC) | Lista, alta/edición, borrado, **buscar**, **mover ubicación**, **mover producto**, etiquetas |
| `/locations/:start/:end` | `LocationDetails.tsx` | Contenido entre dos ubicaciones |
| `/inventory` | `Inventory.tsx` | Inventario por producto y por ubicación |
| `/kardex` | `Kardex.tsx` | Movimientos por producto y por rango de fechas |
| `/reports` | `Reports.tsx` | Reporte por rango de ids de producto → ubicaciones |
| `/users` | `Users.tsx` | Alta de usuarios |
| `/tickets` | `Ticket.tsx` | Placeholder (33 LOC) |

Servicios (`src/services/*.ts`): `commonService`, `invoiceService`, `kardexService`, `kardoService`,
`locationService`, `orderService`, `printService`, `productService`, `roleService`, `userService`.
Estado: Redux Toolkit (`userSlice`, `notificationSlice`). i18n con `i18next` + `react-i18next`.
Exportación con `xlsx` + `file-saver`.

**Problemas a corregir en la reescritura:** `Table` recibe `mockData` (nombre engañoso),
dos librerías de UI (Chakra **y** MUI) conviviendo, `Kardex`/`Reports` con tablas planas sin
paginación de servidor, `Locations.tsx` concentra 5 flujos en un archivo, y no hay ruta 404
ni estados de carga/error por página. El detalle de defectos está en §1.3-bis.

### 1.3 Mobile (mobile-app) — 26 pantallas

Navegación (`App.js`): `AuthContext` + stack (`Login`, `Config`) **o** `TabNavigator`
(`Home`, `User` + pantallas ocultas accesibles por navegación): `Invoices`, `Orders`, `Tickets`,
`Location`, `Inventory`. La config de servidor se guarda en AsyncStorage y se inyecta como
`baseURL` en el interceptor axios (`http://<serverUrl>:4000`, **puerto y `http` hardcodeados**).

| Grupo | Pantallas |
|---|---|
| Auth / shell | `LoginScreen`, `HomeScreen`, `ConfigScreen`, `UserScreen` |
| Facturas | `InvoiceScreen` (stack), `InvoiceListScreen`, `InvoiceDetailScreen` (790 LOC), `InvoiceAssignStoreScreen` (537 LOC) |
| Órdenes | `OrderScreen` (stack), `OrderHomeScreen`, `OrderListScreen`, `OrderCreateScreen`, `OrderDetailsCreateScreen` (585 LOC), `OrderDetailScreen` (970 LOC) |
| Ubicaciones | `LocationsScreen`, `LocationsOptionsScreen`, `SearchLocationContentScreen`, `MoveProductLocation` (631 LOC), `MoveProductAdmin` (617 LOC, **muerto**) |
| Inventario | `InventoryScreen`, `InventoryOptionsScreen`, `InventorySearchScreen` |
| Etiquetas | `FreeLabelScreen` (413 LOC), `MeshScreen` (468 LOC) |
| Otros | `TicketScreen`, `TicketListScreen` |

Capacidades transversales: escáner de código de barras con `expo-barcode-scanner` + máscara
(`react-native-barcode-mask`); `react-native-vision-camera` está declarado y **nunca se importa**.
Formularios con `react-hook-form` + `yup`, tablas con `react-native-paper`, `@react-navigation`.
Persistencia sólo de `user` y `axios` en AsyncStorage — **sin caché de negocio, sin cola offline,
sin reintentos**.

**Defectos confirmados a corregir en el port (no replicar):**

| # | Defecto | Impacto |
|---|---|---|
| M1 | **La sesión no se restaura al arrancar**: `bootstrapAsync` está vacío; `RESTORE_TOKEN`/`LEAVE` nunca se despachan | Se pide login en cada arranque aunque el token siga guardado |
| M2 | **Sin control de expiración**: `utils/jwt.js` (`isTokenExpired`) no se importa en ningún sitio; axios no tiene interceptor de respuesta | 401/403/500 sin logout, refresh ni mensaje; `timeout: 0` |
| M3 | **Máquina de estados del escaneo de 2 pasos rota** (replicada en 4 pantallas: `InvoiceAssignStore`, `OrderDetail`, `MoveProductLocation`, `MoveProductAdmin`): `step` sólo sube una vez y la rama que asigna/mueve queda inalcanzable hasta que un error la devuelve a 0 | El escaneo de ubicación falla y exige un tercer escaneo |
| M4 | **Ventana de escaneo desalineada**: la máscara visual es 300×100 pero la validación por `e.bounds` acepta sólo 250×50 | Escaneos válidos descartados |
| M5 | **Riesgo de `RangeError` al imprimir**: no se valida que "unidades por caja" sea numérico → `Array(NaN)` | Crash en `OrderDetailScreen` / `InvoiceDetailScreen` |
| M6 | **Enum de estados incompleto** (`src/data/status.js`): faltan `discrepancy`, `done`, `assigned` | Cabeceras vacías y filas sin acción |
| M7 | **Sin permisos por rol en la UI**: `roleId` sólo pinta una etiqueta | Errores 403 silenciosos |
| M8 | **Código muerto**: `MoveProductAdmin.js` (617 LOC), `TicketList`, `TicketItem`, `AssignLocationCard`, `ConfigModal` (contiene el texto placeholder "Caca"), `utils/jwt.js`, `utils/wait.js` | Deuda y confusión |
| M9 | **Dependencias no declaradas**: `prop-types` (17 archivos) y `@expo/vector-icons` | Build frágil |
| M10 | **Config de servidor incompleta**: sólo `serverUrl`; sin esquema, puerto, precarga ni test de conexión | Android necesitará cleartext o HTTPS |
| M11 | **Bugs varios**: `FreeLabelScreen` llama `setCurrentNotification("msg","error")` con firma incorrecta; `OrderCreateScreen` no pasa `getReadyInvoices`; `InvoiceDetailScreen:140` usa `useEffect(async …)`; paginación en cliente en `MoveProductLocation` | Comportamiento inconsistente |
| M12 | **Usuario enviado por nombre** en casi todas las mutaciones (no por id) | Trazabilidad y autorización frágiles |

**Nota de mapa:** `MoveProductAdmin` es la variante sin paginación de `MoveProductLocation`; al
unificar en `feature/inventory/MoveProductScreen` desaparece la duplicación de 1.200 LOC.

### 1.3-bis Web (frontend) — defectos confirmados a corregir

| # | Defecto | Impacto |
|---|---|---|
| W1 | **Axios roto**: `authHeader()` se evalúa una sola vez al crear el módulo (token obsoleto tras login sin recargar) y el `catch` **no hace `return`/`throw`** | En error la promesa resuelve `undefined`; notificaciones poco fiables |
| W2 | **Permisos superficiales**: sólo 5 usos de `hasPermission` en todo el código, uno **equivocado** (`Locations.tsx` usa `invoice:create`); `/users` y todas las acciones destructivas sin gating; sin guardas por ruta | Escalada de privilegios desde la UI |
| W3 | **Menú hardcodeado** en `Layout.tsx`, duplicado en `Home.tsx`, sin filtrar por permisos; `NavItem` usa `<a href>` → recarga completa | UX pobre y bug de sesión |
| W4 | **Kardex roto**: `getData` nunca se invoca (`store` siempre `null`) y `setKardexLocation` nunca se llama | El modal siempre muestra la rama de importar archivo; el select de ubicación queda vacío |
| W5 | **Fechas**: `toLocaleTimeString` con opciones de fecha (pierde la fecha) en 3 sitios; `FECHA_MOVIMIENTO` y `CEATED` salen ambos de `createdAt` | Reportes incorrectos |
| W6 | **Estado vacío inalcanzable** en `LocationDetails` (`{!data && …}` con `data` siempre array) y export siempre habilitado | UI engañosa |
| W7 | **Código muerto**: `types/catalog.ts` (1.817 LOC), `organisms/DataTable`, `types/labels.ts`, imports sin uso | Ruido y peso |
| W8 | **Duplicación**: abecedario de ubicaciones en 3 archivos, lógica de "cajas" en 2, boilerplate xlsx/Blob/saveAs en 4, `JSON.parse(localStorage.user)` en 5+ | Mantenimiento |
| W9 | **`Table` usa `react-table` v7** (sin mantenimiento), tipa `UseSortBy` pero **no invoca `useSortBy`** → sin ordenamiento; 100 % cliente | No escala |
| W10 | **Sin estados de carga/error** por página; `StrictMode` + `useEffect` sin cancelación → doble fetch | Condiciones de carrera |
| W11 | **i18n parcial**: sólo Login/Sidebar/Invoices/Orders/Modal; ~60 cadenas y todas las validaciones hardcodeadas; `Table` inyecta UI en inglés | Inconsistencia |
| W12 | **Dependencias declaradas sin ningún import**: MUI (3), `material-react-table`, `table-pagination-chakra-ui`, `framer-motion` | Peso innecesario |

**Endpoint ausente en el cliente web:** no hay UI para `PATCH /invoices/:id`
(`invoice:approve`), es decir, **la conciliación/cierre de factura no es accesible desde la web**.
Se resuelve con el nuevo `InvoiceDetailPage` (§3.3).

### 1.4 Flujos de negocio núcleo (los que el rewrite debe preservar 1:1)

**A. Recepción de factura (inbound)**
1. `POST /invoices` → factura con `invoiceNumber` (único), `receiptDate`, `invoiceType`
   (`NATIONAL` | `INTERNATIONAL`) y su detalle (`InvoiceDetail`: `quantity`, `boxQuantity`,
   `unitsPerBox`, `container`, `unitMeasure`).
2. Captura física: `PATCH /invoices/detail/capture` con `products[]`
   (`physicalQuantity`, `boxQuantity`, `location`, …). Se calculan `difference`,
   `physicalBoxQuantity`, `physicalAssignItems`.
3. Estado de línea: si `quantity == physicalQuantity` → `captured`; si difieren → `discrepancy`;
   si ya estaba `assigned` se respeta `assigned`.
4. Ajuste de línea (`adjustInvoiceDetail`): cuando la cantidad física sube se emite kardex `IN`;
   cuando baja, `OUT`.
5. Cuando **ninguna** línea está en `pending`/`discrepancy`/`assigned`, la factura pasa a
   `pending-to-locate` (`updateInvoiceStatus` / `updateInvoiceStatusByInvoiceID`).
6. Asignación a ubicación (`assignLocationStore`): valida que la ubicación exista, suma
   `physicalAssignItems`, marca la línea `done` al completar `quantity`, y emite kardex `LOCATION`.
7. `PATCH /invoices/:id` (`adjustInvoice`) cierra/valida.

**B. Orden / surtido (outbound)**
1. `POST /orders` → orden (`orderNumber` único, `datePackaging`, `codigoCliente`, `tipoConcepto`).
2. Detalle con `quantity` y `physicalAssignItems`; flag `supplied`.
3. Asignación de usuario (`assignOrder`) y captura (`updateOrderDetails`,
   `updateStoreLocationWithOrder`, `confirmOrder`).
4. Descuento de stock por ubicación y emisión de kardex `OUT` / `pending-to-out`.
5. `converted` marca órdenes ya volcadas al sistema externo.

**C. Movimiento de stock entre ubicaciones**
`moveProductFromLocation` (`PATCH /locations/inventory`): valida origen y destino, descuenta
en origen, suma en destino, emite kardex `MOVE`/`LOCATION` y `ADJUSTMENT` cuando hay corrección.

**D. Alta masiva de productos**
`POST /store/many`: filtra productos ya existentes y **ids duplicados dentro del propio payload**,
inserta los nuevos y genera un kardex `INITIAL` por cada uno. Devuelve un resumen con
`existingProducts`, `duplicatedIds`, `newProducts`.

**E. Impresión de etiquetas**
`POST /prints` encola N etiquetas (`labels[]` con `labelId` y `data`); el cron las convierte a ZPL
y las envía a la Zebra. En móvil: `FreeLabelScreen` (etiqueta libre, tamaños `sm`/`lg`,
Nacional/Importado, `loop` de copias) y `MeshScreen` (etiqueta de malla).

---

## 2. Modelo de datos — de Prisma/MySQL a PostgreSQL

### 2.1 Esquema actual (Prisma, 196 líneas)

Entidades: `User`, `Role`, `RolePermission`, `Permission`, `Invoice`, `InvoiceDetail`, `Order`,
`OrderDetail`, `Location`, `Labels`, `Store`, `Kardex`, `Print`, enum `InvoiceType`.

### 2.2 Defectos detectados que el rediseño corrige

| # | Problema | Impacto | Corrección |
|---|---|---|---|
| 1 | `Store.productId` es `@id` pero **no** autoincremental, y convive con `productCode @unique` | PK viene de un sistema externo; riesgo de colisión y de romper FKs | `product_id BIGINT` como PK sustituta + `product_code` único; `source_product_id` separado si hace falta |
| 2 | `InvoiceDetail.productId` / `OrderDetail.productId` sin FK ni tabla `Product` | Datos huérfanos; el catálogo se consulta por código contra un servicio externo | Tabla `products` real + FK |
| 3 | `Store.stock` desnormalizado vs. `Kardex` (ledger) — pueden divergir | Inventario inconsistente; el "arreglo" es correr kardex | `stock_by_location` como proyección mantenida en transacción + job de reconciliación |
| 4 | `Location` guarda `location` (código) como string; `Kardex.location` y `InvoiceDetail.location` idem | Sin integridad referencial en ubicaciones | FK a `locations.id`, `location_code` desnormalizado sólo para lectura |
| 5 | Estados como `String @default("pending")` | Valores inválidos posibles | Enums PostgreSQL nativos |
| 6 | `Kardex.units Int` | No soporta fracciones (hay `Decimal` en detalles) | `NUMERIC(14,3)` |
| 7 | `Kardex.productCode String` sin FK | Huérfanos | FK a `products` |
| 8 | Sin `updatedBy` / auditoría | No se sabe quién cambió qué | `created_by`, `updated_by` + tabla `audit_log` |
| 9 | `Print.data Json` sin tipar | Errores en runtime | `JSONB` + validación de esquema por `label_id` |
| 10 | `Kardex.user String` (nombre libre o `'admin'`) | Sin trazabilidad real de quién movió | FK `user_id` |
| 11 | `Invoice.receiptDate` / `Order.datePackaging` son `String` | Comparaciones y rangos de fecha frágiles | `DATE` |
| 12 | Migraciones inconsistentes (36 legacy en `migrations/` + 3 en `prisma/migrations/`) | El historial no reconstruye la BD | **Baseline nuevo** generado desde el esquema real |

### 2.2-bis Defectos de lógica confirmados en el código actual

Estos no son de esquema sino de implementación, y **no deben replicarse**:

| # | Defecto | Evidencia | Corrección en el rewrite |
|---|---|---|---|
| L1 | **`difference` nunca se calcula**: existe en modelo, DTO y mapper, pero no hay ninguna resta `quantity − physicalQuantity` | `invoice.service.ts:518` sólo escribe `difference: 0` | Columna generada `GENERATED ALWAYS AS (physical_quantity - quantity) STORED` |
| L2 | **Borrados "lógicos" que son físicos**: `Invoice.deleted` y `Location.deleted` nunca se escriben | `deleteInvoice` hace `invoice.delete`; `deleteLocation` hace `location.delete` | Borrado lógico real o eliminar el flag |
| L3 | **Sin transacciones en stock**: `Kardex` y `Store.stock` se actualizan por separado | `addKardex` → `updateStoreStock` fuera de transacción | Una transacción por movimiento + test de cuadre |
| L4 | **Race conditions por read-modify-write**: el stock se calcula en JS | `updateStoreStock` lee y reescribe | `UPDATE ... SET quantity = quantity + ?` atómico o lock optimista |
| L5 | **Fire-and-forget**: salidas de kardex nunca esperadas | `order.service.ts:350` usa `.map()` sin `await`/`Promise.all`; `print.cron.ts` usa `forEach(async …)` | `await` explícito o corrutinas supervisadas |
| L6 | **Errores de negocio con HTTP 200** y 500 con cuerpo `{}` | `addInvoice`/`addOrder` devuelven `{error:true}` con 200; `res.json(error)` con `Error` serializa `{}` | `ApiError` tipado con códigos HTTP correctos |
| L7 | **`update[0].id` sin comprobar** en captura con `products: []` | `invoice.service.ts:329` → TypeError | Validación de entrada + lista vacía rechazada |
| L8 | **`GET /invoices/pending` filtra por `Invoice.userId`**, campo inexistente en el modelo | `invoice.service.ts:271` | Filtro por `invoice_lines.assigned_user_id` |
| L9 | **Validación de stock insuficiente inoperante al mover**: compara `.total` sobre un array | `moveProductFromLocation`, `location.service.ts:550` | Tipar el retorno y validar de verdad |
| L10 | **`deleteStore` no espera el `updateMany`** → responde `{}` | `kardex.service.ts:171` | `await` |
| L11 | **`GET /products` no filtra `deleted`** → productos borrados seleccionables | `product.service.ts:3` | Filtro + FK real |
| L12 | **Mass assignment** en `PUT /orders/detail`: spread de `req.body` a Prisma | `updateOrder(req.body)` | DTO cerrado con allowlist |
| L13 | **N+1 sistemático**: un query por fila | `getInvoiceDetail`, `getOrderDetail`, `getLocationContent`, `getProductLocationsHasStock`, `getStoreByIdRange` | Joins y proyecciones |
| L14 | **Sin paginación en ninguna ruta**; varios listados cargan la tabla entera en memoria | Todos los `GET` de colección | Paginación obligatoria (ya en el contrato §4) |
| L15 | **Zona horaria inconsistente** en el rango de fechas (mezcla `Z` y hora local) | `getKardexByDateRange` | `TIMESTAMPTZ` + UTC en todo el rango |

### 2.2-ter Hallazgos de seguridad (todos a eliminar)

| # | Hallazgo | Evidencia |
|---|---|---|
| S1 | **3 rutas sin autenticación**: `GET /kardo` (importa facturas), `PATCH /prints` (marca impreso), `DELETE /invoices/detail/:id` (borrado físico) | `kardo.routes.ts`, `print.routes.ts`, `invoice.routes.ts` |
| S2 | **`role === 1` hardcodeado** como bypass de permisos en 4 puntos; el rol admin **no tiene permisos en BD** | `role-validator`, `admin-role-validator`, `invoice.controller` |
| S3 | **JWT no revocable** (24 h), sin logout, sin refresh, y `disabled` no se comprueba en el middleware | `security.ts`, `token-validator.middleware.ts` |
| S4 | **SQL injection por concatenación** | `kardo.service.ts:25` (`WHERE DataTransferId = ${id}`) |
| S5 | **Credenciales hardcodeadas**: usuario/admin en el seed (`admin`/`admin12345`), login AXZY externo, password SA de SQL Server en `docker-compose.yml` | `prisma/seeds/security.ts`, `axzy-validator.middleware.ts`, `docker-compose.yml` |
| S6 | **`console.log` de datos sensibles** (config completa de SQL Server, bodies, resultados) | `sqlServer.ts:6-7` y múltiples |
| S7 | **Errores de autorización devueltos como 401** en vez de 403 | `role-validator`, `admin-role-validator` |
| S8 | **Sin rate limiting ni bloqueo** en el login; bcrypt cost 10 | `user.controller.login` |

### 2.2-quater Catálogo de malas prácticas del código BASE → corrección → test que las bloquea

Instrucción explícita del proyecto: *"si ves que algo está mal hay que corregirlo, como no sea una
mala práctica que tengamos en el código de BASE"*. Este es el inventario completo. Cada fila tiene
**tres columnas obligatorias**: la mala práctica, cómo se corrige, y **qué test impide que vuelva**.
Sin la tercera columna, la corrección se pierde en el primer sprint con prisa.

| # | Mala práctica en BASE | Corrección en el sistema nuevo | Test que lo bloquea |
|---|---|---|---|
| 1 | **Cero tests** en los tres repos (no hay runner ni carpeta) | Suite por capas (§3.6) | Cobertura mínima por capa en CI |
| 2 | **Matriz de stock implícita** en un `if/else` con default "cualquier otro tipo resta" | `StockMovement` declara su efecto; `when` exhaustivo | Un test por `moveType`; el `when` no compila si falta uno |
| 3 | **Estados como strings libres** (`'pending'`, `'done'`…) duplicados en cada servicio | Enums + transiciones que devuelven `Either` | Test de transiciones válidas **y de las inválidas** |
| 4 | **`difference` nunca calculada** (siempre `0`) | Columna generada + regla de discrepancia | Test de discrepancia en los dos sentidos |
| 5 | **Sin transacciones** entre `Store.stock` y `Kardex` | Una transacción por movimiento | Test de fallo a mitad: no deja movimiento huérfano |
| 6 | **Read-modify-write** del stock en JS (race conditions) | `UPDATE … SET qty = qty + ?` atómico | **Test de concurrencia** con N hilos |
| 7 | **`PENDING_TO_OUT` no afecta stock** por accidente; `LOCATION` **resta** | Efectos declarados + tránsito explícito (§2.6) | Invariante de conservación I1 |
| 8 | **Errores de negocio con HTTP 200** (`{error:true}`) | Códigos correctos (409/422) | Test que exige el código, no 200 |
| 9 | **`res.json(error)` con `Error` → cuerpo `{}`** | `ApiError` tipado | Test de forma de error |
| 10 | **3 rutas sin autenticación** (`GET /kardo`, `PATCH /prints`, `DELETE /invoices/detail/:id`) | Auth en todas | **Test que recorre la tabla de rutas** |
| 11 | **`role === 1` hardcodeado**; admin sin permisos sembrados | Permisos explícitos; admin con `*` | Test de que permisos y rutas están en biyección |
| 12 | **Soft-delete que en realidad era físico** (`Invoice.deleted`, `Location.deleted` nunca escritos) | Un solo modo de borrado, coherente | Test de que el borrado lógico no borra filas |
| 13 | **Mass assignment**: `update({...req.body})` | DTO cerrado con allowlist | Test que envía campo extra y exige que se ignore |
| 14 | **N+1 sistemático** (un query por fila) | Joins y proyecciones | **Test de presupuesto de queries** por endpoint |
| 15 | **Sin paginación**; listados cargando la tabla entera en memoria | Contrato paginado obligatorio | Test de `limit` máximo y de `total` |
| 16 | **`kardex.user` como nombre libre** (string, no FK) | FK `user_id` | Test de integridad referencial |
| 17 | **Usuario enviado por nombre** en el móvil | `user_id` siempre | Test de contrato |
| 18 | **Validación desactivada** (`validateRequests: false` en OpenAPI) | RequestValidation real | Test que envía payload inválido y exige 400 |
| 19 | **SQL por concatenación** (`kardo.service.ts:25`) | Queries parametrizadas | Test de inyección |
| 20 | **Secretos en el repo** (password SA, credenciales AXZY, admin/admin12345) | Config/secretos externos | **Escáner de secretos en CI** |
| 21 | **`console.log` de datos sensibles** (config SQL Server completa) | Logger estructurado con redacción | Test de que no se loguea la config |
| 22 | **`getProducts` no filtra `deleted`** → productos borrados seleccionables | Filtro `active` + FK | Test de que un producto inactivo no es seleccionable |
| 23 | **Zona horaria mezclada** en rangos de fecha (`Z` vs hora local) | `TIMESTAMPTZ` + UTC en todo el rango | Test de rango en el borde de medianoche |
| 24 | **Migraciones inconsistentes** (36 legacy + 1 que recrea todo el esquema) | Baseline Flyway desde la BD real | Test que aplica todas las migraciones desde cero |
| 25 | **Sin rate limiting** en el login | Rate limit + bloqueo por intentos | Test de N intentos fallidos |
| 26 | **Código muerto masivo**: `MoveProductAdmin` (617 LOC), `types/catalog.ts` (1.817 LOC), `DataTable`, `jwt.js`, `wait.js`, `ConfigModal` (con el texto "Caca"), `ConfirmOrder`, `addKardexOutConfirm`, `getInvoiceByNumber` | No se porta nada muerto; se borra | Umbral de código no alcanzable + revisión de PR |
| 27 | **Dos librerías de UI** en el mismo front (Chakra **y** MUI) | Un solo sistema: `axzy_ui_system` + Tailwind | Lint que prohíbe imports de MUI/Chakra |
| 28 | **Duplicación de lógica**: abecedario de ubicaciones en 3 archivos, lógica de "cajas" en 2, export XLSX en 4, `JSON.parse(localStorage)` en 5+ | Una sola implementación por regla, en `stock-domain` o `shared/lib` | Test de la implementación única + revisión |
| 29 | **`axios` con header calculado una sola vez** y `catch` que no propaga el error (resuelve `undefined`) | Interceptor de request + normalizador que lanza `ApiError` | Test de que un error de red se propaga como excepción |
| 30 | **`Table` tipa `UseSortBy` pero no invoca `useSortBy`** (ordenamiento inexistente) | Tabla con ordenamiento server-side real | Test E2E que ordena y verifica el orden |
| 31 | **`mockData` como nombre de prop** para datos reales | Nombres que describen la realidad | Revisión de PR |
| 32 | **Permiso equivocado** en el front (`Locations.tsx` usa `invoice:create`) | Permisos tipados desde catálogo | Test que verifica que cada botón exige su permiso |
| 33 | **Menú hardcodeado y duplicado** en `Layout.tsx` y `Home.tsx` | Fuente única `APP_SCREENS` | Test de que el menú se deriva de permisos |
| 34 | **`PATCH` para borrar** (`/x/delete/:id`) mezclado con `DELETE /x/:id` | Un solo verbo por intención | Test de contrato de rutas |
| 35 | **Nombres engañosos**: `Store` (no es una tienda), `Labels` en plural, `kardex` vs `kardo`, `Location.location`, typos `proccess*`, `getPenginInvoices` | Nombres del dominio en `stock-domain` | Revisión + lint de nombres |
| 36 | **`catch` que traga errores** devolviendo `undefined` (`sqlServer.ts`) | Propagación explícita con error tipado | Test de fallo del servicio externo |

> **El desarrollo completo de estas 36 (más las contradicciones internas, las reglas de negocio a
> revisar y la metodología de propuesta/rollback) está en
> [PLAN_REFACTOR_MEJORAS.md](PLAN_REFACTOR_MEJORAS.md)**, con identificadores `R-nn`, causa raíz,
> mejor práctica aplicada, riesgo y test. Este catálogo es el resumen ejecutivo.

**Cómo se hace cumplir esto y no se queda en una tabla:** las filas 10, 11, 14, 18, 20, 25 y 26 no
dependen de disciplina humana, sino de **puertas automáticas en CI** (§3.6). Las demás están
cubiertas por la suite de pruebas. La tabla existe para que en la revisión de cada PR se pueda
preguntar "¿esto reintroduce alguna de las 36?".

### 2.3 Decisiones de negocio cerradas (afectan al esquema)

Enums nativos del modelo nuevo:

```sql
invoice_type      : NATIONAL | INTERNATIONAL
invoice_status    : PENDING | IN_PROGRESS | PENDING_TO_LOCATE | COMPLETED | CANCELLED
invoice_line_status: PENDING | CAPTURED | DISCREPANCY | PENDING_APPROVAL | APPROVED | LOCATED
                   | CANCELLED
order_status      : PENDING | IN_PROGRESS | PACKAGED | COMPLETED | CANCELLED
order_line_status : PENDING | ASSIGNED | SUPPLIED | CANCELLED
kardex_move_type  : INITIAL | IN | OUT | ADJUSTMENT_IN | ADJUSTMENT_OUT | MOVE_OUT | MOVE_IN
                  | LOCATED | PENDING_TO_LOCATE | PENDING_TO_OUT | RETURN
                  | TRANSFER_OUT | TRANSFER_IN          -- entre bodegas (dos fases)
adjustment_reason : SHORTAGE | DAMAGE | THEFT | SUPPLIER_ERROR | COUNT_ERROR
                  | RETURN_TO_SUPPLIER | EXPIRED | OTHER
adjustment_origin : INVOICE_DISCREPANCY | FULFILLMENT_SHORTAGE | MANUAL_COUNT | DAMAGE_REPORT
transfer_status   : DRAFT | DISPATCHED | IN_TRANSIT | RECEIVED | CANCELLED
location_kind     : SHELF | RECEIVING            -- RECEIVING = la antigua 'ADMIN' (B7)
```

**B1 — Discrepancia: bloquea hasta aprobación de supervisor.**
Cuando `physical_quantity != quantity`, la línea queda en `DISCREPANCY` y **no se puede ubicar**.
Un usuario con permiso `invoice:approve` resuelve: aprueba el ajuste, el sistema fija
`quantity = physical_quantity`, registra el movimiento de ajuste con motivo, y la línea pasa a
`APPROVED` → recién entonces se puede ubicar. La factura **no puede llegar a `COMPLETED`** con
líneas en `DISCREPANCY` ni `PENDING_APPROVAL`. Esto es una **pantalla nueva** que el legacy no
tenía: la bandeja de discrepancias pendientes de aprobación.

**B2 — Merma: movimiento de ajuste con motivo obligatorio, nunca borrado.**
Toda diferencia de inventario se registra como `ADJUSTMENT_IN`/`ADJUSTMENT_OUT` con
`adjustment_reason` **obligatorio** y usuario. El ledger es append-only: **corregir nunca es
borrar, es añadir un movimiento nuevo**. Esto hace el inventario auto-auditable.

**B3 — Merma y discrepancia son la MISMA regla, con distinto origen.**
No hay dos flujos de diferencia. Hay **una sola regla de ajuste** (`AdjustStock`) con un `origin`:
`INVOICE_DISCREPANCY`, `FULFILLMENT_SHORTAGE`, `MANUAL_COUNT`, `DAMAGE_REPORT`. La diferencia es
solo quién la dispara y qué permiso exige. Menos código y un único sitio donde equivocarse.

**B4 — Bodegas: esquema multi-bodega con bodega por defecto y traslados en dos fases.**
Ver §2.6.

### 2.4 Esquema objetivo (PostgreSQL 16)

```txt
-- Identidad y seguridad
users            (id, username UNIQUE, password_hash, name, disabled, role_id FK, created_at, updated_at)
roles            (id, name UNIQUE, ...)
permissions      (id, key UNIQUE, module, resource, action, name, description,
                  scopes permission_scope[], sensitive, active, sort_order)
                 -- 'invoice.approve'. DISEÑO COMPLETO EN PLAN_ROLES_PERMISOS_POLITICAS.md
                 -- (76 permisos, 4 alcances NONE|OWN|AREA|ALL, roles con clave estable,
                 --  políticas ABAC, excepciones por persona, API keys de servicio)
role_permissions (role_id, permission_id) PK compuesta
refresh_tokens   (id, user_id FK, token_hash, expires_at, revoked_at, user_agent, ip)
audit_log        (id, occurred_at TIMESTAMPTZ, business_date DATE,
                  record_hash, prev_hash,                  -- cadena de hashes (tamper-evident)
                  actor_user_id FK, actor_username, actor_name, actor_roles[],
                  source_channel, source_device, source_ip, source_user_agent,
                  source_service_key_id FK,
                  entity_type, entity_id, entity_label,
                  action, action_level, previous_state JSONB, new_state JSONB,
                  changed_fields[], reason, note,
                  permission_key, decision, decision_code, decision_stage,
                  access_trace JSONB,                      -- trace RBAC+ABAC del momento
                  result, error_code, error_message, duration_ms,
                  correlation_id, causation_id, idempotency_key, request_id,
                  offline_created_at, synced_at, clock_skew_ms)
                 -- PARTICIONADA POR MES, append-only (sin UPDATE ni DELETE),
                 -- con trigger de bloqueo y verificación periódica de la cadena.
                 -- DISEÑO COMPLETO EN PLAN_AUDITORIA.md

access_counter   (id, bucket_hour, actor_user_id, permission_key, entity_type, scope,
                  count, denied_count)                  -- lecturas agregadas (Nivel 3)
config_versions  (id, kind, version, snapshot JSONB, created_by, created_at, reason)
                 -- kind = 'permission_matrix' | 'policies'; permite REPRODUCIR
                 -- una decisión histórica tal como se tomó

-- Catálogo
products         (id BIGSERIAL PK, product_code UNIQUE NOT NULL, name, description,
                  unit_measure, barcode, active, created_at, updated_at)

-- Almacén físico (multi-bodega desde el día uno, con una sola por defecto)
warehouses       (id, code UNIQUE, name, address, active, is_default BOOLEAN,
                  created_at, updated_at)
                 -- se siembra UNA fila (is_default = true). Añadir más no requiere migración
locations        (id, warehouse_id FK, hall, code, position, levels INT CHECK (levels >= 1),
                  kind location_kind NOT NULL DEFAULT 'SHELF',  -- SHELF | RECEIVING
                  active, created_at, updated_at)
                 -- código compuesto canónico: hall + code + position (ver §2.5)
                 -- UNIQUE (warehouse_id, hall, code, position)
                 -- Se siembra UNA ubicación kind = 'RECEIVING' por bodega: es la antigua 'ADMIN'
                 -- ya como ubicación real (decisión B7). Ahí vive lo recibido y no ubicado.

-- Autenticación de servicio (decisión B8)
service_api_keys (id, name, key_hash, scopes TEXT[], active,
                  last_used_at, created_at, revoked_at)
                 -- para las integraciones intencionales: Zebra (print:create) y ERP (kardo:read)

-- Inventario (ledger + proyección)
stock_by_location(id, product_id FK, location_id FK, warehouse_id FK,   -- warehouse_id
                  quantity NUMERIC(14,3) NOT NULL DEFAULT 0,             -- desnormalizado a propósito
                  updated_at, UNIQUE (product_id, location_id),          -- para reportes por bodega
                  CHECK (quantity >= 0))                                 -- sin joins
product_stock    (product_id PK FK, quantity NUMERIC(14,3) NOT NULL DEFAULT 0, updated_at)
                 -- ex 'Store': total por producto, proyección de stock_by_location
stock_in_transit (id, product_id FK, from_warehouse_id FK, to_warehouse_id FK,
                  transfer_id FK, quantity NUMERIC(14,3) NOT NULL,
                  dispatched_at TIMESTAMPTZ, received_at TIMESTAMPTZ NULL,
                  CHECK (quantity > 0))
                 -- mercancía que salió de una bodega y aún no entró en la otra
transfers        (id, code UNIQUE, from_warehouse_id FK, to_warehouse_id FK,
                  status transfer_status, dispatched_by FK, dispatched_at,
                  received_by FK NULL, received_at NULL, note,
                  created_at, updated_at)
kardex           (id, product_id FK, warehouse_id FK, move_type kardex_move_type,
                  units NUMERIC(14,3), quantity_after NUMERIC(14,3), container, order_number,
                  location_id FK NULL, from_location_id FK NULL, to_location_id FK NULL,
                  transfer_id FK NULL, adjustment_reason adjustment_reason NULL,
                  adjustment_origin adjustment_origin NULL,   -- INVOICE_DISCREPANCY, MANUAL_COUNT…
                  approved_by FK NULL, approved_at NULL,      -- quién aprobó la merma
                  user_id FK, invoice_line_id FK NULL, order_line_id FK NULL,
                  client_uuid UUID NULL,                      -- idempotencia offline (móvil)
                  moved_at TIMESTAMPTZ, note, created_at)
                 -- APPEND-ONLY: sin UPDATE ni DELETE (permiso de BD revocado)
                 -- CHECK: si move_type es ADJUSTMENT_* entonces adjustment_reason IS NOT NULL
kardex_balances  (product_id, location_id, quantity) -- vista materializada de reconciliación
                 -- recalculada desde kardex; se compara contra stock_by_location

-- Recepción
invoices         (id, invoice_number UNIQUE, receipt_date DATE, invoice_type invoice_type,
                  status invoice_status, customer_code, concept, sent BOOLEAN,
                  reviewed_by, reviewed_at, deleted, created_at, updated_at)
invoice_lines    (id, invoice_id FK ON DELETE CASCADE, product_id FK, container,
                  unit_measure, quantity NUMERIC(14,3), box_quantity NUMERIC(14,3),
                  units_per_box NUMERIC(14,3), physical_quantity NUMERIC(14,3),
                  physical_box_quantity NUMERIC(14,3), located_quantity NUMERIC(14,3),
                  difference NUMERIC(14,3) GENERATED ALWAYS AS (physical_quantity - quantity) STORED,
                  status invoice_line_status, assigned_user_id FK NULL,
                  location_id FK NULL, deleted, created_at, updated_at)
                 -- Regla B1: status DISCREPANCY ⇒ no se puede ubicar ⇒ requiere
                 --   approved_by + approved_at + adjustment_reason antes de pasar a APPROVED.
                 --   CHECK: (status = 'APPROVED') ⇒ (approved_by IS NOT NULL)

-- Surtido
orders           (id, order_number UNIQUE, packaging_date DATE, status order_status,
                  customer_code, concept, converted BOOLEAN DEFAULT FALSE, sent BOOLEAN,
                  deleted, created_at, updated_at)
order_lines      (id, order_id FK ON DELETE CASCADE, product_id FK, unit_measure,
                  quantity NUMERIC(14,3), supplied BOOLEAN, supplied_quantity NUMERIC(14,3),
                  status order_line_status, assigned_user_id FK NULL,
                  location_id FK NULL, deleted, created_at, updated_at)

-- Etiquetas
label_templates  (id, code UNIQUE, name, description, zpl TEXT, size, is_active,
                  field_schema JSONB)
print_jobs       (id, label_template_id FK, payload JSONB, copies INT,
                  status PRINT_STATUS (PENDING|SENT|PRINTED|FAILED),
                  attempts INT, last_error, printed_at, created_by, created_at, updated_at)

-- Integración (si se conserva)
data_transfers   (id, source_id BIGINT, object_type, payload JSONB,
                  status, processed_at, error)
```

### 2.5 Reglas de negocio a codificar en BD

1. **`stock_by_location.quantity >= 0`** — la BD impide stock negativo; los ajustes que
   descuadren deben pasar por `ADJUSTMENT_*` explícito.
2. **Kardex append-only** — permisos de BD: el rol de la app sólo puede `INSERT`/`SELECT`
   sobre `kardex`. Las correcciones son movimientos nuevos, nunca `UPDATE`.
3. **`quantity_after`** se escribe en la misma transacción que actualiza las proyecciones,
   de modo que el ledger sea auto-auditable.
4. **Código de ubicación canónico** — normalizar `hall-code-position` (mayúsculas, sin espacios)
   en una función SQL y usar `UNIQUE`; el escáner lee ese formato.
5. **Transiciones de estado válidas** — `CHECK`/trigger para impedir saltos ilegales
   (p. ej. `PENDING` → `LOCATED` sin `CAPTURED`).
6. **`difference` generada** — nunca se escribe a mano (hoy sí se hace en `invoice.service`).
   Al migrar hay que **poblar la columna en el ETL** (el legacy siempre escribió `0`, así que el
   primer cálculo retroactivo es parte del entregable de Fase 7, no un efecto colateral).
7. **`adjustment_reason` obligatorio en los ajustes** — `CHECK (move_type NOT LIKE 'ADJUSTMENT%'
   OR adjustment_reason IS NOT NULL)`. La BD hace imposible una merma sin motivo.
8. **Toda línea `APPROVED` tiene aprobador** — `CHECK (status <> 'APPROVED' OR approved_by IS NOT
   NULL)`. Hace imposible cerrar una discrepancia sin que alguien la haya firmado (regla B1).

### 2.6 Diseño multi-bodega y traslados en dos fases (resuelve la decisión B4)

Me planteaste "puede tener varias bodegas o solo una, y si debemos considerar movimientos de una
bodega a otra". Lo importante aquí **no es cuántas bodegas hay, sino cómo se modela el traslado
entre ellas**, porque un traslado tiene un estado intermedio incómodo: la mercancía **no está en
ninguna de las dos bodegas** mientras viaja. Si se modela como dos movimientos instantáneos
(sacar de A, meter en B), el inventario total **baja durante el trayecto** y los reportes mienten.

**La recomendación: esquema preparado para N bodegas, con una sola por defecto, y el traslado como
flujo de dos fases que se activa cuando haga falta.**

Cómo queda:

1. **`warehouses` se siembra con UNA fila** (`is_default = true`). Toda `location` cuelga de una
   bodega. Operar con una sola bodega es el caso trivial: no cambia nada respecto al plan original
   y **no hay migración pendiente** cuando aparezca la segunda.
2. **`stock_by_location.warehouse_id` está desnormalizado a propósito.** Los reportes por bodega
   (que son la mayoría: "existencias de la bodega X") se resuelven **sin un solo join**.
3. **Traslado en dos fases**, cada fase con su movimiento en el ledger:

```txt
Fase 1 — DESPACHO (transfers.status: DISPATCHED → IN_TRANSIT)
   stock_by_location(origen)  -= N        -- sale del estante
   stock_in_transit           += N        -- entra "en camión"
   kardex: TRANSFER_OUT (from_location, transfer_id)

Fase 2 — RECEPCIÓN (transfers.status: IN_TRANSIT → RECEIVED)
   stock_in_transit           -= N        -- sale de "camión"
   stock_by_location(destino) += N        -- entra al estante
   kardex: TRANSFER_IN (to_location, transfer_id)
```

4. **El stock total nunca miente**, porque `product_stock` se calcula como
   `Σ stock_by_location + Σ stock_in_transit`. Hay un test de invariante que lo verifica en cada
   transición.
5. **La mercancía en tránsito es visible y auditable**: `stock_in_transit` tiene `transfer_id`,
   `dispatched_at`, quién despachó y quién recibió. Un traslado que nunca se recibe queda como
   deuda visible, no como pérdida silenciosa.

**Por qué esto no es sobreingeniería aunque hoy tengas una sola bodega:**

Tu sistema legacy **ya tenía esta idea a medias, sin saberlo**. La regla de `updateStoreStock` dice
que `PENDING_TO_OUT` **no afecta al stock** — es una intención de salida, no una salida. Eso es
exactamente "en tránsito". Formalizarlo no inventa un concepto nuevo: hace explícito y auditable
algo que ya existía como efecto colateral de un `if/else`.

**Movimiento dentro de la MISMA bodega** (lo que hoy hace `moveProductFromLocation`) **no usa este
flujo**: es atómico y de una sola transacción, porque la mercancía nunca sale del edificio. Tener
los dos caminos separados evita el error clásico de tratar un cambio de estante como un envío.

**Cuándo se implementa:**
- **Fase 0/1**: se crea el esquema (`warehouses`, `stock_in_transit`, `transfers`) y se siembra la
  bodega por defecto. Coste casi nulo.
- **Fase 2**: traslados **dentro** de la misma bodega (el flujo que ya usas hoy).
- **Fase 3 o posterior**: traslados **entre** bodegas, detrás de un flag de configuración. Como el
  esquema y el dominio ya lo contemplan, es conectar la UI y las reglas de permiso, no rediseñar.

**Decisión que sigue en tu tejado:** si sabes que **nunca** habrá más de una bodega, puedo quitar
`warehouses` y dejar el traslado inter-bodega fuera del alcance del todo. Mi recomendación es
mantenerlo como está descrito, porque el coste de prepararlo ahora es de horas y el de retrofitarlo
con datos ya migrados es de semanas.

### 2.7 CORRECCIÓN: qué hace el legacy al ubicar (verificado, y me equivoqué dos veces)

**Esta sección llevaba dos versiones erróneas del mismo análisis.** Las corrijo aquí porque el
documento afirmaba un descuadre de inventario que **no existe**, y un plan que exagera un defecto
toma decisiones equivocadas —en este caso, feature flags y tests de migración para un problema que
no está donde yo decía.

**Los dos errores, y en qué consistían:**

| Versión | Afirmaba | Por qué era falso |
|---|---|---|
| v1 | Ubicar resta **dos veces** del total (descuadre 2×) | Asumí que `addKardexLocation` llamaba a `updateStoreStock`. **No lo llama** |
| v2 | Ubicar resta **una vez** (descuadre 1×) | Asumí que `removeUnitsFromKardex` llamaba a `updateStoreStock`. **Tampoco lo llama** |

**El error de raíz fue el mismo en las dos:** no verificar las llamadas reales a `updateStoreStock`.
El grep de sus **cinco** invocaciones en `kardex.service.ts` demuestra que solo una podría afectar
a ubicar (la línea 212, dentro de `updateKardex`, que se usa para **ajustar líneas de factura**, no
para ubicar).

**El comportamiento real, transcrito y verificado:**

```txt
inicio                                        store.stock = 1000
1. captura: addKardex(PENDING-TO-LOCATE)
   → kardex.create + updateStoreStock(...)     ← SÍ llama, y PENDING-TO-LOCATE SUMA
                                              store.stock = 1100
2. ubicar: addKardexLocation
   → removeUnitsFromKardex: kardex.update     ← MUTA la fila, NO toca store.stock
   → kardex.create(LOCATION)                  ← NO llama a updateStoreStock
                                              store.stock = 1100
```

**Conclusión: ubicar NO descuadra el total.** El inventario global del legacy queda correcto.

**Entonces, ¿cuáles son los defectos reales?** Son dos, y son distintos de lo que yo describí:

| # | Defecto real | Evidencia | Impacto |
|---|---|---|---|
| **D-1** | **El libro se muta**: `removeUnitsFromKardex` hace `kardex.update` sobre un registro histórico | `kardex.service.ts:338-366` | El kardex **no es un libro**: tras ubicar, el registro de recepción dice 0. No se puede reconstruir qué se recibió. Es la corrección del hallazgo R-02, y **es el defecto importante** |
| **D-2** | **El modelo no puede representar dónde está el inventario**: hay un solo número por producto | `Store.stock` | El kardex sabe la ubicación (`kardex.location`), pero el inventario no se puede consultar por ubicación ni por bodega. El legacy "cuadra" en total y no sabe dónde está nada |
| **D-3** | **Rama muerta e incorrecta**: `updateStoreStock` con tipo `LOCATION` **resta** (cae al `else`), y nadie la invoca con ese tipo | `kardex.service.ts:552-580` | Es un **defecto latente**: el código contiene la operación incorrecta sin usar. Un rewrite que "reutilice" la función introduce el descuadre |

**D-3 es el más peligroso para un rewrite**, precisamente porque está muerto: quien reescriba puede
conectar esa rama creyendo que está aprovechando código existente, y entonces sí aparecería el
descuadre que yo había descrito. El test de caracterización lo fija explícitamente.

**Por qué el modelo nuevo sigue siendo correcto, aunque el motivo sea otro:**

El diseño no cambia, pero **la justificación sí**. `stock_by_location` como fuente de verdad con
`product_stock` derivado resuelve:

1. **D-1** — el ledger es append-only: ubicar **crea un movimiento nuevo** con su origen y destino,
   no muta el registro de recepción. La historia se conserva.
2. **D-2** — el inventario **es** por ubicación. La pregunta "¿dónde está esta mercancía?" tiene
   respuesta directa, que es el caso de uso que el legacy no podía cubrir.
3. **D-3** — no existe ninguna rama que reste del total al ubicar, porque ubicar **declara** su
   efecto (`TransferWithinWarehouse`) y ningún efecto interno toca el total. La rama latente no se
   puede reproducir.

**Y el impacto real en la migración, corregido:** el `Store.stock` del Excel **no está por debajo del
stock real**. Las 10.914.130 unidades son un total **fiable**. Lo que falta no es el número: es el
**desglose por ubicación**, que el archivo no trae y que hay que descubrir con el conteo físico
(decisión R-73). Eso sigue siendo trabajo real, pero por la razón correcta.

**Lo que NO cambia:** el conteo físico sigue siendo necesario, la reconciliación también, y la
decisión B7 (ubicación de recepción real en vez de la pseudo-ubicación `ADMIN`) sigue siendo
correcta. Lo que cambia es que **la migración no tiene que "corregir" un descuadre heredado**: tiene
que **descubrir una distribución** que nunca se registró.

### 2.7-bis Consulta de verificación para PROD (cuando tengas la BD a mano)

```sql
-- Compara el stock que guarda el legacy contra el neto de su propio kardex.
-- Si no cuadran, el descuadre es real y acumulado.
SELECT s.productCode,
       s.stock AS store_stock,
       SUM(CASE WHEN k.moveType IN ('INITIAL','IN','ADJUSTMENT','PENDING-TO-LOCATE')
                THEN k.units ELSE -k.units END) AS kardex_neto,
       s.stock - SUM(CASE WHEN k.moveType IN ('INITIAL','IN','ADJUSTMENT','PENDING-TO-LOCATE')
                          THEN k.units ELSE -k.units END) AS diferencia
FROM Store s
JOIN Kardex k ON k.productCode = s.productCode AND k.deleted = 0
GROUP BY s.productCode, s.stock
ORDER BY ABS(diferencia) DESC
LIMIT 50;
```

Nota: esta consulta **no es exacta** porque el kardex fue mutado por `removeUnitsFromKardex` (las
`units` originales ya se perdieron). Sirve para dimensionar el problema; la reconciliación fina
requiere el historial de movimientos de ubicación, que la Fase 7 reconstruye.

### 2.8 Política de cambios: qué se mejora sin preguntar y qué no

Decisión confirmada contigo. Se aplica a todo el rewrite y evita dos errores opuestos: mejorar
tímidamente, o cambiar una regla de inventario sin avisar.

**Se mejora sin preguntar** (no puede mover una cantidad, un estado ni un histórico):

| Área | Ejemplos |
|---|---|
| Seguridad | Auth en todas las rutas, permisos explícitos, JWT revocable, sin secretos en el repo, sin SQL por concatenación, rate limiting |
| Errores y contrato | Códigos HTTP correctos, `ApiError` tipado, validación de entrada activa, forma de error estable |
| Rendimiento | Eliminar N+1, paginación, proyecciones, índices |
| Calidad | Tests (toda la §3.6), cobertura, guardias de CI, código muerto fuera, nombres correctos |
| Auditoría técnica | Logger estructurado, `user_id` como FK en vez de nombre, `TIMESTAMPTZ` |

**Se pregunta antes** (puede mover cantidades, estados o el histórico):

| Área | Ejemplos |
|---|---|
| Cantidades | Cómo se calcula un total, qué suma y qué resta, stock negativo |
| Estados | Transiciones de factura, línea, orden, kardex |
| Semántica del ledger | Qué significa cada `moveType`, ubicación temporal, merma y motivo |
| Reglas de negocio | Discrepancias, aprobaciones, conteo, empaquetado |
| Datos existentes | Migración, reconciliación, qué hacer con los descuadres |

**El criterio en una frase:** si el cambio puede alterar lo que el sistema afirma sobre *cuánto hay
de algo* o *en qué estado está*, se pregunta. Todo lo demás, adelante.

### 2.9 Implicación operativa del umbral cero de discrepancia

Decidiste **R-75 sin umbral** (toda discrepancia requiere aprobación) combinado con **R-74**
(segregación de funciones: no puede aprobarla quien la capturó). Las dos decisiones son coherentes
entre sí y son el control interno más fuerte posible, pero **juntas crean un cuello de botella que
hay que dimensionar antes de construir**, no después.

**El escenario concreto:** una factura de importación con 40 líneas donde el conteo físico difiere
en **una sola pieza** en 15 de ellas. Con las reglas actuales:

- Las 15 líneas quedan en `DISCREPANCY` y **ninguna se puede ubicar**.
- El operador que las capturó **no puede aprobarlas**.
- Se necesitan **15 aprobaciones** de otra persona, una por línea, antes de que la mercancía pueda
  subir a un estante.

Con un contenedor real eso puede significar **decenas de aprobaciones por recepción**, y si el
gerente no está disponible, **la mercancía se queda en el andén**. Para un almacén que recibe a
diario, ese es el riesgo operativo más alto de todo el diseño.

**Propuestas para que el control no frene la operación (necesito tu decisión):**

| # | Medida | Efecto |
|---|---|---|
| D-1 | **Aprobación por factura, no por línea**: el supervisor resuelve la factura completa con una acción, viendo el listado de diferencias | De 15 aprobaciones a 1. **Es la corrección más efectiva** y no debilita el control: sigue habiendo una persona distinta autorizando |
| D-2 | **Bandeja de aprobación con contador y alerta** cuando hay más de N pendientes o llevan más de X horas | Evita que las aprobaciones se acumulen sin que nadie lo note |
| D-3 | **Notificación push al jefe de bodega** cuando se crea una discrepancia | Reduce el tiempo de espera del operador |
| D-4 | **Umbral solo para *auto*-aprobación**: el operador puede aprobar diferencias de ±1 pieza (error de conteo evidente) pero no más | Rompe la segregación de funciones. Requiere que lo aceptes explícitamente |
| D-5 | **Nada**: se acepta el cuello de botella porque el control lo vale | Válido, pero entonces hay que asegurar que **siempre haya un aprobador disponible** en horario de recepción |

**DECIDIDO (R-86): D-1 + D-2 + D-3.**

- **D-1 aprobación por factura** (confirmado): el supervisor resuelve la factura completa en una
  acción, viendo el listado de diferencias. El control se mantiene intacto — una persona distinta
  sigue autorizando — y el trabajo del supervisor pasa de 15 acciones a 1.
- **D-2 bandeja con contador y alerta** cuando hay más de N pendientes o llevan más de X horas.
- **D-3 notificación push** al jefe de bodega al crearse una discrepancia.

**Implicación de diseño de D-1:** la aprobación deja de ser una operación sobre `invoice_lines` y
pasa a serlo sobre `invoices`, con las líneas en `DISCREPANCY` como detalle. El permiso sigue siendo
`invoice.approve`, y la política PB3 (segregación de funciones) se evalúa **línea por línea**:
una factura no se puede aprobar si el que la capturó fue quien la intenta aprobar.

**Lo que sigue importando:** el operador nunca debe quedarse esperando sin saber por qué. De ahí
D-2 y D-3, que no son opcionales en la práctica.

**Lo que hay que evitar es descubrir esto en producción**, durante la primera recepción de un
contenedor real, con el personal esperando.


---

## 3. Arquitectura objetivo

### 3.1 Monorepo

```txt
STOCK/
├── stock-domain/       # ⭐ Kotlin Multiplatform PURO — el corazón del sistema (§3.2-bis)
│                       #    targets: jvm (backend + tests) · android · iosArm64 · iosSimulatorArm64
├── stock-network/      # KMP: Ktor Client, DTOs desde OpenAPI, auth + refresh, Idempotency-Key
├── stock-database/     # KMP: SQLDelight — caché offline + cola de sincronización
├── stock-datastore/    # KMP: multiplatform-settings — serverUrl, preferencias, idioma
├── backend/            # Ktor 3 · Kotlin 2.x · Exposed · Flyway · consume stock-domain
├── mobile/             # Compose Multiplatform: composeApp + androidApp + iosApp
├── web/                # React 19 + Vite + Tailwind v4 + axzy_ui_system (FSD)
├── shared-contracts/   # OpenAPI 3.1 + JSON Schemas (fuente única de verdad de la API)
├── infra/              # docker-compose (Postgres 16, backend, web, nginx), migraciones, scripts
├── docs/               # ADRs, guías de dominio, runbooks
└── BASE/               # repos legacy — SOLO LECTURA (referencia)
```

**Grafo de dependencias (unidireccional, sin ciclos):**

```txt
backend      ──→ stock-domain ──→ (solo stdlib Kotlin + kotlinx)
mobile       ──→ stock-domain
             ──→ stock-network ──→ stock-domain
             ──→ stock-database ─→ stock-domain
             ──→ stock-datastore
web          ──→ shared-contracts (vía OpenAPI generado, no código Kotlin)
```

`stock-domain` está en la **raíz y no dentro de `backend/` ni de `mobile/`** a propósito: es un
ciudadano de primera clase del proyecto, no un detalle de implementación de ninguno de los dos.
Esa ubicación es lo que hace visible que su API pública la consumen dos productos distintos.

`shared-contracts/openapi.yaml` es la **fuente de verdad** del contrato HTTP. El backend genera su
validación desde ahí; web y mobile generan sus clientes tipados desde ahí; y un test de contrato
verifica que los DTOs de `stock-network` siguen coincidiendo con el spec. Así los tres clientes no
pueden divergir del servidor.

**Nota sobre Gradle:** `stock-domain`, `stock-network`, `stock-database` y `stock-datastore` usan
**Kotlin Multiplatform**, y `backend` los consume por su target `jvm`. Eso permite correr los tests
del dominio y de la red en JVM puro, sin emulador y sin base de datos. Es un *composite build*:
`backend` y `mobile` incluyen los módulos por `includeBuild`/`include`, no por publicación en un
repositorio de artefactos.

### 3.2 Backend — Ktor modular

```txt
backend/src/main/kotlin/dev/axzy/stock/
├── Application.kt                 # módulo raíz, plugins, wiring
├── config/                        # HOCON + env, DatabaseFactory (HikariCP)
├── plugins/                       # ContentNegotiation, StatusPages, Auth(JWT),
│                                  # CallLogging, CORS, OpenAPI, RateLimit, RequestValidation
├── common/                        # errores tipados, Result, paginación, CoroutineExt
├── security/                      # JwtService, RefreshTokenService, PasswordHasher(argon2id)
│                                  # PermissionRoute (hasPermission("invoice:read"))
├── identity/                      # users, roles, permissions, auth
│                                  # motor de acceso RBAC+ABAC → ver PLAN_ROLES_PERMISOS_POLITICAS.md
├── catalog/                       # products
├── warehouse/                     # locations, stock_by_location, moves
├── receiving/                     # invoices + lines + captura + asignación a ubicación
├── fulfillment/                   # orders + lines + empaque + surtido
├── inventory/                     # kardex (ledger), consultas, reportes, reconciliación
├── labeling/                      # label_templates, print_jobs, render ZPL, dispatcher Zebra
│   ├── zpl/                       # plantillas y builder
│   └── jobs/                      # worker que reemplaza el cron
└── integration/                   # data_transfers (ex kardo) — opcional
```

**Vertical slice por módulo:** cada módulo es autocontenido y expone una única interfaz pública
hacia fuera. **Los módulos no se importan entre sí por dentro** (p. ej. `fulfillment` no importa
las clases internas de `warehouse`): si necesita algo, lo recibe como dependencia inyectada en su
constructor, o pasa por el `domain` compartido. Eso es lo que hace que se puedan testear por
separado y que un cambio en `receiving` no rompa `fulfillment`.

Cada módulo tiene `application/` (casos de uso), `infrastructure/` (Exposed DAOs + tablas) y
`api/` (rutas Ktor + DTOs). **Ya no hay `domain/` dentro de cada módulo: se movió al módulo
compartido `stock-domain` (§3.2-bis).**

Decisiones clave:

| Tema | Decisión | Motivo |
|---|---|---|
| Framework | **Ktor 3** | Kotlin-nativo, permite compartir el módulo `domain` con KMP |
| ORM | **Exposed** (DSL + DAO puntual) | Control explícito de transacciones y del ledger |
| Migraciones | **Flyway** | SQL versionado y revisable; los datos migran desde MySQL |
| Pool | HikariCP | Estándar |
| Auth | JWT access (15 min) + refresh opaco en BD (30 días) | El cliente móvil necesita sesiones largas y revocables |
| Password | **argon2id** | bcryptjs actual es débil para estándar 2026 |
| Permisos | Reemplazar el atajo `role === 1` por permiso explícito `*` en el rol admin | El hardcode actual es un agujero |
| Validación | `RequestValidation` + esquemas generados desde OpenAPI | Un solo contrato |
| Errores | `StatusPages` + `ApiError(code, message, details)` | Formato uniforme consumible por los 3 clientes |
| Observabilidad | CallLogging + Micrometer + health/ready | Operación real |
| Tests | Testcontainers (PostgreSQL) + Ktor `testApplication` | Probar los flujos A–E de §1.4 |
| Cron | Worker `kotlinx.coroutines` con backoff (no `node-cron`) | Reintentos y métricas |
| Sync externo | `integration` module detrás de flag | Decidir si `kardo` sigue vivo |

### 3.2-bis El módulo de dominio compartido — la decisión estructural del proyecto

Este es el punto donde se decide si el rewrite sale bien o mal. **El módulo `stock-domain` es
código Kotlin puro sin ninguna dependencia de framework**, y es el corazón del sistema:

```txt
stock-domain/                          # Kotlin Multiplatform, SIN Ktor, SIN Exposed, SIN SQLDelight
├── build.gradle.kts                   # targets: jvm (backend+tests) · android · iosArm64 · iosSimulatorArm64
└── src/
    ├── commonMain/kotlin/dev/axzy/stock/domain/
    │   ├── model/                     # Product, Invoice, InvoiceLine, Order, OrderLine,
    │   │                              # Location, StockLevel, KardexEntry, LabelTemplate…
    │   ├── value/                     # ProductCode, LocationCode, Quantity, Container,
    │   │                              # InvoiceNumber — con validación en el constructor
    │   ├── status/                    # enums + máquinas de transición (§3.2-ter)
    │   ├── inventory/                 # StockMovement sealed + matriz movimiento→efecto
    │   ├── rule/                      # reglas de captura, ubicación, empaque, merma
    │   ├── case/                      # casos de uso puros: CaptureLine, LocateLine,
    │   │                              # MoveStock, ConfirmOrder, PlanLabelCopies…
    │   └── error/                     # DomainError sealed (sin excepciones de framework)
    ├── commonTest/kotlin/…            # tests puros: rápidos, sin BD, sin emulador
    ├── jvmTest/kotlin/…               # property-based (kotest) sobre el ledger
    └── androidMain · iosMain          # casi vacíos: solo si hace falta algo de plataforma
```

**La regla de oro, verificable en CI:**

```
stock-domain  →  stdlib de Kotlin + kotlinx-datetime + kotlinx-serialization
                ✗ Ktor   ✗ Exposed   ✗ SQLDelight   ✗ Compose   ✗ androidx
```

Se verifica con un test de arquitectura (Konsist o un check de Gradle que inspeccione el classpath
de `commonMain`), no con buena voluntad.

**Quién consume `stock-domain` y cómo:**

| Consumidor | Qué usa del dominio | Cómo lo conecta |
|---|---|---|
| Backend Ktor | casos de uso + invariantes + enums | `application/` orquesta el caso de uso y `infrastructure/` persiste con Exposed |
| Móvil KMP | los mismos casos de uso + invariantes | `network/` trae los DTOs, `database/` cachea con SQLDelight, la UI observa |
| Web React | sólo los enums y su semántica, vía OpenAPI | se generan oneOf/enums desde `shared-contracts/openapi.yaml` |

**Por qué esto es lo que más valor aporta en tu caso concreto:** los cuatro defectos más graves del
legacy (la matriz de movimientos, el algoritmo de contenido por ubicación, las transiciones de
estado y la `difference` inexistente) **no son problemas de framework: son lógica de negocio**.
Puestos aquí, se prueban en JVM puro en milisegundos y **el backend y el móvil no pueden
divergir** porque ejecutan literalmente el mismo código. Sin este módulo, la opción "duplicar la
validación en el móvil para dar feedback offline" garantiza que las dos versiones se separen.

**Contrapartida honesta:** tiene un coste. Hay que modularizar el repo desde el día uno, definir
bien las fronteras antes de escribir features, y cada cambio en los modelos obliga a compilar los
targets de Android e iOS (aunque `commonMain` compila rápido). Para un proyecto de este tamaño se
paga solo, pero no es gratis.

### 3.2-ter Los cuatro artefactos del dominio que hay que escribir con más cuidado

Estos cuatro son donde el legacy falla y donde se concentra el riesgo. Los cuatro van en
`stock-domain`, con tests puros, **antes** de escribir el backend.

**(1) La matriz movimiento → efecto en stock.** Hoy está enterrada en un `if/else` de
`updateStoreStock`, con una regla por defecto ("cualquier otro tipo resta") que es una trampa.
Se modela explícito:

```kotlin
sealed interface StockMovement {
    val units: Quantity
    val effect: StockEffect          // cada movimiento DECLARA su efecto, no se deduce
}

// Efectos posibles
sealed interface StockEffect {
    data object IncreaseTotal : StockEffect                       // INITIAL, IN, ADJUSTMENT_IN
    data object NoEffect : StockEffect                            // PENDING_TO_OUT (intención, no salida)
    data class IncreaseLocation(val location: LocationCode) : StockEffect
    data class DecreaseLocation(val location: LocationCode) : StockEffect
    data class Transfer(val from: LocationCode, val to: LocationCode) : StockEffect
}
```

Y un test que recorra **todos** los `moveType` del legacy y afirme el efecto esperado. Cuando
alguien añada un movimiento nuevo, el `when` exhaustivo **no compila** hasta que declare su efecto.

**(2) La máquina de estados.** Hoy son strings libres (`'pending'`, `'captured'`,
`'pending-to-locate'`, `'done'`…) duplicados en cada servicio, y el legacy llegó a filtrar por un
campo inexistente. Se modela como transiciones explícitas y **las transiciones inválidas dejan de
ser representables**:

```kotlin
// En vez de: invoiceLine.status = "done"
fun InvoiceLine.locate(units: Quantity): Either<DomainError, InvoiceLine> = when (status) {
    InvoiceLineStatus.CAPTURED, InvoiceLineStatus.DISCREPANCY ->
        if (locatedQuantity + units > quantity) Left(DomainError.ExceedsInvoiceQuantity)
        else Right(copy(locatedQuantity = locatedQuantity + units,
                       status = if (locatedQuantity + units == quantity) LOCATED else ASSIGNED))
    else -> Left(DomainError.InvalidTransition(status, "locate"))
}
```

**(3) El algoritmo de contenido por ubicación.** Portado **tal cual** desde `getLocationContent`
(rama `ADMIN` y rama normal con su búsqueda del último `ADJUSTMENT`), no simplificado de memoria.
Se inmoviliza con tests contra datos reales **antes** de considerar cualquier rediseño: hay
histórico que depende de esa semántica.

**(4) `difference`.** El legacy nunca la calculó. Aquí es una propiedad derivada del modelo
(`physicalQuantity - quantity`) y su valor **dispara la regla de discrepancia** que decidamos en
§7 (pendiente de tu confirmación): hoy el hueco es funcional, no cosmético.

### 3.3 Web — React 19 replicando el FSD de `RESPONSIVA/web`

Se clona **exactamente** la arquitectura del repo de referencia. Esto es lo verificado en su
código (no supuestos):

**Capas y estructura**

```txt
web/src/
├── app/          # bootstrap, store, App.tsx (router), guards, toast, index.css
├── entities/     # <entidad>/{api,model,ui} + barrel index.ts SIEMPRE
├── features/     # <dominio>/<caso>/{model,ui}  ← el SLICE es el elemento FSD
├── widgets/      # <widget>/{model,ui} + barrel
├── pages/        # <dominio>/<Nombre>Page.tsx
└── shared/       # api, i18n, lib, pdf, ui, utils, validation
```

- **Alias TS/Vite:** `@app/*`, `@shared/*`, `@entities/*`, `@features/*`, `@widgets/*`, `@pages/*`
  (declarados en `tsconfig.app.json` y duplicados en `vite.config.ts`, que además fuerza el dedupe
  de `react`/`react-dom` a `./node_modules`).
- **Fronteras FSD** con `eslint-plugin-boundaries`, patrón `"features/*/*"` para que el elemento sea
  el **slice** (así el lint funciona con o sin barrel). Las 6 policies se copian tal cual.
  Regla de dependencia verificada empíricamente en el repo de referencia:

```txt
app      →  app, shared, entities, features, widgets, pages
pages    →  app*, widgets, features, entities, shared
widgets  →  app*, features, entities, shared
features →  app*, entities, shared
entities →  entities, shared
shared   →  shared   (hoja)
          (*) `app` sólo para TIPOS: RootState/AppDispatch/AppState
```

**Los 3 patrones arquitectónicos clave a clonar** (resuelven los tres choques con el lint):

1. **Inyección de hooks de sesión.** `shared` no puede importar `entities` (que tiene el token en
   Redux). Solución: `shared/api/session.ts` expone `setSessionHooks(...)` y `app/store.ts` lo
   cablea una sola vez. Incluye el **refresh single-flight** (`let refreshPromise` compartido) y el
   caso especial `ACCOUNT_DEACTIVATED` que **no** debe disparar logout.
2. **Interfaz estructural local.** `entities/user/model/usePermission.ts` declara su propia
   `AuthStateLike` en vez de importar `RootState` de `@app/store`, porque `entities` no puede
   depender de `app`. Documentado en el propio código.
3. **DI de PDFs/artefactos pesados.** Las features no pueden importar `@widgets/*`, así que la
   **página** importa los `download*Pdf` y se los inyecta al hook de la feature.

**Contratos operativos a respetar**

| Tema | Patrón verificado |
|---|---|
| Capa HTTP | `shared/api/client.ts` único punto: `ApiError` propia, axios con `timeout: 30000`, interceptor de request (`Bearer` + `Accept-Language`), interceptor de response con refresh single-flight + `handleUnauthorized()`, y normalizador central que lanza `ApiError`. Todas las entidades usan `api.*`; **ninguna importa axios directo** |
| Config runtime | `API_CONSTANTS.BASE_URL = window.__APP_CONFIG__?.API_URL \|\| import.meta.env.VITE_API_URL \|\| "/api/v1"`; `index.html` carga `/config.js` (generado por `render-config.sh` en el contenedor) |
| Tablas | `shared/api/table.ts`: `POST /entidad/query` con `{page, limit, filters, sort}` → `{data, total}`; `shared/api/clientTable.ts` para tablas client-side. `ITDataTable` se usa en **70 archivos** del ref; `ITSearchSelect` en 28 |
| Fechas en filtros | ISO **con zona local** (`2026-09-19T00:00:00.000-07:00`), no UTC: una fecha = día local completo; un rango = inicio del primer día → fin del último |
| Estado | **RTK v2 con `createAsyncThunk`, NO RTK Query** (0 usos de `createApi`). Sólo 4 slices globales (`auth`, `tickets`, `notifications`, `toast`); el resto es `useState` + `api.*` dentro de hooks de feature |
| Contrato feature↔página | La feature exporta **un hook** + **un componente raíz**; la página hace `const fx = useX(); <XTab fx={fx} />`. Se exporta `export type UseX = ReturnType<typeof useX>` |
| Ruteo | react-router-dom v7 con **`HashRouter`** (compatibilidad con el build de Electron), `<Route element={<PrivateRoutes/>}>` como layout route, `RequirePermission` por ruta, y `*` → `/login` |
| Permisos | **Gate de 3 niveles**: token (`PrivateRoutes`) → permiso por URL (`RequiresPermission`) → visibilidad del menú. El backend siempre revalida. `PermissionScope = "NONE"\|"OWN"\|"AREA"\|"ALL"`; catálogo y roles **dinámicos desde la BD**, no uniones hardcodeadas |
| Menú | Fuente única `entities/permission/model/screens.ts` → `APP_SCREENS` con `ScreenRequirement` (`anyOf`/`allOf`), `fixedRole`, `path`, `match: "exact"\|"prefix"`, `excludes`, `children`. Helpers `isScreenVisible`, `screenActions`, `screenLeaves`. **Nunca hardcodear el menú** (el legacy lo hace y duplica Layout/Home) |
| Estilos | `app/index.css` con **3 cosas obligatorias**: (1) `@layer theme, base, axzy-ui-system, components, utilities;` antes de los imports, (2) `@import "@axzydev/axzy_ui_system/dist/index.css" layer(axzy-ui-system);`, (3) **`@source "../node_modules/@axzydev/axzy_ui_system/src/**/*.{ts,tsx}";`** — sin el `@source`, Tailwind v4 no escanea `node_modules` y **las clases del kit no se generan** |
| Tema | Paleta en JS vía `ITThemeProvider` (`ITThemePalette`: primary/secondary/ternary/alert/warning/danger/info/success + `layout` + `table`). Montado en `main.tsx` con orden `Provider → ITThemeProvider → ToastProvider → Router → App` |
| i18n | 1 namespace por dominio, `es` + `en`, `common` por defecto, **claves tipadas** con `declare module "i18next"` → `CustomTypeOptions.resources`. Uso siempre `useTranslation(["<dominio>", "common"])`; namespace secundario con prefijo `common:`; helper `dyn(tt)` para claves armadas en runtime; `fileName(key)` para nombres de archivo exportados |
| Validación | **Funciones puras `(value) => string \| null`** en `shared/validation/`, con el mensaje traducido dentro. El repo de referencia tiene `yup` en `dependencies` y **no lo usa** (0 imports) — **no replicar esa deuda** |
| Formularios | Estado en `features/<dom>/<caso>/model/use<X>Form.ts`; `validate()` local que llena `errors: Record<string,string>`; la UI es presentacional pura por props; errores de API en `ITAlert`/`ITToast` |
| Idempotencia | `shared/lib/useRequestKey.ts`: clave base por montaje + huella FNV-1a del payload → header `Idempotency-Key`. **Se reutiliza para el offline del móvil (§3.4)** |
| Toasts | Patrón dominante: `ITToast` local con `useState` dentro de la feature (`{toast && <ITToast …/>}`). Existe un slice `toast` + `ToastProvider` con portal, pero está infrautilizado — elegir **uno** en el proyecto nuevo |
| E2E | Playwright con `workers: 1` en serie (los tests comparten BD real), `locale: "es-MX"`, `timezoneId: "America/Mexico_City"`, project `setup` de auth + `storageState`, y `webServer` array con API + app |
| Build | `vite.config.ts` del ref: `modulePreload: false`, `target: "esnext"`, **`minify: false`**, `cssCodeSplit: false`, **sin lazy loading** (`React.lazy`/`Suspense` = 0 usos) — decisión deliberada, no descuido |

**Equivalencia de patrones: FSD y MVVM no compiten (aclaración necesaria)**

Se suele plantear "FSD o MVVM" como si fueran alternativas, y no lo son: **operan en ejes
distintos**.

- **FSD es del eje de módulos**: define capas y quién puede importar a quién. Se hace cumplir con
  `eslint-plugin-boundaries`. Responde *"¿dónde vive este archivo?"*.
- **MVVM es del eje de presentación**: define cómo la UI habla con el estado. Responde
  *"¿cómo se comunica esta pantalla con su lógica?"*.

Por eso este proyecto usa **las dos a la vez**, igual que el repo de referencia:

| Concepto MVVM | Equivalente en este proyecto web |
|---|---|
| `View` | el componente `*Page` / el `ui/` de la feature — **presentacional puro, todo por props** |
| `ViewModel` | el hook `use<X>` de la feature (`features/<dom>/<caso>/model/`) + su slice/thunk de Redux |
| `Model` | `entities/<x>/api` + `entities/<x>/model` (tipos y acceso a datos) |
| binding | el contrato `const fx = useX(); <XTab fx={fx} />` — la página inyecta el objeto entero |
| `Observable`/`StateFlow` | `useSelector` de Redux para lo global; `useState` dentro del hook para lo local |

En React no hay data binding bidireccional como en WPF/Android, así que MVVM se materializa como
**"hook de feature + componente presentacional"**. Ese es precisamente el patrón verificado en
`RESPONSIVA/web` (`useTimeClockEmployees` + `TimeClockEmployeesTab fx={fx}`). **No hay que inventar
nada: se clona.**

Y para el **móvil**, la combinación es la misma idea en otro eje: **vertical slice** (módulos, por
caso de uso) + **MVVM/MVI** (presentación, dentro de cada slice).

**Resumen de tecnología web decidida (y desviaciones justificadas del ref):**

- React 19 + Vite 6/7 + TS 5.7 strict + `@vitejs/plugin-react-swc`.
- **Tailwind CSS v4** vía `@tailwindcss/vite`.
- `@axzydev/axzy_ui_system` **1.4.0** (el ref usa 1.3.6). Import del CSS + `@source` obligatorio.
- Redux Toolkit v2 con thunks, react-router-dom v7 (`HashRouter`), axios, react-icons, `file-saver`.
- i18n `i18next` + `react-i18next` con claves tipadas; es/en.
- **Sin** MUI, Chakra, `material-react-table`, `table-pagination-chakra-ui`, `framer-motion`
  (el legacy los declara/usaba y son lastre) y **sin `yup`** (el ref lo tiene sin usar).
- E2E Playwright; sin tests unitarios (igual que el ref) — a cambiar si se quiere calidad real.

**Mapeo de páginas (features del sistema actual → nuevas páginas FSD):**

| Página nueva | Ruta | Slice de UI del kit |
|---|---|---|
| `LoginPage` | `/login` | `ITPage` + `ITInput` + `ITButton` |
| `DashboardPage` | `/` | `ITGrid` + `ITStatCard` + `ITPage` |
| `InvoicesListPage` | `/invoices` | `ITDataTable` (server-side) + `ITTripleFilter` por estado |
| `InvoiceDetailPage` | `/invoices/:id` | `ITStepper` (captura → ubicación → cierre) + `ITDataTable` de líneas |
| `InvoiceCapturePage` | `/invoices/:id/capture` | `ITFormBuilder`/`ITField` + escáner de código |
| `OrdersListPage` | `/orders` | `ITDataTable` |
| `OrderDetailPage` | `/orders/:id` | `ITStepper` + `ITDataTable` |
| `OrderNewPage` | `/orders/new` | `ITField` + `ITSearchSelect` (producto, `onSearch` async) |
| `LocationsPage` | `/locations` | `ITDataTable` + `ITDialog` (alta/editar/borrar/mover) |
| `LocationContentPage` | `/locations/:code` | `ITDataTable` + `ITAccordion` |
| `InventoryPage` | `/inventory` | `ITSearchSelect` (producto) + `ITStatCard` + `ITDataTable` |
| `KardexPage` | `/kardex` | `ITDatePicker` (rango) + `ITDataTable` |
| `ReportsPage` | `/reports` | `ITDatePicker` + `ITDataTable` + export XLSX |
| `LabelsPage` | `/labels` | `ITField` + `ITDropfile` + preview ZPL |
| `PrintQueuePage` | `/print-queue` | `ITDataTable` + `ITBadget` de estado |
| `UsersPage` | `/users` | `ITDataTable` + `ITDialog` |
| `RolesPermissionsPage` | `/roles` | `ITDataTable` + `ITChipInput` de permisos |
| `NewLabelTemplatePage` | `/labels/templates/new` | `ITWysiwyg`/editor ZPL + validación |

Estructura de ejemplo de una entidad:

```txt
src/entities/invoice/
├── api/invoiceApi.ts          # get/post/patch tipados contra shared/api
├── model/types.ts             # Invoice, InvoiceLine, enums de estado
├── model/invoice.slice.ts     # (si hay estado cliente)
├── model/labels.ts            # estado → color/etiqueta
└── index.ts                   # barrel público
```

**Componentes del kit que ya tienen doc con ejemplos reales**
(`~/DEV/PTNV/RESPONSIVA/AXZY_UI_SYSTEM.md`, 44 componentes): `ITThemeProvider`, `ITLayout`,
`ITNavbar`, `ITSidebar`, `ITTopBar`, `ITButton`, `ITInput`, `ITSelect`, `ITSearchSelect`,
`ITDatePicker`, `ITTimePicker`, `ITCalendar`, `ITSlideToggle`, `ITDropfile`, `ITFormBuilder`,
`ITTable`, `ITDataTable`, `ITTabs`, `ITStepper`, `ITPagination`, `ITTripleFilter`, `ITDialog`,
`ITToast`, `ITLoader`, `ITBadget`, `ITImage`, `ITText`, `ITStack`, `ITFlex`, `ITGrid`, `ITSlider`,
`ITAvatar`, `ITStatCard`, `ITSegmentedControl`, `ITDivider`, `ITPageHeader`, `ITPage`, `ITChip`,
`ITField`, `ITAccordion`, `ITDropdownMenu`, `ITMultiSelect`, `ITChipInput`, `ITMaskedInput`,
`ITWysiwyg`.

> Nota: el kit es **sólo web** (React + Tailwind). No es reutilizable en KMP; en móvil se
> replica el lenguaje visual con un design system Compose propio (§3.4).

### 3.4 Mobile — Kotlin Multiplatform + Compose Multiplatform

```txt
mobile/
├── composeApp/                     # UI compartida Android + iOS
│   └── src/commonMain/kotlin/dev/axzy/stock/
│       ├── app/                    # App(), navegación, DI (Koin)
│       ├── core/                   # tema, tokens de diseño, i18n, formatters
│       ├── designsystem/           # equivalentes Compose de ITButton/ITDataTable/ITChip/ITBadge
│       ├── feature/                # VERTICAL SLICE por caso de uso
│       │   ├── auth/               # LoginScreen + LoginViewModel + LoginUiState
│       │   ├── config/             # serverUrl, test de conexión, perfil
│       │   ├── home/               # grid de categorías + contadores
│       │   ├── invoices/           # List · Capture · Locate  (MVI: máquina de estados)
│       │   ├── orders/             # List · New · Detail · Package (MVI)
│       │   ├── locations/          # List · Content · Search
│       │   ├── inventory/          # Move · Detail · Search (MVI: 2 pasos de escaneo)
│       │   └── labels/             # FreeLabel · MeshLabel
│       ├── scanner/                # expect/actual del escáner + ScanSession (MVI)
│       └── printer/                # expect/actual del cliente de impresión
├── androidApp/                     # entry point Android (CameraX + ML Kit)
└── iosApp/                         # entry point iOS (AVFoundation + Vision), consume composeApp
```

**Lo que cambió respecto al diseño inicial del plan:** el `shared/` con `core`, `domain`,
`network`, `database` y `datastore` **ya no vive aquí**. El `domain` subió al módulo raíz
`stock-domain` (§3.2-bis) para poder compartirlo con el backend, y el resto de módulos KMP
(`stock-network`, `stock-database`, `stock-datastore`) se mueven al nivel raíz del monorepo para
que el grafo de dependencias sea explícito y no queden escondidos dentro de `mobile/`.

**Dentro de `composeApp` se usa vertical slice, no capas:** todo lo de "capturar factura" está en
`feature/invoices/`, no repartido entre una carpeta `ui/` y otra `model/` globales. El motivo es
de navegación: así se ve de un golpe qué existe y se borra un flujo completo sin dejar huérfanos.

**Patrón de presentación dentro de cada feature (MVVM + MVI híbrido):**

```txt
feature/inventory/move/
├── MoveProductViewModel.kt      # androidx.lifecycle.ViewModel (ya es KMP)
├── MoveProductUiState.kt        # data class + sealed para las partes finitas
├── MoveProductIntent.kt         # sealed interface: escaneos, tecleo, confirmar
├── MoveProductScreen.kt         # composable puro: emite Intent, pinta State
└── MoveProductViewModelTest.kt  # test del reducer, sin UI
```

Regla de aplicación, para no caer en sobreingeniería:

| Tipo de pantalla | Patrón | Por qué |
|---|---|---|
| Con máquina de estados real (escáner de 2 pasos, captura de factura, surtido, movimiento) | **MVI**: Intents sellados + reducer puro + `StateFlow<UiState>` | Las transiciones inválidas dejan de ser representables — es exactamente el bug M3 del legacy |
| CRUD simple (Login, Config, listados, perfil) | **MVVM** simple: `ViewModel` + `StateFlow` + métodos | Ceremonia mínima donde no hay secuencia que romper |

Ejemplo de por qué MVI aquí y no MVVM a secas — el estado del escáner se vuelve imposible de
romper, a diferencia del `step` numérico suelto del legacy:

```kotlin
sealed interface ScanState {
    data object WaitingProduct : ScanState
    data class WaitingLocation(val product: Product, val units: Quantity) : ScanState
    data class ReadyToConfirm(val product: Product, val units: Quantity, val to: LocationCode) : ScanState
    data class Error(val reason: DomainError) : ScanState
}
```

Stack: Kotlin 2.x, Compose Multiplatform, **Ktor Client**, **kotlinx.serialization**,
**SQLDelight**, **Koin**, `androidx.lifecycle.ViewModel` (KMP), `kotlinx-datetime`,
**Napier** (logs), `multiplatform-settings`.
Escáner: `expect/actual` con **CameraX + ML Kit** (Android) y **AVFoundation + Vision** (iOS);
**la ventana de validación del código se define una sola vez en `commonMain`** y la comparten las
dos plataformas (el legacy tenía la máscara visual de 300×100 y la validación de 250×50
desalineadas — bug M4).
Navegación: `androidx.navigation` multiplataforma o Voyager.

**Equivalencia de las 26 pantallas actuales:**

| Actual (RN) | KMP |
|---|---|
| `LoginScreen` | `feature/auth/LoginScreen` |
| `HomeScreen` | `feature/home/HomeScreen` (grid de categorías + contadores) |
| `ConfigScreen` | `feature/config/ConfigScreen` (serverUrl, test de conexión) |
| `UserScreen` | `feature/config/ProfileScreen` |
| `InvoiceScreen/List/Detail/AssignStore` | `feature/invoices/{List,Detail,AssignLocation,Capture}` |
| `OrderScreen/Home/List/Create/DetailsCreate/Detail` | `feature/orders/{List,New,Detail,Package}` |
| `LocationsScreen/Options/SearchLocationContent` | `feature/locations/{List,Content,Search}` |
| `MoveProductLocation/MoveProductAdmin` | `feature/inventory/MoveProductScreen` (con rol como parámetro) |
| `InventoryScreen/Options/Search` | `feature/inventory/{Options,Detail,Search}` |
| `FreeLabelScreen/MeshScreen` | `feature/labels/{FreeLabel,MeshLabel}` |
| `Ticket*` | `feature/tickets/` (si se conserva) |

**Offline-first (mejora sobre el estado actual):** hoy el app falla sin red y sólo guarda el token.
El diseño nuevo:

1. Cola local (`SQLDelight`) de operaciones de captura/movimiento con `client_uuid`.
2. Toda escritura del piso es **idempotente** vía `Idempotency-Key` (el UUID del cliente).
3. Sincronización en background con reintentos y backoff exponencial.
4. Catálogo de productos y ubicaciones precargado para poder operar sin señal.
5. Resolución de conflictos: el servidor manda; el ledger registra el `client_uuid` para auditar.

Esto es crítico: el uso real es en piso de almacén con señal intermitente.

### 3.5 El catálogo real — perfil del Excel y decisiones de importación

Analicé el archivo `INVENTARIO REAL LUNES 10 DE JUN 2024 - Copy.xlsx` (hoja `Hoja1`). Estas son
las cifras reales, no estimaciones:

| Métrica | Valor |
|---|---|
| Filas del archivo | 1.400 → **1.398 útiles** (+2 filas vacías al final, a ignorar) |
| Productos únicos | **1.398** |
| `CIDPRODUCTO` | 1 → 1.398, **secuencial y sin huecos** |
| `CCODIGOPRODUCTO` | **único**, 6–14 caracteres, alfabeto `0-9` + `A-Z` (sin Ñ, O ni W) |
| `CUNIDADMEDIDA` | **`PZA` en el 100%** de las filas |
| `CEXISTENCIAS` | entero, no negativo, máximo **846.600**; **0 en el 23,9%** |
| Suma de existencias | **10.914.130** unidades |
| Nulos | **cero** |
| Decimales | **cero** |

**La calidad de datos es excelente**, lo que permite que el importador sea **estricto** en vez de
defensivo: puede rechazar una fila en lugar de intentar adivinar. Es una ventaja, porque un
importador tolerante esconde errores de captura que después reaparecen como stock mal contado.

**Hallazgos que afectan al modelo:**

| # | Hallazgo | Implicación |
|---|---|---|
| P1 | `CNOMBREPRODUCTO` y `CDESCRIPCIONPRODUCTO` son **idénticos en las 1.398 filas** | Es una columna duplicada. En el modelo nuevo `products.name` es la única fuente; se elimina la duplicación que el legacy arrastraba a cada línea de factura y orden |
| P2 | **201 productos** tienen código `X` y `XN`; en **180 casos comparten el mismo nombre** | **Requiere decisión de negocio** (ver §7) |
| P3 | `unit_measure` es texto libre con **un solo valor** (`PZA`) | Migrar a catálogo `units_of_measure` evita el clásico `KG`/`Kg`/`KGS` como tres unidades distintas |
| P4 | **No hay campo de código de barras** | El legacy usa `product_code` como código de escaneo. Se conserva así (es lo que está impreso en las etiquetas y estantes) pero el modelo deja `barcode` como campo aparte y opcional, para no atar el identificador al formato de etiqueta |
| P5 | **El archivo solo trae stock total, sin ubicaciones** | Es el punto crítico de la migración: el modelo nuevo guarda stock **por ubicación** y este archivo no dice en qué estantería está cada pieza (ver §3.5.1) |
| P6 | **Las 10.914.130 unidades del Excel vienen del `Store.stock` del legacy**, que arrastra el descuadre del bug de §2.7 | El número del Excel **está por debajo del real**. Al migrar hay que decidir si se carga tal cual (fiel al legacy) o se reconcilia primero. Se cuantifica en Fase 7 con la consulta de §2.7-bis |

**Modelo de `products` derivado de los datos reales:**

```txt
products (id BIGSERIAL PK,
          product_code  VARCHAR(20) NOT NULL UNIQUE,   -- 14 máx real, 20 por holgura
          name          VARCHAR(120) NOT NULL,         -- 52 máx real
          description   TEXT NULL,                     -- deja de duplicar name
          unit_measure_id FK units_of_measure NOT NULL,
          barcode       VARCHAR(64) NULL UNIQUE,       -- hoy = product_code
          external_id   BIGINT NULL,                   -- CIDPRODUCTO del ERP (ver D10 en §7)
          active        BOOLEAN NOT NULL DEFAULT TRUE,
          created_at, updated_at)

units_of_measure (id, code VARCHAR(8) UNIQUE NOT NULL, name, allows_fraction BOOLEAN)
                  -- seed: PZA (allows_fraction = false), KG, M2, CAJA, LT…
```

**Reglas del importador (estrictas por diseño):**

1. `product_code` es la **clave de identidad** para el upsert (no el `CIDPRODUCTO`).
2. `CIDPRODUCTO` se conserva como `external_id` **solo si decides mantener la referencia cruzada**
   (decisión D10 en §7).
3. Normalización determinista: `trim` + mayúsculas + colapsar espacios internos. Como el alfabeto
   real no tiene minúsculas ni espacios, cualquier fila que los traiga se **rechaza indicando el
   número de fila**, no se corrige en silencio.
4. `unit_measure` debe existir en el catálogo; si no, la fila se rechaza con el motivo.
5. El import devuelve **informe por fila**: creados, actualizados, rechazados con motivo.
6. **Modo dry-run obligatorio**: se puede simular el import completo y ver el informe sin escribir.

#### 3.5.1 Migración del stock inicial — el problema de las ubicaciones

El Excel trae **10.914.130 unidades como total por producto**, pero el modelo nuevo tiene el stock
**por ubicación**. No se puede inventar dónde está cada pieza: solo una persona con el almacén
delante puede decirlo. Hay tres caminos y conviene decidir antes de Fase 7:

| Opción | Cómo funciona | Ventaja | Riesgo |
|---|---|---|---|
| **A. Ubicación virtual `ADMIN`** (lo más cercano al legacy) | El stock entra como kardex `INITIAL` en una ubicación virtual y se reubica con el flujo normal de movimiento | Sistema operativo el día uno; trazable | El stock no está en un estante físico hasta que alguien lo mueve |
| **B. Importar a 0 y hacer conteo físico** | Los productos se cargan con stock 0 y el stock se descubre ubicación por ubicación | El sistema coincide con la realidad física desde el inicio | Requiere conteo físico completo antes de operar |
| **C. Repartir el total entre ubicaciones** | Se distribuye el stock por criterio histórico o estimado | El sistema arranca "cuadrado" en apariencia | **Inventa datos**: el descuadre aparece después y es más difícil de diagnosticar |

**Recomendación: A y B combinadas.** Cargar los 1.398 productos con su stock en la ubicación
virtual `ADMIN` (idéntico al legacy, para que Fase 7 sea una migración fiel) **y** planificar el
conteo físico por ubicación como actividad de arranque, con una pantalla de conteo que use la regla
única `AdjustStock` (B3) para mover de `ADMIN` a la ubicación real. Se rechaza explícitamente C.

La razón de fondo: **el sistema nunca debe afirmar que sabe algo que no sabe.** Un inventario que
arranca con datos inventados tarda meses en recuperar la confianza del personal de piso.

### 3.6 Estrategia de pruebas — "pruebas a todo el flujo de inventarios"

Requisito explícito del proyecto. Hoy **los tres repos legacy no tienen ni un test** (verificado:
no hay carpeta de tests ni runner en ningún `package.json`), así que la única especificación del
comportamiento es el código. Eso es lo que hace arriesgado el rewrite, y por eso las pruebas no son
una fase final: son parte de la definición de "terminado" de cada fase.

**Principio rector: probar el flujo completo, no las clases.** Cada regla se prueba en la capa más
barata donde pueda fallar, y el flujo de inventario se prueba **de punta a punta** además de por
capas. Un test que solo verifica que `AdjustStock` devuelve `Right` no dice nada sobre si el
inventario cuadra después de 10.000 operaciones.

#### Pirámide de pruebas

| Nivel | Qué cubre | Herramienta | Dónde corre | Velocidad |
|---|---|---|---|---|
| **1. Dominio puro** | Matriz movimiento→efecto, máquinas de estado, `difference`, reglas de captura/ubicación/empaque, `AdjustStock` con sus 4 orígenes | `kotlin.test` + **Kotest** (property-based) | `stock-domain/commonTest` + `jvmTest` | **ms**, sin BD ni emulador |
| **2. Invariantes** | Que el ledger nunca produzca stock negativo y que el total se conserve | Kotest property + generadores | `stock-domain/jvmTest` | segundos |
| **3. Repositorio** | Que el append-only, los `CHECK` y la transaccionalidad **se cumplan en la BD real** | Testcontainers PostgreSQL + Exposed | `backend` | segundos |
| **4. Concurrencia** | Que N escrituras simultáneas no pierdan unidades | corrutinas + Testcontainers | `backend` | segundos |
| **5. API / integración** | Contratos, códigos de estado, permisos, errores tipados, idempotencia | Ktor `testApplication` + Testcontainers | `backend` | segundos |
| **6. Contrato** | Que los DTOs de `stock-network` y `openapi.yaml` no divergen | validador OpenAPI | CI | ms |
| **7. UI móvil** | Reducers MVI y ViewModels: las transiciones del escáner | `kotlin.test` + Turbine | `composeApp/commonTest` | ms |
| **8. E2E web** | Los flujos A–E completos sobre API y BD reales | Playwright | CI | minutos |
| **9. E2E móvil** | Flujo A y C en Android e iOS, **incluido el modo offline** | Compose UI test + instrumentado | CI / local | minutos |
| **10. Paridad backend↔móvil** | Que el mismo caso de uso produce el mismo resultado por los dos caminos | test que ejecuta el caso compartido en JVM y compara | CI | ms |

**El nivel 10 merece explicación porque justifica el módulo compartido:** como el dominio es
literalmente el mismo código en backend y móvil, la *lógica* no puede divergir. Pero la
*integración* sí (el móvil puede cachear mal, serializar mal o replicar mal el estado). Ese test
compara el resultado del caso de uso compartido alimentado con los mismos datos por ambos caminos.

#### Invariantes que se prueban siempre (el corazón de la confianza)

Se prueban como **property-based tests**: se generan secuencias aleatorias de operaciones válidas y
se afirma que las invariantes se mantienen. Así se cubren combinaciones que a nadie se le ocurriría
escribir a mano.

| # | Invariante | Formulación |
|---|---|---|
| I1 | **Conservación total** | `Σ stock_by_location + Σ stock_in_transit == Σ entradas del ledger` tras **cualquier** secuencia |
| I2 | **No negatividad** | `stock_by_location.quantity >= 0` siempre, reforzado por `CHECK` de BD |
| I3 | **Append-only** | `kardex` no admite `UPDATE` ni `DELETE` con los permisos de la app |
| I4 | **Auto-auditoría** | `kardex.quantity_after` coincide con el recálculo desde el inicio del producto |
| I5 | **Conciliación** | `stock_by_location` coincide con `kardex_balances` recalculado |
| I6 | **Ajuste con motivo** | Todo `ADJUSTMENT_*` tiene `adjustment_reason` no nulo |
| I7 | **Aprobación** | Ninguna línea `APPROVED` sin `approved_by` |
| I8 | **Idempotencia** | Repetir el mismo `Idempotency-Key` no duplica el movimiento |
| I9 | **Atomicidad** | Un fallo a mitad de un movimiento no deja stock tocado ni movimiento huérfano |
| I10 | **Traslado** | Despachar resta del origen y suma a tránsito; recibir suma al destino y libera tránsito; nunca se crea ni destruye stock |

#### Casos obligatorios sobre el flujo de inventario

Se escriben **en Fase 0 contra el sistema legacy** como tests de caracterización (golden tests) y
luego se reapuntan a la API nueva. Es el entregable que convierte "el código es la especificación"
en "los tests son la especificación".

**Recepción (flujo A):**
- Alta de factura con líneas; número duplicado → **error de conflicto, no 200**.
- Captura con cantidad **igual** → `CAPTURED`; **mayor** → `DISCREPANCY`; **menor** → `DISCREPANCY`.
- `difference` calculada en ambos sentidos y **visible** en la respuesta.
- Línea en `DISCREPANCY` **no se puede ubicar** → `409`, verificado por API.
- Aprobación por supervisor: fija la cantidad, exige motivo, registra el ajuste, deja `APPROVED`.
- **Rechazo de aprobación** devuelve la línea a captura con nota.
- Factura **no puede llegar a `COMPLETED`** con líneas en `DISCREPANCY`/`PENDING_APPROVAL`.
- Ubicación parcial acumulativa: varias asignaciones hasta completar → `LOCATED`.
- Asignar a ubicación inexistente → error tipado, no un 500.
- Captura con lista de productos vacía → **error de validación**, no un `TypeError` (bug L7 del legacy).

**Surtido (flujo B):**
- Surtir más de lo que hay en la ubicación → rechazado.
- Surtir de una ubicación donde el producto no está → rechazado.
- Cierre de orden descuenta exactamente lo surtido.
- Faltante en surtido → `AdjustStock` con origen `FULFILLMENT_SHORTAGE` y motivo.
- Todas las líneas `VERIFIED` → `PENDING_TO_OUT`; tras confirmar → `OUT` reales.

**Ubicar en estantería (el bug B6 de §2.7 — regresión obligatoria):**
- Recibir 100 en la ubicación temporal `RECEPCIÓN` y ubicarlos en una estantería:
  **el total NO cambia** (entra 100 a la estantería, salen 100 de recepción).
- Tras ubicar, el kardex sigue teniendo el registro de recepción **con sus 100 units originales**
  (append-only: nada se muta).
- Recibir 100, ubicar 40 y ubicar 60 → mismo total que ubicar 100 de una vez.
- Ubicar más de lo que hay en la ubicación temporal → rechazado.
- Test de caracterización que **captura el comportamiento actual de PROD** (que sí descuenta) para
  dejar constancia documentada de la diferencia y evitar que la regresión vuelva.

**Movimientos (flujo C):**
- Mover dentro de la misma bodega: atómico, no genera tránsito.
- Mover más de lo disponible → rechazado (hoy el legacy **no lo valida**, bug L9).
- Mover a la misma ubicación de origen → rechazado.
- Traslado entre bodegas: estado intermedio en tránsito, total conservado (I10).
- Traslado nunca recibido → visible como deuda, no como pérdida silenciosa.

**Merma (reglas B1/B2/B3):**
- Ajuste sin motivo → **rechazado por la BD**, no solo por código.
- Ajuste que dejaría stock negativo → rechazado.
- Ajuste con los cuatro orígenes, cada uno con su permiso.
- Historial de ajustes devuelve motivo, usuario y aprobador.
- **Mismo usuario captura y aprueba** → comportamiento según la decisión D9 de §7
  (si se exige segregación de funciones, el test debe rechazarlo).

**Concurrencia (el riesgo real del legacy):**
- Dos operadores moviendo el mismo producto a la vez → **el total cuadra** (hoy el legacy pierde unidades).
- N capturas simultáneas sobre la misma línea de factura → sin sobreasignación.
- Dos despachos del mismo traslado → sin duplicar (idempotencia).

**Offline del móvil (flujos A y C sin red):**
- Captura sin señal → se encola; al recuperar señal sincroniza **una sola vez**.
- El mismo `clientUuid` enviado dos veces → un solo movimiento.
- Conflicto (el servidor cambió el saldo mientras el móvil estaba offline) → respuesta explícita,
  nunca pérdida silenciosa.

**Seguridad y contrato (transversal, cubre el catálogo de §2.2-quater):**
- **Test que recorre toda la tabla de rutas y falla si alguna no exige autenticación** — habría
  atrapado las 3 rutas abiertas del legacy.
- Test que verifica que **todo permiso sembrado se exige en alguna ruta y viceversa**.
- Un usuario sin permiso recibe **403, no 401**.
- Mass assignment: enviar un campo extra y verificar que se ignora.
- **Test de presupuesto de queries**: cada endpoint paginado tiene un número máximo de queries
  (atrapa el N+1 sistemático del legacy).
- Inyección: `'; DROP TABLE` no pasa.
- Forma de error: siempre `{ error: { code, message, details } }`, nunca `{}`.
- Rango de fechas en el borde de medianoche (zona horaria).

#### Cobertura y puertas de calidad en CI

| Puerta | Umbral |
|---|---|
| Cobertura de `stock-domain` | **≥ 95%** (lógica pura y crítica: no hay excusa) |
| Cobertura del backend | ≥ 85% en `application/`, ≥ 70% global |
| Cobertura de web y móvil | ≥ 60% (se prioriza E2E sobre unitarios en UI) |
| Guardia de arquitectura | `stock-domain` **sin** dependencias de framework — falla el build |
| Escáner de secretos | falla el build |
| Lint de fronteras FSD (web) | falla el build |
| Migraciones desde cero | `flyway migrate` sobre BD vacía + tests |
| Property-based | invariantes I1–I10 en verde |

**Regla de proceso:** ningún *issue* del flujo de inventario se cierra sin su test, y todo bug de
producción entra primero como **test que falla** y después como corrección. Es la única forma de
que la deuda de "cero tests" no se repita en el sistema nuevo.

### 3.7 Control de acceso: documento propio

El flujo de roles, permisos y políticas se rediseña **por completo** y tiene su propio documento:
**[PLAN_ROLES_PERMISOS_POLITICAS.md](PLAN_ROLES_PERMISOS_POLITICAS.md)**. Resumen de lo que contiene
y de por qué es un documento aparte:

- **RBAC + ABAC híbrido**, adaptando el modelo que **ya funciona en producción** en
  `~/DEV/PTNV/RESPONSIVA/web` (que tiene consola de roles, políticas, simulación y bitácora).
- **Pipeline de decisión en 3 etapas**: Identidad → RBAC → ABAC, con **default deny**. Esto
  reemplaza el bypass `if (role === 1) return next()` del legacy (hallazgo S2) y el rol admin sin
  permisos sembrados (hallazgo S3).
- **76 permisos en 8 módulos**, con nomenclatura `recurso.accion` y **alcance**
  (`NONE | OWN | AREA | ALL`) donde `AREA` es "su bodega" — lo que resuelve la multi-bodega **sin
  crear roles por bodega**.
- **5 roles base**: `ADMIN`, `GERENTE_OPERACIONES`, `JEFE_BODEGA`, `OPERADOR_ALMACEN`, `AUDITOR`.
- **Descomposición de "sacar"** en tres permisos distintos (`order.package`,
  `inventory.adjust_shortage`, `transfer.dispatch`), que es la corrección de fondo al ejemplo
  `ALMACEN:SACA`.
- **12 políticas ABAC concretas** para el almacén (umbral de discrepancia, segregación de funciones,
  horario laboral, productos de alto valor, canal de servicio del ERP).
- **El motor vive en `stock-domain`** (puro, compartido backend/móvil/web), así que la decisión es
  idéntica en los tres: **la UI es una pista, el servidor es la autoridad**.
- **7 pantallas de administración** (roles, matriz global, políticas, personas y acceso efectivo,
  probador, bitácora, catálogo).
- **50 casos de prueba** (A1–A50), incluido el que considero más importante: **el operador completa
  el flujo de recepción sin recibir un solo 403**. Un sistema de permisos que impide trabajar está
  tan roto como uno que permite todo.
- **Migración del `roleId` numérico a `roles.key` estable**, y `user_roles` N:M en lugar del
  `roleId` único del legacy.

**Por qué documento aparte:** el control de acceso tiene su propio esquema (9 tablas), su propio
contrato de API (18 endpoints), su propio motor y sus propias pantallas. Meterlo en el plan general
habría hecho ilegible a los dos.

### 3.8 Auditoría y trazabilidad: documento propio

Requisito de primer orden: **todo debe ser auditable** — quién, cuándo, dónde, de dónde, a qué hora,
y **con qué permiso**. Documento propio:
**[PLAN_AUDITORIA.md](PLAN_AUDITORIA.md)**.

Lo esencial, y por qué no es "una tabla de logs":

- **Se audita la autorización, no solo el cambio.** Cada registro guarda el **trace de la decisión**
  (identidad → RBAC → ABAC), con **qué política decidió** y **el resultado de cada condición**. Y
  guarda la **versión** de la matriz y de las políticas vigentes, para poder **reproducir** la
  decisión histórica. Sin eso, la pregunta *"¿esta aprobación era legítima?"* no tiene respuesta,
  porque los permisos cambian con el tiempo.
- **Se auditan también los intentos denegados.** Son la señal más valiosa: detectan abuso y detectan
  permisos mal configurados que bloquean trabajo legítimo.
- **Auditoría total con coste controlado**: 4 niveles de criticidad. Los eventos críticos con trace
  completo y en la misma transacción; las lecturas normales con **contador agregado**, no un registro
  por lectura. Registrar todo con el mismo detalle hace la auditoría inmanejable y nadie la lee.
- **Inmutable de verdad**: permisos de BD revocados, trigger de bloqueo y **cadena de hashes**
  (tamper-evident): alterar un registro del medio rompe la cadena y se detecta.
- **`source_channel`** distingue persona (web/móvil) de integración (ERP/Zebra) de proceso interno.
  Sin eso no se puede interpretar un movimiento sin persona detrás.
- **El móvil offline guarda las dos horas** (`offline_created_at` y `synced_at`) más el
  `clock_skew_ms`: capturar a las 22:00 y sincronizar a las 09:00 son dos hechos distintos.
- **Intercepción en dos niveles** para no depender de la disciplina: un plugin HTTP captura el
  contexto, y una **anotación `@Auditable` en los casos de uso del dominio** declara qué se audita y
  genera el diff. **Un test estructural falla el build si un caso de uso crítico no es auditable.**
- **6 pantallas**: bitácora general, historia de un recurso, un flujo completo por
  `correlation_id`, actividad de una persona, detalle con el trace desplegable, y verificación de
  integridad de la cadena.
- **28 casos de prueba** (U1–U28), incluidos los dos que marcan la diferencia: que el trace guarde el
  estado **del momento** y no el actual, y que se pueda **reproducir** una decisión histórica.

**El dato más relevante del legacy:** **no tiene ninguna tabla de auditoría.** Su único rastro es el
`Kardex`, y es **mutable** (`removeUnitsFromKardex` hace `kardex.update`). Es decir, el legacy **no
puede responder "quién hizo esto"** para casi nada. La ventaja de eso: no hay datos históricos que
preservar, así que la auditoría **nace bien** en lugar de parchearse.

---

## 4. Contrato de API v1 (diseño objetivo)

Público objetivo: paridad funcional con §1.1, más lo que falta para soportar los clientes nuevos.

```txt
# ── Auth
POST   /v1/auth/login              { username, password } -> { accessToken, refreshToken, user, permissions }
POST   /v1/auth/refresh            { refreshToken } -> { accessToken, refreshToken }
POST   /v1/auth/logout             revoca refresh token
GET    /v1/auth/me                 usuario + permisos efectivos

# ── Users / Roles
GET    /v1/users                   paginado, filtros
GET    /v1/users/options           para selectores (ex /users/list)
POST   /v1/users                   user:create
PATCH  /v1/users/:id               user:update
PATCH  /v1/users/:id/password      user:update
GET    /v1/roles                   lista con permisos
PATCH  /v1/roles/:id/permissions   user:update  (reemplaza el seed-only)
GET    /v1/permissions             catálogo completo

# ── Products (la API es la fuente maestra — B5)
GET    /v1/products                paginado + ?search=&active=
GET    /v1/products/lookup/:code   por código de barras (escáner)
GET    /v1/products/:id
POST   /v1/products                product:create
PATCH  /v1/products/:id            product:update
DELETE /v1/products/:id            product:delete (soft: active = false)
POST   /v1/products/import         importación masiva desde Excel/CSV (ex POST /store/many)
                                   → devuelve informe: creados, actualizados, duplicados, errores por fila
GET    /v1/products/import/template  plantilla XLSX con las columnas esperadas
POST   /v1/products/:id/barcode    asignar/reasignar código de barras
# (sin /sync: la API manda — el ERP deja de ser fuente)

# ── Warehouses (B4)
GET    /v1/warehouses              lista (hoy una sola)
POST   /v1/warehouses              warehouse:create (admin)
PATCH  /v1/warehouses/:id          warehouse:update (admin)

# ── Locations
GET    /v1/locations               paginado + ?warehouseId=&hall=&search=
POST   /v1/locations               location:create
PATCH  /v1/locations/:id           location:update
DELETE /v1/locations/:id           location:delete (soft)
GET    /v1/locations/:code/content contenido (paginado)
GET    /v1/locations/codes?from=&to=  rango de códigos
POST   /v1/locations/move          mover DENTRO de la misma bodega (atómico):
                                   { productId, fromLocation, toLocation, units, clientUuid }

# ── Transfers (entre bodegas — dos fases, B4)
GET    /v1/transfers               paginado + ?status=&fromWarehouse=&toWarehouse=
POST   /v1/transfers               crear borrador: { from, to, lines[{productId, units}] }
POST   /v1/transfers/:id/dispatch  transfer:dispatch → TRANSFER_OUT + stock_in_transit
POST   /v1/transfers/:id/receive   transfer:receive  → TRANSFER_IN  + libera tránsito
POST   /v1/transfers/:id/cancel    sólo si está en DRAFT
GET    /v1/stock/in-transit        mercancía en tránsito (incluye traslados sin recibir)

# ── Invoices (recepción)
GET    /v1/invoices                paginado + ?status=&search=&from=&to=
POST   /v1/invoices                invoice:create (con líneas opcionales)
GET    /v1/invoices/:id
PATCH  /v1/invoices/:id            invoice:approve (cierre/conciliación final)
DELETE /v1/invoices/:id            invoice:delete
POST   /v1/invoices/:id/lines      invoice:create
PATCH  /v1/invoices/lines/:lineId  invoice:capture
POST   /v1/invoices/:id/capture    captura física en lote (ex /detail/capture)
POST   /v1/invoices/lines/:lineId/locate   asignar a ubicación (ex /assign)
                                   → 409 si la línea está en DISCREPANCY (regla B1)
GET    /v1/invoices/:id/work       líneas listas para trabajar
GET    /v1/invoices/:id/pending    líneas pendientes
GET    /v1/invoices/assigned-to-me  ex /to-work/:id
DELETE /v1/invoices/lines/:lineId  soft delete

# ── Discrepancias y ajustes (reglas B1 + B3)
GET    /v1/adjustments/pending             bandeja de aprobación (pantalla nueva)
POST   /v1/invoices/lines/:lineId/approve  invoice:approve
                                           → fija quantity = physical_quantity, exige
                                             adjustment_reason, registra el ajuste y
                                             deja la línea en APPROVED (ya ubicable)
POST   /v1/invoices/lines/:lineId/reject   invoice:approve → devuelve a captura con nota
POST   /v1/inventory/adjust                ajuste manual: { productId, locationId, units,
                                             reason, origin, note } → ADJUSTMENT_IN/OUT
GET    /v1/inventory/adjustments           historial de ajustes + motivo + aprobador
GET    /v1/adjustment-reasons              catálogo de motivos (enum) para la UI

# ── Orders (surtido)
GET    /v1/orders                  paginado + ?status=
POST   /v1/orders
GET    /v1/orders/:id
PATCH  /v1/orders/:id
DELETE /v1/orders/:id
POST   /v1/orders/:id/lines
PATCH  /v1/orders/lines/:lineId
POST   /v1/orders/:id/confirm      empaque confirmado -> descuenta stock, emite OUT
POST   /v1/orders/:id/convert      marca converted (integración)
DELETE /v1/orders/lines/:lineId

# ── Inventory / Kardex
GET    /v1/inventory                    proyección por producto (ex Store)
GET    /v1/inventory/:productCode       stock total + desglose por ubicación
GET    /v1/inventory/:productCode/kardex  ledger del producto (paginado)
POST   /v1/inventory/adjust             { productCode, locationCode, units, reason } -> ADJUSTMENT
GET    /v1/kardex                       paginado + ?productCode=&location=&from=&to=&moveType=
GET    /v1/kardex/export                XLSX/CSV

# ── Reports
GET    /v1/reports/stock-by-product?from=&to=
GET    /v1/reports/stock-by-location?from=&to=
GET    /v1/reports/kardex?from=&to=
GET    /v1/reports/invoice-discrepancies?from=&to=
GET    /v1/reports/order-fulfillment?from=&to=
GET    /v1/reports/:id/export           XLSX

# ── Labels & printing
GET    /v1/labels/templates
POST   /v1/labels/templates              admin
PATCH  /v1/labels/templates/:id          admin
POST   /v1/labels/preview                { templateId, data } -> ZPL (texto) o PNG
POST   /v1/print-jobs                    encolar (ex POST /prints)
GET    /v1/print-jobs                    paginado + ?status=
PATCH  /v1/print-jobs/:id                reintentar / cancelar
POST   /v1/print-jobs/:id/reprint

# ── System
GET    /v1/health, /v1/ready
GET    /v1/system/stats                  dashboard
```

**Convenciones:** paginación `?page=1&limit=20` → `{ data, total, page, limit }`; orden
`?sort=field:asc`; errores `{ error: { code, message, details } }`; fechas ISO-8601 UTC;
`Idempotency-Key` obligatorio en todo `POST`/`PATCH` que mueva stock.

---

## 5. Roadmap por fases

### Fase 0 — Fundamentos, dominio y especificación ejecutable (2,5 semanas)
- Crear el monorepo con los **módulos KMP de la raíz** (`stock-domain`, `stock-network`,
  `stock-database`, `stock-datastore`) y `infra/docker-compose.yml` con PostgreSQL 16 + pgAdmin.
- **`stock-domain` con su guardia de arquitectura en CI**: un test (Konsist o check de Gradle) que
  **falle el build** si `stock-domain` llega a depender de Ktor, Exposed, SQLDelight, Compose o
  androidx. Sin esta guardia, la regla se rompe en la primera prisa.
- **Los 4 artefactos del dominio de §3.2-ter escritos con TDD**: matriz movimiento→efecto,
  máquina de estados, algoritmo de contenido por ubicación, y `difference` + regla de discrepancia.
  Tests puros en JVM (`commonTest`/`jvmTest`).
- **Las 10 invariantes de §3.6 como property-based tests (Kotest)** desde el primer día. Es el
  entregable que da la confianza de que el inventario cuadra: se generan secuencias aleatorias de
  operaciones y se afirma que I1–I10 se mantienen.
- **Importador del catálogo (§3.5)** con las reglas estrictas, **modo dry-run** e informe por fila.
  Se prueba contra el Excel real de 1.398 productos y se verifica que el dry-run reproduce las
  cifras exactas del archivo (suma = 10.914.130).
- `shared-contracts/openapi.yaml` v1 con auth + products + locations + warehouses.
- **Tests de caracterización de los flujos A–E (§1.4) contra la API legacy**: levantar el backend
  viejo con una copia de la BD y grabar, por cada flujo, las transiciones de estado y los
  movimientos de kardex esperados. **Es el entregable más valioso de esta fase**: el legacy no
  tiene ni un test, así que hoy la única especificación es el código. Estos golden tests alimentan
  los tests del dominio nuevo y cubren la lista de casos obligatorios de §3.6.
- **Esquema de auditoría desde el primer día** (`audit_log` particionada, `access_counter`,
  `config_versions`), con la anotación `@Auditable` en los casos de uso del dominio y el
  **test estructural que falla el build si un caso de uso crítico no es auditable**.
  Si la auditoría se deja para el final, hay que instrumentar retroactivamente todo lo escrito
  y siempre se olvidan casos (`PLAN_AUDITORIA.md` §10).
- **Baseline Flyway generado desde el esquema real** de la BD (no desde `prisma/migrations`).
- **Spike de escáner KMP** (CameraX/ML Kit + AVFoundation + Vision) con la ventana de validación
  definida una sola vez en `commonMain` — el punto de mayor riesgo técnico del móvil.
- ADRs: Ktor vs Spring, Exposed, Flyway, offline-first, destino de `kardo`, regla de discrepancias,
  **estrategia de pruebas de §3.6**, **stock inicial (A+B de §3.5.1)** y **el ADR de "dominio
  compartido"** documentando por qué existe la guardia de dependencias.
- **Aceptación:** `docker compose up` levanta Postgres; `openapi.yaml` valida; **los tests del
  dominio pasan en JVM en <1 s y el build del dominio no tiene ninguna dependencia de framework**
  (verificado por la guardia); **las 10 invariantes pasan con secuencias aleatorias**; el dry-run
  del importador reproduce la suma exacta del Excel; los tests de caracterización corren en verde
  contra el legacy; spike de escáner funcionando en Android e iOS; ADRs aprobados.

### Fase 1 — Backend núcleo (2–3 semanas)
- Scaffold Ktor, HikariCP, Flyway con el esquema de §2.3, `V1__baseline.sql`.
- Auth (login/refresh/logout/me), argon2id, RBAC sin el atajo `role === 1`.
- CRUD products (incl. import masivo), locations, users, roles/permissions.
- `GET /v1/health`, `StatusPages`, logging, OpenAPI servido.
- **Capa de traducción DTO ↔ dominio** en cada módulo: los DTOs de la API son *contratos de
  transporte* y no se filtran al dominio (hoy el legacy mezcla ambos y de ahí los `Decimal` vs
  `number`, los strings de estado y el `difference: 0`).
- Tests Testcontainers de identidad + catálogo + ubicaciones; y un test de contrato que verifique
  que los DTOs de `stock-network` coinciden con `openapi.yaml`.
- **Aceptación:** login real, permisos por rol verificados por test, CRUD completo, cobertura de
  casos de uso de identidad/catálogo/ubicaciones, y el test de contrato en verde.

### Fase 2 — Recepción e inventario (3,5 semanas)
- `invoices` + `invoice_lines`: creación, captura física, `difference` generada, estados y
  transiciones validadas.
- **Flujo de discrepancia con aprobación (B1)**: la línea en `DISCREPANCY` **no se puede ubicar**;
  el supervisor con `invoice:approve` resuelve y la línea pasa a `APPROVED`. Incluye la bandeja de
  pendientes de aprobación, que es una pantalla nueva que el legacy no tenía.
- **La regla única `AdjustStock` (B3)** con sus cuatro orígenes (`INVOICE_DISCREPANCY`,
  `FULFILLMENT_SHORTAGE`, `MANUAL_COUNT`, `DAMAGE_REPORT`) y `adjustment_reason` obligatorio (B2).
- `stock_by_location` + `product_stock` transaccionales; **Kardex append-only** con `quantity_after`.
- **Matriz movimiento→efecto en stock** portada del legacy y cubierta por test unitario por cada
  `moveType` (`INITIAL`, `IN`, `OUT`, `ADJUSTMENT_IN/OUT`, `MOVE_OUT/IN`, `LOCATED`,
  `PENDING_TO_LOCATE`, `PENDING_TO_OUT`).
- **Algoritmo de contenido de ubicación** portado tal cual desde `getLocationContent` y validado
  contra datos reales antes de simplificar.
- Asignación a ubicación y **traslados dentro de la misma bodega** (atómicos).
- Reportes CSV/XLSX base; job de reconciliación ledger ↔ proyección.
- Los tests de caracterización de Fase 0 se reapuntan a la API nueva y deben pasar sin cambios.
- **Aceptación:** una factura completa se puede crear, capturar, **detectar discrepancia con
  `difference` calculada y bloquearse hasta que un supervisor la apruebe**, asignar a ubicación y
  quedar en `COMPLETED`; **ninguna línea puede llegar a `APPROVED` sin `approved_by`** (verificado
  por el `CHECK` de BD, no sólo por código); **ningún movimiento `ADJUSTMENT_*` puede existir sin
  motivo**; el ledger cuadra con las proyecciones en un test property-based con operaciones
  aleatorias; los golden tests del legacy pasan sin modificar las expectativas.

### Fase 3 — Surtido y exportación al ERP (2,5 semanas)
- `orders` + `order_lines`: creación, asignación, empaque, confirmación, descuento de stock,
  kardex `OUT`/`PENDING_TO_OUT`.
- **Exportación de órdenes convertidas al ERP (R-72/R-82)**: feature **nueva**, el flujo nunca
  existió. Se dispara al confirmar el empaque, con **idempotencia obligatoria** (un reintento no
  puede duplicar la orden en el ERP), reintentos con backoff, estado `PENDING_EXPORT` visible y
  auditoría del envío. Requiere definir el contrato con el ERP (endpoint o escritura en su base).
- **Faltantes en surtido** reutilizando `AdjustStock` con origen `FULFILLMENT_SHORTAGE` (B3) — no
  se implementa un segundo flujo.
- Tests de flujo **B**; verificación de no-negatividad de stock.
- **Aceptación:** orden completa empaquetada descuenta stock exacto y es trazable en kardex; un
  faltante durante el surtido genera un ajuste con motivo y no bloquea el cierre de la orden sin
  registro.

### Fase 3-bis — Traslados entre bodegas (1 semana, opcional según B4)
- `transfers` + `stock_in_transit` con el flujo de dos fases de §2.6: `DISPATCHED` → `IN_TRANSIT`
  → `RECEIVED`, con `TRANSFER_OUT` y `TRANSFER_IN` en el ledger.
- Invariante verificado por test: **`Σ stock_by_location + Σ stock_in_transit` se conserva en cada
  transición** — el stock total nunca miente aunque la mercancía esté en el camión.
- UI de despacho y de recepción de traslado (pantallas nuevas).
- **Se activa sólo si hay más de una bodega.** El esquema ya existe desde Fase 1, así que esta fase
  es conectar UI y permisos, no rediseñar.
- **Aceptación:** despachar N unidades de la bodega A las resta de A y las muestra en tránsito; la
  recepción en B las quita de tránsito y las suma a B; un traslado nunca recibido aparece como
  deuda visible con su responsable.

### Fase 4 — Etiquetas e impresión (2 semanas)
- `label_templates` con las 8 plantillas ZPL migradas a `JSONB field_schema`.
- **Máquina de estados de impresión `PENDING → SENT → PRINTED | FAILED` (R-76/R-83)**: una etiqueta
  **no se marca impresa hasta que la impresora confirma**. Si la Zebra está apagada, queda `FAILED`
  con `attempts` y reintenta. El `forEach(async)` del legacy se sustituye por `await` supervisado
  para impedir la doble impresión.
- `POST /v1/labels/preview` para web y móvil.
- **Aceptación:** encolar N etiquetas, preview sin imprimir, reintento tras fallo simulado.

### Fase 5 — Web React (4 semanas, en paralelo desde Fase 1)
- Scaffold FSD clonado de `RESPONSIVA/web` (alias, boundaries, `shared/api/client.ts`,
  ToastProvider, theme con `ITThemeProvider`).
- Auth + guards + layout (`ITLayout` + `ITSidebar` generado desde permisos).
- Entidades y páginas en el orden: dashboard → facturas → órdenes → ubicaciones →
  inventario/kardex → reportes → etiquetas → usuarios/roles.
- Export XLSX, i18n es/en, Playwright E2E de los flujos A, B y C.
- **Aceptación:** paridad de las 13 pantallas actuales + las nuevas, boundaries sin violaciones,
  E2E en verde.

### Fase 6 — Mobile KMP (5–6 semanas, en paralelo desde Fase 2)
- Scaffold KMP + Compose Multiplatform, tema y design system Compose.
- **`composeApp` por vertical slice** (una carpeta por caso de uso) y `stock-network` /
  `stock-database` / `stock-datastore` ya consumidos desde el día uno.
- **`stock-network`** con Ktor Client + refresh token + `Idempotency-Key`; **`stock-database`**
  con SQLDelight (caché + cola de sincronización).
- Orden de implementación: auth/config → home → facturas → órdenes → ubicaciones → inventario →
  etiquetas.
- **Patrón por pantalla según §3.4**: MVI con Intents sellados en escáner, captura, surtido y
  movimiento; MVVM simple en CRUD y listados.
- **Los tests del reducer corren en JVM** (sin emulador): es la razón de haber puesto las máquinas
  de estado en `stock-domain` y no en la UI.
- Escáner `expect/actual` (CameraX/ML Kit + AVFoundation/Vision) con la ventana de validación
  compartida desde `commonMain`.
- **Aceptación:** mismo flujo A y C operable en Android **y** iOS; captura funciona sin red y
  sincroniza al recuperar señal sin duplicar movimientos; **los tests de los ViewModels con
  máquina de estados pasan en JVM sin arrancar emulador**; el escáner no descarta códigos válidos
  por desalineación de la ventana (bug M4 del legacy cubierto por test).

### Fase 7 — Migración de datos y despliegue (2,5 semanas)
- **Importar el catálogo desde el Excel real** (1.398 productos) con el importador de §3.5 y
  verificar el cuadre: suma de existencias = **10.914.130**, `PZA` al 100%, 334 productos en cero.
  Cualquier desviación se explica antes de continuar.
- **Resolver el sufijo `N` antes de importar** (decisión abierta 1): si son un solo producto con dos
  códigos, fusionar; si son dos, importar los 1.398. Esta decisión no se puede posponer a después
  de operar.
- **Cuantificar el descuadre heredado del bug B6 (§2.7)** con la consulta de §2.7-bis, por
  producto, y **presentarlo al negocio antes de cortar**. El `Store.stock` del legacy está por
  debajo del real porque venía perdiendo unidades en cada ubicación. Hay que decidir con datos
  delante: cargar el número del legacy (fiel, pero sabiendo que es bajo) o reconciliar contra
  conteo físico.
- **Conteo físico como proyecto propio (R-73/R-81)**: hay que contar **1.398 productos**. Lo ejecuta
  **personal de almacén** con una **pantalla de conteo nueva** (el jefe de bodega asigna zonas; los
  operadores cuentan). Cada conteo genera evento de auditoría. **No es un script: es trabajo físico
  planificado con fechas y personal.**
- **Stock inicial en la ubicación de recepción** (`kind = RECEIVING`, la antigua `ADMIN`) según
  §3.5.1, y el conteo físico traslada a los estantes con `AdjustStock`. Se rechaza inventar
  ubicaciones.
- ETL MySQL → PostgreSQL: usuarios/roles/permisos, `Kardex` → ledger nuevo, facturas/órdenes con
  historial. **La columna `difference` se puebla en el ETL** (el legacy siempre escribió `0`).
- Recalcular `stock_by_location` reproduciendo el ledger; comparar contra `Store.stock` y
  **reportar diferencias antes de cortar**.
- **Ensayo completo de migración sobre una copia** y verificación de las 10 invariantes sobre los
  datos migrados (no solo sobre datos sintéticos).
- Docker: backend, web (nginx), Postgres, backup programado (`pg_dump` + retención).
- Runbook de rollback y período de doble lectura.
- **Aceptación:** el catálogo migrado cuadra al peso con el Excel; las 10 invariantes pasan **sobre
  datos reales migrados**; inventario cuadrado (o diferencias explicadas y firmadas); despliegue
  reproducible desde cero con `docker compose up`.

**Estimación total:** ~22–25 semanas de una persona, o ~14–16 con dos (web y mobile en paralelo).

El incremento respecto a la estimación anterior viene de tres cosas que las decisiones cerraron y
que antes no estaban dimensionadas: la **exportación al ERP** (flujo que nunca existió, +0,5 sem),
la **confirmación real de impresión** con máquina de estados (+0,5 sem), y la **pantalla de conteo
físico** para la reconciliación previa al corte (+1 sem de desarrollo, y el conteo en sí es trabajo
de almacén, no de desarrollo).

Distribución por componente: dominio compartido + fundamentos ~2,5 semanas (incluye los
property-based tests de invariantes y el importador) · backend ~11 semanas (incluye la bandeja de
aprobación de discrepancias, que es pantalla y flujo nuevos) · web ~4,5 semanas · móvil KMP ~6
semanas · migración de datos y despliegue ~2,5 semanas, con web y móvil solapando el backend. La
Fase 3-bis (traslados entre bodegas, ~1 semana) es opcional y sólo se ejecuta si hay más de una
bodega; el esquema ya está listo desde Fase 1, así que no bloquea nada.

**Sobre el coste de las pruebas:** van **dentro** de cada fase, no como fase aparte, y representan
del orden del 25–30% del esfuerzo de backend. Es la partida que el legacy se ahorró y que ahora se
paga con intereses: sin tests no hay forma de saber si el inventario cuadra, y un inventario en el
que no se confía no se usa.


### Las 4 decisiones de arquitectura (resumen para ADR)

| Eje | Decisión | Alternativa descartada y por qué |
|---|---|---|
| Dominio | **Módulo KMP compartido `stock-domain`**, puro, en la raíz del monorepo | Duplicar el dominio en backend y móvil: las dos implementaciones divergen sin tests que las comparen, y el riesgo es stock mal calculado |
| Módulos backend | **Vertical slice por módulo** con frontera pública única | Capas horizontales globales: obliga a tocar 4 carpetas lejanas por feature y no aísla los cambios |
| Módulos web | **FSD completo** clonado de `RESPONSIVA/web` | Feature-folders: nada impide el grafo circular; FSD ya está probado y tiene lint que lo hace cumplir |
| Presentación móvil | **Híbrido MVVM + MVI** (MVI donde hay máquina de estados) | MVI puro: sobreingeniería en Config/Login. MVVM puro: reproduce el bug de estados dispersos del escáner |
| Presentación web | **Hook de feature + componente presentacional** (`fx`), que es MVVM adaptado a React | No es un eje separado de FSD: ambos conviven (§3.3) |
| Reglas de negocio | **En `stock-domain`, con tests puros en JVM** | En solo-backend: el móvil no puede validar offline. Duplicadas: divergen sin tests |


---

## 6. Matriz de trazabilidad — features actuales → destino

| # | Feature actual | Origen | Destino | Fase |
|---|---|---|---|---|
| 1 | Login JWT | backend `/users/login`, web, mobile | `/v1/auth/login` + refresh | 1 |
| 2 | RBAC por permisos | backend middlewares + seed | `/v1/roles`, guards web, permisos móvil | 1 / 5 / 6 |
| 3 | Gestión de usuarios | `Users.tsx`, `UserScreen` | `UsersPage` + `feature/config` | 1 / 5 / 6 |
| 4 | Catálogo de productos | `/products`, `/store/many` | `/v1/products` + import | 1 |
| 5 | Alta masiva de productos | `addManyStores` | `POST /v1/products/import` | 1 |
| 6 | CRUD ubicaciones | `Locations.tsx`, `LocationsScreen` | `/v1/locations` + `LocationsPage` | 1 / 5 / 6 |
| 7 | Búsqueda por rango de ubicaciones | `getAllLocationCodesFromLocationId` | `/v1/locations/codes` | 2 |
| 8 | Contenido de ubicación | `getLocationContent`, `SearchLocationContentScreen` | `/v1/locations/:code/content` | 2 / 5 / 6 |
| 9 | Facturas: crear/listar/detalle | `/invoices`, `Invoices.tsx` | `/v1/invoices` | 2 |
| 10 | Captura física + discrepancia | `updateDetails`, `adjustInvoiceDetail` | `/v1/invoices/:id/capture` | 2 |
| 11 | Asignar línea a ubicación | `assignLocationStore` | `/v1/invoices/lines/:id/locate` | 2 |
| 12 | Cierre automático de factura | `updateInvoiceStatus` | transición `PENDING_TO_LOCATE` | 2 |
| 13 | Mover producto entre ubicaciones | `moveProductFromLocation` | `POST /v1/locations/move` | 2 |
| 14 | Ajuste de inventario | `adjustmentLocationProduct` | `POST /v1/inventory/adjust` | 2 |
| 15 | Kardex (ledger) | `Kardex.tsx`, `/store` | `/v1/inventory/:code/kardex` | 2 |
| 16 | Reporte por rango de ids | `getStoreByIdRange` | `/v1/reports/stock-by-product` | 2 |
| 17 | Reporte por rango de ubicación | `getStoreByLocationRange` | `/v1/reports/stock-by-location` | 2 |
| 18 | Reporte por fechas | `getKardexByDateRange` | `/v1/reports/kardex` + `/v1/kardex?from=&to=` | 2 |
| 19 | Órdenes: crear/listar/detalle | `/orders`, `Orders.tsx` | `/v1/orders` | 3 |
| 20 | Asignar orden a usuario | `assignOrder` | `PATCH /v1/orders/lines/:id` | 3 |
| 21 | Confirmar empaque | `confirmOrder`, `updateStoreLocationWithOrder` | `POST /v1/orders/:id/confirm` | 3 |
| 22 | Conversión de orden | campo `converted` | `POST /v1/orders/:id/convert` | 3 |
| 23 | Etiquetas: plantillas ZPL | `Labels` + seeds | `label_templates` | 4 |
| 24 | Cola de impresión + cron | `print.cron.ts`, `Print` | `print_jobs` + worker | 4 |
| 25 | Etiqueta libre / malla | `FreeLabelScreen`, `MeshScreen` | `feature/labels/*` | 4 / 6 |
| 26 | Vista previa de etiqueta | (no existe) | `POST /v1/labels/preview` | 4 |
| 27 | Escáner de código de barras | `CodeScanner.js` | `expect/actual` CameraX/AVFoundation | 6 |
| 28 | Inventario por producto/ubicación | `Inventory.tsx`, `InventoryScreen` | `InventoryPage` + `feature/inventory` | 2 / 5 / 6 |
| 29 | Config de servidor en móvil | `ConfigScreen` | `feature/config` + `datastore` | 6 |
| 30 | Exportación XLSX | `xlsx` + `file-saver` | `/v1/reports/:id/export` + helper web | 2 / 5 |
| 31 | Dashboard | `Home.tsx`, `HomeScreen` | `DashboardPage` + `/v1/system/stats` | 5 |
| 32 | Tickets | `Ticket.tsx` (placeholder), `TicketScreen`/`List` | **Decidir**: portar o eliminar | — |
| 33 | Sync SQL Server (`kardo`) | `/kardo`, `kardo.service` | módulo `integration` **o** descartar | — |
| 34 | Validación externa AXZY hardcodeada | `axzy-validator` (comentado) | **Eliminar** | 0 |

---

## 7. Riesgos y decisiones abiertas

### Riesgos

| Riesgo | Prob. | Impacto | Mitigación |
|---|---|---|---|
| **Paridad funcional rota al portar**: los flujos núcleo tienen reglas sutiles y **no hay ni un test** en el repo legacy que las capture | Alta | Alto | **Fase 1.5 dedicada a "especificación ejecutable"**: escribir tests de caracterización de los flujos A–E contra la API vieja *antes* de reescribir, y usarlos como golden tests del backend nuevo |
| Overflow de `Int` en `Store.stock` y `Kardex.units` | Media | Alto | `NUMERIC(14,3)` en el esquema nuevo |
| Divergencia ledger ↔ proyección | Alta | Alto | Todo en una transacción + job de reconciliación + test property-based |
| Diferencias al migrar desde MySQL | Alta | Alto | Informe de diferencias *antes* del corte; nadie firma a ciegas |
| **El esquema real de la BD no coincide con las migraciones versionadas** (36 legacy + 1 que recrea todo) | Alta | Alto | Generar el **baseline Flyway desde la BD real** (`pg_dump`/introspección), no desde `prisma/migrations` |
| **Datos históricos dependen del algoritmo de `getLocationContent`** (no es `Σ IN − Σ OUT` simple) | Alta | Alto | El algoritmo depende de `ADMIN` y de `LOCATION`, y **ambos cambian con el fix de §2.7**. Se decide con datos delante (§2.7-bis) y se congela con tests de caracterización antes de tocar |
| **Reglas contables ambiguas** en el legacy: `PENDING-TO-OUT` no afecta y `LOCATION` resta, ambas por accidente | **Resuelto** | Alto | La causa raíz es el modelo (un total en vez de stock por ubicación). Con `stock_by_location` como verdad **no existe operación que reste del total** (§2.7) |
| Compose Multiplatform en iOS (cámara, performance) | Media | Medio | Spike en Fase 0/6 con CameraX/AVFoundation vía `expect/actual` |
| Sesiones móviles largas | Media | Medio | Refresh opaco revocable en BD, no JWT de larga vida |
| Doble fuente de verdad durante el rewrite | Alta | Medio | `BASE/` congelado como lectura; una sola API nueva |
| Carga de `bodyParser(50mb)` sin streaming en el legacy | Media | Bajo | Limitar tamaño real por ruta + carga por lotes en el backend nuevo |
| **Migrar datos con estados inválidos**: hay filas con `status` fuera del enum previsto | Media | Medio | ETL con informe de valores no mapeados y decisión explícita por cada uno |

### Orden de ataque recomendado (independiente de las fases)

Por impacto/riesgo, el backend nuevo debe construirse **de dentro hacia fuera**:

1. **Ledger de inventario** (`kardex` + `stock_by_location`) con su matriz de movimientos y tests.
   Es el corazón: si esto queda mal, todo lo demás es cosmético.
2. **Recepción** (facturas + captura + discrepancias) — incluye el caso de uso `difference` que el
   legacy **nunca implementó**; hay que decidir la regla con el negocio, no copiar el hueco.
3. **Surtido** (órdenes) — depende de que el stock por ubicación sea fiable.
4. **Identidad/permisos** puede ir primero si se prefiere tener el andamiaje HTTP antes, pero no
   bloquea al dominio.
5. **Web y móvil en paralelo** en cuanto el contrato OpenAPI de cada módulo esté congelado.

### Decisiones cerradas

| # | Decisión | Resolución |
|---|---|---|
| B1 | Regla de discrepancia | **Bloquea la ubicación hasta aprobación de un supervisor** con `invoice:approve` (§2.3) |
| B2 | Merma / faltantes | **Movimiento de ajuste con motivo obligatorio, nunca borrado** (kardex append-only) |
| B3 | ¿Una o dos reglas de diferencia? | **Una sola regla `AdjustStock`** con distinto `origin` |
| B4 | Multi-bodega y traslados | **Esquema multi-bodega con una por defecto + traslados en dos fases** (§2.6) |
| B5 | Fuente maestra del catálogo | **La API nueva es la fuente maestra.** Se importa el Excel inicial de productos y a partir de ahí `products` es soberana. Sin sync periódico |
| D1 | Arquitectura de dominio | Módulo KMP `stock-domain` compartido, puro (§3.2-bis) |
| D2 | Módulos backend | Vertical slice por módulo (§3.2) |
| D3 | Estructura web | FSD completo clonado de `RESPONSIVA/web` (§3.3) |
| D4 | Presentación móvil | MVVM + MVI híbrido (§3.4) |
| D5 | Reglas de negocio | En `stock-domain`, tests puros en JVM |
| D6 | Motor de BD | PostgreSQL 16 en Docker |
| D7 | Contrato de usuario en mutaciones | **Siempre `user_id`**, nunca el nombre (el legacy envía el nombre) |
| D8 | Transporte en producción | HTTPS detrás de nginx; el móvil usa `AppConfig(scheme, host, port)` en vez de la IP pelada |
| D9 | Regla de discrepancia y merma | Cerradas como B1/B2/B3 |
| D10 | `external_id` del ERP | **Pendiente** — decisión abierta 3 |
| D11 | Catálogo perfilado contra datos reales | Excel analizado: 1.398 productos, calidad excelente, importador **estricto** (§3.5) |
| D12 | Estrategia de pruebas | Suite de 10 niveles + invariantes I1–I10 + puertas de CI (§3.6) |
| B6 | Al ubicar, **el legacy muta el libro** en lugar de añadir un movimiento | **Confirmado**: `removeUnitsFromKardex` hace `kardex.update`. **Corregido mi análisis: el total NO descuadra** (§2.7). El modelo nuevo lo resuelve siendo append-only |
| B7 | Ubicación `ADMIN` | **Ubicación real con id real, marcada `kind = RECEIVING`** (ubicación temporal de recepción) |
| B8 | Rutas intencionalmente abiertas | `PATCH /prints` (Zebra) y `GET /kardo` (ERP) **siguen sin JWT de usuario, pero con API key de servicio**. `DELETE /invoices/detail/:id` **sí es descuido**: se cierra con auth |
| B9 | Política de cambios | **Se mejora sin preguntar lo que no mueve cantidades/estados/histórico; se pregunta lo que sí** (§2.8) |
| R-65 | Ubicar = traslado, no mutación | **Confirmado.** En el legacy el total tampoco bajaba, pero **el registro histórico se mutaba**. Aquí ubicar crea un movimiento nuevo: el total es invariante y la historia se conserva (§2.7) |
| R-71 | Sufijo `N` | **Son dos SKUs distintos** (decisión de negocio). Se importan los 1.398 tal cual. *Riesgo documentado: si resultaran ser un producto partido, el stock quedaría duplicado* |
| R-72 | Órdenes al ERP | **El ERP sigue vivo y hay que devolverle las órdenes convertidas.** Es una **feature nueva** (el flujo nunca existió): se dispara al confirmar el empaque, con job de reintentos e idempotencia |
| R-73 | Stock heredado | **Reconciliar con conteo físico ANTES de cortar.** Es un proyecto aparte: 1.398 productos a contar, con pantalla de conteo nueva |
| R-74 | Segregación de funciones | **El que captura NO puede aprobar su propia discrepancia** (política PB3 activa) |
| R-75 | Umbral de discrepancia | **Sin umbral: TODA discrepancia requiere aprobación.** Ver §2.9 |
| R-86 | **Resolución del cuello de botella (D-1)** | **Aprobación POR FACTURA, no por línea.** El supervisor ve el listado de diferencias y resuelve la factura completa con una acción. **Mismo control** (una persona distinta autoriza), pero el trabajo pasa de 15 acciones a 1 |
| R-76 | Confirmación de impresión | **`PENDING` hasta que la impresora confirme.** Si la Zebra está apagada, la etiqueta queda pendiente y reintenta |
| R-77 | Permiso de ubicar | **El operador SÍ ubica mercancía** (`invoice.locate` con alcance `OWN`). Era un hueco del modelo de permisos, no una decisión |
| R-78 | Retención de auditoría | Nivel 1 **permanente** · Nivel 2 3 años · Nivel 3 1 año · Nivel 4 30 días |
| R-79 | Resistencia de la auditoría | **Cadena de hashes interna + verificación periódica.** *Riesgo residual aceptado: un DBA con acceso total puede recalcularla. Un cambio accidental o vía aplicación se detecta* |
| R-80 | Validación de cajas | **Validar que `cantidad / unidadesPorCaja` sea entero.** Si no, error en la captura |
| R-81 | Quién ejecuta el conteo | **Personal de almacén con una pantalla de conteo nueva** (asignación de zonas por el jefe de bodega) |
| B12 | Auditoría | **Todo debe ser auditable**: quién, cuándo, dónde, de dónde, hora, y **con qué permiso**. Se audita la autorización (con la versión vigente de permisos y políticas), no solo el cambio. Ver **[PLAN_AUDITORIA.md](PLAN_AUDITORIA.md)** |
| B11 | Estrategia de refactor | **Corregir con mejores prácticas, no replicar defectos.** 71 cambios en 3 clases; los de clase A/B se corrigen, los de clase C (mueven stock) requieren confirmación de negocio y llevan feature flag. Ver **[PLAN_REFACTOR_MEJORAS.md](PLAN_REFACTOR_MEJORAS.md)** |
| B10 | Control de acceso | **Rediseño completo con RBAC + ABAC**, pipeline Identidad→RBAC→ABAC con default deny, motor en `stock-domain`, 7 pantallas. Documento propio: **[PLAN_ROLES_PERMISOS_POLITICAS.md](PLAN_ROLES_PERMISOS_POLITICAS.md)** |

### Decisiones abiertas (necesito tu confirmación)

**Surgidas del análisis del Excel (nuevas):**

1. **El sufijo `N` — la más importante.** Encontré **201 productos** cuyo código es otro código más
   `N` (`ABRATL1G` / `ABRATL1GN`), y en **180 casos comparten exactamente el mismo nombre**.
   ¿Son **dos SKUs distintos** (lo que asume el legacy) o es **un producto con dos códigos**?
   - Si son distintos: no hay nada que hacer, se importan los 1.398 tal cual.
   - Si es un producto con dos códigos: **hay stock partido en dos** y fusionarlos antes de operar
     es trivial; descubrirlo después obliga a reconciliar dos historiales de kardex.
   Es la única pregunta que puede invalidar la importación inicial, por eso va primera.
2. **Stock inicial sin ubicaciones (P5, §3.5.1).** El Excel trae el total por producto pero el
   modelo guarda stock por ubicación. Propongo **A+B combinadas**: cargar en la ubicación virtual
   `ADMIN` (fiel al legacy) y planificar el conteo físico por ubicación como actividad de arranque
   con la regla `AdjustStock`. ¿Te sirve, o prefieres arrancar con conteo físico completo (opción B)
   antes de operar?
3. **`external_id` — conservar el `CIDPRODUCTO` del ERP.** El Excel lo trae (1 → 1.398, secuencial,
   sin huecos). ¿Lo guardamos como `external_id` para referencia cruzada con otros sistemas, o el
   `product_code` (que es el código de barras) basta como identificador de negocio?
4. **Segregación de funciones en la aprobación (era D9).** Con B1, ¿el mismo usuario que captura la
   discrepancia puede aprobarla, o debe ser **otro**? Lo segundo es el control interno correcto
   (evita que alguien tape su propio error); lo primero es más ágil con poco personal. Afecta al
   `CHECK` de BD y a si el dominio debe validar `approved_by != captured_by`.

**Ya existentes, siguen abiertas:**

5. **`kardo` / SQL Server** — ¿sigue existiendo el sistema externo? Si no, elimino el módulo y
   `converted`/`sent` pasan a ser sólo informativos (**hoy esas columnas nunca se escriben ni se
   leen: no hay flujo de conversión implementado**).
6. **Tickets** — hoy es un placeholder de 33 LOC en web y `TicketList`/`TicketItem` en móvil están
   **muertos**. ¿Es una feature real pendiente o la descarto?
7. **Unidades de medida (P3)** — hoy el 100% es `PZA`. ¿Migramos a catálogo `units_of_measure`
   (recomendado, para evitar el clásico `KG`/`Kg`/`KGS`) o lo dejo como texto libre?
8. **Reimpresión / anulación de etiquetas** — ¿se requiere trazabilidad? (asumo que sí, con
   `reprint` e historial).
9. **Autenticación de usuario** — ¿SSO, o usuario/password propio con argon2id?
   (la integración AXZY del legacy está **desactivada** y tiene credenciales hardcodeadas: se elimina).
10. **iOS ahora o después** — Compose Multiplatform para iOS está en el alcance; si se quiere
    reducir riesgo, se puede lanzar sólo Android en Fase 6 y iOS en una Fase 8.

### Pendiente de tu lado

- ~~**El Excel de productos**~~ — **recibido y analizado** (§3.5). Ya no bloquea: el esquema de
  `products` está derivado de datos reales y el importador está especificado con reglas estrictas.
  Lo que queda de tu lado sobre el Excel son las decisiones 1 (sufijo `N`) y 3 (`external_id`).

---

## 8. Criterios de éxito

- **Paridad funcional:** los 5 flujos núcleo (§1.4) operables en web y móvil, **validados contra
  los tests de caracterización** escritos sobre la API legacy.
- **Integridad:** imposible dejar stock negativo; kardex append-only; toda merma con motivo;
  el ledger y las proyecciones cuadran en un test property-based.
- **El bug B6 corregido y no reproducible:** ubicar mercancía **no cambia el total**, el registro
  de recepción **conserva sus unidades originales**, y existe un test de regresión que falla si
  alguien reintroduce un descuento al ubicar (§2.7).
- **Auditabilidad (requisito de primer orden):** **todo es auditable** — quién, cuándo, dónde, de
  dónde, a qué hora y **con qué permiso**. Cada evento crítico guarda el **trace de la decisión**
  (identidad → RBAC → ABAC, con la política que decidió y cada condición evaluada) y la **versión**
  de la matriz y las políticas, para poder **reproducir** la decisión histórica. Se auditan también
  los intentos denegados. La auditoría es **inmutable** (permisos de BD + trigger + cadena de hashes)
  y **verificable** (`/audit/integrity`). Detalle en **[PLAN_AUDITORIA.md](PLAN_AUDITORIA.md)**.
- **Trazabilidad:** cada movimiento identifica **id de usuario** (no nombre), dispositivo, canal de
  origen, ubicación y timestamp, incluyendo los que llegaron offline (con las dos horas: captura y
  sincronización).
- **Seguridad:** ninguna ruta sin autenticación; autorización por permiso explícito (sin bypass
  `role === 1`); JWT revocable con refresh; sin secretos ni credenciales en el repositorio;
  sin concatenación de SQL.
- **Correctitud de lo que hoy está roto:** `difference` calculada; borrados coherentes (lógico o
  físico, pero uno solo); `PATCH /invoices/:id` (aprobación) accesible desde la web; paginación en
  todos los listados; sin N+1 en las consultas de detalle.
- **Arquitectura:** boundaries FSD sin violaciones en web (verificable con lint); `domain` del
  backend sin dependencias de framework; contrato OpenAPI único consumido por los 3 clientes.
- **Operación:** `docker compose up` levanta todo; backup y restore probados.
- **Pruebas (requisito explícito del proyecto):** las **10 invariantes I1–I10 de §3.6 pasan como
  property-based tests con secuencias aleatorias de operaciones**; existe un test por cada caso
  obligatorio del flujo de inventario; las puertas de CI están activas (cobertura ≥95% en el
  dominio, guardia de arquitectura, escáner de secretos, lint de fronteras FSD, migraciones desde
  cero); y **ningún issue del flujo de inventario se cierra sin su test**.
- **Datos reales migrados:** el catálogo importado cuadra al peso con el Excel (1.398 productos,
  10.914.130 unidades) y **las 10 invariantes se verifican sobre los datos migrados**, no solo
  sobre datos sintéticos.
- **Deuda del legacy saldada:** ninguna de las **36 malas prácticas de §2.2-quater** reaparece; cada
  una tiene su corrección y el test que la bloquea.

---

## Anexo A — Fuentes leídas

**Backend** (`~/DEV/STOCK/BASE/backend`): `src/index.ts`, `src/routes/*.ts` (9),
`src/middlewares/*.ts` (5), `src/dto/*.ts` (11), `src/service/*.ts` (11),
`src/controller/*.ts` (10), `src/mappers/*.ts`, `src/transformers/*.ts`,
`src/config/{config,database,sqlServer}.ts`, `src/print.cron.ts`, `prisma/schema.prisma`,
`prisma/seed.ts`, `prisma/seeds/{security,labels}.ts`, `prisma/migrations/`, `migrations/`,
`package.json`, `swagger.yaml` (2.331 líneas).

**Web legacy** (`~/DEV/STOCK/BASE/frontend`): `src/App.tsx`, `src/main.tsx`, `src/i18n.ts`,
`src/pages/*.tsx` (13), `src/services/*.ts` (10), `src/state/*.ts`, `src/types/*.ts` (12),
`src/components/**` (atoms/molecules/organisms/hocs/layout/providers), `src/utils/*.ts`,
`src/hooks/usePermission.tsx`, `src/locales/es-MX.json`, `package.json`, `vite.config.ts`.

**Mobile legacy** (`~/DEV/STOCK/BASE/mobile-app`): `App.js`, `src/screens/*.js` (26),
`src/components/*.js` (20), `src/services/*.js` (5), `src/core/theme.js`, `src/data/status.js`,
`src/utils/{axios,jwt,storage,wait}.js`, `src/context/authContext.js`, `app.json`, `eas.json`,
`package.json`.

**Referencia UI/arquitectura** (`~/DEV/PTNV/RESPONSIVA`): `AXZY_UI_SYSTEM.md` (2.958 líneas,
44 componentes) y `web/` completo (`eslint.config.js` con las 6 policies de boundaries,
`tsconfig.app.json`, `vite.config.ts`, `src/main.tsx`, `src/app/App.tsx` (614 líneas),
`src/app/store.ts`, `src/app/index.css`, `src/shared/api/{client,session,table,clientTable}.ts`,
`src/shared/api/constants/API_CONSTANTS.ts`, `src/entities/user/model/usePermission.ts`,
`src/entities/permission/model/screens.ts` (307 líneas), `src/entities/user/model/roles-catalog.ts`,
`src/shared/validation/*`, `src/shared/i18n/*`, `playwright.config.ts`, `Dockerfile`,
`nginx.conf`, `render-config.sh`, `electron/`).

**Datos reales de negocio**: `~/Downloads/INVENTARIO REAL LUNES 10 DE JUN 2024 - Copy.xlsx`
(hoja `Hoja1`, 1.402 filas, 7 columnas) — perfilado completo en §3.5: 1.398 productos únicos,
`product_code` único, `PZA` al 100%, 10.914.130 unidades totales, cero nulos y cero decimales.

**UI system** (`~/DEV/AXZY/AXZY_UI_SYSTEM`): `package.json` v1.4.0,
`src/{components,hooks,theme,types}`, `LLM_DOCS.md`, `ITDataTable_Instructions.txt`,
`ITSearchSelect_Instructions.txt`, `GUIA_EJEMPLOS_REALES.md`.

### Anexo B — Informes de auditoría generados

Estos documentos (en la raíz del workspace, **fuera de los repos legacy**) contienen el detalle
exhaustivo con rutas y números de línea. Son la evidencia de este plan:

| Documento | Contenido |
|---|---|
| [REPORTE_ARQUITECTURA_FSD_RESPONSIVA_WEB.md](REPORTE_ARQUITECTURA_FSD_RESPONSIVA_WEB.md) | Arquitectura y convenciones del repo de referencia FSD (~2.300 líneas): capas, boundaries, data fetching, RTK, routing/guards, UI kit, permisos, estilos, i18n, PDF/QR, testing, Docker/Electron, inventario de 79 rutas / 18 entidades / 56 slices / 74 páginas |
| [PLAN_AUDITORIA.md](PLAN_AUDITORIA.md) | **Diseño de auditoría y trazabilidad**: las 6 dimensiones (quién/cuándo/dónde/de dónde/permiso/qué), 4 niveles de criticidad, el `AccessDecisionTrace` completo, cadena de hashes, particionado, 3 tablas nuevas, 11 endpoints, 6 pantallas y 28 casos de prueba |
| [PLAN_REFACTOR_MEJORAS.md](PLAN_REFACTOR_MEJORAS.md) | **Estrategia del refactor**: 71 cambios identificados (R-01…R-71) en 3 clases (defecto técnico / contradicción interna / regla de negocio), metodología de propuesta, política de rollback con feature flags, priorización en 3 bloques y 15 mejoras por buenas prácticas |
| [PLAN_ROLES_PERMISOS_POLITICAS.md](PLAN_ROLES_PERMISOS_POLITICAS.md) | **Diseño completo del control de acceso**: RBAC + ABAC, 76 permisos, 5 roles, 12 políticas, 9 tablas, 18 endpoints, 7 pantallas y 50 casos de prueba |
| [MOBILE_APP_INVENTORY_REPORT.md](MOBILE_APP_INVENTORY_REPORT.md) | Inventario del app Expo: 26 pantallas, 20 componentes, 20 endpoints, escáner, estados, y el checklist de 14 correcciones a aplicar al portar a KMP |

> Nota: el inventario del backend y del frontend web legacy se entregó íntegro en la sesión de
> análisis y sus hallazgos están incorporados en §1.1, §1.2, §1.3-bis, §2.2-bis, §2.2-ter y §8.2.

---

## §10-bis. RESUELTO — el móvil es su propio build de Gradle

> **Esta sección describía una limitación que ya no aplica, y se conserva por su valor
> histórico: el diagnóstico era correcto y la conclusión era incompleta.**

**La limitación era real.** Con AGP 9, `com.android.application` y
`org.jetbrains.kotlin.multiplatform` no pueden coexistir en el mismo build de Gradle:
en cuanto los dos están en el classpath, Kotlin intenta crear un `KotlinAndroidTarget`
que referencia `com.android.build.gradle.api.BaseVariant`, eliminada en AGP 9, y el
build entero falla.

**La conclusión era incompleta.** Se resolvió que `androidApp` necesitaba su *propio*
`settings.gradle.kts` con `includeBuild("../..")`. Funcionaba, pero tenía dos
consecuencias malas:

1. **IntelliJ no podía sincronizar `mobile-app`**, porque no era raíz de ningún build
   de Gradle. Falla con `Task 'prepareKotlinBuildScriptModel' not found`.
2. Un `include(":mobile-app")` en la raíz **no arregla nada**: un directorio no puede
   ser raíz de dos builds.

**LA SOLUCIÓN CORRECTA** —la que usa el proyecto de referencia
`~/DEV/PTNV/RESPONSIVA/PuertoNuevoApp`— es hacer que **todo el móvil sea un build
autocontenido**:

```
mobile-app/                      <- raíz de SU PROPIO build de Gradle
├── settings.gradle.kts          include(":shared") + include(":androidApp")
├── build.gradle.kts             los plugins con `apply false`
├── shared/                      la biblioteca KMP
├── androidApp/                  com.android.application, submódulo NORMAL
└── iosApp/
```

`mobile-app` **no está incluido en el monorepo**. Consume `stock-domain` por
`includeBuild("..")`, que es la **misma instancia y no una copia**, así que las
decisiones de negocio siguen siendo idénticas en backend y móvil por construcción.

**EL MISTERIO DE `apply false`, resuelto.** En una versión anterior de este documento se
afirmó que declarar los plugins en la raíz del monorepo con `apply false` «no servía».
**Era falso.** AGP 9 se distribuye como **un solo artefacto** que contiene los
descriptores de todos sus plugins, así que declarar cualquiera de ellos con
`apply false` **mete las clases de AGP en el classpath de script de TODOS los
subproyectos**. Entonces, dentro de `kotlin {}`, hay dos candidatos para `android {}` y
Kotlin resuelve el miembro del receptor antes que la extensión externa: gana el
`android()` heredado de KMP, que devuelve un `KotlinAndroidTarget` sin `namespace` ni
`compileSdk`.

> **REGLA QUE SE QUEDA:** un plugin de AGP 9 declarado con `apply false` contamina el
> classpath de script de todos los subproyectos del build. Si algún submódulo usa el
> bloque `android {}` del plugin de KMP **y no aplica ese plugin de AGP**, la
> declaración lo rompe. Por eso el móvil tiene que estar en su propio build: así cada
> build declara lo suyo y la cuestión no se cruza.

**Precio, dicho claro:** los comandos del móvil se lanzan desde `mobile-app/`, no desde
la raíz. Es el mismo precio que en el proyecto de referencia, y ya está en los scripts
(`scripts/movil-android.sh`, `scripts/ios.sh`).
