# Despliegue
> **Plan.** Qué existe hoy para desplegar y qué falta. Última verificación: 2026-10-09.

## Qué existe

| Pieza | Dónde | Qué hace |
|---|---|---|
| Base de datos | `infra/docker-compose.yml`, `infra/postgres/` | PostgreSQL local con Docker |
| CI | `.github/workflows/fase-0-dominio.yml` | Compila y prueba el monorepo. Tiene un job aparte para el APK, porque `./gradlew build` de la raíz no lo incluye |
| Framework iOS | `scripts/ios.sh` | Genera el framework de Kotlin; no hay proyecto de Xcode en el repo |

## Qué falta

- **Despliegue del backend y de la web.** No hay Dockerfile de la API ni de la web en el repo.
  No hay definido dónde se publica.
- **Respaldos.** Ver [respaldos.md](respaldos.md).
- **Proyecto de iOS.** Ver [ARRANQUE.md](../../ARRANQUE.md).
- **HTTPS.** La decisión D8 del plan original indica HTTPS detrás de nginx en producción. No hay
  configuración de nginx en el repo.

Hasta cerrar estos puntos, el sistema se ejecuta en local y no hay nada que desplegar en un
ambiente compartido.
