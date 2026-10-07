// Configuración de desarrollo. En producción nginx sirve este archivo con el
// `API_URL` del entorno desplegado, así que no se recompila la web para
// apuntarla a otro backend.
window.__APP_CONFIG__ = {
  // Ruta relativa: el proxy de Vite (dev) o nginx (prod) la reenvía al backend.
  API_URL: "/v1",
};
