# Entornos y variables

> **Normativo.** Cómo se levanta cada parte y qué variables cambian su comportamiento. Última verificación: 2026-10-09.
> El arranque paso a paso está en [ARRANQUE.md](../../ARRANQUE.md).

## Puertos locales

| Parte | Comando | Puerto |
|---|---|---|
| Base de datos | `docker compose up -d` en `infra/` (`infra/docker-compose.yml`) | Ver `infra/docker-compose.yml` |
| API | `./scripts/api.sh` | 8082 |
| Web | `./scripts/web.sh` | 5173 |
| APK | `./scripts/movil-android.sh` | — |
| iOS | `./scripts/ios.sh` (genera el framework; no hay `.xcodeproj`) | — |

## Variables que cambian el comportamiento

| Variable | Dónde se lee | Efecto |
|---|---|---|
| `STOCK_SEED` | `scripts/api.sh` | `true` siembra el administrador inicial |
| `STOCK_ADMIN_PASSWORD` | `scripts/api.sh` | Fija la contraseña del administrador en lugar de generarla |
| `PORT` | `scripts/api.sh` | Puerto de la API (8082 por defecto) |
| `API_PORT` | `scripts/web.sh` | Puerto al que apunta el proxy `/v1` de Vite (8082 por defecto) |
| `VITE_API_URL` | `web/` | URL de la API en el build |
| `VITE_DEV_API_TARGET` | `web/vite.config.ts` | Destino del proxy `/v1`; `scripts/web.sh` lo fija desde `API_PORT` |

Sin `STOCK_ADMIN_PASSWORD`, la contraseña se genera y se imprime una sola vez. Quien arranca
por primera vez debe cambiarla: el sistema lo exige (`mustChangePassword`).

## Secretos

- La contraseña de la base sale de `infra/.env`, que no se versiona.
- La clave de firma de los JWT la genera el script de arranque.
- Ningún secreto va en `docker-compose.yml` ni en el código. El legacy sí lo tenía
  ([seguridad/owasp.md](../seguridad/owasp.md), S5).

## Ambientes

Solo existe el entorno local. No hay staging ni producción definidos en el repo.
La publicación está pendiente: [operacion/despliegue.md](despliegue.md).
