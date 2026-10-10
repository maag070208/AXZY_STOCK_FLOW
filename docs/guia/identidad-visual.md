# Identidad visual: colores, iconos y animaciones
> **Referencia.** Dónde vive cada recurso visual de STOCK por plataforma y qué está conectado.
> Última verificación: 2026-10-09.

## Colores

| Uso | Color | Dónde aparece |
|---|---|---|
| Azul marino | `#16315F` | Iconos (glifo y fondo) |
| Naranja | `#F28C28` | Iconos |
| Turquesa | `#3A86A8` | Iconos |
| Fondo claro | `#F2F6FC` | `theme-color` claro en `web/index.html`; `ic_launcher_background` en Android claro |
| Fondo oscuro | `#0C1730` | `theme-color` oscuro en `web/index.html`; `values-night/ic_launcher_background.xml` |

## Iconos por plataforma

| Plataforma | Ruta | Qué hay | Enlazado |
|---|---|---|---|
| Web | `web/public/` | `favicon.svg` (cambia con el tema del sistema), `favicon.ico`, `apple-touch-icon.png`, `icon-192`/`512`, `icon-maskable-192`/`512`, `site.webmanifest` | Sí, en `web/index.html` |
| iOS | `mobile-app/iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/` | Light, Dark y Tinted de 1024 px y `Contents.json` | Sí, por el catálogo de assets. No hay `.xcodeproj` ([lo-no-verificado](lo-no-verificado.md)) |
| Android | `mobile-app/androidApp/src/main/res/` | `mipmap-*` en cinco densidades; `mipmap-night-*` para el tema oscuro; `ic_launcher_background` en `values/` y `values-night/` | Sí, `android:icon="@mipmap/ic_launcher"` en `AndroidManifest.xml` |

El calificador `-night` hace que Android use `mipmap-night-*` cuando el sistema está en modo
oscuro. El icono que muestra el launcher lo elige el sistema según su configuración. No se ha
comprobado en un dispositivo.

## Animaciones

| Archivo | Ruta | Uso |
|---|---|---|
| `stockflow-loader-light.json`, `stockflow-loader-dark.json` | `web/src/shared/assets/lottie/` | Loader. Versionado; no se ha verificado su uso en pantalla |
| `stockflow-intro-light.json`, `stockflow-intro-dark.json` | `web/src/shared/assets/lottie/` | **Sin uso**: ninguna pantalla los carga. Pendiente decidir dónde va la intro |

Los cuatro son JSON de Lottie de 512 × 512 px.

## Fuera del repositorio

El paquete de iconos original vive fuera del repo (`Downloads/stockflow-icons 2/`) y no está
versionado. Sus archivos que el repo **no** tiene:

- `master/*.svg`: arte fuente. Hace falta para regenerar iconos.
- `web/dark/`: versiones PNG oscuras de los iconos web. La web no las enlaza.
- `web/favicon-16.png` y `favicon-32.png`: no enlazados; `favicon.ico` cubre esos tamaños.
- `web/head-snippet.html`: fragmento de referencia. `index.html` ya tiene esas etiquetas.
- `android/playstore-icon-*-512.png`: para subir a Google Play; es un artefacto de publicación.
