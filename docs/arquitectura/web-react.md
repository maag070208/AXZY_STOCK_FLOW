# Web: React con AXZY UI System
> **Normativo.** Cómo se construye la web de StockFlow con `@axzydev/axzy_ui_system`. Las convenciones salen del uso real en `~/DEV/CYC/web` (misma familia de productos, misma versión del UI system). Última revisión: 2026-10-09. Complementa a [web-fsd.md](web-fsd.md), que define las capas.

## 1. Versión

```json
"@axzydev/axzy_ui_system": "^1.4.7"
```

- Se usa el rango `^1.4.7`, como en CYC. El lockfile fija la versión exacta instalada; en CYC es `1.4.7`.
- Una actualización de versión menor se prueba en la web antes de subir el lockfile, porque
  cambia componentes que todas las pantallas usan.
- Versiones anteriores (`1.3.x`) no se usan: el `d.ts` de 1.3.6 no tiene las props de §3 que
  la web necesita.

## 2. Entrada de la aplicación

`main.tsx` monta los proveedores en este orden, y el orden importa:

```tsx
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <Provider store={store}>                                  {/* estado global */}
      <ITThemeProvider theme={customTheme} showFab={false}    {/* tema del UI system */}
        density={1} appearance="flat" radius={10} shadow={1}>
        <ToastProvider>                                       {/* notificaciones */}
          <HashRouter>                                        {/* rutas */}
            <App />
          </HashRouter>
        </ToastProvider>
      </ITThemeProvider>
    </Provider>
  </React.StrictMode>
);
```

- `customTheme` es un `ITThemePalette` completo. Las claves obligatorias en 1.4.7 son `primary`,
  `secondary`, `ternary`, `danger`, `success`, `info`, `alert`, `warning`, y los objetos `layout`
  (`sidebarBg`, `sidebarText`, `navbarBg`, `navbarText`) y `table` (`headerBg`, `headerText`,
  `rowBg`, `rowText`, `rowHover`). Los colores de StockFlow se definen una sola vez, aquí.
- `appearance="flat"` (fondo claro, sin sombras, borde fino) es la identidad visual de
  [guia/identidad-visual.md](../guia/identidad-visual.md). Cambiarla es una decisión de diseño, no técnica.
- El modo oscuro no se usa. `main.tsx` de CYC fija `it-theme-dark-mode` en `light` si no existe;
  StockFlow hace lo mismo para que el tema no dependa de la preferencia del sistema operativo.

## 3. Componentes con contrato común

En CYC, `ITDataTable` aparece en 46 archivos e `ITPage` en 27. Las props que se repiten son el
contrato de la web: se encapsulan en dos componentes de `shared/ui` y las pantallas no las repiten.

### 3.1 `ITDataTable`

Las props que las tablas de CYC repiten en 46 archivos:

| Prop | Valor usado en CYC | Qué controla |
|---|---|---|
| `columns` | `Column<T>[]` | Definición de columnas |
| `fetchData` | `(params) => Promise<{ data, total }>` | Paginación, filtros y orden **en el servidor** |
| `reloadTrigger` | Contador que cambia tras guardar | Vuelve a pedir la página actual |
| `defaultItemsPerPage` | `25` | Filas por página al entrar |
| `itemsPerPageOptions` | `[25, 50, 100]` | Opciones del paginador |
| `layout` | `"fixed"` | Respeta el ancho de cada columna; evita scroll horizontal |
| `density` | `"compact"` | Altura de fila y padding |
| `virtualized` | `true` | Solo renderiza las filas visibles |
| `virtualizedMaxHeight` | `400` | Altura del contenedor con scroll |
| `rowHeight` | `50` | Altura de fila que usa el virtualizador |

**Problema encontrado.** Cada tabla convierte sus columnas y su `fetchData` con dobles casts:

```tsx
columns={columns as unknown as Column<Record<string, unknown>>[]}
fetchData={fetchData as unknown as (p: ITDataTableFetchParams) => Promise<ITDataTableResponse<Record<string, unknown>>>}
```

Hay 85 `as unknown as` en CYC, 29 de ellos solo para `Column`. Un cast doble apaga el compilador: un
campo renombrado en la respuesta no da error en ningún sitio.

**Decisión.** `shared/ui/data-table` es un componente genérico que:

1. Recibe `T` y las columnas tipadas con `T`, sin casts en las pantallas.
2. Fija los valores de la tabla de §3.1 como defaults; una pantalla solo pasa lo que cambia.
3. Expone `fetchData` como `(params) => Promise<Page<T>>`, con `Page<T>` definido una vez en `shared/api`.

### 3.2 `ITPage`

Las props que las páginas de CYC repiten en 27 archivos:

| Prop | Uso | Nota |
|---|---|---|
| `title`, `description`, `icon` | Encabezado | Siempre presentes |
| `breadcrumbs` | Migas de pan | Se construyen con un hook (`useBreadcrumbs`), no a mano |
| `actions` | Botones del encabezado | Filtros y acción principal |
| `noPadding` | Casi siempre `true` | El padding lo da el contenedor de la página |
| `className` | `"m-0! px-4! max-w-screen!"` | Se repite en casi todas las páginas |

**Problema encontrado.** La cadena `"m-0! px-4! max-w-screen!"` aparece copiada en cada página para
pelear contra el padding por defecto de `ITPage`. `ITPage` ya tiene `noPadding`, `horizontalPadding` y
`maxWidth` para eso; las banderas `!` (importantes) son un síntoma de que el contenedor no está bien
definido.

**Decisión.** `shared/ui/page` envuelve `ITPage` con el padding y el ancho de StockFlow. Las pantallas
pasan solo `title`, `description`, `icon`, `breadcrumbs` y `actions`. Si una página necesita otro
ancho, pasa `maxWidth`; no reescribe clases.

**Regla de Tailwind.** `ITPage` avisa en su documentación que `horizontalPadding` debe escribirse
como literal, porque Tailwind solo genera las clases que ve en el código fuente. Lo mismo vale
para todas las clases de §6.

## 4. Índice de componentes compartidos

Los wrappers de §3 viven en `src/shared/ui/`, que es la única capa que puede importar del UI system
en las páginas. Una pantalla no importa `ITDataTable` ni `ITPage` directamente.

| Wrapper | Envuelve | Añade |
|---|---|---|
| `shared/ui/data-table` | `ITDataTable` | Tipado sin casts, defaults de §3.1 |
| `shared/ui/page` | `ITPage` | Padding, ancho máximo, migas |
| `shared/ui/kpi-tile` | Tarjeta de indicador | Tono de color común |

Una regla de ESLint (`eslint-plugin-boundaries`, ver [stack.md](stack.md)) impide que una página
importe del UI system directamente.

## 5. Hoja de estilos: `index.css` y el orden de capas

**Estado: decisión pendiente, con una prueba que la resuelve.**

CYC importa así:

```css
@layer theme, base, components, utilities, axzy-ui-system;

@import "tailwindcss";
@import "@axzydev/axzy_ui_system/dist/index.css" layer(axzy-ui-system);
```

El README del paquete documenta el orden contrario:

```css
@layer theme, base, axzy-ui-system, components, utilities;
```

**Las dos afirmaciones tienen argumentos.**

- Con el orden del README, las utilidades de la app (`utilities`) ganan a las del UI system. Es lo que
  el README pide para que el reset del kit no pise la app.
- Con el orden de CYC, las utilidades del UI system ganan a las de la app. Es lo que hacía funcionar el
  sidebar en escritorio: la clase `hidden` de la app, en la capa `utilities`, no tapa a `lg:flex` del kit.
  El commit `b016baf` de CYC cambió el orden y quitó el workaround de `.hidden` basándose en eso.

**Prueba que decide.** Con el backend de la web levantado y un usuario con sesión:

1. Desktop a 1280 px: el sidebar debe verse. Captura con cada orden.
2. Una pantalla con tablas y un botón con clase de la app (`hidden` en un elemento del kit): el elemento debe
   comportarse como lo define la clase de la app.
3. Se elige el orden que pasa ambas. Si ninguno pasa las dos, se abre un ADR.

**Estado (2026-10-10): se usa el orden del README, y la prueba 1 está en verde.**
`web/src/app/index.css` declara `@layer theme, base, axzy-ui-system, components, utilities;` y una
prueba de navegador (`web/tests/catalog.spec.ts`, «el menú lateral se ve a 1280 px») comprueba que el
menú se **ve** —no solo que exista en el DOM— con el viewport en 1280 × 800.

La prueba 2 sigue sin verificar: no hay todavía ningún elemento del kit con una clase `hidden` de la
aplicación. No se cambia el orden sin ella; si aparece el caso, se hace la prueba y, si el orden del
README no la pasa, se cambia y se anota aquí.

## 6. Tailwind

- `tailwindcss` `4.3.3` y `@tailwindcss/vite` `^4.3.3`, como en CYC.
- El plugin va en `vite.config.ts`. Los alias de `@app`, `@shared`, `@features`, `@entities`, `@widgets`
  y `@pages` se configuran en `vite.config.ts` y en `tsconfig` a la vez.
- **`dist/index.css` ya trae compiladas las clases del kit.** No hace falta `@source` para
  `node_modules/@axzydev/axzy_ui_system`. Solo se usa `@source` si se importa el código fuente del kit,
  lo que no se hace.
- Las clases se escriben como literales en el código. Una clase armada con plantillas
  (`` `px-${n}` ``) no se genera y no aparece en la hoja final.

## 7. Qué no se hace

- No se reescriben estilos del kit dentro de la app. Si un componente necesita otro aspecto, se pide al
  kit una prop o una variante.
- No se usan `!` (importantes) de Tailwind para ganar a un estilo del kit: es el síntoma de una capa mal
  ordenada (§5).
- No se importan componentes del kit desde las páginas (§4).
