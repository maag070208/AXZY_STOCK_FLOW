# Corte al sistema nuevo
> **Plan.** Pasos del corte y de su reversión. Estado: pendiente de ejecutar. Última verificación: 2026-10-09.

## Prerrequisitos

1. Conteo físico de los 1.398 productos y reconciliación contra el total heredado
   ([ADR-0012](../adr/0012-conciliar-stock-heredado-con-conteo.md)). **Pendiente.**
2. Recorrido de los flujos de la web y de la app contra el backend ([roadmap](../guia/roadmap.md)).
   **Pendiente.**
3. Respaldo verificado de la base, con restauración probada ([operacion/respaldos.md](../operacion/respaldos.md)).
   **Pendiente: no existe mecanismo de respaldo en el repo.**
4. Decisiones abiertas cerradas: el ERP (`external_id`, D10), y el módulo `kardo`
   ([M09](../modulos/M09-integracion-externa/README.md)).

## Pasos

1. Congelar el legacy: sin altas nuevas en `BASE/` desde el inicio del conteo.
2. Importar el catálogo ([catalogo-excel.md](catalogo-excel.md)) en modo estricto.
3. Cargar el stock inicial a `SIN-UBICAR`.
4. Ejecutar el conteo y los ajustes con motivo para llevar el stock a su valor contado.
5. Verificar con `comprobar_proyecciones()` que la proyección cuadra con el kardex.
6. Abrir el sistema nuevo a los operadores por rol ([M07](../modulos/M07-usuarios-accesos/README.md)).

## Reversión

- Hasta el paso 6, el legacy sigue en servicio y se puede volver a él sin pérdida.
- Después del paso 6, la reversión es **restaurar el respaldo de antes del corte**. Las
  escrituras hechas en el sistema nuevo entre el corte y la reversión se pierden, por eso el
  corte se hace con un periodo de observación corto.

## Lo que este documento no decide

- Duración del periodo de observación.
- Quién firma el corte. Es una decisión de negocio, no técnica.
