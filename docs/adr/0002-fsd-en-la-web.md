# ADR-0002 — FSD en la web
> **Normativo.** Decisión aceptada. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada |
| **Fecha** | 2026-10-09 |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

La web es React 19 + Vite + TypeScript. Una interfaz con estado tiene muchos módulos que se
importan entre sí, y sin una regla de dirección de dependencias dos pantallas acaban
acopladas: cambiar una rompe la otra y nadie lo ve hasta el despliegue
([web-fsd.md](../arquitectura/web-fsd.md) §2.2).

Se eligió FSD (Feature-Sliced Design) porque su fuerza es exactamente esa: la dirección de
dependencias. A diferencia de la API, aquí la unidad de organización es la capa, y dentro de
la capa la feature ([general.md](../arquitectura/general.md)).

## Decisión

`web/src/` se organiza en capas FSD: `app/`, `pages/`, `widgets/`, `features/`, `entities/`
y `shared/` ([web-fsd.md](../arquitectura/web-fsd.md) §2.1).

- Una capa solo puede importar de las capas inferiores:
  `app → pages → widgets → features → entities → shared` ([web-fsd.md](../arquitectura/web-fsd.md) §2.2).
- Está prohibido que una página importe de otra página, que una feature importe de otra
  feature y que `shared/` importe de cualquier capa. Si `shared/` importa de `entities/`, la
  arquitectura está rota ([web-fsd.md](../arquitectura/web-fsd.md) §2.2).
- La regla la impone el lint, no la convención: `eslint-plugin-boundaries` con
  `default: "disallow"` y una política explícita por capa (`web/eslint.config.js`).
- Los datos de servidor se tratan según el contrato: las rutas van sin `/v1` porque
  `API_CONSTANTS.BASE_URL` ya lo incluye (`web/src/shared/api/constants/API_CONSTANTS.ts`),
  el nombre visible de una ubicación lo compone el servidor y los decimales llegan como
  `string`, no como `number` ([web-fsd.md](../arquitectura/web-fsd.md) §2.3).
- Una pantalla sin endpoint no muestra filas inventadas: dice qué hará y que el endpoint no
  existe ([web-fsd.md](../arquitectura/web-fsd.md) §2.3).

## Consecuencias

Cambiar una pantalla no rompe otra, y la dirección de dependencias se comprueba en cada
ejecución del lint en lugar de depender de la memoria del revisor.

Se paga ceremonia al mover código: si un widget necesita una acción de una feature, o se le
pasa por props o el código baja de capa. `shared/` no puede contener lógica de negocio.

Reglas operativas que se derivan:

- Cuando una capa necesita importar de una superior, el diseño está mal. El arreglo es bajar
  el código de capa, no silenciar la regla del lint.
- `shared/` aloja `api`, `ui`, `lib` y `config`, sin reglas de negocio
  ([web-fsd.md](../arquitectura/web-fsd.md) §2.1).

## Alternativas consideradas

- **Organizar por tipo de archivo** (`components/`, `hooks/`, `services/`). Es el cajón por
  tipo que FSD existe para evitar: no hay forma de saber qué puede importar qué.
- **Dejar la dirección de dependencias sin comprobar.** Es lo que había antes de la regla: el
  acoplamiento vuelve en el primer atajo.
- **Organizar por feature sin capas.** Se pierde la distinción entre entidad, feature, widget
  y página, y una página puede acabar importando de otra.

## Referencias

- `web/eslint.config.js`
- `web/src/app/`, `web/src/pages/`, `web/src/widgets/`, `web/src/features/`,
  `web/src/entities/`, `web/src/shared/`
- `web/src/shared/api/constants/API_CONSTANTS.ts`
- [general.md](../arquitectura/general.md) y [web-fsd.md](../arquitectura/web-fsd.md) §2.1, §2.2, §2.3
