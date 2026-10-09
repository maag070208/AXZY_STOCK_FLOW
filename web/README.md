# Web — Almacén Integral

Web de React + Vite + TypeScript con arquitectura **Feature-Sliced Design (FSD)**.
Consume el contrato HTTP de [`docs/api/contrato.md`](../docs/api/contrato.md), que es la
**fuente de verdad única**: si algo de este cliente no coincide con el contrato,
el que está mal es el cliente.

La arquitectura, los alias, el cliente HTTP, el tema y el layout se clonaron del
proyecto que ya está en producción (`RESPONSIVA/web`); lo que se decidió distinto
está anotado abajo, con el motivo.

---

## Cómo se levanta

Requisitos: **Node 20** y **pnpm 10**.

```bash
pnpm install                      # dependencias
pnpm run dev                      # http://localhost:5173
```

Verificación (los mismos comandos que se usaron para aceptar esta entrega):

```bash
pnpm install
pnpm exec tsc --noEmit            # tipos
pnpm run build                    # tsc + bundle
pnpm exec eslint . --max-warnings=0   # incluye las reglas de boundaries de FSD
```

### Variables y configuración

| Prioridad | Origen | Para qué |
|---|---|---|
| 1 | `window.__APP_CONFIG__.API_URL` de `public/config.js` | runtime; nginx lo sobreescribe en despliegue |
| 2 | `VITE_API_URL` (`.env`) | build |
| 3 | `/v1` (valor por defecto) | proxy de Vite en desarrollo, nginx en producción |

La misma imagen se despliega contra distintos backends sin recompilar: por eso la
URL se resuelve en **runtime** y no en build.

En desarrollo, Vite reenvía `/v1` a `http://localhost:8080`
(`VITE_DEV_API_TARGET` lo cambia), así que no hay CORS que configurar.

> **Nota de entorno:** los scripts de `package.json` fijan
> `SWC_NATIVE_BINDING_CACHE="$PWD/.cache/swc"`. `@swc/core` materializa su addon
> en `~/Library/Caches/swc-native-<uid>`; cuando el `HOME` no es escribible
> (contenedores, CI, sandboxes), el plugin de React falla con
> «Failed to load native binding». La ruta debe ser **absoluta**.

---

## Estructura

```
web/
├── index.html              # carga /config.js antes del bundle
├── vite.config.ts          # alias @app @pages @widgets @features @entities @shared
├── tsconfig.json           # los mismos alias para el compilador
├── eslint.config.js        # boundaries entre capas (la dirección de importar es una regla)
├── public/config.js        # API_URL en runtime
└── src/
    ├── app/                        # composición: router, guards, providers, arranque
    │   ├── App.tsx                 # las rutas y el permiso que exige cada una
    │   ├── main.tsx                # createRoot + ThemeProvider + HashRouter
    │   ├── index.css               # capas de Tailwind + CSS del UI kit
    │   ├── guards/
    │   │   ├── PrivateRoutes.tsx        # nivel 1: ¿hay sesión?
    │   │   └── RequiresPermission.tsx   # nivel 2: ¿tiene el permiso de esta URL?
    │   ├── providers/AppProviders.tsx   # sesión + avisos + layout
    │   └── toast/ToastProvider.tsx      # ITToast envuelto en contexto
    │
    ├── pages/                      # una carpeta por área, una pantalla por archivo
    │   ├── auth/LoginPage.tsx
    │   ├── home/HomePage.tsx
    │   ├── reception/{InvoicesPage,InvoiceDetailPage,PendingApprovalPage}.tsx
    │   ├── fulfillment/{OrdersPage,OrderDetailPage}.tsx
    │   ├── transfer/TransfersPage.tsx
    │   ├── catalog/{ProductsPage,LocationsPage}.tsx
    │   ├── inventory/{InventoryPage,KardexPage,AdjustmentApprovalPage}.tsx
    │   ├── label/LabelsPage.tsx
    │   ├── report/ReportsPage.tsx
    │   ├── access/{UsersPage,AuditPage,HealthPage}.tsx
    │   └── errors/NotFoundPage.tsx
    │
    ├── widgets/                    # bloques de UI con lógica propia
    │   └── app-shell/
    │       ├── ui/AppShell.tsx         # ITLayout + ITTopbar + logout
    │       └── model/buildNavigation.ts # APP_SCREENS + permisos → ITNavigationItem[]
    │
    ├── features/                   # acciones del usuario
    │   └── auth/login/{model/useLogin.ts, ui/LoginForm.tsx}
    │
    ├── entities/                   # acceso a datos por dominio
    │   ├── session/                # sesión activa (store externo)
    │   ├── access/                 # hooks de permisos + bitácora
    │   ├── catalog/                # productos, ubicaciones, bodegas
    │   ├── inventory/              # existencias, kardex, ajustes
    │   ├── reception/              # facturas, captura, aprobación
    │   ├── fulfillment/            # órdenes, empaque, exportación al ERP
    │   ├── transfer/               # traslados y tránsito
    │   ├── label/                  # plantillas ZPL, preview, cola de impresión
    │   ├── report/                 # reportes y exportaciones
    │   └── health/                 # /v1/health
    │
    └── shared/                     # sin conocimiento del negocio
        ├── api/
        │   ├── client.ts               # axios + Bearer + refresh + cabeceras + ApiError con code
        │   ├── types.ts               # TODOS los DTOs del contrato
        │   ├── session.ts             # puente cliente HTTP ↔ sesión
        │   └── lib/                   # newId, timezone, QueryParams, paginación
        ├── auth/
        │   ├── api/authApi.ts         # login, refresh, logout, me, change-password
        │   ├── model/sessionStore.ts  # tokens + usuario (store externo)
        │   ├── model/permissions.ts   # catálogo de los 82 permisos por módulo
        │   ├── model/screens.ts       # APP_SCREENS: la fuente única del menú
        │   ├── model/usePermissions.ts# useCan / usePermissionScope / …
        │   └── lib/{scopes,tokenStorage}.ts
        ├── ui/                        # ThemeProvider, PageShell, PhasePlaceholder, chip de alcance
        ├── lib/                       # fechas operativas vs instantes, cantidades, debounce
        └── i18n/                      # es / en
```

Cada capa se importa **sólo por su alias** (`@entities/catalog`, `@shared/api`), y
la dirección de las dependencias la verifica ESLint:

```
app → pages → widgets → features → entities → shared
```

`shared` no importa de nadie; `entities` no conoce `features`. Una violación es un
**error** de lint, no una convención: se comprobó durante la entrega creando una
sonda (`shared` importando `pages`) y ESLint la rechazó.

---

## El cliente HTTP

`shared/api/client.ts` implementa lo que pide el contrato:

| Requisito | Dónde |
|---|---|
| `Authorization: Bearer` | interceptor de petición |
| Renovación ante `401`, **un solo reintento** | interceptor de respuesta (`_retry` por petición) |
| Una sola renovación simultánea | `refreshPromise` compartida |
| `X-Request-Id` | uuid por petición (también en el reintento: es otra petición) |
| `X-Client-Channel: WEB` | constante de `API_CONSTANTS` |
| `X-Client-Timezone` | `Intl.DateTimeFormat().resolvedOptions().timeZone` |
| `Idempotency-Key` | opción `idempotencyKey` o campo del cuerpo |
| `{ code, message, details }` → `ApiError.code` | `toApiError`; **la UI decide con `code`** |
| Descarga binaria con error legible | `downloadFile` (lee el blob de error para rescatar el `code`) |

Casos que el contrato distingue y el cliente respeta:

- **`TOKEN_REUSED`** no es un `401` cualquiera: la familia quedó revocada. Se
  descarta la sesión, **no** se llama a `/auth/logout` (el token ya no sirve) y la
  UI dice «tu sesión se cerró por seguridad».
- Un `401` sin refresh posible cierra la sesión local y **propaga el error
  original**, para que la pantalla pueda mostrar el `code` real.
- Errores sin cuerpo (red caída, 502 de nginx) se normalizan a `NETWORK_ERROR` /
  `HTTP_<status>`: la UI nunca tiene que distinguir «sin `code`» de «con `code`».

## La sesión y los permisos

El estado de sesión es un **store externo** (`sessionStore`, con
`useSyncExternalStore`) y no un slice de Redux. Motivo: el cliente HTTP necesita
leer el token **fuera de React y antes del primer render** —una recarga en
`/invoices` no puede pintar el login mientras llega `/auth/me`— y, además,
`entities` no puede importar el store de `app`.

- `GET /v1/auth/me` al arrancar reconstruye permisos y alcances sin volver a
  autenticar (una sola vez, aunque `StrictMode` monte dos veces).
- El token va a `localStorage` (no `sessionStorage`): el turno de noche recarga a
  media operación y volver a teclear credenciales en una terminal de piso es
  peor riesgo que la ventana de exposición, que el refresh rotado acota.
- `scopes` es un **mapa permiso → alcance** (`NONE < OWN < AREA < ALL`), con
  comparación por jerarquía en `shared/auth/lib/scopes.ts`.

## El menú (y por qué no está hardcodeado)

`shared/auth/model/screens.ts` (`APP_SCREENS`) es la **fuente única** del menú y
de la matriz de visibilidad. `widgets/app-shell/model/buildNavigation.ts` la
traduce a `ITNavigationItem[]` filtrando por los permisos de la sesión, y
`pages/home/HomePage.tsx` dibuja sus tarjetas **del mismo catálogo**.

En el legacy el menú estaba escrito a mano en `Layout.tsx` **y duplicado** en
`Home.tsx`, sin filtrar por permisos y con `<a href>` (recarga completa y pérdida
de sesión). Aquí agregar una pantalla es agregar una entrada al catálogo.

La protección es de **tres niveles** y el backend revalida siempre:

1. `PrivateRoutes` → ¿hay sesión?
2. `RequiresPermission` → ¿tiene el permiso de esta URL?
3. `APP_SCREENS` + `isScreenVisible` → ¿se muestra el enlace?

---

## Qué se clonó del proyecto de referencia y qué se decidió distinto

### Clonado tal cual

- Estructura FSD de seis capas y **los alias** (`@app`, `@pages`, `@widgets`,
  `@features`, `@entities`, `@shared`) en `tsconfig.json` y `vite.config.ts`.
- `eslint.config.js` con `eslint-plugin-boundaries` y su tabla de políticas.
- El patrón del cliente HTTP: `shared/api/session.ts` como puente de ganchos para
  romper el ciclo `client → store → client`, y el par `api.get/post/put/patch/delete`.
- `ITThemeProvider` como proveedor del tema y `ITLayout` + `ITTopbar` +
  `ITSidebar` (con `isCollapsed`) como chrome de la aplicación.
- `HashRouter`, la carga de `/config.js` en `index.html` y el orden de importación
  del CSS (`index.css` al final, con las capas de Tailwind declaradas por delante).
- Las versiones: React 19.0.0, Vite ^6.1.0, `@vitejs/plugin-react-swc` 3.8.0,
  TypeScript ~5.7.2, `@axzydev/axzy_ui_system` 1.3.6, tailwindcss 4.3.3,
  eslint-plugin-boundaries ^7.2.0.

### Distinto, con motivo

| Decisión | Por qué |
|---|---|
| **Sin Redux** | El único estado global es la sesión, y el cliente HTTP tiene que leerla fuera de React antes del primer render. Un store externo + `useSession` cumple eso con menos piezas. Las dependencias de Redux se retiraron para no arrastrarlas. |
| **`scopes` (mapa) en vez de `permissions` (arreglo)** | El contrato entrega `{ "invoice.approve": "AREA" }`. El plan exige distinguir alcances; un arreglo plano pierde esa información. |
| **`refreshToken` + `expiresIn`** | El contrato devuelve `accessToken`/`refreshToken` y un refresh rotado. El proyecto de referencia usa `data.token` y otro endpoint; aquí manda `docs/historico/CONTRATO_API.md §2`. |
| **`TOKEN_REUSED` como caso propio** | El contrato lo separa de `INVALID_CREDENTIALS` porque el mensaje al usuario es distinto. El cliente cancela la sesión sin llamar a `/auth/logout`. |
| **Paleta verde industrial** | El acento del proyecto de referencia es azul hotelero. Las dos aplicaciones conviven en las mismas terminales y no deben confundirse. |
| **`PageShell` y `PhasePlaceholder` en `shared/ui`** | Las 16 pantallas pendientes comparten texto y estados; tenerlos en un componente evita el defecto del legacy (menú duplicado en dos archivos que se desincronizan). |
| **`pnpm-workspace.yaml` con `allowBuilds`** | pnpm 10 bloquea los scripts de instalación; `@swc/core`, `esbuild` y `unrs-resolver` los necesitan para compilar. |
| **`SWC_NATIVE_BINDING_CACHE` en los scripts** | Sin él, `@swc/core` intenta escribir en `~/Library/Caches` y el build falla en entornos con `HOME` restringido. |
| **Sin `tsc -b` ni `Formik`/`yup`** | `tsc -b` con proyectos referenciados haría que `pnpm exec tsc --noEmit` no comprobara `src`; para el build basta `tsc --noEmit && vite build`. `Formik`/`yup` se añadirán cuando haya formularios con validación por campo, no antes. |

---

## Estado: lo que falta para cerrar la Fase 5

Las 12 rutas protegidas del legacy están todas (13 pantallas contando el login) y
las nuevas que el plan exige. **16 de ellas son marcadores**: renderizan su
título, el permiso con el alcance de la sesión y los endpoints del contrato que
las alimentarán. `HealthPage` es la única implementada de verdad, porque sirve de
ejemplo del patrón completo (llamada, estados de carga/error por `code`, versión).

Orden propuesto, de más a menos dependencia:

1. **Entidades reales con datos**: `catalog` (productos, ubicaciones) y
   `reception` (facturas). Son las que desbloquean el resto y las que ya tienen
   sus DTOs y su API escritos.
2. **Tablas paginadas de servidor** con `ITSearchTable`/`ITDataTable` sobre el
   sobre de paginación (`page`, `size`, `sort`, `q`), incluido el buscador con
   `useDebouncedValue`.
3. **Flujo A (recepción)**: captura de líneas, `difference` del servidor,
   aprobación **por factura** (R-86) y ubicación con `DISCREPANCY_NOT_APPROVED`.
4. **Flujo B (surtido)**: empaque, faltante como ajuste `FULFILLMENT_SHORTAGE`,
   confirmación y exportación al ERP con su estado visible.
5. **Inventario**: existencias, kardex append-only y ajustes con motivo
   obligatorio; bandeja de ajustes por aprobar.
6. **Etiquetas**: plantillas, preview sin imprimir y cola con estados reales
   (`PENDING → SENT → PRINTED | FAILED`).
7. **Reportes y exportación XLSX/CSV**: el cliente ya soporta la descarga
   binaria; falta la UI de filtros y el `file-saver` (misma versión que el repo de
   referencia) para materializar el archivo.
8. **i18n es/en completo**: hoy están `common` y `auth`. Faltan los namespaces por
   pantalla, con la decisión de `code` → texto ya resuelta en
   `authorizationMessageKey`.
9. **Playwright E2E** de los flujos A, B y C, más las 10 invariantes del plan.
10. **Consola de roles** (pantalla nueva del plan de permisos): el catálogo de los
    82 permisos y `APP_SCREENS` ya están tipados para alimentarla.

Deuda técnica conocida y acotada:

- El bundle es de ~609 kB (185 kB gzip) en un solo trozo; se resolverá con
  `manualChunks` cuando haya páginas reales, no antes de tener qué separar.
- El refresco de permisos al recuperar el foco de la ventana no está: hoy un
  cambio de permisos exige recargar.
- `AppProviders` muestra el aviso de sesión pero no limpia `lastErrorCode`; el
  `ref` evita repetirlo. Cuando exista el flujo de cambio de contraseña conviene
  mover ese estado al login.

## Ambigüedades del contrato detectadas

1. **Doble endpoint de aprobación.** §5 describe `POST /invoices/{id}/lines/{lineId}/approve`
   y luego `POST /invoices/{id}/approve` diciendo que la aprobación es **por
   factura, no por línea** (R-86). El cliente expone las dos, pero la UI debe
   ofrecer la de factura. Conviene marcar la de línea como obsoleta en el contrato.
2. **`PATCH /v1/prints` (impresora).** Está bajo el prefijo `/v1` pero **no** es
   `/v1/labels/...`, rompe el patrón de §1.1 y no tiene entrada en la tabla de
   permisos de §11 más allá de «credencial de servicio». La web no lo consume;
   queda anotado para el backend.
3. **`GET /v1/audit/log` con `audit.view`.** Aparece en §11 pero no está descrito
   en ninguna sección: no se documentan sus filtros ni el sobre de paginación. La
   pantalla de bitácora usa una suposición (`page`, `size`, `userId`, `action`,
   `from`, `to`, `allowed`) que hay que cerrar con el backend.
4. **Estados de línea reutilizados en órdenes.** `OrderLine.status` reutiliza los
   valores de `InvoiceLineStatus` (`ASSIGNED`, `LOCATED`, …), que son del flujo de
   recepción. Se tipó igual para no inventar, pero un `status` de línea de orden
   merece su propia unión.
5. **`kind` de ubicación.** §3 dice `SHELF` o `RECEIVING`, y el ejemplo de kardex
   muestra `toLocationDisplayName: "RECEPCION"`. No queda claro si el nombre es
   una etiqueta libre o derivada de `kind`; la web pinta `displayName` tal cual.
6. **`POST /v1/products/import` devuelve 200 y no 201** aunque cree registros con
   `dryRun=false`; §1.2 reserva `201` para «recurso creado». Se siguió el ejemplo
   del contrato.
7. **`size` máximo 200.** §1.4 lo dice para los listados, pero `/v1/warehouses` y
   `/v1/label-templates` se muestran **sin** sobre de paginación: no está claro si
   esas dos listas también están limitadas a 200 elementos.
