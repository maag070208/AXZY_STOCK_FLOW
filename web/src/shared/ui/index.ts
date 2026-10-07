/**
 * Componentes base compartidos.
 *
 * Se exportan por archivo (no con `export *`) para no arrastrar el provider del
 * tema en cada import de la app.
 */
export { PhasePlaceholder } from "./phase-placeholder";
export { PermissionScopeTag } from "./permission-scope-tag";
export { PageShell } from "./page-shell";
export { ThemeProvider, appTheme } from "./theme";
