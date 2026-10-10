import js from "@eslint/js";
import boundaries from "eslint-plugin-boundaries";
import reactHooks from "eslint-plugin-react-hooks";
import reactRefresh from "eslint-plugin-react-refresh";
import globals from "globals";
import tseslint from "typescript-eslint";

export default tseslint.config(
  { ignores: ["dist", "node_modules", "playwright-report", "test-results"] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    files: ["**/*.{ts,tsx}"],
    languageOptions: {
      ecmaVersion: 2020,
      globals: globals.browser,
    },
    plugins: {
      "react-hooks": reactHooks,
      "react-refresh": reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      "react-refresh/only-export-components": ["warn", { allowConstantExport: true }],
      "@typescript-eslint/no-explicit-any": "off",
      "@typescript-eslint/no-unused-vars": ["warn", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
    },
  },
  {
    // Las pruebas E2E corren en Node, no en el navegador.
    files: ["tests/**/*.ts", "playwright.config.ts", "vite.config.ts"],
    languageOptions: { globals: globals.node },
    rules: {
      "react-hooks/rules-of-hooks": "off",
      "react-refresh/only-export-components": "off",
      "no-empty-pattern": "off",
    },
  },
  {
    /*
     * FSD (docs/arquitectura/web-fsd.md §2.2). Una capa solo importa de las
     * inferiores; `default: disallow` hace que una capa nueva no herede permisos
     * por olvido. `app` se permite desde features/widgets/pages solo para los
     * tipos del store (`RootState`, `AppDispatch`), que se importan con
     * `import type` y desaparecen en el build.
     */
    files: ["src/**/*.{ts,tsx}"],
    plugins: { boundaries },
    settings: {
      "import/resolver": {
        typescript: { project: "./tsconfig.json" },
      },
      "boundaries/elements": [
        { type: "app", pattern: "app/*" },
        { type: "shared", pattern: "shared/*" },
        { type: "entities", pattern: "entities/*" },
        { type: "features", pattern: "features/*/*" },
        { type: "widgets", pattern: "widgets/*" },
        { type: "pages", pattern: "pages/*" },
      ],
    },
    rules: {
      "boundaries/dependencies": [
        "error",
        {
          default: "disallow",
          policies: [
            {
              from: { element: { type: "app" } },
              allow: {
                to: {
                  element: { types: { anyOf: ["app", "shared", "entities", "features", "widgets", "pages"] } },
                },
              },
            },
            { from: { element: { type: "shared" } }, allow: { to: { element: { type: "shared" } } } },
            {
              from: { element: { type: "entities" } },
              allow: { to: { element: { types: { anyOf: ["entities", "shared"] } } } },
            },
            {
              from: { element: { type: "features" } },
              allow: { to: { element: { types: { anyOf: ["app", "entities", "shared"] } } } },
            },
            {
              from: { element: { type: "widgets" } },
              allow: { to: { element: { types: { anyOf: ["app", "features", "entities", "shared"] } } } },
            },
            {
              from: { element: { type: "pages" } },
              allow: {
                to: { element: { types: { anyOf: ["app", "widgets", "features", "entities", "shared"] } } },
              },
            },
          ],
        },
      ],

    },
  },
  {
    /*
     * El UI system se usa a través de los envoltorios de `shared/ui`
     * (docs/arquitectura/web-react.md §4). `ITDataTable` y `ITPage` necesitan
     * adaptación —paginación 1-indexada, `limit` en lugar de `size`, tipado de
     * columnas— y por eso no se importan desde una pantalla, una feature o una
     * entidad: el que sabe hacerlo es el envoltorio.
     *
     * `shared/ui` queda fuera a propósito: es donde viven los envoltorios.
     */
    files: ["src/pages/**/*.{ts,tsx}", "src/features/**/*.{ts,tsx}", "src/widgets/**/*.{ts,tsx}", "src/entities/**/*.{ts,tsx}"],
    rules: {
      "no-restricted-syntax": [
        "error",
        {
          selector:
            "ImportDeclaration[source.value='@axzydev/axzy_ui_system'] ImportSpecifier[imported.name=/^(ITDataTable|ITPage)$/]",
          message:
            "Usa @shared/ui/data-table o @shared/ui/page: el envoltorio fija el tipado y traduce el contrato de paginación.",
        },
      ],
    },
  },
);
