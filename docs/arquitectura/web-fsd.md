# Web — Feature-Sliced Design (`web/`)
> **Normativo.** Arquitectura de la web React y sus límites de importación.
> Última verificación: 2026-10-09.
> **Nota de reconstrucción (2026-10-10).** Capas FSD vigentes para la web nueva ([web-react.md](web-react.md)). Las reglas siguen vigentes; las rutas de código que cita pertenecen al sistema anterior y se remapean al código nuevo (ver [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

La decisión está en [ADR 0002](../adr/0002-fsd-en-la-web.md).

## 2.1 La arquitectura: FSD (Feature-Sliced Design)

```
web/src/
├── app/        providers, router, guards, estilos globales
├── pages/      una carpeta por pantalla
├── widgets/    bloques grandes reutilizables (layout, tabla)
├── features/   acciones con significado de negocio (login, capture)
├── entities/   modelos del negocio y su acceso a datos (catalog, reception)
└── shared/     api, ui, lib, config — sin lógica de negocio
```

Las carpetas existen tal cual en el árbol (`web/src/app`, `web/src/pages`,
`web/src/widgets`, `web/src/features`, `web/src/entities`, `web/src/shared`).

## 2.2 La regla de dependencias — la única que importa de verdad

> **Una capa solo puede importar de las capas inferiores.**

```
app  →  pages  →  widgets  →  features  →  entities  →  shared
```

**Prohibido**, y es la razón de existir de FSD:

- Una **página** no importa de otra página.
- Una **feature** no importa de otra feature.
- **`shared/` no importa de nada.** Si `shared/` importa de `entities/`, se ha roto.
- **`pages` no importa de `app`**, salvo los tipos del store con `import type`.

**Por qué:** sin esta regla, dos pantallas acaban acopladas y cambiar una rompe la otra sin
que nadie lo vea hasta el despliegue.

**La regla está impuesta por el linter, no por disciplina.**
`web/eslint.config.js` declara los elementos de `boundaries/elements` (`app`, `shared`,
`entities`, `features`, `widgets`, `pages`) y una política de `boundaries/dependencies` con
`default: "disallow"` y las relaciones permitidas capa a capa:

| Desde | Puede importar |
|---|---|
| `app` | todo (`app`, `shared`, `entities`, `features`, `widgets`, `pages`) |
| `shared` | solo `shared` |
| `entities` | `entities`, `shared` |
| `features` | `app`, `entities`, `shared` |
| `widgets` | `app`, `features`, `entities`, `shared` |
| `pages` | `app`, `widgets`, `features`, `entities`, `shared` |

`app` aparece en `features`, `widgets` y `pages` por una razón concreta y acotada: los tipos del
store (`RootState`, `AppDispatch`) se importan con `import type` para tipar los `useSelector` y los
`dispatch`. El linter no distingue un `import type`, así que la relación se permite y la disciplina
la pone la revisión: **nunca un valor de `app` desde una capa inferior**.

`pnpm lint` (`eslint . --max-warnings=0`, `web/package.json`) falla si se rompe, y **se comprobó que
falla**: con una sonda que importaba `@pages` desde `shared`, el linter respondió
`There is no policy allowing dependencies from elements of type "shared" to elements of type "pages"`
(docs/pruebas/verificacion.md §2).

**La segunda guardia del linter.** Una página, una feature o una entidad **no pueden importar
`ITDataTable` ni `ITPage`** del UI system: se usan los envoltorios de `shared/ui`
([web-react.md](web-react.md) §4). Lo impone una regla `no-restricted-syntax`, y también se comprobó
haciéndola fallar.

## 2.3 Reglas concretas que el compilador no detecta

**1. Las rutas van sin `/v1`.** `API_CONSTANTS.BASE_URL` ya vale `/v1`
(`web/src/shared/api/constants/API_CONSTANTS.ts`).

```ts
get("/invoices/pending-approval")       // ✅
get("/v1/invoices/pending-approval")    // ❌ → /v1/v1/... → 404
```

**Ni `tsc` ni `eslint` detectan esto**: ninguno valida una URL en ejecución. Un error de
ruta relativa **solo se ve llamándola**. Por eso: **se prueba con `curl` antes de escribir
la interfaz, siempre** (ver [verificacion.md](../pruebas/verificacion.md)).

**2. El nombre visible de una ubicación lo compone el servidor.** El cliente nunca concatena
`hall + code + position`. Si cambia el formato de las etiquetas, no hay que tocar el
backend, la web y el móvil a la vez. La composición está en
`backend/src/main/kotlin/dev/axzy/stock/backend/application/shared/LocationDisplayName.kt`
(`locationDisplayName`).

**3. Los datos de servidor son `string` decimal, no `number`.** `"5193.000"`. Tipar contra el
contrato en lugar de contra la respuesta real pinta `undefined`.

**4. Una pantalla sin endpoint no muestra filas inventadas.** Dice qué hará y que el endpoint
no existe. **Una tabla con datos falsos es peor que una pantalla vacía**, porque el usuario
decide con ellos.
