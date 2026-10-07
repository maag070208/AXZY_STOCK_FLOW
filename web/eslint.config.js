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
      "@typescript-eslint/no-unused-vars": [
        "warn",
        { argsIgnorePattern: "^_", varsIgnorePattern: "^_" },
      ],
    },
  },
  {
    /*
     * Archivos de composición: exportan el provider **y** el hook que lo
     * consume (`useSession`, `useToast`). No son componentes de un solo export,
     * así que la regla de Fast Refresh no aplica y no debe ensuciar el lint.
     */
    files: ["src/app/**/*.{ts,tsx}", "src/shared/ui/**/*.{ts,tsx}"],
    rules: {
      "react-refresh/only-export-components": "off",
    },
  },
  {
    // Tests E2E: corren en Node y no son código React. El `use()` de los
    // fixtures de Playwright no es el hook de React.
    files: ["tests/**/*.ts", "playwright.config.ts"],
    languageOptions: { globals: globals.node },
    rules: {
      "react-hooks/rules-of-hooks": "off",
      "react-refresh/only-export-components": "off",
      "no-empty-pattern": "off",
    },
  },
  {
    // Arquitectura FSD: la dirección de las dependencias es la regla, no una
    // convención. `app` conoce todo; `shared` no conoce a nadie; `entities` no
    // conoce `features`; `pages` no conoce `app` (salvo los tipos del store,
    // que se importan con `import type` desde `@app/store`).
    files: ["src/**/*.{ts,tsx}"],
    plugins: { boundaries },
    settings: {
      "import/resolver": {
        typescript: { project: "./tsconfig.json" },
      },
      "boundaries/elements": [
        { type: "app", pattern: "app/*" },
        { type: "shared", pattern: "shared/*" },
        { type: "entities", pattern: "entities/*/*" },
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
                  element: {
                    types: {
                      anyOf: ["app", "shared", "entities", "features", "widgets", "pages"],
                    },
                  },
                },
              },
            },
            {
              from: { element: { type: "shared" } },
              allow: { to: { element: { type: "shared" } } },
            },
            {
              from: { element: { type: "entities" } },
              allow: { to: { element: { types: { anyOf: ["entities", "shared"] } } } },
            },
            {
              from: { element: { type: "features" } },
              allow: { to: { element: { types: { anyOf: ["entities", "shared"] } } } },
            },
            {
              from: { element: { type: "widgets" } },
              allow: {
                to: { element: { types: { anyOf: ["features", "entities", "shared"] } } },
              },
            },
            {
              from: { element: { type: "pages" } },
              allow: {
                to: {
                  element: {
                    types: { anyOf: ["widgets", "features", "entities", "shared"] },
                  },
                },
              },
            },
          ],
        },
      ],
    },
  }
);
