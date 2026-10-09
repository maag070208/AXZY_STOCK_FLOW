# Plantilla de arquitectura — `RESPONSIVA/web` (Puerto Nuevo ERP)

> **Documento de replicación.** Describe, con rutas y extractos reales, las convenciones y la arquitectura del frontend de producción ubicado en `/Users/axzy/DEV/PTNV/RESPONSIVA/web`.
> Todo lo aquí escrito fue **leído del código fuente**; cuando algo es ambiguo o inconsistente se marca explícitamente con ⚠️ **Desviación** o ❓ **No verificable**.
> **No se inventó nada.** No se instalaron dependencias ni se modificó ningún archivo del repo analizado.

---

## 0. Resumen ejecutivo del stack

| Capa | Tecnología | Fuente |
|---|---|---|
| UI | React **19.0.0** | `package.json` |
| Build | Vite **^6.1.0** + `@vitejs/plugin-react-swc` 3.8.0 | `vite.config.ts` |
| Lenguaje | TypeScript **~5.7.2**, `strict: true` | `tsconfig.app.json` |
| Estado | Redux Toolkit **^2.5.1** + `react-redux` ^9.2.0 | `src/app/store.ts` |
| Rutas | react-router-dom **^7.2.0** (`HashRouter`) | `src/app/main.tsx` |
| Design system | `@axzydev/axzy_ui_system` **1.3.6** | `package.json` |
| HTTP | axios **^1.7.9** (cliente propio) | `src/shared/api/client.ts` |
| i18n | i18next ^26 + react-i18next ^17 (es + en) | `src/shared/i18n/config.ts` |
| Realtime | ably **^2.27.0** | `src/shared/lib/ably.ts` |
| PDF | `@react-pdf/renderer` **^4.6.1** + `file-saver` | `src/shared/pdf/`, `src/widgets/*/model/pdf.ts` |
| QR | `qrcode` **^1.5.4** | `src/widgets/employee-credential/model/credential.ts` |
| Iconos | `react-icons` ^5.5.0 (Font Awesome `Fa*`) | transversal |
| CSS | Tailwind CSS **4.3.3** vía `@tailwindcss/vite` | `src/app/index.css` |
| Validación | ⚠️ `yup` ^1.6.1 está en `dependencies` **pero NO se usa** (ver §10.1) | — |
| Lint | ESLint 9 flat config + `eslint-plugin-boundaries` ^7.2.0 | `eslint.config.js` |
| E2E | `@playwright/test` ^1.63.0 | `playwright.config.ts` |
| Escritorio | Electron ^44.4.5 + electron-builder ^26.15.3 | `electron/`, `electron-builder.yml` |
| Contenedor | Docker multi-stage → nginx 1.27-alpine | `Dockerfile`, `nginx.conf` |

**Nombre del paquete:** `cartas-responsivas-web`, `productName: "Puerto Nuevo"`, versión `1.0.0`.
`main: dist-electron/main.cjs`, `type: module`.

**Scripts relevantes** (`package.json`):

```json
"dev": "vite --port 5006 --strictPort --host 0.0.0.0",
"build": "tsc -b && vite build --mode development",
"build:prod": "tsc -b && vite build --mode production",
"build:image": "vite build --mode development",   // sin typecheck (lo usa Docker)
"lint": "eslint .",
"desktop": "pnpm run desktop:build && electron .",
"desktop:dist": "pnpm run desktop:build && electron-builder --mac --win --linux",
"test": "playwright test"
```

❌ **No existen** (verificado por grep sobre todo `src/`): `createApi` / `fetchBaseQuery` / `@reduxjs/toolkit/query` (**no hay RTK Query**), `React.lazy` / `Suspense` / `lazy()` (**no hay lazy loading**), `@tanstack/*`, `zustand`, `swr`.

---

## 1. Estructura del proyecto y capas tipo Feature-Sliced Design

### 1.1 Árbol de `src/` (carpetas reales de primer nivel)

```
src/
├── app/          # bootstrap, store, router, guards, toast, CSS global
├── entities/     # 18 dominios: api + model + (a veces) ui, con barrel index.ts
├── features/     # 17 dominios / 56 slices de caso de uso: model + ui
├── pages/        # 74 páginas en 54 archivos: pages/<dominio>/<Nombre>Page.tsx
├── shared/       # api, assets, i18n, lib, pdf, ui, utils, validation
├── widgets/      # 8 widgets: composición compleja (PDF, paneles, kanban)
└── vite-env.d.ts
```

No hay carpeta `processes/` (no aplica en FSD para SPA). **No hay `layouts/`**: el layout lo provee el UI kit (`ITLayout`) desde `app/guards/PrivateRoutes.tsx`.

### 1.2 Contenido real de `src/app/`

```
src/app/App.tsx                         (614 líneas)  ← tabla de rutas
src/app/main.tsx                        ( 52 líneas)  ← createRoot + providers
src/app/store.ts                        ( 26 líneas)
src/app/index.css                       ( 19 líneas)
src/app/guards/PrivateRoutes.tsx        (245 líneas)
src/app/guards/RequirePermission.tsx    ( 30 líneas)
src/app/guards/useAblyNotifications.ts  ( 20 líneas)
src/app/toast/ToastProvider.tsx         ( 47 líneas)
src/app/toast/toast.slice.ts            ( 48 líneas)
```

### 1.3 Contenido real de `src/shared/`

```
src/shared/api/client.ts                 ← instancia axios + interceptores + verbos
src/shared/api/clientTable.ts            ← tabla client-side (filtro/orden locales)
src/shared/api/table.ts                  ← contrato server-side de ITDataTable
src/shared/api/session.ts                ← hooks inyectables de sesión (evita ciclo con app/)
src/shared/api/constants/API_CONSTANTS.ts
src/shared/api/types/TResult.ts
src/shared/assets/                       ← SVGs, JSON Lottie
src/shared/i18n/{config,index,dyn,format}.ts + i18next.d.ts + locales/{es,en}/*.json (28 ns × 2)
src/shared/lib/{ably,desktop,newId,useDebouncedValue,useIsMobile,useRequestKey}.ts
src/shared/pdf/{PdfFooter,PdfLetterhead}.tsx + theme.ts
src/shared/ui/  ← 12 componentes: charts, collapsible-card, date-picker-portal, kanban,
│                 kpi-tile, location-map, lottie-loader, panel-card, profile-avatar,
│                 section-header, stat-card, step-wizard, summary-badge
src/shared/utils/{dates,itDevice,tableFilters}.ts
src/shared/validation/{curp,email,nss,phone,postal,required,rfc,index}.ts
```

### 1.4 Sub-estructura por capa (la convención real)

| Capa | Patrón de carpeta | ¿Tiene `index.ts` (barrel)? |
|---|---|---|
| `entities` | `entities/<entidad>/{api,model,ui}/` | **Sí, siempre** (18/18). Es la **API pública obligatoria**. |
| `features` | `features/<dominio>/<caso-de-uso>/{model,ui}/` | **Normalmente sí** (44 barrels). ⚠️ Excepción: los slices de `kitchen` (`items`, `lots`, `ledger`, `count`, `overview`, `restock`, `stock-out`, `catalog`, `cost-centers-spending`) **no tienen barrel** y las páginas los importan en profundidad (`@features/kitchen/items/ui/KitchenItemsPanel`). |
| `widgets` | `widgets/<widget>/{model,ui}/` o `widgets/<grupo>/<widget>/{model,ui}` | Sí (13 barrels), salvo `widgets/tickets/kanban-ui` que sí lo tiene pero sin `ui/`. |
| `pages` | `pages/<dominio>/<Nombre>Page.tsx` | **Sin barrel**, salvo `pages/kitchen/index.tsx` (⚠️ un solo archivo con **21 funciones `Kitchen*Page`** exportadas, cada una con su propio `<ITPage>`). |
| `shared/ui` | `shared/ui/<componente>/ui/*.tsx` + `index.ts` | Sí, con `export { default as X }`. |

### 1.5 Regla de dependencia entre capas (resumen normativo)

```
app      →  app, shared, entities, features, widgets, pages   (puede TODO)
pages    →  app*, widgets, features, entities, shared
widgets  →  app*, features, entities, shared
features →  app*, entities, shared
entities →  entities, shared
shared   →  shared                                            (hoja)
```

\* `app` es accesible solo para **tipos** (`RootState`, `AppDispatch`, `AppState`). Está documentado en el comentario de `eslint.config.js` y en `src/entities/user/model/usePermission.ts:6`.

**Verificación empírica de que las reglas se respetan** (grep sobre todo `src/`):

- `src/entities/` **no** importa `@features`, `@widgets` ni `@pages` → 0 coincidencias.
- `src/entities/` **no** importa `@app` → 0 coincidencias reales (solo una mención en un comentario). Por eso `usePermission.ts` declara su propia interfaz `AuthStateLike` en lugar de importar `RootState`.
- `src/shared/` **no** importa ninguna otra capa → 0 coincidencias.
- `src/features/` **no** importa `@widgets` ni `@pages` → 0 coincidencias reales (solo un comentario en `src/features/schedule/overtime-approval/model/types.ts:5` explicando justo esa restricción).

**Dependencias cruzadas entre entidades** (permitidas, documentadas por tipo):

```ts
// src/entities/subarea/index.ts
export type { Subarea } from "@entities/department";   // reexporta tipo de otra entidad
// src/entities/time-clock/api/timeClockApi.ts
import type { AccessReportTableResponse } from "@entities/access";
// src/entities/overtime/model/types.ts
import type { OvertimePdfMeta } from "@entities/schedule";
// src/entities/permission/model/screens.ts
import type { Permission, PermissionScope, UserRole } from "@entities/user";
```

**Las únicas violaciones de barrel detectadas** (import profundo a `entities/*/model/...` desde fuera de `entities/`):

```
src/pages/inventory/NewMovementPage.tsx:8   import { TYPE_BADGE_COLOR } from "@entities/inventory/model/movementColors";
src/pages/inventory/MovementsPage.tsx:9     import { TYPE_BADGE_COLOR } from "@entities/inventory/model/movementColors";
src/pages/inventory/DashboardPage.tsx:8     import { TYPE_BADGE_HEX }   from "@entities/inventory/model/movementColors";
src/widgets/movement-pdf/ui/MovementsReportPdf.tsx:7  (idem)
```

⚠️ **Desviación real a no replicar**: `movementColors.ts` no se reexporta desde `src/entities/inventory/index.ts`.

### 1.6 Convención de naming

- Archivos de modelo/hook: `use<Algo>.ts` en `model/`, **camelCase**.
- Slices Redux: `*.slice.ts` (`auth.slice.ts`, `ticket.slice.ts`, `notification.slice.ts`, `toast.slice.ts`).
- Clientes de API: `<entidad>Api.ts` (`userApi.ts` exporta `authApi` y `usersApi`; `departmentApi.ts` exporta `departmentsApi`; `hrApi.ts` exporta `personalApi`). ⚠️ El nombre del archivo y el del objeto exportado **no siempre coinciden**.
- Páginas: `<Nombre>Page.tsx`, export `default`.
- Componentes UI de feature: `<Algo>Panel.tsx`, `<Algo>Tab.tsx`, `<Algo>Table.tsx`, `<Algo>Form.tsx`, `<Algo>Dialog.tsx`.

---

## 2. Fronteras de importación FSD

### 2.1 `eslint.config.js` — bloque de arquitectura (copiable tal cual)

```js
{
  // Arquitectura FSD (src/app, src/shared, src/entities, src/features,
  // src/widgets, src/pages). Excepción: pages/features/widgets pueden
  // importar `@app/store` (sólo tipos RootState/AppDispatch) para tipado.
  files: ["src/**/*.{ts,tsx}"],
  plugins: { boundaries },
  settings: {
    "import/resolver": {
      typescript: { project: "./tsconfig.app.json" },
    },
    "boundaries/elements": [
      { type: "app",      pattern: "app/*" },
      { type: "shared",   pattern: "shared/*" },
      { type: "entities", pattern: "entities/*" },
      { type: "features", pattern: "features/*/*" },
      { type: "widgets",  pattern: "widgets/*" },
      { type: "pages",    pattern: "pages/*" },
    ],
  },
  rules: {
    "boundaries/dependencies": [
      "error",
      {
        default: "disallow",
        policies: [
          { from: { element: { type: "app" } },
            allow: { to: { element: { types: { anyOf: ["app","shared","entities","features","widgets","pages"] } } } } },
          { from: { element: { type: "shared" } },
            allow: { to: { element: { type: "shared" } } } },
          { from: { element: { type: "entities" } },
            allow: { to: { element: { types: { anyOf: ["entities","shared"] } } } } },
          { from: { element: { type: "features" } },
            allow: { to: { element: { types: { anyOf: ["app","entities","shared"] } } } } },
          { from: { element: { type: "widgets" } },
            allow: { to: { element: { types: { anyOf: ["app","features","entities","shared"] } } } } },
          { from: { element: { type: "pages" } },
            allow: { to: { element: { types: { anyOf: ["app","widgets","features","entities","shared"] } } } } },
        ],
      },
    ],
  },
}
```

**Puntos clave del diseño:**

1. `"features": "features/*/*"` → **el elemento FSD de una feature es el slice** (`features/ticket/tickets-list`), no el dominio (`features/ticket`). Por eso el bloque de límites funciona igual con o sin barrel.
2. `default: "disallow"` → lista blanca explícita.
3. `app` se permite desde `features`/`widgets`/`pages` **por el tipado** (`import type { RootState } from "@app/store"`). No hay regla que distinga `import type` de `import` de valor.
4. El resolver TS apunta a `./tsconfig.app.json`, que es donde viven los `paths`.

### 2.2 Resto del `eslint.config.js` (3 bloques)

```js
// Bloque 0 — base TS/React
{ ignores: ["dist", "dist-electron", "release"] },
{ extends: [js.configs.recommended, ...tseslint.configs.recommended],
  files: ["**/*.{ts,tsx,cts}"],
  languageOptions: { ecmaVersion: 2020, globals: globals.browser },
  plugins: { "react-hooks": reactHooks, "react-refresh": reactRefresh },
  rules: {
    ...reactHooks.configs.recommended.rules,
    "react-refresh/only-export-components": ["warn", { allowConstantExport: true }],
    "@typescript-eslint/no-explicit-any": "off",          // ← any permitido
    "@typescript-eslint/no-unused-vars": ["warn", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
  } }

// Bloque 1 — tests/E2E y electron (corren en Node, no son React)
{ files: ["tests/**/*.ts", "playwright.config.ts", "electron/**/*.cts"],
  languageOptions: { globals: globals.node },
  rules: { "react-hooks/rules-of-hooks": "off",
           "react-refresh/only-export-components": "off",
           "no-empty-pattern": "off" } }

// Bloque 2 — boundaries (arriba)
```

### 2.3 Alias de rutas

`tsconfig.app.json`:

```json
"baseUrl": ".",
"paths": {
  "@app/*":      ["src/app/*"],
  "@shared/*":   ["src/shared/*"],
  "@entities/*": ["src/entities/*"],
  "@features/*": ["src/features/*"],
  "@widgets/*":  ["src/widgets/*"],
  "@pages/*":    ["src/pages/*"]
}
```

`vite.config.ts` (duplicado obligatorio para el bundler; **incluye además dos alias de deduplicación de React**):

```ts
resolve: {
  alias: {
    "@app":      path.resolve(__dirname, "./src/app"),
    "@shared":   path.resolve(__dirname, "./src/shared"),
    "@entities": path.resolve(__dirname, "./src/entities"),
    "@features": path.resolve(__dirname, "./src/features"),
    "@widgets":  path.resolve(__dirname, "./src/widgets"),
    "@pages":    path.resolve(__dirname, "./src/pages"),
    react:       path.resolve(__dirname, "./node_modules/react"),
    "react-dom": path.resolve(__dirname, "./node_modules/react-dom"),
  },
},
```

`vite.config.ts` completo en lo demás:

```ts
export default defineConfig({
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  plugins: [tailwindcss(), react()],
  build: {
    modulePreload: false,
    target: "esnext",
    minify: false,          // ← a propósito: sin minificar
    cssCodeSplit: false,    // ← un solo CSS
  },
  resolve: { ... },
});
```

⚠️ Nota: **no hay `server.proxy`** para `/api`. En desarrollo el front apunta directo a `VITE_API_URL`; en producción nginx hace de proxy (§11).

`tsconfig.json` es solo un proyecto de referencias:

```json
{ "files": [], "references": [{ "path": "./tsconfig.app.json" }, { "path": "./tsconfig.node.json" }] }
```

---

## 3. Patrón de data fetching

### 3.1 Cliente compartido: `src/shared/api/client.ts`

Es **el único** punto de acceso HTTP. Resumen fiel de su contenido:

1. **Clase `ApiError`** propia (no se usa `AxiosError` hacia arriba):

```ts
class ApiError extends Error {
  constructor(public status: number, msg: string, public details?: unknown, code?: string) {
    super(msg); this.name = "ApiError"; this.code = code;
  }
}
```

2. **Instancia axios** con constantes externalizadas:

```ts
const axiosInstance = axios.create({
  baseURL: API_CONSTANTS.BASE_URL,
  timeout: API_CONSTANTS.TIMEOUT,          // 30000
  headers: API_CONSTANTS.HEADERS,          // { "Content-Type": "application/json" }
});
```

3. **Interceptor de request** — token + idioma:

```ts
axiosInstance.interceptors.request.use(async (config: InternalAxiosRequestConfig) => {
  const token = getSessionToken();
  if (token) config.headers.Authorization = `Bearer ${token}`;
  config.headers["Accept-Language"] = i18n.language;   // los mensajes del API vienen traducidos
  return config;
}, (error: AxiosError) => Promise.reject(error));
```

4. **Interceptor de response** — refresh single-flight + logout selectivo:

```ts
let refreshPromise: Promise<string | null> | null = null;

const refreshAccessToken = async (): Promise<string | null> => {
  const refreshToken = getSessionRefreshToken();
  if (!refreshToken) return null;
  if (!refreshPromise) {
    refreshPromise = (async () => {
      try {
        const { data } = await axios.post(`${API_CONSTANTS.BASE_URL}/auth/refresh`, { refreshToken });
        updateSessionTokens({ token: data.token, refreshToken: data.refreshToken });
        return data.token as string;
      } catch { return null; } finally { refreshPromise = null; }
    })();
  }
  return refreshPromise;
};

axiosInstance.interceptors.response.use(
  (r: AxiosResponse) => r,
  async (error: AxiosError) => {
    const status = error.response?.status;
    const code = (error.response?.data as { code?: string } | undefined)?.code;
    const original = error.config as RetriableConfig | undefined;

    if (status === 401 && code !== "ACCOUNT_DEACTIVATED" && original && !original._retry
        && !original.url?.includes("/auth/refresh")) {
      original._retry = true;
      const token = await refreshAccessToken();
      if (token) { original.headers.Authorization = `Bearer ${token}`; return axiosInstance(original); }
    }
    if (status === 401) {
      if (code === "ACCOUNT_DEACTIVATED") return Promise.reject(error); // la UI debe mostrar el motivo
      handleUnauthorized();                                            // logout
    }
    return Promise.reject(error);
  }
);
```

5. **Normalizador central de errores** (contrato backend `{ error, message, details }`):

```ts
const handleError = (error: any): never => {
  if (error instanceof ApiError) throw error;
  const data = error?.response?.data as { message?: string; details?: unknown; code?: string } | undefined;
  const status = error?.response?.status ?? 0;
  const message = data?.message ?? error?.message ?? i18n.t("common:errors.network");
  throw new ApiError(status, message, data, data?.code);
};
```

6. **Verbos tipados** que devuelven `response.data` crudo y **objeto `api` de compatibilidad**:

```ts
export const get    = async <T>(url, config?): Promise<T> => { ... };
export const post   = async <T>(url, data?, config?): Promise<T> => { ... };
export const patch  = async <T>(url, data?, config?): Promise<T> => { ... };
export const put    = async <T>(url, data?, config?): Promise<T> => { ... };
export const remove = async <T>(url, config?): Promise<T> => { ... };

/** API wrapper (compat con módulos existentes) */
export const api = {
  get:  <T>(path, config?) => get<T>(path, config),
  post: <T>(path, body?, config?) => post<T>(path, body, config),
  put:  <T>(path, body?, config?) => put<T>(path, body, config),
  patch:<T>(path, body?, config?) => patch<T>(path, body, config),
  delete:<T>(path, config?) => remove<T>(path, config),
};
export { axiosInstance };
```

**Todas las entidades usan `api.*` (el wrapper).** No hay `axios` importado directamente en `entities/`.

### 3.2 Desacoplamiento de la sesión: `src/shared/api/session.ts`

Problema que resuelve: el cliente axios (capa `shared`) necesita el token que vive en Redux (capa `entities`, dentro de `auth`), pero `shared` **no puede** importar `entities`. Solución: **inyección de hooks** desde `app/store.ts`.

```ts
// src/shared/api/session.ts
let getToken: () => string | null = () => null;
let getRefreshToken: () => string | null = () => null;
let setTokens: (tokens: { token: string; refreshToken?: string }) => void = () => {};
let onUnauthorized: () => void = () => {};

export const setSessionHooks = (
  tokenGetter: () => string | null,
  unauthorizedHandler: () => void,
  refreshTokenGetter?: () => string | null,
  tokensSetter?: (tokens: { token: string; refreshToken?: string }) => void
) => { getToken = tokenGetter; onUnauthorized = unauthorizedHandler;
       if (refreshTokenGetter) getRefreshToken = refreshTokenGetter;
       if (tokensSetter) setTokens = tokensSetter; };

export const getSessionToken = () => getToken();
export const getSessionRefreshToken = () => getRefreshToken();
export const updateSessionTokens = (t) => setTokens(t);
export const handleUnauthorized = () => onUnauthorized();
```

Cableado en `src/app/store.ts`:

```ts
setSessionHooks(
  () => store.getState().auth.token,
  () => store.dispatch(logout()),
  () => store.getState().auth.refreshToken,
  (tokens) => store.dispatch(setTokens(tokens))
);
```

👉 **Este es el patrón más importante a clonar**: rompe el ciclo `shared → entities` manteniendo un único store como fuente de verdad.

### 3.3 Configuración en runtime: `src/shared/api/constants/API_CONSTANTS.ts`

```ts
type RuntimeConfig = { API_URL?: string };
declare global { interface Window { __APP_CONFIG__?: RuntimeConfig } }

// Prioridad: 1) config en runtime (nginx/config.js desde AXZY_PTNV_SERVERS/.env),
//            2) VITE_API_URL (build/dev), 3) por defecto ruta relativa del proxy.
const BASE_URL =
  window.__APP_CONFIG__?.API_URL ||
  ((import.meta as any).env?.VITE_API_URL as string | undefined) ||
  "/api/v1";

export const API_CONSTANTS = {
  BASE_URL,
  TIMEOUT: 30000,
  HEADERS: { "Content-Type": "application/json" },
} as const;
```

En `index.html` se carga antes del bundle:

```html
<script src="/config.js"></script>
```

y en el contenedor `render-config.sh` lo genera:

```sh
#!/bin/sh
set -e
: "${WEB_API_URL:=/api/v1}"
printf 'window.__APP_CONFIG__ = { API_URL: "%s" };\n' "$WEB_API_URL" > /usr/share/nginx/html/config.js
```

### 3.4 Contrato de tablas server-side: `src/shared/api/table.ts`

El frontend envía `{ page, limit, filters, sort }` y el backend responde `{ data, total }`.

```ts
export interface ITDataTableFetchParamsPost {
  page: number; limit: number; filters: ColumnFilters;
  sort?: { key: string; direction: "asc" | "desc" };
}
export interface ITDataTableResponse<T> { data: T[]; total: number }

type TableFilterValue = string | number | boolean | [string | null, string | null];
```

Serialización de fechas **en ISO con zona local** (no UTC), con un `localIso()` propio:

```ts
const localIso = (d: Date) => { /* "2026-09-19T00:00:00.000-07:00" */ };

/** Una fecha es el día local completo; un rango va de inicio del primer día a fin del último. */
export const serializeTableFilters = (filters: ColumnFilters): Record<string, TableFilterValue> => {
  const out: Record<string, TableFilterValue> = {};
  for (const [key, value] of Object.entries(filters)) {
    if (value instanceof Date)        out[key] = [startOfDay(value), endOfDay(value)];
    else if (Array.isArray(value)) { const [from, to] = value;
      if (from || to) out[key] = [from ? startOfDay(from) : null, to ? endOfDay(to) : null]; }
    else out[key] = value;
  }
  return out;
};

export const tableQuery   = <R>(path, params) => post<R>(path, { ...params, filters: serializeTableFilters(params.filters) });
export const tableRequest = <T>(path, params) => tableQuery<ITDataTableResponse<T>>(path, params);
```

Uso típico en una entidad: `table: (params) => tableRequest<User>(`/users/query`, params)` → **POST `/entidad/query`**, nunca GET para tablas.

### 3.5 Tablas client-side: `src/shared/api/clientTable.ts`

Para datasets chicos que ya están completos en memoria. Expone:

- `ClientField<T>` = `{ value?, match?: "text"|"equals"|"date", sortValue? }`
- `applyClientTableParams(rows, params, fields)` → aplica filtros de barra/columna + orden vigente.
- `makeClientTableFetch(fetcher, fields)` → adapta un fetcher de arreglo completo a `fetchData` de `ITDataTable` con paginación local.

Se reutiliza en exports PDF/CSV **para respetar exactamente lo que el usuario ve en la tabla** (comentario textual del archivo).

### 3.6 Forma canónica de un `entities/*/api/*Api.ts`

```ts
import { api } from "@shared/api/client";
import { tableRequest, type ITDataTableFetchParamsPost } from "@shared/api/table";
import type { User, LoginResponse, AuthMe } from "../model/types";   // ← import relativo al model

export const authApi = {
  login: (username: string, password: string) =>
    api.post<LoginResponse>(`/auth/login`, { username, password }),
  me: () => api.get<AuthMe>(`/auth/me`),
};

export const usersApi = {
  table: (params: ITDataTableFetchParamsPost) => tableRequest<User>(`/users/query`, params),
  list: (role?: UserRole) => { const qs = role ? `?role=${role}` : ""; return api.get<User[]>(`/users${qs}`); },
  get: (id: string) => api.get<User>(`/users/${id}`),
  create: (data: {...}) => api.post<User>(`/users`, data),
  update: (id, data) => api.put<User>(`/users/${id}`, data),
  delete: (id, force?) => api.delete<{ soft: boolean; forced?: boolean; data: User }>(`/users/${id}${force ? "?force=true" : ""}`),
  import: (file: File) => { const form = new FormData(); form.append("file", file);
    return api.post<{created:number; skipped:{row:number;username:string;reason:string}[]}>(
      `/users/import`, form, { headers: { "Content-Type": "multipart/form-data" } }); },
  downloadAttachment: (id, attachmentId) =>
    api.get<Blob>(`/tickets/${id}/attachments/${attachmentId}/download`, { responseType: "blob" }),
};
```

**Convenciones derivadas:**

- Cada API es un **objeto literal de funciones arrow**, no una clase ni una instancia axios.
- `encodeURIComponent` para claves con punto: `api.put<UserPermissionView[]>(`/users/${id}/permissions/${encodeURIComponent(permission)}`, body)`.
- Query strings se construyen con `URLSearchParams` cuando hay más de 1 parámetro.
- Uploads: `FormData` + `headers: { "Content-Type": "multipart/form-data" }`.
- Descargas: `{ responseType: "blob" }`.

---

## 4. Redux Toolkit

### 4.1 `src/app/store.ts` (completo)

```ts
import { configureStore } from "@reduxjs/toolkit";
import authReducer, { logout, setTokens } from "@entities/user";
import ticketsReducer from "@entities/ticket";
import notificationsReducer from "@entities/notification";
import toastReducer from "@app/toast/toast.slice";
import { setSessionHooks } from "@shared/api/session";

export const store = configureStore({
  reducer: {
    auth: authReducer,
    tickets: ticketsReducer,
    notifications: notificationsReducer,
    toast: toastReducer,
  },
});

setSessionHooks(
  () => store.getState().auth.token,
  () => store.dispatch(logout()),
  () => store.getState().auth.refreshToken,
  (tokens) => store.dispatch(setTokens(tokens))
);

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;
/** Alias compat con providers/componentes que esperan `AppState` como nombre. */
export type AppState = ReturnType<typeof store.getState>;
```

**Solo 4 slices globales.** Todo lo demás es estado local (`useState`) dentro de los hooks de feature.

### 4.2 Inventario completo de slices

| Slice | Archivo | ¿En el store? |
|---|---|---|
| `auth` | `src/entities/user/model/auth.slice.ts` | ✅ |
| `tickets` | `src/entities/ticket/model/ticket.slice.ts` | ✅ |
| `notifications` | `src/entities/notification/model/notification.slice.ts` | ✅ |
| `toast` | `src/app/toast/toast.slice.ts` | ✅ (vive en `app`, no en `entities`) |

Verificado con `grep -rln createSlice src/` → exactamente esos 4 archivos.

### 4.3 ¿RTK Query o thunks? — **Thunks, siempre**

No existe ni una sola llamada a `createApi`, `fetchBaseQuery` ni al entrypoint `@reduxjs/toolkit/query` en todo `src/`. Todas las llamadas al backend son:

1. **Directas desde hooks de feature** (`useState`/`useEffect` + `api.*`) — el patrón **mayoritario**.
2. **`createAsyncThunk`** — solo para el estado verdaderamente global (auth, tickets, notificaciones).

### 4.4 Entidad representativa end-to-end: `ticket`

#### (a) `api` → `src/entities/ticket/api/ticketApi.ts`

```ts
export const ticketsApi = {
  table:  (params: ITDataTableFetchParamsPost) => tableRequest<Ticket>(`/tickets/query`, params),
  list:   (search?: string) => api.get<{ data: Ticket[]; total: number }>(`/tickets${search ? `?q=${encodeURIComponent(search)}` : ""}`),
  get:    (id: string) => api.get<Ticket>(`/tickets/${id}`),
  create: (input: TicketInput) => api.post<Ticket>(`/tickets`, input),
  update: (id, data) => api.put<Ticket>(`/tickets/${id}`, data),
  remove: (id: string) => api.delete<{ soft: boolean; data: Ticket }>(`/tickets/${id}`),
  categories: (includeInactive?) => api.get<TicketCategory[]>(`/tickets/categories${includeInactive ? "?includeInactive=true" : ""}`),
  addComment: (id, text) => api.post<TicketComment>(`/tickets/${id}/comments`, { text }),
  addAssignment: (id, data) => api.post<TicketAssignment>(`/tickets/${id}/assignments`, data),
  // ... attachments, kanban, assignments, filterOptions
};
```

#### (b) `model/types.ts` → interfaces puras, sin clases ni zod

```ts
export interface Ticket {
  id: string; title: string; description: string;
  status: "OPEN" | "IN_PROGRESS" | "CLOSED";
  priority: "LOW" | "MEDIUM" | "HIGH" | "URGENT";
  categoryId?: string | null; category?: TicketCategory | null;
  createdBy: { id: string; name: string; username: string; jobTitle?: string };
  assignments: TicketAssignment[]; comments: TicketComment[]; history: TicketHistoryEntry[];
  createdAt: string; updatedAt: string;
}
/** Valor del filtro "Asignado a" para los tickets sin responsable (igual que la API). */
export const UNASSIGNED = "UNASSIGNED";
```

**Se exportan también constantes de dominio desde `model/`** (`labels.ts`, `waitRating.ts`, `UNASSIGNED`).

#### (c) `model/ticket.slice.ts` → `createAsyncThunk` + `createSlice`

```ts
export interface TicketsState { list: Ticket[]; current: Ticket | null; loading: boolean; error: string | null; }

export const fetchTickets       = createAsyncThunk<Ticket[], string | undefined>("tickets/fetch",  async (search) => (await ticketsApi.list(search)).data);
export const fetchTicketById    = createAsyncThunk<Ticket, string>("tickets/fetchOne", async (id) => ticketsApi.get(id));
export const createTicketThunk  = createAsyncThunk<Ticket, TicketInput>("tickets/create", async (input) => ticketsApi.create(input));
export const updateTicketThunk  = createAsyncThunk<Ticket, { id: string; data: {...} }>("tickets/update", async ({ id, data }) => ticketsApi.update(id, data));
export const addCommentThunk    = createAsyncThunk<{ticketId:string;comment:any}, {ticketId:string;text:string}>("tickets/addComment", async ({ ticketId, text }) => ({ ticketId, comment: await ticketsApi.addComment(ticketId, text) }));
export const deleteTicketThunk  = createAsyncThunk<string, string>("tickets/delete", async (id) => { await ticketsApi.remove(id); return id; });

const slice = createSlice({
  name: "tickets",
  initialState,
  reducers: {
    clearCurrent(state) { state.current = null; },
    clearError(state)   { state.error = null; },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchTickets.pending,   (state) => { state.loading = true; state.error = null; })
      .addCase(fetchTickets.fulfilled, (state, action) => { state.loading = false; state.list = action.payload; })
      .addCase(fetchTickets.rejected,  (state, action) => {
        state.loading = false;
        state.error = action.error.message ?? i18n.t("tickets:list.loadError");   // ← mensaje i18n
      })
      .addCase(updateTicketThunk.fulfilled, (state, action) => {
        const updated = action.payload;
        const idx = state.list.findIndex((t) => t.id === updated.id);
        if (idx >= 0) state.list[idx] = updated;
        if (state.current?.id === updated.id) state.current = updated;
      });
  },
});

export const { clearCurrent, clearError } = slice.actions;
export default slice.reducer;
```

**Naming de `createAsyncThunk`:** `"<slice>/<accion>"` con la accion en inglés: `tickets/fetch`, `tickets/fetchOne`, `tickets/create`, `tickets/update`, `tickets/addComment`, `tickets/delete`.

#### (d) `index.ts` → barrel de la entidad

```ts
// API pública del slice "ticket". Nada fuera de esta carpeta debe importar
// directo desde model/ o api/ — todo pasa por este barrel.
export * from "./model/types";
export * from "./model/labels";
export * from "./model/waitRating";
export * from "./model/ticket.slice";
export { default } from "./model/ticket.slice";   // ← el reducer como default
export { ticketsApi } from "./api/ticketApi";
```

Ese comentario de cabecera (`// API pública del slice "<x>". Nada fuera de esta carpeta debe importar directo desde model/ o api/ — todo pasa por este barrel.`) aparece **de forma casi literal en 15 de los 18 barrels** de entidades. Es la convención explícita.

#### (e) Consumo desde la feature y la página

`src/features/ticket/tickets-list/model/useTicketsList.ts` (hook de caso de uso) → `src/features/ticket/tickets-list/ui/TicketsTable.tsx` (UI) → `src/pages/tickets/TicketsListPage.tsx`:

```tsx
// src/pages/access/TimeClockEmployeesPage.tsx — plantilla canónica de página
import { ITPage } from "@axzydev/axzy_ui_system";
import { FaLink } from "react-icons/fa";
import { useNavigate } from "react-router-dom";
import { useTranslation } from "react-i18next";
import { TimeClockEmployeesTab, useTimeClockEmployees } from "@features/access/time-clock-employees";

export default function TimeClockEmployeesPage() {
  const { t } = useTranslation(["time-clock", "common"]);
  const navigate = useNavigate();
  const fx = useTimeClockEmployees();          // ← el hook vive en la FEATURE, no en la página

  return (
    <ITPage
      noPadding
      title={t("employees.title")}
      description={t("employees.description")}
      icon={<FaLink size={20} />}
      breadcrumbs={[
        { label: t("common:breadcrumbs.home"), onClick: () => navigate("/") },
        { label: t("common:nav.hr"), onClick: () => navigate("/employees") },
        { label: t("employees.title") },
      ]}
      backAction={() => navigate("/hr/time-clock")}
    >
      <TimeClockEmployeesTab fx={fx} />
    </ITPage>
  );
}
```

**El contrato es literal:** la feature exporta un **hook** y un **componente raíz**; la página llama al hook y pasa el objeto completo como la prop `fx`. El tipo se exporta con `export type UseX = ReturnType<typeof useX>;`.

```ts
// src/features/access/time-clock-employees/index.ts
export { useTimeClockEmployees, type UseTimeClockEmployees } from "./model/useTimeClockEmployees";
export { default as TimeClockEmployeesTab } from "./ui/TimeClockEmployeesTab";
export { default as LinksSummary } from "./ui/LinksSummary";
```

#### (f) Variante del hook de feature (con `useState` local + llamadas directas a la API)

```ts
export const useTimeClockEmployees = () => {
  const { t } = useTranslation(["time-clock", "common"]);
  const canLink = useCan("time_clock.link");                 // ← permiso desde la entidad user
  const [q, setQ] = useState("");
  const [reloadKey, setReloadKey] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [toast, setToast] = useState<string | null>(null);   // ← toast LOCAL, no Redux

  const externalFilters = useMemo(() => {
    const filters: Record<string, string | number | boolean> = {};
    if (q.trim()) filters.q = q.trim();
    if (status) filters.status = status;
    return filters;
  }, [q, status]);

  const fetchTableData = useCallback(async (params: ITDataTableFetchParams) => {
    const res = await timeClockApi.employees({ page: params.page, limit: params.limit,
                                               filters: params.filters, sort: params.sort });
    setSummary(res.summary);
    return { data: res.data as unknown as Record<string, unknown>[], total: res.total };
  }, []);

  // ... save/openLink/acceptSuggestion/unlinkEmployee/linkSuggested

  return { t, canLink, q, setQ, status, setStatus, externalFilters, fetchTableData,
           reloadKey, summary, users, target, setTarget, userId, setUserId, saving,
           openLink, confirm, acceptSuggestion, unlinkEmployee, linkSuggested,
           error, setError, toast, setToast };
};
export type UseTimeClockEmployees = ReturnType<typeof useTimeClockEmployees>;
```

**Patrón de refresco de tabla:** `reloadKey` numérico que se incrementa (`setReloadKey(k => k + 1)`) y se pasa a `ITDataTable` como `reloadTrigger`.

---

## 5. Routing, guards, lazy loading y composición de layout

### 5.1 Bootstrap (`src/app/main.tsx`, completo)

```tsx
import { ITThemePalette, ITThemeProvider } from "@axzydev/axzy_ui_system";
import "@axzydev/axzy_ui_system/dist/index.css";
import "@shared/i18n/config";
import { store } from "@app/store";
import ToastProvider from "@app/toast/ToastProvider";
import React from "react";
import { createRoot } from "react-dom/client";
import { Provider } from "react-redux";
import { HashRouter } from "react-router-dom";
import App from "./App";
import "@app/index.css";

if (!localStorage.getItem("it-theme-dark-mode")) {
  localStorage.setItem("it-theme-dark-mode", "light");
}

const customTheme: ITThemePalette = {
  primary: "#0D5777", secondary: "#1A7499", ternary: "#F0F4F7",
  alert: "#F9C74F", warning: "#F9C74F", danger: "#BA1A1A",
  info: "#512bbb", success: "#4ADE80",
  layout: { sidebarBg: "#ffffff", sidebarText: "#54634d", navbarBg: "#0D5777", navbarText: "#ffffff" },
  table: { headerBg: "#8ab1cf9d", headerText: "#0D5777", rowBg: "#ffffff",
           rowText: "#1B1B1F", rowHover: "#0d5777c4" },
};

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <Provider store={store}>
      <ITThemeProvider theme={customTheme} showFab={false} density={1}>
        <ToastProvider>
          <HashRouter>
            <App />
          </HashRouter>
        </ToastProvider>
      </ITThemeProvider>
    </Provider>
  </React.StrictMode>
);
```

**Orden de providers (importa):** `StrictMode` → `Provider` (Redux) → `ITThemeProvider` → `ToastProvider` → `HashRouter` → `App`.
**Se usa `HashRouter`** (no `BrowserRouter`) porque el build también corre dentro de Electron con protocolo propio `app://ptnv`.

### 5.2 Tabla de rutas (`src/app/App.tsx`, 614 líneas)

- `App` es **una sola función** que devuelve `<Routes>` — sin `useMemo`, sin configuración declarativa en array.
- Todas las páginas se importan **estáticamente arriba** (≈65 imports).
- ❌ **No hay `React.lazy`, ni `Suspense`, ni `lazy()`** en ninguna parte. El code-splitting es deliberadamente nulo (`cssCodeSplit: false`, `moduleCachePreload: false`).

Estructura:

```tsx
export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />

      <Route element={<PrivateRoutes />}>            {/* ← layout route sin path */}
        <Route path="/" element={<HomePage />} />

        <Route path="/inventory" element={
          <RequiresPermission permission="devices.view"><DashboardPage /></RequiresPermission>
        } />
        {/* ...~80 rutas más... */}
      </Route>

      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  );
}
```

**Rutas sin `RequiresPermission` en `App.tsx`** (el gate es el propio endpoint): `/`, `/tickets`, `/tickets/kanban`, `/tickets/my-tasks`, `/tickets/new`, `/tickets/:id`, `/departments`, `/departments/:id`, `/subareas`, `/users`, `/users/new`, `/users/:id/edit`, `/users/:id/history`, `/users/import`, `/notifications`. En el caso de `/users*`, el gate real es el menú (§7) y la API.

**Curiosidad documentada en el código:** `/users/:id/edit` renderiza `UsersListPage` (la edición es un diálogo sobre la lista; la ruta se conserva para enlaces directos).

**Sombra de rutas (orden importa):** `/inventory/devices/import` y `/inventory/devices/new` están declaradas **antes** de `/inventory/devices/:id`; `/tickets/tasks` y `/tickets/my-tasks` antes de `/tickets/:id`.

### 5.3 `PrivateRoutes` (`src/app/guards/PrivateRoutes.tsx`, 245 líneas)

Es **layout route + guard + orquestador de sesión + constructor del menú**. Responsabilidades:

1. **Gate de token:**
```tsx
if (!token) return <Navigate to="/login" replace state={{ from: location }} />;
```

2. **Rehidratación de sesión** (3 efectos):
   - `if (token) dispatch(meThunk())` al montar y cada vez que cambia el token.
   - `window.addEventListener("focus", refreshSession)` + `document.visibilitychange` → vuelve a llamar `meThunk()` para que cambios de permisos apliquen **sin relogin**.
   - `dispatch(fetchUnreadCount())`.
3. **Catálogo dinámico de roles:** llama `permissionApi.listRoles()` y lo mete en el store en memoria con `setRolesCatalog(...)`, con `window.addEventListener("focus", loadRoles)`.
4. **Idioma:** `if (user?.language && user.language !== i18n.language) void i18n.changeLanguage(user.language)`.
5. **Realtime Ably:** `useAblyNotifications(user?.id, handleNewNotification)`.
6. **Notificaciones nativas vs web:**
```tsx
const handleNewNotification = useCallback((n: LiveNotification) => {
  const title = n.title ?? i18n.t("notifications:newNotification");
  if (desktop) { desktop.notify({ id: n.id, title, body: n.detail ?? undefined,
                                  route: notificationRoute(n) ?? "/notifications" }); return; }
  setToast(title); setTimeout(() => setToast(null), 3000);
}, [i18n]);

useEffect(() => desktop?.onNotificationClick(({ id, route }) => {
  if (id) void dispatch(markNotificationRead(id));
  if (route) navigate(route);
}), [dispatch, navigate]);
```
7. **Construcción del menú desde el catálogo de pantallas** (§7) con iconos mapeados por id:
```tsx
const NAV_ICONS: Record<string, ReactNode> = {
  start: <FaHouseUser size={14} />, tasks: <FaTicketAlt size={14} />,
  inventory: <FaBoxes size={14} />, kitchen: <FaUtensils size={14} />,
  hr: <FaUserTie size={14} />, settings: <FaCog size={14} />,
};
```
   - `matchesPath(path, match, excludes)` resuelve el resaltado (`exact` vs `prefix` por defecto) y las exclusiones.
   - Un hijo con `children` se convierte en **grupo con título** de `ITSidebar` (el sidebar no tiene tercer nivel).
8. **Render del layout:**
```tsx
return (
  <>
    <ITLayout topBar={topBar} sidebar={sidebar} contentClassName="max-w-screen! m-0! !px-2">
      <Outlet />
    </ITLayout>
    {toast && <ITToast message={toast} type="info" position="top-right" duration={3000} onClose={() => setToast(null)} />}
  </>
);
```

Configuración del chrome:

```tsx
const sidebar: ITSidebarProps = { navigationItems, isCollapsed: true };

const topBar = {
  logo: <img src={logoUrl} alt="Puerto Nuevo Hotel y Villas" className="h-12 w-auto object-contain" />,
  logoText: "Puerto Nuevo",
  userMenu: user ? {
    userName: user.name ?? "—",
    userEmail: user.username,
    menuItems: [
      { label: unreadCount > 0 ? tt("nav.notifications", { count: unreadCount }) : tt("nav.notifications"),
        onClick: () => navigate("/notifications") },
      { label: tt("nav.logout"), onClick: handleLogout },
    ],
  } : undefined,
};

const handleLogout = () => { dispatch(logout()); navigate("/login"); };
```

⚠️ El logo trae texto hardcodeado (`"Puerto Nuevo"`, `logoText`) — no pasa por i18n.

### 5.4 `RequirePermission` (`src/app/guards/RequirePermission.tsx`, completo y comentado)

```tsx
interface Props {
  /** Permiso que debe tener la sesión (con cualquier alcance). */
  permission: Permission;
  children: ReactNode;
}

/**
 * Gate de permiso a nivel de ruta. `PrivateRoutes` ya garantiza que hay sesión;
 * aquí se bloquea el acceso directo por URL a secciones no autorizadas. Mientras
 * el usuario no cargue (`meThunk` en vuelo) no se decide nada para no redirigir
 * en falso.
 */
export default function RequiresPermission({ permission, children }: Props) {
  const user = useSelector((s: RootState) => s.auth.user);
  const scope = usePermission(permission);

  if (!user) return null;
  // Al recargar la página, el usuario persistido puede venir sin permisos hasta
  // que responde `meThunk`: no se redirige en falso (se espera a que carguen).
  if (user.permissions === undefined) return null;
  if (scope === "NONE") return <Navigate to="/" replace />;

  return <>{children}</>;
}
```

⚠️ **`RequirePermission.tsx` exporta `RequiresPermission` (plural) como default**, mientras `App.tsx` lo importa como `RequiresPermission`. El nombre del archivo (singular) y el del componente (plural) difieren: no copiar ese detalle tal cual.

**La regla de tres niveles de protección** (para replicar):
1. `PrivateRoutes` → ¿hay sesión?
2. `RequiresPermission` → ¿el usuario tiene el permiso para ver esta URL?
3. Menú (`APP_SCREENS` + `isScreenVisible`) → ¿se muestra el enlace?
...y siempre, el backend revalida (comentarios explícitos: *"la API lo revalida"*, *"Mismo permiso que PUT/DELETE /checador/empleados en la API"*).

---

## 6. Uso del UI kit `@axzydev/axzy_ui_system`

### 6.1 Cómo se importa y configura

```tsx
// src/app/main.tsx
import { ITThemePalette, ITThemeProvider } from "@axzydev/axzy_ui_system";
import "@axzydev/axzy_ui_system/dist/index.css";
```

· Los **componentes** se importan del barrel raíz del paquete: `from "@axzydev/axzy_ui_system"`.
· Los **tipos** van en un `import type { ... }` separado y con el mismo especificador.
· El **CSS del kit** se importa una sola vez, desde `main.tsx` (no desde `index.css`), y en `index.css` se le asigna capa (§8).

Componentes más usados (conteo real de importaciones sobre `src/`):

| Componente | Usos |
|---|---|
| `ITFlex` | 87 |
| `ITText` | 82 |
| `ITButton` | 67 |
| `ITPage` | 39 |
| `ITBadget` *(sic, con "t")* | 38 |
| `ITAlert` | 33 |
| `ITInput` | 25 |
| `ITGrid` | 25 |
| `ITDialog` | 14 |
| `ITToast` | 13 |
| `ITCard` | 10 |
| `ITSearchSelect` | 9 |
| `ITSelect` | 8 |
| `ITStack` | 7 |
| `ITTextarea` / `ITCheckbox` | 6 / 6 |
| `ITConfirmDialog` | 4 |
| `ITSegmentedControl` | 3 |
| `ITDatePicker` | 3 |
| `ITSlideToggle` / `ITInputNumber` / `ITDropdownMenu` | 2 c/u |
| `ITTabs`, `ITStepper`, `ITLayout`, `ITSidebarProps`, `ITThemeProvider`, `ITDropfile`, `ITDivider` | 1 c/u |

Tipos del kit usados: `Column<T>`, `ITDataTableFetchParams`, `ITDataTableResponse<T>`, `ColumnFilters`, `ColumnFilterValue`, `FileTypeEnum`, `ITNavigationItem`.

`ITDataTable` se usa en **70 archivos**; `ITSearchSelect` en **28**.

### 6.2 Snippet real #1 — `ITSearchSelect` + `ITDataTable` server-side con KPIs

`src/features/access/time-clock-employees/ui/TimeClockEmployeesTab.tsx` (345 líneas). Extractos:

**Filtros de barra con `ITSearchSelect` + `ITGrid`:**

```tsx
<ITCard className="!p-5 border border-slate-200">
  <ITGrid container columns={12} spacing={4}>
    <ITGrid item xs={12} md={5}>
      <ITInput name="checadorEmpleadosQ" label={t("employees.filters.q")}
        placeholder={t("employees.filters.qPlaceholder")}
        value={q} onChange={(e) => setQ(e.target.value)} className="w-full min-w-0" />
    </ITGrid>
    <ITGrid item xs={12} md={4}>
      <ITSearchSelect
        name="checadorEmpleadosEstado"
        label={t("employees.filters.status")}
        options={statusOptions}                       // [{ value, label }]
        value={status}
        onChange={(value) => setStatus(String(value) as TimeClockEmployeeStatus | "")}
        className="w-full min-w-0"
      />
    </ITGrid>
  </ITGrid>
</ITCard>
```

Las opciones se memoizan y se construyen con `t()`:

```tsx
const statusOptions = useMemo(() => [
  { value: "", label: t("employees.filters.all") },
  ...STATUSES.map((e) => ({ value: e, label: t(`employees.statuses.${e}`) })),
], [t]);

const userOptions = useMemo(() => users.map((u) => ({
  value: u.id, label: u.employeeNumber ? `${u.name} · #${u.employeeNumber}` : u.name,
})), [users]);
```

**Definición de columnas con `useMemo<Column<T>[]>`:**

```tsx
const columns = useMemo<Column<TimeClockEmployee>[]>(() => [
  { key: "employeeNumber", label: t("employees.columns.number"), type: "string",
    width: 160, filter: true, sortable: false,
    render: (r) => <ITText className="text-[12px] font-black text-slate-800 whitespace-nowrap">#{r.employeeNumber}</ITText> },

  { key: "link", label: t("employees.columns.user"), type: "catalog",
    width: 260, filter: "search", catalogOptions: peopleOptions, sortable: false,
    render: (r) => { /* badge / sugerencia / "sin vínculo" */ } },

  ...(canLink ? [{
    key: "actions", label: t("employees.columns.actions"), type: "actions" as const, width: 260,
    actions: (r: TimeClockEmployee) => (
      <ITFlex align="center" gap={1}>
        <ITButton variant="filled" color="primary" size="sm" onClick={() => acceptSuggestion(r)}>...</ITButton>
        <ITButton variant="outlined" color="secondary" size="sm" onClick={() => openLink(r)}>...</ITButton>
        {r.link && <ITButton variant="text" color="danger" size="sm" onClick={() => void unlinkEmployee(r)}>...</ITButton>}
      </ITFlex>
    ),
  }] : []),
], [t, canLink, openLink, acceptSuggestion, unlinkEmployee, peopleOptions]);
```

👉 Nótese el patrón de **columnas condicionales por permiso** (`...(canLink ? [colAcciones] : [])`) y `catalogOptions` recibiendo directamente el retorno de un hook de entidad (`usePeopleOptions()` → `{ data, loading, error }`).

**La tabla, con todos los flags reales:**

```tsx
<ITDataTable
  columns={columns as unknown as Column<Record<string, unknown>>[]}
  fetchData={fetchTableData as unknown as (p: ITDataTableFetchParams) => Promise<ITDataTableResponse<Record<string, unknown>>>}
  externalFilters={externalFilters}
  reloadTrigger={reloadKey}
  defaultItemsPerPage={100}
  itemsPerPageOptions={[10, 25, 50, 100]}
  debounceMs={350}
  layout="fixed"
  density="compact"
  virtualized
  virtualizedMaxHeight={420}
  rowHeight={50}
/>
```

⚠️ Hay que **doblar el tipo con `as unknown as`** en cuanto la tabla es genérica: es el precio del patrón. `fetchData` es `useCallback` y hace el `POST /entidad/query` con `{ page, limit, filters, sort }` (§3.4).

### 6.3 Snippet real #2 — `ITSearchSelect` dentro de un `ITDialog`

Mismo archivo:

```tsx
<ITDialog isOpen={!!target} onClose={() => setTarget(null)}
  title={t("employees.dialog.title", { number: target?.employeeNumber ?? "" })}
  className="max-w-md">
  <ITFlex direction="column" gap={3} className="mt-2">
    <ITText className="text-[12px] text-slate-600">
      {t("employees.dialog.clock", { name: target?.name ?? "" })}
    </ITText>
    <ITSearchSelect
      name="checadorVinculoUsuario"
      label={t("employees.dialog.user")}
      placeholder={t("employees.dialog.userPlaceholder")}
      options={userOptions}
      value={userId}
      onChange={(value) => setUserId(String(value))}
      className="w-full min-w-0"
    />
    <ITFlex justify="end" gap={2}>
      <ITButton variant="outlined" color="secondary" onClick={() => setTarget(null)}>
        {t("employees.actions.cancel")}
      </ITButton>
      <ITButton variant="filled" color="primary" disabled={!userId || saving} onClick={confirm}>
        {t("employees.actions.save")}
      </ITButton>
    </ITFlex>
  </ITFlex>
</ITDialog>
```

**Props obligatorias observadas de `ITSearchSelect`:** `name` (id único para el DOM), `label`, `options: {value,label}[]`, `value`, `onChange: (value) => void`, `className="w-full min-w-0"`. `onChange` entrega un valor que **siempre se coercionа con `String(value)`**.

### 6.4 Snippet real #3 — Página de tabs y descarga por inyección de dependencias

`src/pages/reports/ReportsPage.tsx`:

```tsx
export default function ReportsPage() {
  const { t } = useTranslation(["reports", "common"]);
  const navigate = useNavigate();
  const assignedFx      = useAssignedDevicesReport({ download: downloadAssignedDevicesPdf });
  const devicesFx       = useDevicesReport({ download: downloadDevicesPDF });
  const materialOutputsFx = useMaterialOutputsReport({ download: downloadMaterialOutputsPdf });

  return (
    <ITPage title={t("title")} description={t("description")} backAction={() => navigate(-1)}
      icon={<FaChartBar size={20} />} noPadding
      breadcrumbs={[{ label: t("common:breadcrumbs.home"), onClick: () => navigate("/") },
                    { label: t("title") }]}>
      <ITTabs variant="line" defaultActiveId="assigned" items={[
        { id: "assigned", label: t("tabs.assigned"), icon: <FaHandHolding size={13} />, content: <AssignedDevicesTab fx={assignedFx} /> },
        { id: "devices",  label: t("tabs.devices"),  icon: <FaBoxOpen size={13} />,    content: <DevicesTab fx={devicesFx} /> },
        { id: "exits",    label: t("tabs.exits"),    icon: <FaTrashAlt size={13} />,   content: <MaterialOutputsTab fx={materialOutputsFx} /> },
      ]} />
    </ITPage>
  );
}
```

👉 **Patrón de DI para PDFs**: la vista (feature en `reports`) **no importa `@widgets/*`** (lo prohíbe el lint). La **página** importa `downloadAssignedDevicesPdf` de `@widgets/reports` y se lo **inyecta** al hook. Comentario textual en `src/widgets/reports/index.ts`:

> *"API pública del widget `reports`. Provee los generadores PDF; los tabs viven en `@features/report` y reciben el download por DI desde la página."*

### 6.5 Convención de props de `ITDataTable` (uso observado)

- `fetchData` recibe `ITDataTableFetchParams` con `{ page, limit, filters, sort }`.
- La tabla **no conoce** los filtros de barra: se le pasan por `externalFilters: Record<string, string|number|boolean>`.
- Recarga imperativa: `reloadTrigger={reloadKey}`.
- `type` de columna visto: `"string"`, `"number"`, `"catalog"`, `"actions"`.
- `filter: true` o `filter: "search"` (con `catalogOptions`) o `"date"` / `"date-range"`.

---

## 7. Modelo de permisos

### 7.1 Tipos base — `src/entities/user/model/types.ts`

```ts
/** Alcance efectivo de un permiso (ver ROLES_Y_PERMISOS.md §2). */
export type PermissionScope = "NONE" | "OWN" | "AREA" | "ALL";

/**
 * Clave del catálogo dinámico de permisos del API (`GET /permissions/catalog`).
 * Antes era un union hardcodeado; ahora el catálogo vive en la BD y puede
 * crecer sin recompilar la web, así que la clave se tipa como string libre.
 */
export type Permission = string;

/** Clave del rol (dinámico, tabla `roles` de la API). Antes era un union fijo. */
export type UserRole = string;

export interface AuthUser {
  id: string; username: string; email?: string | null; name: string;
  role: UserRole;
  roles?: UserRole[];                                  // principal + adicionales
  departmentId?: string | null;
  permissions?: Partial<Record<Permission, PermissionScope>>;   // opcional a propósito
  language?: AppLanguage;
}

export interface AuthMe extends AuthUser {
  employeeNumber: string | null; jobTitle: string | null;
  department: { id: string; name: string } | null; photoUrl: string | null;
  permissions: Partial<Record<Permission, PermissionScope>>;    // obligatorio en /auth/me
  language: AppLanguage;
}
```

· `permissions` es **opcional en `AuthUser`** porque *"las sesiones persistidas antes del rollout de permisos no lo traen"*; es **obligatorio en `AuthMe`**.
· **El rol y el permiso son `string` libres** — el catálogo es dinámico desde la BD. ❚⚠️ Contrasta con `docs/MODULES.md`, que todavía describe 5 roles estáticos (`ADMIN`, `MANAGER`, `AREA_HEAD`, `HUMAN_RESOURCES`, `EMPLOYEE`) y helpers `isAdmin`/`canManage`/`canManageHR`: **esa documentación está desactualizada**; el código ya no los tiene.

### 7.2 Hook de permisos — `src/entities/user/model/usePermission.ts` (completo)

```ts
import { useSelector } from "react-redux";
import type { PermissionScope, Permission } from "./types";

/**
 * Forma mínima del estado que necesita el hook. Se declara aquí en vez de
 * importar `RootState` de `@app/store` porque los `entities` no pueden depender
 * de `app` (regla FSD en `eslint.config.js`).
 */
interface AuthStateLike {
  auth: { user: { role?: string; permissions?: Partial<Record<Permission, PermissionScope>> } | null };
}

const NO_PERMISSIONS: Partial<Record<Permission, PermissionScope>> = {};

/** Mapa completo de permisos de la sesión (para decidir sobre varios a la vez). */
export const usePermissions = () => useSelector((s: AuthStateLike) => s.auth.user?.permissions ?? NO_PERMISSIONS);

/** Rol de la sesión (solo para elegir el acomodo del tablero; los accesos los deciden los permisos). */
export const useCurrentRole = (): string | null => useSelector((s: AuthStateLike) => s.auth.user?.role ?? null);

/** Alcance efectivo del permiso para la sesión actual (NINGUNO si no aplica). */
export const usePermission = (permission: Permission): PermissionScope =>
  useSelector((s: AuthStateLike) => s.auth.user?.permissions?.[permission] ?? "NONE");

/** ¿La sesión actual tiene el permiso con cualquier alcance? */
export const useCan = (permission: Permission): boolean => usePermission(permission) !== "NONE";

/** Helper puro para decidir sobre un mapa de permisos ya cargado. */
export const can = (permissions, permission): boolean => (permissions?.[permission] ?? "NONE") !== "NONE";
```

**API de 5 funciones:** `usePermissions()`, `useCurrentRole()`, `usePermission(k)`, `useCan(k)`, `can(map, k)`.

👉 **Detalle clave a replicar:** el hook **no importa `RootState`**; declara una interfaz estructural local (`AuthStateLike`) y así respeta la frontera `entities ⊥ app`. Está comentado explícitamente en el código.

### 7.3 Catálogo de pantallas — `src/entities/permission/model/screens.ts` (307 líneas)

Es **la fuente única de verdad del menú y de la matriz de visibilidad**, consumida por:
- `src/app/guards/PrivateRoutes.tsx` (arma el menú).
- La pestaña *"Qué ve cada rol"* de `/roles` (`features/roles/roles-console`).

Tipos:

```ts
/** Requisito de visibilidad: cualquiera (`anyOf`) o todas (`allOf`) las claves. */
export type ScreenRequirement =
  | { readonly anyOf: readonly Permission[] }
  | { readonly allOf: readonly Permission[] };

export interface AppScreen {
  readonly id: string;
  readonly labelKey: NavLabelKey;      // clave i18n del ns `common`, union cerrado para tipar
  readonly path?: string;
  readonly match?: "exact" | "prefix"; // por defecto `prefix`
  readonly excludes?: readonly string[];   // rutas hijas que NO deben resaltar este ítem
  readonly requirement?: ScreenRequirement;
  readonly fixedRole?: UserRole;       // regla fija por rol, ignora permisos
  readonly actions?: readonly Permission[];  // permisos de BOTONES; si se omite, se derivan
  readonly children?: readonly AppScreen[];
}
```

`NavLabelKey` es un **union cerrado de ~50 literales** (`"nav.home"`, `"nav.tickets"`, `"nav.kitchenPurchaseOrders"`, …) para que la i18n del menú esté tipada.

Extracto literal del catálogo:

```ts
export const APP_SCREENS: readonly AppScreen[] = [
  { id: "start", labelKey: "nav.home", path: "/", match: "exact" },
  {
    id: "tasks", labelKey: "nav.tasks",
    children: [
      { id: "tickets", labelKey: "nav.tickets", path: "/tickets",
        excludes: ["/tickets/tasks", "/tickets/my-tasks"],
        actions: ["tickets.create", "tickets.edit", "tickets.close", "tickets.delete"] },
      { id: "adminTasks", labelKey: "nav.adminTasks", path: "/tickets/tasks", requirement: { anyOf: ["tasks.complete"] } },
      { id: "myTasks", labelKey: "nav.myTasks", path: "/tickets/my-tasks", fixedRole: "EMPLOYEE" },
    ],
  },
  {
    id: "kitchen", labelKey: "nav.kitchen", requirement: { anyOf: ["kitchen.view"] },
    children: [
      { id: "kitchenOverview", labelKey: "nav.kitchenOverview", path: "/kitchen", match: "exact" },
      { id: "kitchenItems", labelKey: "nav.kitchenItems", path: "/kitchen/items", excludes: ["/kitchen/items/new"], requirement: { anyOf: ["kitchen.view"] } },
      {
        id: "kitchenPurchases", labelKey: "nav.kitchenPurchases",
        children: [
          { id: "kitchenRestock", labelKey: "nav.kitchenRestock", path: "/kitchen/restock" },
          { id: "kitchenPurchaseOrders", labelKey: "nav.kitchenPurchaseOrders", path: "/kitchen/purchase-orders", requirement: { anyOf: ["purchase_orders.view"] } },
          { id: "kitchenInvoices", labelKey: "nav.kitchenInvoices", path: "/kitchen/invoices", requirement: { anyOf: ["invoices.view"] } },
        ],
      },
    ],
  },
  { id: "settings", labelKey: "nav.settings", children: [
      { id: "catalogs", labelKey: "nav.catalogs", path: "/catalogs", requirement: { anyOf: ["catalogs.manage"] } },
      { id: "users",    labelKey: "nav.users",    path: "/users",    requirement: { anyOf: ["users.view"] } },
      { id: "clocks",   labelKey: "nav.clocks",   path: "/time-clocks", requirement: { anyOf: ["time_clocks.manage"] } },
      { id: "roles",    labelKey: "nav.roles",    path: "/roles",    requirement: { anyOf: ["roles.manage"] } },
  ] },
];
```

Regla de tres niveles documentada en el encabezado del archivo:

> *"Un grupo (con `children`) es visible si lo es al menos uno de sus hijos. Dentro de un grupo, un hijo con `children` es una **sección**: el menú la muestra como encabezado de subítems (ITSidebar no tiene tercer nivel) y no navega."*

Helpers exportados:

| Función | Firma | Uso |
|---|---|---|
| `screenHasPermission` | `(permissions, permission) => boolean` | clave con alcance ≠ NONE |
| `screenScopeOf` | `(permissions, permission) => PermissionScope` | alcance, `"NONE"` por defecto |
| `isScreenVisible` | `(permissions, screen, role?) => boolean` | respeta `fixedRole`, `allOf`/`anyOf` y grupos |
| `isScreenVisibleInGroup` | `(permissions, screen, parent, role?) => boolean` | un hijo oculto por su padre no se muestra |
| `screenLeaves` | `() => ScreenLeaf[]` | hojas del catálogo con su grupo |
| `referencedPermissions` | `() => Set<Permission>` | claves mencionadas (para separar "acciones") |
| `screenActions` | `(screen, catalogKeys) => Permission[]` | permisos de botones, **derivados** del recurso si no se declaran |
| `screenRequirementKeys` | `(screen) => Permission[]` | claves que exige ver la pantalla |

Lógica central:

```ts
export const isScreenVisible = (permissions, screen, role?): boolean => {
  if (screen.fixedRole) return role === screen.fixedRole;
  if (screen.requirement) {
    if ("allOf" in screen.requirement) return screen.requirement.allOf.every((k) => screenHasPermission(permissions, k));
    return screen.requirement.anyOf.some((k) => screenHasPermission(permissions, k));
  }
  if (screen.children?.length) return screen.children.some((c) => isScreenVisible(permissions, c, role));
  return true;
};

/**
 * Permisos de las acciones de una pantalla: los declarados en `actions` o, si
 * no hay, los demás permisos del mismo recurso que su requisito, tomados del
 * catálogo (así un permiso nuevo como `devices.export` aparece solo).
 */
export const screenActions = (screen, catalogKeys) => {
  if (screen.actions) return [...screen.actions];
  if (!screen.requirement) return [];
  const required = "allOf" in screen.requirement ? screen.requirement.allOf : screen.requirement.anyOf;
  const resources = new Set(required.map((k) => k.slice(0, k.indexOf("."))));
  return catalogKeys.filter((k) => !required.includes(k) && resources.has(k.slice(0, k.indexOf("."))));
};
```

**Convención de claves de permiso:** `"<recurso>.<accion>"`, p. ej. `devices.view`, `devices.create`, `loans.view`, `kitchen.stock_in`, `kitchen.adjust`, `purchase_orders.create`, `invoices.register`, `time_clock.view`, `time_clocks.manage`, `hr.records`, `hr.disciplinary_reports`, `access.log`, `access.void`, `reports.view`, `roles.manage`, `catalogs.manage`, `users.view`, `users.create`, `schedules.view`, `overtime.view`, `payroll.view`, `tasks.complete`, `tickets.edit`, `tickets.close`, `tickets.delete`, `dashboard.view`, `inventory.audit`.

### 7.4 Catálogo de roles dinámico — `src/entities/user/model/roles-catalog.ts`

No hay un catálogo de roles hardcodeado. Es un **store en memoria con `useSyncExternalStore`**:

```ts
let catalog: RoleOption[] = [];
const listeners = new Set<() => void>();

export const setRolesCatalog = (roles: RoleOption[]): void => { catalog = roles; for (const l of listeners) l(); };
export const getRolesCatalog = (): RoleOption[] => catalog;
export const subscribeRoles = (listener: () => void): (() => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; };

/** Catálogo reactivo: los componentes se vuelven a pintar cuando llega/cambia. */
export const useRolesCatalog = (): RoleOption[] =>
  useSyncExternalStore(subscribeRoles, getRolesCatalog, getRolesCatalog);

export const roleOf = (key: string): RoleOption | undefined => catalog.find((r) => r.key === key);
export const roleLabel = (role: string): string =>
  roleOf(role)?.name ?? i18n.t(`roles:role.${role}`, { defaultValue: role });   // fallback a i18n y luego a la clave
export const isStaffRole = (role: string): boolean => roleOf(role)?.staff ?? false;
export const roleOptions = ({ activeOnly = true, staffOnly = false } = {}) =>
  catalog.filter((r) => (!activeOnly || r.active) && (!staffOnly || r.staff)).map((r) => ({ id: r.key, name: r.name }));
```

`RoleOption` = `{ key, name, description, module, staff, system, active, sortOrder, userCount? }`. Quien llena el catálogo es `PrivateRoutes` (`permissionApi.listRoles().then(setRolesCatalog)`), y `roleLabel` cae en cascada: catálogo → `i18n.t("roles:role.<key>")` → la clave cruda.

### 7.5 API de administración de permisos — `src/entities/permission/`

`src/entities/permission/api/permissionApi.ts` (340 líneas) define los tipos de administración y el objeto `permissionApi`:

```ts
export const permissionApi = {
  getAdmin:          () => api.get<RolesAdminData>("/permissions/admin"),
  listRoles:         () => api.get<RoleAdmin[]>("/permissions/roles"),
  createRole:        (dto: RoleCreateDto) => api.post<RoleAdmin>("/permissions/roles", dto),
  updateRole:        (key, dto) => api.patch<RoleAdmin>(`/permissions/roles/${key}`, dto),
  deleteRole:        (key) => api.delete<...>(`/permissions/roles/${key}`),
  getCatalog:        () => api.get<PermissionCatalog[]>("/permissions/catalog"),
  getPolicies:       () => api.get<PolicyListData>("/permissions/policies"),
  createPolicy:      (dto) => api.post<PolicyAdmin>("/permissions/policies", dto),
  updatePolicy:      (id, dto) => api.patch<PolicyAdmin>(`/permissions/policies/${id}`, dto),
  deletePolicy:      (id) => api.delete<...>(`/permissions/policies/${id}`),
  listMembers:       () => api.get<AccessMember[]>("/permissions/members"),
  getMemberAccess:   (userId) => api.get<UserAccessView>(`/permissions/members/${userId}/access`),
  addRoleMember:     (role, userId) => api.post<...>(`/permissions/roles/${role}/members`, { userId }),
  removeRoleMember:  (role, userId) => api.delete<...>(`/permissions/roles/${role}/members/${userId}`),
  simulate:          (dto: SimulationDto) => api.post<AccessSimulation>("/permissions/simulate", dto),
  activity:          (params) => tableRequest<AccessActivityRow>("/permissions/activity/query", params),
  saveMatrix:        (changes: MatrixChange[]) => api.put<...>("/permissions/matrix", { changes }),
  createCatalog:     (dto) => api.post<PermissionCatalog>("/permissions/catalog", dto),
  updateCatalog:     (key, dto) => api.patch<PermissionCatalog>(`/permissions/catalog/${key}`, dto),
};
```

Tipos notables: `PermissionCatalog` (`{ key, module, name, description, scopes, sensitive, active, sortOrder }`), `MatrixCell` (`{ role, permission, scope }`), y un **modelo ABAC** completo (`PolicyAdmin`, `PolicyCondition`, `PolicyEffect = "ALLOW"|"DENY"`, `PolicyFieldType`, `AccessSimulation`, `PolicyExplanation`, `ConditionTrace`, `RuleTrace`).

La consola de roles es una feature grande: `src/features/roles/roles-console/` (7 hooks en `model/`, 30 componentes en `ui/` organizados en `access/`, `activity/`, `matrix/`, `policies/`, `roles/`, `shared/`).

---

## 8. Estilos

### 8.1 `src/app/index.css` (archivo completo, 19 líneas)

```css
@layer theme, base, axzy-ui-system, components, utilities;

@import "tailwindcss";
@import "@axzydev/axzy_ui_system/dist/index.css" layer(axzy-ui-system);

@source "../node_modules/@axzydev/axzy_ui_system/src/**/*.{ts,tsx}";

:root {
  --layout-bg: #f1f5f9;
}

/* Forzar que el modal sea más ancho para que quepan las 2 columnas */
.it-dialog-panel {
  width: min(94vw, 900px) !important;
}

html {
  scroll-behavior: smooth;
  overflow-x: hidden;
}
```

Puntos no obvios, **todos necesarios**:

1. **Declaración explícita del orden de capas** con `@layer theme, base, axzy-ui-system, components, utilities;` — así el CSS del UI kit puede ponerse en una capa intermedia (`layer(axzy-ui-system)`) y las utilidades de Tailwind siguen ganando.
2. **`@source`** apunta a los **fuentes** del paquete del UI kit dentro de `node_modules` para que Tailwind 4 detecte las clases usadas por el kit (Tailwind 4 no escanea `node_modules` por defecto).
3. `--layout-bg` es un **token propio** expuesto al kit.
4. Override global de `.it-dialog-panel` con `!important` para diálogos de 2 columnas.
5. ⚠️ **No hay `@theme` ni tokens de diseño propios** más allá de `--layout-bg`; la paleta se define en JS (§5.1, `customTheme`).
6. ⚠️ **No hay configuración de dark mode**: el único rastro es `localStorage "it-theme-dark-mode"` que `main.tsx` inicializa a `"light"` si no existe. **No hay toggle en el código**; el modo oscuro lo maneja el UI kit leyendo esa clave. (No verificable en este repo: la implementación vive dentro del paquete `@axzydev/axzy_ui_system`.)

### 8.2 Tokens de tema (`ITThemePalette`, en `main.tsx`)

```ts
const customTheme: ITThemePalette = {
  primary: "#0D5777", secondary: "#1A7499", ternary: "#F0F4F7",
  alert: "#F9C74F", warning: "#F9C74F", danger: "#BA1A1A",
  info: "#512bbb", success: "#4ADE80",
  layout: { sidebarBg: "#ffffff", sidebarText: "#54634d", navbarBg: "#0D5777", navbarText: "#ffffff" },
  table: { headerBg: "#8ab1cf9d", headerText: "#0D5777", rowBg: "#ffffff",
           rowText: "#1B1B1F", rowHover: "#0d5777c4" },
};

<ITThemeProvider theme={customTheme} showFab={false} density={1}>
```

`#0D5777` es el **azul de marca** y se repite hardcodeado en componentes con Tailwind arbitrario (`text-[#0D5777]`, `bg-[#0D5777]/10`) y en `PDF_COLORS.band = "#0a4560"` (`src/shared/pdf/theme.ts`).

### 8.3 Uso de Tailwind en el código

- Clases utilitarias **inline en `className`** de los componentes del kit (`<ITFlex className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">`).
- Densidad tipográfica muy apretada y consistente: `text-[12px]`, `text-[9px]`, `text-[11px]`, `font-black`, `font-bold`, `uppercase tracking-widest text-slate-400`.
- Modificadores importantes de Tailwind 4 en `ITLayout`: `contentClassName="max-w-screen! m-0! !px-2"` (sufijo `!` = importante; prefijo `!` heredado de v3, ambos estilos aparecen).
- Plantilla de tarjeta KPI muy repetida:

```tsx
<ITFlex key={k.key} grow={1} basis="160px" align="center" gap={3}
  className="rounded-2xl border border-slate-200 bg-white p-4 shadow-sm">
  <ITFlex align="center" justify="center" className={`h-10 w-10 shrink-0 rounded-xl ${k.tint}`}>
    <ITText className="text-sm font-black">{k.value}</ITText>
  </ITFlex>
  <ITText className="truncate text-[10px] font-bold uppercase tracking-widest text-slate-400">
    {t(`employees.kpis.${k.key}`)}
  </ITText>
</ITFlex>
```

---

## 9. i18n

### 9.1 Estructura

```
src/shared/i18n/
├── config.ts        ← init de i18next + resources + ns + detección
├── index.ts         ← barrel: exporta i18n default, dyn, format
├── dyn.ts           ← helper de traducción "laxa" para claves dinámicas
├── format.ts        ← dateLocale, formatDate, fileName
├── i18next.d.ts     ← tipado fuerte de claves
└── locales/{es,en}/<namespace>.json    (28 namespaces × 2 idiomas = 56 archivos)
```

Namespaces (`NS_LIST` en `config.ts`), 28 en total:

```
common, device, device-types, auth, home, dashboard, custody-letters, tickets, users,
employees, departments, subareas, inventory, reports, material-outputs, notifications,
audit, catalog, roles, disciplinary-reports, access, access-report, time-clock,
schedules, overtime, weekly-attendance, kitchen
```

⚠️ Desajuste real: los archivos en disco incluyen `en/device.json` y `en/device-types.json` y ambos están en `NS_LIST`, pero el listado de imports de `config.ts` **también** cubre todo. No hay namespaces huérfanos detectados.

### 9.2 `config.ts`

```ts
export const defaultNS = "common" as const;
export const APP_LANGUAGES = ["es", "en"] as const;
export type AppLanguage = (typeof APP_LANGUAGES)[number];
export const NS_LIST = [ /* 28 ns */ ] as const;

export const resources = {
  en: { common: commonEn, device: deviceEn, /* ... */ kitchen: kitchenEn },
  es: { common: commonEs, device: deviceEs, /* ... */ kitchen: kitchenEs },
} as const;

const STORAGE_KEY = "ptnv-language";
const storedLanguage = typeof window !== "undefined" ? window.localStorage.getItem(STORAGE_KEY) : null;

i18n.use(initReactI18next).init({
  resources,
  lng: storedLanguage ?? "es",
  fallbackLng: "es",
  defaultNS,
  ns: NS_LIST,
  interpolation: { escapeValue: false },
  returnNull: false,
});

i18n.on("languageChanged", (lng) => {
  if (typeof window !== "undefined") window.localStorage.setItem(STORAGE_KEY, lng);
});

export default i18n;
```

- **Recursos 100 % estáticos** (JSON importados, sin `i18next-http-backend` ni lazy loading de ns).
- Idioma por defecto `es`, clave de persistencia `ptnv-language`.
- El **idioma del sistema** (columna `sys_config.LANGUAGE` del backend) pisa al local vía `PrivateRoutes` (§5.3), y **también** viaja en cada request como `Accept-Language` para que la API devuelva mensajes traducidos (§3.1).

### 9.3 Tipado fuerte — `i18next.d.ts`

```ts
import "i18next";
import type { defaultNS, resources } from "./config";

declare module "i18next" {
  interface CustomTypeOptions {
    defaultNS: typeof defaultNS;
    resources: (typeof resources)["es"];   // el español es el contrato de claves
  }
}
```

### 9.4 Export de archivos — `format.ts`

```ts
export const dateLocale = (): string => (i18n.language?.startsWith("en") ? "en-US" : "es-MX");

export const formatDate = (value: string | Date, options?: Intl.DateTimeFormatOptions): string => {
  const d = typeof value === "string" ? new Date(value) : value;
  if (isNaN(d.getTime())) return "—";
  return d.toLocaleDateString(dateLocale(), options);
};

export type FileNameKey = keyof (typeof import("./locales/es/common.json"))["files"];

/** Prefijo de un archivo descargado (CSV/PDF), en el idioma de la interfaz. */
export const fileName = (key: FileNameKey): string => i18n.t(`common:files.${key}`);
```

`dyn.ts` existe para claves armadas en runtime (el tipado estricto pelearía):

```ts
type LooseTranslate = (key: string, options?: Record<string, unknown>) => string;
export const dyn = (tt: TFunction<any>): LooseTranslate => (key, options) => (tt as LooseTranslate)(key, options);
```

### 9.5 Convenciones de uso en componentes

```tsx
const { t } = useTranslation(["time-clock", "common"]);        // ← SIEMPRE array con `common`
t("employees.filters.all")                                     // ns primario: sin prefijo
t("common:breadcrumbs.home")                                   // ns secundario: prefijo `ns:`
t("employees.dialog.title", { number: target?.employeeNumber ?? "" })   // interpolación {{number}}
```

- Estructura de claves: anidada por pantalla → `{ title, description, columns, actions, toasts, errors, kpis, filters, statuses, dialog }`.
- Los **enums del backend** se traducen con clave dinámica:
```tsx
{ value: e, label: t(`employees.statuses.${e}`) }
{ value: e, label: t(`employees.confidence.${r.suggestion.confidence}`) }
{ key: k.key, ... t(`employees.kpis.${k.key}`) }
```
- En `features/*/model/use*.ts` con hooks, la traducción se reexpone como `t` en el objeto de retorno (`return { t, ... }`) para que la UI no vuelva a llamar `useTranslation`.

---

## 10. Formularios, validación, toasts, notificaciones, PDF y QR

### 10.1 Formularios y validación

❌ **`yup` NO se usa.** Está en `dependencies` (`"yup": "^1.6.1"`) y `docs/MODULES.md` lo declara como la tecnología de validación, pero `grep -rn "yup" src/ tests/` devuelve **cero coincidencias**. ⚠️ Es deuda/dependencia muerta. **No lo incluyas en la plantilla nueva.**

**Lo que sí se usa:** validadores puros que devuelven `string | null` (mensaje traducido o `null` si es válido) en `src/shared/validation/`:

```ts
// src/shared/validation/index.ts
export { validateCurp } from "./curp";
export { validateEmail } from "./email";
export { validateNss } from "./nss";
export { validatePhone } from "./phone";
export { validatePostal } from "./postal";
export { validateRequired, validateMinLength } from "./required";
export { validateRfc } from "./rfc";
```

```ts
// src/shared/validation/required.ts
import { i18n } from "@shared/i18n";

/** Non-empty helper. Returns null when valid, otherwise a translated error message. */
export const validateRequired = (value: string | null | undefined,
  label = i18n.t("common:validation.thisField")): string | null => {
  if (value == null || value.trim() === "") return i18n.t("common:validation.required", { label });
  return null;
};

export const validateMinLength = (value, min: number, label = ...): string | null => {
  if (value == null || value.trim() === "") return i18n.t("common:validation.required", { label });
  return value.length >= min ? null : i18n.t("common:validation.minLength", { label, min });
};
```

**Patrón de validación en el hook del formulario** (`src/features/auth/login/model/useLogin.ts`):

```ts
export const useLogin = () => {
  const dispatch = useDispatch<AppDispatch>();
  const { t: tt } = useTranslation(["auth"]);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errors, setErrors] = useState<{ username?: string; password?: string }>({});
  const [toast, setToast] = useState<{ message: string; type: "error" | "success" } | null>(null);

  const validate = (): boolean => {
    const e: { username?: string; password?: string } = {};
    if (!username.trim()) e.username = i18n.t("auth:login.validation.usernameRequired");
    else if (username.trim().length < 3) e.username = i18n.t("auth:login.validation.usernameMin", { min: 3 });
    if (!password) e.password = i18n.t("auth:login.validation.passwordRequired");
    else if (password.length < 6) e.password = i18n.t("auth:login.validation.passwordMin", { min: 6 });
    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const handleSubmit = async (e: React.FormEvent): Promise<boolean> => {
    e.preventDefault();
    if (!validate()) return false;
    setIsSubmitting(true);
    const action = await dispatch(loginThunk({ username, password }));
    if (loginThunk.fulfilled.match(action)) return true;      // ← match tipado del thunk
    setIsSubmitting(false);
    const rejected = action.payload as unknown;
    if (rejected && typeof rejected === "object" && "code" in rejected) {
      const code = (rejected as { code?: string }).code;
      if (code === "ACCOUNT_DEACTIVATED") {
        const reason = (rejected as { details?: { reason?: string } }).details?.reason;
        setDeactivatedMsg(reason ? i18n.t("auth:login.deactivatedWithReason", { reason })
                                 : i18n.t("auth:login.deactivated"));
        return false;
      }
    }
    setToast({ message: tt("login.invalidCredentials"), type: "error" });
    return false;
  };

  const canSubmit = !!username && !!password;
  return { username, password, setUsername, setPassword, errors, isSubmitting, canSubmit,
           deactivatedMsg, toast, dismissToast: () => setToast(null), handleSubmit };
};
```

**Resumen del patrón de formulario a replicar:**
1. El estado del formulario vive en `features/<dom>/<caso>/model/use<X>Form.ts`.
2. `validate()` local que llena `errors: Record<string, string>` con mensajes de `i18n.t` (se llama a `i18n` importado, no al `t` del hook, para evitar problemas de closures).
3. El hook devuelve el estado + setters + `errors` + `isSubmitting` + `canSubmit` + `handleSubmit`.
4. La UI (`ui/<X>Form.tsx`) es **presentacional pura**: recibe todo por props.
5. Errores de la API se muestran con `ITAlert variant="error"` y/o `ITToast`.
6. `rejectWithValue` en los thunks para llevar `{ code, message, details, status }` serializables.

**Idempotencia en altas** — `src/shared/lib/useRequestKey.ts`:

```ts
/**
 * Clave de idempotencia (`Idempotency-Key`) para las altas de un formulario:
 * base aleatoria por montaje + huella del contenido. El mismo envío repetido
 * (doble clic, reintento tras un corte) lleva la misma clave y la API no lo
 * duplica; si el contenido cambia, es una petición nueva.
 */
export const useRequestKey = () => {
  const base = useRef<string | null>(null);
  return useCallback((payload: unknown): string => {
    base.current ??= randomBase();          // crypto.getRandomValues (funciona en http no seguro)
    return `${base.current}-${fingerprint(JSON.stringify(payload))}`;   // FNV-1a 32 bits
  }, []);
};
```

### 10.2 Toasts — **tres mecanismos coexistentes**

| # | Mecanismo | Dónde vive | Uso real |
|---|---|---|---|
| 1 | **Slice Redux `toast` + `ToastProvider`** | `src/app/toast/` | Montado siempre en `main.tsx`, pero **casi sin uso**: solo `useUserForm.ts` hace `dispatch(showToast(...))`. |
| 2 | **`ITToast` local con `useState`** | dentro de cada feature | **El patrón dominante** (37 archivos usan `ITToast`). |
| 3 | `ITToast` sin portal en `PrivateRoutes` | para notificaciones Ably en web | §5.3 |

**Slice (`src/app/toast/toast.slice.ts`):**

```ts
export type ToastType = "success" | "error" | "warning" | "info" | "primary" | "danger";
export type ToastPosition = "top-right" | "top-center" | "top-left"
                         | "bottom-right" | "bottom-center" | "bottom-left";

export interface ToastState { message: string; type: ToastType; duration: number;
                              position: ToastPosition; isVisible: boolean; }

const initialState: ToastState = { message: "", type: "info", duration: 2500,
                                   position: "bottom-center", isVisible: false };

reducers: {
  showToast(state, action: PayloadAction<Partial<Omit<ToastState,"isVisible">> & { message: string }>) {
    state.message = action.payload.message;
    state.type = action.payload.type ?? "info";
    state.duration = action.payload.duration ?? 2500;
    state.position = action.payload.position ?? "bottom-center";
    state.isVisible = true;
  },
  hideToast(state) { state.isVisible = false; },
}
```

**Provider (portal + auto-hide):**

```tsx
const ToastProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const dispatch = useDispatch();
  const { message, type, duration, position, isVisible } = useSelector((s: AppState) => s.toast);

  useEffect(() => {
    if (isVisible) {
      const timer = setTimeout(() => dispatch(hideToast()), duration);
      return () => clearTimeout(timer);
    }
  }, [isVisible, duration, message, dispatch]);

  return (<>
    {children}
    {isVisible && createPortal(
      <div className="fixed inset-0 z-[99999] pointer-events-none">
        <ITToast message={message} type={type} duration={duration} position={position}
                 onClose={() => dispatch(hideToast())} />
      </div>, document.body)}
  </>);
};
```

**Patrón local dominante (lo que hay que clonar):**

```tsx
const [toast, setToast] = useState<string | null>(null);   // expuesto por el hook de la feature
// ...
{toast && <ITToast message={toast} type="success" position="top-right" duration={3000}
                   onClose={() => setToast(null)} />}
```

Con `setToast(t("employees.toasts.linked", { number: row.employeeNumber, name: res.link?.name ?? "" }))`.

### 10.3 Notificaciones en tiempo real (Ably)

`src/shared/lib/ably.ts`:

```ts
import * as Ably from "ably";

let ablyClient: Ably.Realtime | null = null;

export const ABLY_API_KEY = "yuJ-ow.7iF_wA:tuWolCDo1xBN4tpDEbZFD7A60KNnT_AsTNvhxkII-go";  // ⚠️ hardcodeada

const getAblyClient = (): Ably.Realtime => {
  if (!ablyClient) ablyClient = new Ably.Realtime({ key: ABLY_API_KEY });
  return ablyClient;
};

export const useAblyChannel = (channelName: string | undefined,
                               subscriptions: Record<string, (data: unknown) => void>) => {
  const handlersRef = useRef(subscriptions);
  handlersRef.current = subscriptions;          // ← evita re-suscribirse en cada render

  useEffect(() => {
    if (!channelName) return;
    const client = getAblyClient();
    const channel = client.channels.get(channelName);
    const events = Object.keys(handlersRef.current);
    const listeners = events.map((event) => {
      const listener = (message: Ably.Message) => handlersRef.current[event]?.(message.data);
      channel.subscribe(event, listener);
      return { event, listener };
    });
    return () => listeners.forEach(({ event, listener }) => channel.unsubscribe(event, listener));
  }, [channelName]);
};
```

⚠️ **Hallazgo de seguridad a NO replicar:** `ably.ts:6` tiene la API key **hardcodeada en el fuente**. `.env` define `VITE_ABLY_API_KEY=yuJ-ow...` y `src/vite-env.d.ts` la declara (`readonly VITE_ABLY_API_KEY?: string`), pero `ably.ts` **no la lee**. La variable de entorno existe y no se usa.

**Puente `app/`:**

```ts
// src/app/guards/useAblyNotifications.ts
export const useAblyNotifications = (userId: string | undefined,
                                     onNotification?: (n: LiveNotification) => void) => {
  useAblyChannel(userId ? `user:${userId}` : undefined, {
    NOTIFICATION: (data: unknown) => {
      store.dispatch(prependNotification(data));   // ← importa el store, no useDispatch
      onNotification?.((data ?? {}) as LiveNotification);
    },
  });
};
```

Canal por usuario: `user:<id>`. Evento: `NOTIFICATION`. El reducer `prependNotification` inserta en la lista y suma al `unreadCount`.

**Notificaciones nativas (Electron):** `src/shared/lib/desktop.ts` expone `desktop: DesktopBridge | undefined` (`window.desktop`), con `notify({ id, title, body, route })` y `onNotificationClick(cb)`. En el navegador es `undefined` y se usa un toast; en Electron, notificación del sistema que al hacer clic navega a `route` y marca leída.

### 10.4 PDF

**Tema compartido** — `src/shared/pdf/theme.ts`: `PDF_COLORS` (paleta de marca con comentario: *"el azul viene muestreado directamente del logo"*), `pdfTheme = StyleSheet.create({ page, content, band, bandAccentLine, logoBadge, summaryRow, summaryCard, tableHeader, tableRow, tableRowAlt, cell, badge, filterBox, footer, ... })` y `badgeStyleFor(kind)`.

**Componentes compartidos:** `src/shared/pdf/PdfLetterhead.tsx`, `src/shared/pdf/PdfFooter.tsx`.

**Patrón de descarga** (`src/widgets/reports/model/pdf.ts`) — función `async` por reporte, `pdf(createElement(...)).toBlob()`, nombre con `fileName()` del i18n + fecha `YYYYMMDD`, y `saveAs`:

```ts
import { saveAs } from "file-saver";
import { pdf } from "@react-pdf/renderer";
import { createElement } from "react";
import { fileName } from "@shared/i18n";
import ReportPDF from "../ui/ReportPDF";

export const downloadReportPDF = async (rows: ReportRow[], filters?: ReportFilters): Promise<void> => {
  const blob = await pdf(createElement(ReportPDF, { rows, filters }) as any).toBlob();
  const now = new Date();
  const dd = String(now.getDate()).padStart(2, "0");
  const mm = String(now.getMonth() + 1).padStart(2, "0");
  const yy = now.getFullYear();
  saveAs(blob, `${fileName("deliveryReport")}_${yy}${mm}${dd}.pdf`);
};
```

Detalle: se usa `createElement` (no JSX) y `as any` por los tipos de `@react-pdf/renderer` v4. Otros nombres de archivo usan `meta.period` y `meta.date` (`setTimeout` de nombre: `${fileName("accessReport")}_${meta.period}_${meta.date}.pdf`).

**Widgets PDF existentes** (8): `custody-letter`, `disciplinary-report`, `movement-pdf`, `purchase-order-pdf`, `reports` (7 PDFs: `ReportPDF`, `DevicePDF`, `AssignedDevicesPdf`, `MaterialOutputsPdf`, `AccessReportPDF`, `OvertimePDF`, `WeeklyAttendancePdf`), `tickets/ticket-pdf`, más el preview HTML (`CustodyLetterHtml.tsx`, `DisciplinaryReportPreview.tsx`) para vista previa en pantalla.

**PDF en el navegador vs descarga:** se generan **client-side**; no hay endpoint de PDF en el backend.

### 10.5 QR / credencial de empleado — `src/widgets/employee-credential/`

Es el widget más elaborado (README propio). Contrato de payload **versionado**:

```ts
/**
 * Payload embebido en el QR de la credencial — esquema v2.
 * El QR codifica un único objeto JSON en UTF-8 con claves compactas...
 *   { "v": 2, "id": "8f3b…", "no": "1234", "name": "…", "pos": "…", "dept": "…" }
 * Los campos opcionales se omiten cuando vienen nulos o vacíos. `id` es la clave de negocio.
 */
export const QR_PAYLOAD_VERSION = 2;

export interface CredentialQrPayload { v: number; id: string; no?: string; name?: string; pos?: string; dept?: string; }

export const buildQrPayload = (profile: PersonalProfile): CredentialQrPayload => ({
  v: QR_PAYLOAD_VERSION,
  id: profile.id,
  no: clean(profile.employeeNumber),
  name: clean(profile.name),
  pos: clean(profile.jobTitle),
  dept: clean(profile.department?.name),
});

/** Serializa omitiendo claves vacías; orden de claves estable para que el símbolo sea reproducible. */
export const serializeQrPayload = (payload: CredentialQrPayload): string => {
  const data: Record<string, string | number> = { v: payload.v, id: payload.id };
  if (payload.no) data.no = payload.no;
  if (payload.name) data.name = payload.name;
  if (payload.pos) data.pos = payload.pos;
  if (payload.dept) data.dept = payload.dept;
  return JSON.stringify(data);
};
```

Generación del QR (nítido y luego reducido, sin suavizado):

```ts
const QR_PIXEL_SCALE = 12;

export const generateCredentialQr = async (profile: PersonalProfile): Promise<string> => {
  const text = serializeQrPayload(buildQrPayload(profile));
  const { modules } = QRCode.create(text, { errorCorrectionLevel: "M" });
  return QRCode.toDataURL(text, {
    width: modules.size * QR_PIXEL_SCALE,
    margin: 0,
    errorCorrectionLevel: "M",
    color: { dark: PDF_COLORS.band, light: PDF_COLORS.white },
  });
};
```

El widget separa `model/{buildQrPayload,credential,renderCredential,image,pngDpi,cardSpec,useEmployeeCredential}.ts` y `ui/{EmployeeCredentialDialog,EmployeeCredentialPreview}.tsx`. `cardSpec.ts` define `CREDENTIAL_W_PX`/`CREDENTIAL_H_PX`; `pngDpi.ts` resuelve el DPI del PNG exportado.

---

## 11. Testing, Docker/nginx y empaquetado Electron

### 11.1 Playwright — `playwright.config.ts` (completo resumido)

```ts
import { defineConfig, devices } from "@playwright/test";
import { E2E, healthUrl } from "./tests/e2e/support/env";

const PUERTO_WEB = new URL(E2E.webUrl).port || "5006";

export default defineConfig({
  testDir: "./tests/e2e",
  globalTeardown: "./tests/e2e/support/global-teardown.ts",

  // Serie a propósito: los tests comparten la base real, y el consecutivo de
  // préstamo del backend se calcula con `count() + 1` bajo aislamiento
  // Serializable, así que dos préstamos concurrentes chocan.
  fullyParallel: false,
  workers: 1,

  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },

  reporter: [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],

  use: {
    baseURL: E2E.webUrl,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "retain-on-failure",
    locale: "es-MX",                      // la app escribe montos y fechas en español
    timezoneId: "America/Mexico_City",    // mismo huso que la API por defecto
  },

  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/, use: { ...devices["Desktop Chrome"] } },
    { name: "chromium", dependencies: ["setup"],
      use: { ...devices["Desktop Chrome"], storageState: E2E.storageState } },
  ],

  // Los dos servidores reales: la API y la app. Si ya están corriendo, se reutilizan.
  webServer: [
    { command: "npm run dev", cwd: "../api", url: healthUrl, reuseExistingServer: true,
      timeout: 90_000, stdout: "ignore", stderr: "pipe" },
    { command: "npm run dev", url: `http://localhost:${PUERTO_WEB}`, reuseExistingServer: true,
      timeout: 120_000, stdout: "ignore", stderr: "pipe" },
  ],
});
```

**Estructura de `tests/`:**

```
tests/e2e/
├── *.spec.ts                  (24 specs)
├── .auth/admin.json           ← storageState (sesión autenticada)
└── support/
    ├── env.ts                 ← E2E.webUrl, E2E.storageState, healthUrl
    ├── auth.setup.ts          ← proyecto "setup": login y guarda storageState
    ├── fixtures.ts            ← fixtures custom
    ├── api.ts, accessApi.ts, ticketsApi.ts, overtimeSeed.ts   ← helpers de datos
    ├── global-teardown.ts
    └── pages/                 ← Page Objects: LoginPage.ts, NewLoanPage.ts, TicketsPage.ts, components.ts, ...
```

❌ **No hay tests unitarios ni de componentes** (no hay vitest/jest en `devDependencies`). Los 24 specs cubren flujos completos contra **API + DB reales**.

### 11.2 Docker — `Dockerfile` (multi-stage)

```dockerfile
# syntax=docker/dockerfile:1

# =========================
# Builder
# =========================
FROM node:20-bullseye-slim AS builder
WORKDIR /app

# pnpm (la caché de npm evita re-descargarlo en cada build).
RUN --mount=type=cache,target=/root/.npm npm install -g pnpm@10

# Dependencias primero: esta capa se reusa mientras no cambie el lockfile.
COPY package.json pnpm-lock.yaml .npmrc* ./
RUN --mount=type=cache,id=pnpm-store,target=/pnpm/store \
    pnpm install --frozen-lockfile --store-dir=/pnpm/store

# La imagen solo necesita el artefacto: `vite` (esbuild) compila sin tipar.
# El typecheck (`tsc -b`) sigue corriendo en `pnpm run build` (local/CI).
COPY . .
ARG VITE_API_URL
ENV VITE_API_URL=$VITE_API_URL
RUN pnpm run build:image

# =========================
# Runtime — nginx
# =========================
FROM nginx:1.27-alpine AS runtime
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --chmod=755 render-config.sh /docker-entrypoint.d/10-render-config.sh
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

`nginx.conf` (SPA fallback + cache largo + proxy al API + `config.js` en runtime):

```nginx
server {
  listen 80;
  server_name _;
  root /usr/share/nginx/html;
  index index.html;

  # SPA fallback — todas las rutas devuelven index.html
  location / { try_files $uri $uri/ /index.html; }

  # Cache largo para assets estáticos
  location ~* \.(?:js|css|woff2?|ttf|eot|ico|png|jpg|jpeg|gif|svg)$ {
    expires 1y;
    add_header Cache-Control "public, immutable";
    try_files $uri =404;
  }

  location /logo-puerto-nuevo.png { expires 1y; add_header Cache-Control "public, immutable"; }

  # Proxy al API
  location /api/ {
    proxy_pass http://api:4001/api/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_read_timeout 60s;
  }
}
```

`render-config.sh` → `/docker-entrypoint.d/10-render-config.sh` (nginx lo ejecuta al arrancar) genera `config.js` con `WEB_API_URL` (default `/api/v1`).

**CI** — `.github/workflows/docker-publish.yml`: en `push` a `main`, tags `v*` o `workflow_dispatch`; buildx + login Docker Hub (secretos `DOCKERHUB_USERNAME`/`DOCKERHUB_TOKEN`); **solo `linux/amd64`** (comentario: se quitó arm64 por QEMU, ~4 min por imagen), `provenance: false`, `sbom: false`, caché `type=gha`. Imagen: `axzydev/axzy_ptnv_web`, tags `latest` + tag de git.

### 11.3 Electron

- `electron/main.cts` (TypeScript en modo `NodeNext`, compilado a `dist-electron/` por `tsconfig.electron.json`).
- `electron/preload.cts` → puente `window.desktop` (`notify`, `onNotificationClick`).
- Carga `dist/` por **protocolo propio `app://ptnv`** (registrado como `standard: true, secure: true, supportFetchAPI: true, corsEnabled: true`) para que las rutas absolutas `/assets/…` y `/config.js` funcionen sin cambiar `base`, y `HashRouter` no necesite servidor.
- **Resolución de la URL del API** (en este orden): `config.json` en `userData` (menú *Servidor → Editar configuración*) → `PTNV_API_URL` → `desktop.apiUrl` del `package.json` empaquetado → `http://localhost:4001/api/v1`.
- `ELECTRON_DEV_URL` (p. ej. `http://localhost:5006`) carga el dev server de Vite.
- `electron-builder.yml`: `appId: com.axzydev.puertonuevo.erp`, salida `release/`, `files: [dist/**, dist-electron/**, package.json, "!node_modules/**/*"]`. Targets: **mac dmg (arm64+x64, firma ad-hoc `identity: "-"`, `hardenedRuntime: false` por comentario sobre notificaciones)**, **win nsis x64**, **linux AppImage x64**. Scripts: `desktop:build` (`build:prod` + `tsc -p tsconfig.electron.json`), `desktop`, `desktop:dev`, `desktop:dist`.

### 11.4 Skills de agentes

`.agents/skills/` (sincronizado por `skills-lock.json`, `version: 1`, `sourceType: "autoskills-registry"`) incluye 12 skills de terceros: `accessibility`, `bash-defensive-patterns`, `composition-patterns`, `frontend-design`, `nodejs-backend-patterns`, `nodejs-best-practices`, `playwright-best-practices`, `react-best-practices`, `seo`, `tailwind-css-patterns`, `typescript-advanced-types`, `vite`. No afectan el build; son guía para agentes.

---

## 12. Inventario completo de páginas y features

### 12.1 Páginas — **74 componentes de página** en **54 archivos** `src/pages/**/*.tsx`

Todas usan `ITPage` (ratio 74/74 de componentes; 53 de los 54 archivos lo renderizan directamente — `pages/kitchen/index.tsx` contiene 21 `<ITPage>`, uno por función).

⚠️ **Detalle estructural importante:** `src/pages/kitchen/index.tsx` (460 líneas) **no es un barrel**: define directamente las 21 funciones `Kitchen*Page` y las exporta con nombre. Es la única carpeta de `pages/` que agrupa varias páginas en un archivo.

| Dominio | Páginas (`src/pages/<dominio>/`) |
|---|---|
| `access` (6) | `AccessPage`, `AccessReportPage`, `TimeClockPage`, `TimeClockReportPage`, `TimeClockEmployeesPage`, `TimeClocksPage` |
| `auth` (1) | `LoginPage` |
| `catalog` (1) | `CatalogPage` |
| `departments` (2) | `DepartmentsPage`, `DepartmentDetailPage` |
| `employees` (7) | `EmployeesListPage`, `EmployeeRecordsPage`, `EmployeeDetailPage`, `EmployeeProfileEditPage`, `HrReportsPage`, `DisciplinaryReportDetailPage`, `DocumentCatalogPage` |
| `home` (1) | `HomePage` |
| `inventory` (15) | `DashboardPage`, `DevicesPage`, `DeviceFormPage`, `EditDevicePage`, `DeviceDetailPage`, `DeviceTypesPage`, `DeviceImportPage`, `MovementsPage`, `NewMovementPage`, `LoansPage`, `NewLoanPage`, `EditLoanPage`, `LoanDetailPage`, `ReturnsPage`, `NewLoanReturnPage` |
| `kitchen` (1 archivo, **21 páginas**) | **todas definidas y exportadas desde `src/pages/kitchen/index.tsx`**: `KitchenOverviewPage`, `KitchenItemsPage`, `KitchenItemDetailPage`, `KitchenItemImportPage`, `KitchenLotsPage`, `KitchenStockInPage`, `KitchenStockOutPage`, `KitchenLedgerPage`, `KitchenCountPage`, `KitchenRestockPage`, `KitchenCatalogPage`, `KitchenPurchaseOrdersPage`, `KitchenPurchaseOrderFormPage`, `KitchenPurchaseOrderDetailPage`, `KitchenCostCenterSpendingPage`, `KitchenInvoicesPage`, `KitchenInvoiceFormPage`, `KitchenInvoiceDetailPage`, `KitchenSuppliersPage`, `KitchenSupplierFormPage`, `KitchenSupplierDetailPage` |
| `notifications` (1) | `NotificationsPage` |
| `overtime` (1) | `OvertimeApprovalPage` |
| `reports` (1) | `ReportsPage` |
| `roles` (1) | `RolesPage` |
| `schedules` (4) | `SchedulesPage`, `ScheduleFormPage`, `AssignSchedulesPage`, `PayrollPage` |
| `subareas` (1) | `SubareasPage` |
| `tickets` (7) | `TicketsListPage`, `NewTicketPage`, `TicketDetailPage`, `EditTicketPage`, `KanbanPage`, `MyTasksPage`, `AdminTasksPage` |
| `users` (4) | `UsersListPage`, `UserFormPage`, `UserHistoryPage`, `UserImportPage` |

### 12.2 Features — 17 dominios, 56 slices

| Dominio | Slices (`features/<dominio>/<slice>/`) |
|---|---|
| `access` (4) | `report`, `time-clock`, `time-clock-employees`, `time-clocks` |
| `auth` (1) | `login` |
| `catalog` (1) | `catalog` ⚠️ (sin `model/`: solo `ui/` con 6 tabs: `SimpleCatalogTab`, `DeviceTypesTab`, `DocumentTypesTab`, `GendersTab`, `BloodTypesTab`, `TicketCategoriesTab`) |
| `department` (2) | `department-detail`, `departments-list` |
| `home` (2) | `admin-dashboard`, `role-dashboard` (con 12 widgets: `AccessToday`, `AttendanceToday`, `Kitchen`, `MyEquipment`, `OvertimeWeek`, `People`, `RecordsCharts`, `SetupGaps`, `SystemHealth`, `Tasks`, `Tickets` + `WidgetCard`) |
| `hr` (3) | `disciplinary-reports`, `employee-detail`, `employee-records-board` |
| `inventory` (4) | `audit`, `device-import`, `unit-identities`, `unit-picker` |
| `kitchen` (11) | `catalog`, `cost-centers-spending`, `count`, `import`, `invoices`, `items`, `ledger`, `lots`, `overview`, `purchase-orders`, `restock`, `stock-in`, `stock-out`, `suppliers` *(14)* |
| `notification` (1) | `list` |
| `report` (3) | `assigned-devices-tab`, `devices-tab`, `material-outputs-tab` |
| `roles` (1) | `roles-console` (7 hooks + 30 componentes en subcarpetas) |
| `schedule` (4) | `assign`, `overtime-approval`, `schedule-form`, `schedules-table`, `weekly-attendance` (+ barrel `features/schedule/index.ts`) |
| `subarea` (1) | `subareas-crud` |
| `sys-config` (1) | `sys-config` ⚠️ (sin `model/types.ts`; hook en `model/useSysConfig.ts` + barrel de dominio `features/sys-config/index.ts`) |
| `ticket` (6) | `create-ticket`, `edit-ticket`, `kanban`, `tasks-table`, `ticket-detail`, `tickets-list` |
| `user` (6) | `employees-list`, `import-users`, `user-form`, `user-history`, `user-permissions`, `users-list` |

### 12.3 Entidades — 18 (`src/entities/`)

`access`, `audit-log`, `dashboard`, `department`, `hr`, `inventory`, `kitchen`, `material-output`, `notification`, `overtime`, `permission`, `report`, `schedule`, `subarea`, `sys-config`, `ticket`, `time-clock`, `user`.

### 12.4 Widgets — 8 dominios / 13 slices (`src/widgets/`)

`catalog/{departments,subareas,tabs}`, `custody-letter`, `disciplinary-report`, `employee-credential`, `movement-pdf`, `purchase-order-pdf`, `reports`, `tickets/{kanban-ui,ticket-attachments,ticket-detail-modal,ticket-pdf}`.

### 12.5 Rutas completas (`src/app/App.tsx`, 79 `path="..."`)

```
/login
/
/inventory                                   /inventory/devices
/inventory/devices/import                    /inventory/devices/new
/inventory/devices/:id                       /inventory/devices/:id/edit
/inventory/device-types                      /inventory/movements
/inventory/movements/new                     /inventory/loans
/inventory/loans/new                         /inventory/loans/:id
/inventory/loans/:id/edit                    /inventory/returns
/inventory/returns/new
/tickets                                     /tickets/kanban
/tickets/my-tasks                            /tickets/tasks
/tickets/new                                 /tickets/:id/edit
/tickets/:id
/departments                                 /departments/:id
/subareas
/employees                                   /employees/records
/employees/catalogs/documents                /employees/:id/edit
/employees/disciplinary-reports              /employees/disciplinary-reports/:id
/employees/:id
/reports
/users                                       /users/new
/users/:id/edit                              /users/:id/history
/users/import
/catalogs                                    /roles
/notifications
/access                                      /access/report
/hr/time-clock                               /hr/time-clock/entries-exits
/hr/time-clock/employees                     /time-clocks
/schedules                                   /schedules/new
/schedules/:id/edit                          /schedules/assign
/schedules/overtime/approval                 /schedules/payroll
/kitchen                                     /kitchen/items
/kitchen/items/import                        /kitchen/items/:id
/kitchen/lots                                /kitchen/stock-in
/kitchen/stock-out                           /kitchen/movements
/kitchen/count                               /kitchen/restock
/kitchen/catalog                             /kitchen/purchase-orders
/kitchen/purchase-orders/new                 /kitchen/purchase-orders/:id/edit
/kitchen/purchase-orders/:id                 /kitchen/cost-centers
/kitchen/suppliers                           /kitchen/suppliers/new
/kitchen/suppliers/:id/edit                  /kitchen/suppliers/:id
/kitchen/invoices                            /kitchen/invoices/new
/kitchen/invoices/:id
*                                            → <Navigate to="/login" replace />
```

---

## 13. Checklist de replicación (lo que hay que copiar literalmente)

### Archivos a clonar tal cual
1. `eslint.config.js` — **el bloque de `boundaries` con las 6 políticas**.
2. `tsconfig.app.json` (paths) + `tsconfig.json` (referencias) + `tsconfig.node.json`.
3. `vite.config.ts` (alias + `define __APP_VERSION__` + dedupe de react/react-dom + plugin tailwind).
4. `src/shared/api/client.ts`, `session.ts`, `table.ts`, `clientTable.ts`, `constants/API_CONSTANTS.ts`.
5. `src/shared/lib/{ably,desktop,newId,useDebouncedValue,useIsMobile,useRequestKey}.ts`.
6. `src/shared/i18n/{config,index,dyn,format}.ts` + `i18next.d.ts`.
7. `src/shared/pdf/theme.ts` + `PdfLetterhead.tsx` + `PdfFooter.tsx`.
8. `src/app/main.tsx` (orden de providers), `store.ts` (con `setSessionHooks`), `guards/*`.
9. `src/app/index.css` (**el orden de `@layer` y el `@source` al kit son obligatorios**).
10. `Dockerfile`, `nginx.conf`, `render-config.sh`, `electron-builder.yml`, `playwright.config.ts`.

### Reglas de oro del template
1. **El barrel `index.ts` de cada entidad es la única puerta de entrada.** Nada importa `entities/x/model/*` ni `entities/x/api/*` desde fuera.
2. **`shared` nunca importa `entities` → se usa el patrón de hooks inyectables** (`session.ts` + `setSessionHooks`).
3. **`entities` nunca importa `@app`** → se declara una interfaz estructural local (`AuthStateLike`).
4. **Las features no importan widgets** → los PDFs y similares se **inyectan por props desde las páginas**.
5. **La feature expone `(hook, componente)` y la página hace `const fx = useX(); <XTab fx={fx} />`.** Un solo objeto `fx`.
6. **Toda la tabla pasa por `ITDataTable`** con `fetchData` server-side (`POST /x/query` + `serializeTableFilters`) o client-side (`makeClientTableFetch`).
7. **El menú se genera desde `APP_SCREENS`**, nunca hardcodeado en el sidebar.
8. **Triple gate de permisos:** token (PrivateRoutes) → permiso por ruta (`RequiresPermission`) → visibilidad del menú (`isScreenVisible`). El backend revalida.
9. **No hay lazy loading ni code splitting** (decisión deliberada: un solo bundle).
10. **i18n obligatorio**: 1 namespace por dominio, `es` + `en`, `common` como default, claves tipadas por `resources.es`.
11. **Sin tests unitarios**: solo E2E de Playwright contra API+DB reales.
12. **Comentarios en el código solo cuando explican una decisión no obvia** (y en español).

### ⚠️ Desviaciones / cosas a NO clonar
1. **`RequirePermission.tsx` (archivo) exporta `RequiresPermission` (componente)** — nombres desalineados.
2. **`ITBadget`** — el componente del kit está mal escrito con "t" y así se usa.
3. **Deep imports a `@entities/inventory/model/movementColors`** en 4 archivos, sin reexportar desde el barrel.
4. **`kitchen`** rompe la convención: 9 slices sin `index.ts`, importados en profundidad, y 21 páginas en un barrel `pages/kitchen/index.tsx` de 460 líneas.
5. **`yup` en `dependencies` sin usarse** (nunca se importa). También la fila "Validación: yup" en `docs/MODULES.md` es incorrecta.
6. **`ABLY_API_KEY` hardcodeada** en `src/shared/lib/ably.ts` mientras `VITE_ABLY_API_KEY` existe sin usarse. Sacar la clave del fuente.
7. **`docs/MODULES.md` está desactualizado**: describe 5 roles estáticos y helpers `isAdmin`/`canManage`/`canManageHR` que ya no existen; el modelo real es de permisos dinámicos con alcances `NONE|OWN|AREA|ALL`.
8. **Zero-copy del tipo genérico de `ITDataTable`**: obliga a `as unknown as Column<Record<string, unknown>>[]` en las columnas y en `fetchData`.
9. **El logo tiene texto hardcodeado** (`logoText: "Puerto Nuevo"`, `alt` en español) fuera de i18n.
10. **Sin `server.proxy` en Vite**: en dev hay que apuntar `VITE_API_URL` al backend directamente (o confiar en el default relativo `/api/v1`, que en dev no resuelve sin proxy).

### ❓ No verificable desde este repo
- La implementación del **dark mode**: `ITThemeProvider` lee `localStorage["it-theme-dark-mode"]`, pero el toggle y los tokens oscuros viven dentro del paquete `@axzydev/axzy_ui_system` (no inspeccionado).
- El **README.md del widget `employee-credential`** existe en el repo (`src/widgets/employee-credential/README.md`) y no fue leído en su totalidad; puede contener más detalle del render PNG/DPI.
- Existencia de documentos referenciados pero ausentes en el repo: `ROLES_Y_PERMISOS.md` (§2 citado en `types.ts`) y `TIME_CLOCK.md` (citado en `App.tsx`). **No están en el repositorio**, solo se referencian en comentarios.
