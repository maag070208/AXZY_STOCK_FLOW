# ADR-0016 — Docker Compose para el entorno, con migraciones como servicio aparte
> **Normativo.** Decisión aceptada. Detalle en [arquitectura/infraestructura.md](../arquitectura/infraestructura.md).

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

El arranque anterior exigía tres terminales y scripts que derivaban credenciales de un archivo
local. Un arranque con `STOCK_DB_RUN_MIGRATIONS=false` por defecto dejó a la base de desarrollo
sin la migración `V9` durante semanas: los traslados respondían 500 sin que nadie lo viera hasta
probarlos.

## Decisión

1. **Docker Compose** levanta el entorno completo con un comando: PostgreSQL, el servicio de
   migraciones, la API y la web.
2. **Las migraciones son un servicio propio** (`migrate`), que corre Flyway y termina. La API
   **depende** de que `migrate` termine bien (`condition: service_completed_successfully`). La
   API no migra al arrancar: un arranque que migra solo puede sorprender, y el de la API
   anterior sorprendió.
3. **Sin valores por defecto para secretos.** Compose lee las credenciales de un archivo `.env`
   que no se versiona; si falta una variable obligatoria, el servicio no arranca. El archivo
   `.env.example` documenta cada variable.
4. **La base expone su puerto solo a `localhost`**, y la API y la web se comunican por la red
   interna de Compose.

## Consecuencias

- Un solo comando levanta todo; la misma composición sirve para CI y para una máquina de piso
  de prueba.
- Las migraciones quedan visibles como un paso propio: si fallan, el fallo está en `migrate`,
  no escondido dentro del arranque de la API.
- Compose no sustituye al despliegue en producción; ese está fuera de este alcance y se decide
  aparte.

## Alternativas descartadas

- **Migrar al arrancar la API.** Ya se probó y dejó a la base desfasada sin aviso.
- **Scripts de shell** como los de `scripts/` anteriores. Dependían de la máquina y no eran
  reproducibles en CI.
