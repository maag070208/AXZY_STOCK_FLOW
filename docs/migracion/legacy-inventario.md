# Inventario del legacy (`BASE/`)
> **Referencia.** Qué había en el sistema anterior y qué problemas tenía. Las cifras vienen del
> análisis de 2026-10-07 ([historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md)
> §0 y §1); el código de `BASE/` no se ha vuelto a contar. Última verificación: 2026-10-09.

## Qué es

Tres repos bajo `BASE/`, todos del mismo sistema de almacén, con clientes sobre una sola API:

| Repo | Stack | Tamaño (plan de 2026-10-07) | Rol |
|---|---|---|---|
| `BASE/backend` | Node 16, Express 4, Prisma 4, MySQL (y SQL Server para la sincronización) | ~4.000 LOC TS, 9 routers, 60 rutas | API única |
| `BASE/frontend` | React 18, Vite 3, Chakra 2, Redux | 4.833 LOC TSX, 13 páginas | Web administrativa |
| `BASE/mobile-app` | Expo 47, React Native 0.70, JS puro | 8.754 LOC JS, 26 pantallas | Operación en piso (escáner) |

Ninguno de los tres tenía pruebas automatizadas en ese momento.

## Qué se conserva

- Los flujos de negocio: recepción de facturas, ubicación, surtido, etiquetas, existencias.
  La lista de rutas está en [mapa-legacy-nuevo.md](../historico/MAPA-LEGACY-NUEVO-ANTERIOR.md).
- Las reglas de negocio que el código ya cumplía bien: los estados de factura y de orden, y
  la forma de capturar cajas y unidades.

## Qué se descarta

- Los hallazgos de seguridad S1 a S8: rutas sin autenticación, bypass `role === 1`, JWT no
  revocable, SQL por concatenación, credenciales en el código, `console.log` de datos
  sensibles, errores de autorización con 401, y login sin límite de intentos. Cada uno tiene
  su corrección en el sistema nuevo ([seguridad/owasp.md](../seguridad/owasp.md)).
- El borrado físico de facturas y ubicaciones que se llamaba «lógico».
- El libro que se mutaba al ubicar ([ADR-0009](../adr/0009-ubicacion-de-recepcion-real.md)).

## Defectos de modelo que el sistema nuevo corrige

1. **Un solo número por producto.** El legacy no sabe en qué ubicación está el stock. El sistema
   nuevo lo guarda por ubicación ([ADR-0007](../adr/0007-multi-bodega-desde-el-esquema.md)).
2. **Dos fuentes de existencias sin transacción.** `Store.stock` y el kardex se actualizaban en
   pasos separados. El sistema nuevo deriva el total de la proyección.
3. **El campo de diferencia nunca se calcula.** Las discrepancias no tenían un número que
   auditar ([ADR-0008](../adr/0008-discrepancias-y-merma.md)).

Una corrección del análisis original: el total del legacy **no** descuadraba al ubicar. Lo que
fallaba era el registro histórico, que se mutaba. El detalle está en
[historico/PLAN_ALMACEN_INTEGRAL.md](../historico/PLAN_ALMACEN_INTEGRAL.md) §2.7.
