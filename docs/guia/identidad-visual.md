# Identidad visual: colores, iconos y animaciones
> **Referencia.** Dónde vive cada recurso visual de StockFlow y qué está conectado.
> Última verificación: 2026-10-10 (el paquete de marca entra al repositorio).

## Colores

| Uso | Color | Dónde aparece |
|---|---|---|
| Azul marino | `#16315F` | Glifo y fondo del icono; `primary` del tema; `theme_color` del manifiesto |
| Naranja | `#F28C28` | Iconos; `alert` del tema |
| Turquesa | `#3A86A8` | Iconos; `secondary` e `info` del tema |
| Fondo claro | `#F2F6FC` | Fondo de la aplicación (`app/index.css`), `background_color` del manifiesto, `theme-color` en claro |
| Fondo oscuro | `#0C1730` | `theme-color` en oscuro. El modo oscuro **no** está activo: `app/main.tsx` lo fija en `light` |

Los colores del tema se declaran **una sola vez** en `web/src/app/main.tsx`; el CSS solo ajusta el
fondo de la página y los bordes del armazón.

## El paquete de marca

Todo el arte está **versionado**, en un solo sitio por plataforma. El paquete original
(`Downloads/stockflow-icons 2/`) ya no hace falta para construir ni para regenerar: lo que se
consumiría en una regeneración futura también está dentro.

| Ruta | Qué es | Quién lo usa |
|---|---|---|
| `web/public/` | `favicon.ico`, `favicon.svg` (cambia con el tema del sistema), `favicon-16/32.png`, `apple-touch-icon.png`, `icon-192/512`, `icon-maskable-192/512`, `site.webmanifest` | `web/index.html`. **Una prueba de navegador comprueba que cada archivo declarado se sirve de verdad** |
| `web/public/dark/` | Las mismas versiones PNG en oscuro | **Nadie todavía.** El `<link rel="apple-touch-icon">` no admite variante por preferencia; se usarían si algún día se sirve un manifiesto oscuro |
| `web/src/shared/assets/brand/` | Los cuatro SVG fuente: glifo e icono, claro y oscuro | El **glifo claro** en la barra superior (`app/guards/PrivateRoutes.tsx`) y el **icono claro** en la entrada y el cambio de contraseña |
| `web/src/shared/assets/lottie/` | Cuatro JSON de Lottie de 512 × 512 px | El **loader claro**, mientras la importación del catálogo trabaja (`features/catalog/ImportProductsDialog`) |
| `brand/android/` | `light/res` y `dark/res` con `mipmap-*` en cinco densidades, `foreground` adaptativo, `monochrome` (Android 13+) y el color de fondo; `playstore-icon-*-512.png` | **Fase 10.** Se copia a `app/src/main/res` cuando exista la app |
| `brand/ios/` | `AppIcon.appiconset` con Light, Dark y Tinted de 1024 px y `Contents.json` | **Fase 10.** Se arrastra a `Assets.xcassets` |
| `brand/LEEME.txt` | El manifiesto del paquete, tal como lo entregó el diseñador | Documentación |

**Por qué se usa el arte claro y no el oscuro.** El modo oscuro está fijado en `light`
(`app/main.tsx`), así que el tema oscuro no se puede activar: usar el glifo oscuro sobre un fondo
blanco sería un error de contraste. Las variantes oscuras están versionadas para cuando se active.

**El SVG del favicon cambia de color solo** (lleva un `@media (prefers-color-scheme: dark)` dentro),
y por eso la barra del navegador sí respeta el tema del sistema aunque la aplicación no lo haga.

## Animaciones: el loader sí, la intro no

| Archivo | Uso | Estado |
|---|---|---|
| `stockflow-loader-light.json` | La importación del catálogo, mientras la API lee el archivo | **En uso** |
| `stockflow-loader-dark.json` | — | Versionado; el modo oscuro está apagado |
| `stockflow-intro-*.json` | — | **Sin uso**: alarga el camino a la pantalla de trabajo y no se pone sin decidirlo |

**El loader se carga aparte y con el motor ligero.** Las dos cosas están medidas con `vite build`:

| Qué | Tamaño | Comprimido |
|---|---|---|
| Paquete inicial (sin el loader) | 617 kB | **187 kB** |
| Envoltorio del loader, en el inicial | 2 kB | 1 kB |
| Motor ligero + animación, en un fragmento aparte | 201 kB | **50 kB** |

`web/src/shared/ui/loader/Loader.tsx` usa `import()` dinámico, así que el motor y los 33 kB de la
animación **solo se descargan cuando el loader aparece**, y una sola vez.

**Por qué `lottie-web` y no `lottie-react`, que es lo que usa CYC.** Porque se midió. `lottie-react`
deja un fragmento de **762 kB (204 kB comprimidos)**: arrastra el motor completo, con expresiones,
controles y superposiciones, sin que la compilación ligera lo reduzca (las tres variantes —
`Lottie`, `LottieSvg`, `LottieLight`— dan el mismo tamaño). El motor ligero de `lottie-web`
(`build/player/lottie_light.min.js`, solo SVG) son **167 kB (47,5 kB comprimidos)**: **157 kB
comprimidos menos para el mismo resultado visible**.

El precio es que la API es imperativa —se crea la animación en un `useEffect` y **se destruye** al
desmontar— y que hay que declarar los tipos del motor ligero a mano
(`web/src/types/lottie-web-light.d.ts`), porque el paquete solo publica los de su entrada principal.
Está en `Loader.tsx`, con el porqué.
