# Web: React with AXZY UI System
> **Normative.** How the StockFlow web is built with `@axzydev/axzy_ui_system`. The conventions come from real use in `~/DEV/CYC/web` (same product family, same UI system version). Last revised: 2026-10-09. Complements [web-fsd.md](web-fsd.md), which defines the layers.

## 1. Version

```json
"@axzydev/axzy_ui_system": "^1.4.7"
```

- The range `^1.4.7` is used, as in CYC. The lockfile pins the exact installed version; in CYC it is `1.4.7`.
- A minor version update is tested in the web before the lockfile is updated, because
  it changes components that every screen uses.
- Earlier versions (`1.3.x`) are not used: the `d.ts` of 1.3.6 does not have the props of §3 that
  the web needs.

## 2. Application entry

`main.tsx` mounts the providers in this order, and the order matters:

```tsx
createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <Provider store={store}>                                  {/* global state */}
      <ITThemeProvider theme={customTheme} showFab={false}    {/* UI system theme */}
        density={1} appearance="flat" radius={10} shadow={1}>
        <ToastProvider>                                       {/* notifications */}
          <HashRouter>                                        {/* routes */}
            <App />
          </HashRouter>
        </ToastProvider>
      </ITThemeProvider>
    </Provider>
  </React.StrictMode>
);
```

- `customTheme` is a complete `ITThemePalette`. The mandatory keys in 1.4.7 are `primary`,
  `secondary`, `ternary`, `danger`, `success`, `info`, `alert`, `warning`, and the objects `layout`
  (`sidebarBg`, `sidebarText`, `navbarBg`, `navbarText`) and `table` (`headerBg`, `headerText`,
  `rowBg`, `rowText`, `rowHover`). The StockFlow colors are defined once, here.
- `appearance="flat"` (light background, no shadows, thin border) is the visual identity of
  [guia/identidad-visual.md](../guia/identidad-visual.md). Changing it is a design decision, not a technical one.
- Dark mode is not used. CYC's `main.tsx` sets `it-theme-dark-mode` to `light` if it does not exist;
  StockFlow does the same so that the theme does not depend on the operating system preference.

## 3. Components with a common contract

In CYC, `ITDataTable` appears in 46 files and `ITPage` in 27. The props that repeat are the
web's contract: they are encapsulated in two components of `shared/ui` and the screens do not repeat them.

### 3.1 `ITDataTable`

The props that the CYC tables repeat in 46 files:

| Prop | Value used in CYC | What it controls |
|---|---|---|
| `columns` | `Column<T>[]` | Column definition |
| `fetchData` | `(params) => Promise<{ data, total }>` | Pagination, filters and sorting **on the server** |
| `reloadTrigger` | Counter that changes after saving | Requests the current page again |
| `defaultItemsPerPage` | `25` | Rows per page on entry |
| `itemsPerPageOptions` | `[25, 50, 100]` | Pager options |
| `layout` | `"fixed"` | Respects the width of each column; avoids horizontal scroll |
| `density` | `"compact"` | Row height and padding |
| `virtualized` | `true` | Renders only the visible rows |
| `virtualizedMaxHeight` | `400` | Height of the container with scroll |
| `rowHeight` | `50` | Row height used by the virtualizer |

**Problem found.** Each table converts its columns and its `fetchData` with double casts:

```tsx
columns={columns as unknown as Column<Record<string, unknown>>[]}
fetchData={fetchData as unknown as (p: ITDataTableFetchParams) => Promise<ITDataTableResponse<Record<string, unknown>>>}
```

There are 85 `as unknown as` in CYC, 29 of them just for `Column`. A double cast turns off the compiler: a
field renamed in the response gives no error anywhere.

**Decision.** `shared/ui/data-table` is a generic component that:

1. Receives `T` and the columns typed with `T`, without casts in the screens.
2. Fixes the values of the table in §3.1 as defaults; a screen only passes what changes.
3. Exposes `fetchData` as `(params) => Promise<Page<T>>`, with `Page<T>` defined once in `shared/api`.

### 3.2 `ITPage`

The props that the CYC pages repeat in 27 files:

| Prop | Use | Note |
|---|---|---|
| `title`, `description`, `icon` | Header | Always present |
| `breadcrumbs` | Breadcrumbs | Built with a hook (`useBreadcrumbs`), not by hand |
| `actions` | Header buttons | Filters and the main action |
| `noPadding` | Almost always `true` | The padding comes from the page container |
| `className` | `"m-0! px-4! max-w-screen!"` | Repeated on almost every page |

**Problem found.** The string `"m-0! px-4! max-w-screen!"` is copied into every page to fight the default padding of
`ITPage`. `ITPage` already has `noPadding`, `horizontalPadding` and `maxWidth` for that;
the `!` (important) flags are a symptom that the container is not well defined.

**Decision.** `shared/ui/page` wraps `ITPage` with the StockFlow padding and width. The screens pass only
`title`, `description`, `icon`, `breadcrumbs` and `actions`. If a page needs another width, it passes
`maxWidth`; it does not rewrite classes.

**Tailwind rule.** `ITPage` warns in its documentation that `horizontalPadding` must be written
as a literal, because Tailwind only generates the classes it sees in the source code. The same applies
to all the classes in §6.

## 4. Index of shared components

The wrappers of §3 live in `src/shared/ui/`, which is the only layer that can import from the UI system
in the pages. A screen does not import `ITDataTable` or `ITPage` directly.

| Wrapper | Wraps | Adds |
|---|---|---|
| `shared/ui/data-table` | `ITDataTable` | Typing without casts, §3.1 defaults |
| `shared/ui/page` | `ITPage` | Padding, maximum width, breadcrumbs |
| `shared/ui/kpi-tile` | Indicator card | Common color tone |

An ESLint rule (`eslint-plugin-boundaries`, see [stack.md](stack.md)) prevents a page
from importing from the UI system directly.

## 5. Stylesheet: `index.css` and the layer order

**Status: pending decision, with a test that resolves it.**

CYC imports like this:

```css
@layer theme, base, components, utilities, axzy-ui-system;

@import "tailwindcss";
@import "@axzydev/axzy_ui_system/dist/index.css" layer(axzy-ui-system);
```

The package README documents the opposite order:

```css
@layer theme, base, axzy-ui-system, components, utilities;
```

**Both claims have arguments.**

- With the README's order, the app's utilities (`utilities`) win over the UI system's. That is what
  the README asks for so that the kit's reset does not override the app.
- With CYC's order, the UI system's utilities win over the app's. That is what made the desktop sidebar work: the
  app's `hidden` class, in the `utilities` layer, does not cover the kit's `lg:flex`. CYC's commit `b016baf` changed the order and removed the `.hidden` workaround based on that.

**Test that decides.** With the web backend running and a user with a session:

1. Desktop at 1280 px: the sidebar must be visible. Capture with each order.
2. A screen with tables and a button with an app class (`hidden` on an element of the kit): the element must behave as the app's class defines.
3. The order that passes both is chosen. If neither passes both, an ADR is opened.

**Status (2026-10-10): the README's order is used, and test 1 is green.**
`web/src/app/index.css` declares `@layer theme, base, axzy-ui-system, components, utilities;` and a
browser test (`web/tests/catalog.spec.ts`, "the side menu is visible at 1280 px") checks that the menu is **visible** —not just that it exists in the DOM— with the viewport at 1280 × 800.

Test 2 is still not verified: there is not yet any element of the kit with an application `hidden` class.
The order is not changed without it; if the case appears, the test is run and, if the README's order does not pass it, it is changed and noted here.

## 6. Tailwind

- `tailwindcss` `4.3.3` and `@tailwindcss/vite` `^4.3.3`, as in CYC.
- The plugin goes in `vite.config.ts`. The aliases `@app`, `@shared`, `@features`, `@entities`, `@widgets`
  and `@pages` are configured in `vite.config.ts` and in `tsconfig` at the same time.
- **`dist/index.css` already ships the compiled classes of the kit.** `@source` is not needed for
  `node_modules/@axzydev/axzy_ui_system`. `@source` is only used if the kit's source code is imported,
  which is not done.
- Classes are written as literals in the code. A class built with templates
  (`` `px-${n}` ``) is not generated and does not appear in the final stylesheet.

## 7. What is not done

- The kit's styles are not rewritten inside the app. If a component needs a different look, the kit is asked for a
  prop or a variant.
- Tailwind `!` (important) is not used to win over a kit style: it is the symptom of a badly ordered layer (§5).
- Kit components are not imported from the pages (§4).

## 8. Session and browser tests

**The session lives in memory, and reloading the page closes it.** The tokens are in the Redux state,
never in `localStorage` or `sessionStorage`: a page whose data can be read from the same origin
must not store tokens that work outside the tab. The consequence is that a reload asks to sign in
again. That is why the tests enter each module **with a click on the menu** and not with `goto`: a
`goto` to `/stock` reloads and loses the session.

The browser tests are split into two classes:

| File | API | What it asserts |
|---|---|---|
| `tests/login.spec.ts` | Real (`127.0.0.1:8081`) | That the web and the API understand each other: sign-in, the credentials error and the screen the API decides |
| `tests/catalog.spec.ts`, `tests/modules.spec.ts`, `tests/receiving.spec.ts`, `tests/transfers.spec.ts`, `tests/orders.spec.ts`, `tests/labels.spec.ts` | Mocked with `page.route` | The interface, the **exact request** the web makes and what it shows for each API error |

Four things learned when writing them:

- **`page.route` gives priority to the last registered route.** And Playwright's `?` glob matches
  any character, not only a question mark: `**/v1/transfers?*` also matches `/v1/transfers/in-transit`. That is why the specific routes are registered **after** the general one.
- **A test that waits for a route has to wait for the request, not the screen.** `requestTo(page,
  pathname)` fails if the web requests another route, even if the table stays empty and "looks right".
- **Labels requests `/v1/labels/jobs`.** The print queue does not live under `/v1/label-templates`:
  `/label-templates/jobs` falls into the template detail (`{id}`) and answers 400. The labels test
  checks the real route so that the regression fails again in the suite.
- **The sign-in test's credentials are its own** (`E2E_ADMIN_PASSWORD`): see ARRANQUE.md.


### 8.1 Writes: how the actions are distributed

A write action lives on its detail screen (`/invoices/:id`, `/transfers/:id`, `/orders/:id`)
or in the queue (reprint). The pattern is the same everywhere:

- **The button is offered according to permission and state**, but the API decides the same way. If the state does not allow the
  action, the API answers its code (`INVALID_TRANSITION`, `LINE_ALREADY_RESOLVED`, …) and the web shows it
  as is, without inventing a message.
- **What the web knows will fail is not sent.** A shortage without a reason, a count with fractional boxes or an empty
  decision reason are detected in the dialog; the test asserts that **no request goes out**.
- **After each action the resource is requested again** (or the response is used if it carries the full resource). What is
  shown is what the server has.
- **Actions that move stock or write to the ERP ask for confirmation** when they cannot be undone
  from the screen: cancelling a dispatched transfer returns goods to the origin.
- **The list of adjustment reasons** lives in `shared/lib/adjustmentReasons.ts`. The API does not expose it; it mirrors
  the domain enum and has to be updated if the domain gains a reason.
