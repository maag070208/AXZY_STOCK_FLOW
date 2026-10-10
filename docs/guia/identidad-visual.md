# Visual identity: colors, icons and animations
> **Reference.** Where each visual resource of StockFlow lives and what is connected.
> Last verified: 2026-10-10 (the brand package enters the repository).

## Colors

| Use | Color | Where it appears |
|---|---|---|
| Navy blue | `#16315F` | Icon glyph and background; `primary` of the theme; `theme_color` of the manifest |
| Orange | `#F28C28` | Icons; `alert` of the theme |
| Turquoise | `#3A86A8` | Icons; `secondary` and `info` of the theme |
| Light background | `#F2F6FC` | Application background (`app/index.css`), `background_color` of the manifest, `theme-color` in light |
| Dark background | `#0C1730` | `theme-color` in dark. Dark mode is **not** active: `app/main.tsx` sets it to `light` |

The theme colors are declared **only once** in `web/src/app/main.tsx`; the CSS only adjusts the
page background and the borders of the shell.

## The brand package

All the artwork is **versioned**, in a single place per platform. The original package
(`Downloads/stockflow-icons 2/`) is no longer needed to build or to regenerate: what a future regeneration would consume is also inside.

| Path | What it is | Who uses it |
|---|---|---|
| `web/public/` | `favicon.ico`, `favicon.svg` (changes with the system theme), `favicon-16/32.png`, `apple-touch-icon.png`, `icon-192/512`, `icon-maskable-192/512`, `site.webmanifest` | `web/index.html`. **A browser test checks that each declared file is actually served** |
| `web/public/dark/` | The same PNG versions in dark | **Nobody yet.** The `<link rel="apple-touch-icon">` does not support a variant by preference; they would be used if a dark manifest were ever served |
| `web/src/shared/assets/brand/` | The four source SVGs: glyph and icon, light and dark | The **light glyph** in the top bar (`app/guards/PrivateRoutes.tsx`) and the **light icon** on sign-in and the password change |
| `web/src/shared/assets/lottie/` | Four Lottie JSON files of 512 × 512 px | The **light loader**, while the catalog import works (`features/catalog/ImportProductsDialog`) |
| `brand/android/` | `light/res` and `dark/res` with `mipmap-*` in five densities, adaptive `foreground`, `monochrome` (Android 13+) and the background color; `playstore-icon-*-512.png` | **Phase 10.** It is copied to `app/src/main/res` when the app exists |
| `brand/ios/` | `AppIcon.appiconset` with Light, Dark and Tinted at 1024 px and `Contents.json` | **Phase 10.** It is dragged into `Assets.xcassets` |
| `brand/LEEME.txt` | The package manifest, as delivered by the designer | Documentation |

**Why the light artwork is used and not the dark.** Dark mode is fixed to `light`
(`app/main.tsx`), so the dark theme cannot be activated: using the dark glyph on a white background
would be a contrast error. The dark variants are versioned for when it is activated.

**The favicon SVG changes color on its own** (it carries a `@media (prefers-color-scheme: dark)` inside),
and that is why the browser bar does respect the system theme even though the application does not.

## Animations: the loader yes, the intro no

| File | Use | Status |
|---|---|---|
| `stockflow-loader-light.json` | The catalog import, while the API reads the file | **In use** |
| `stockflow-loader-dark.json` | — | Versioned; dark mode is off |
| `stockflow-intro-*.json` | — | **Unused**: it lengthens the path to the work screen and is not added without deciding to |

**The loader is loaded separately and with the lightweight engine.** Both things are measured with `vite build`:

| What | Size | Compressed |
|---|---|---|
| Initial bundle (without the loader) | 617 kB | **187 kB** |
| Loader wrapper, in the initial bundle | 2 kB | 1 kB |
| Lightweight engine + animation, in a separate chunk | 201 kB | **50 kB** |

`web/src/shared/ui/loader/Loader.tsx` uses dynamic `import()`, so the engine and the 33 kB of the
animation **are only downloaded when the loader appears**, and only once.

**Why `lottie-web` and not `lottie-react`, which is what CYC uses.** Because it was measured. `lottie-react`
produces a chunk of **762 kB (204 kB compressed)**: it drags in the full engine, with expressions,
controls and overlays, without the lightweight build reducing it (the three variants —
`Lottie`, `LottieSvg`, `LottieLight`— give the same size). The lightweight engine of `lottie-web`
(`build/player/lottie_light.min.js`, SVG only) is **167 kB (47.5 kB compressed)**: **157 kB
compressed less for the same visible result**.

The price is that the API is imperative —the animation is created in a `useEffect` and **destroyed** on
unmount— and that the lightweight engine's types have to be declared by hand
(`web/src/types/lottie-web-light.d.ts`), because the package only publishes those of its main entry.
It is in `Loader.tsx`, with the reason.
