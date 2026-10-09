# ADR-0013 — Las órdenes surtidas se devuelven al ERP con reintentos e idempotencia

> **Normativo.** Decisión aceptada; envío real pendiente. Última verificación: 2026-10-09.

| | |
|---|---|
| **Estado** | Aceptada. Envío real pendiente |
| **Fecha** | 2026-10-09 (decisiones R-72 y D10 del plan original) |
| **Reemplaza a** | — |
| **Reemplazada por** | — |

## Contexto

El ERP sigue en uso y necesita las órdenes ya convertidas. En el legacy esa conversión
nunca existió: es una capacidad nueva, no una corrección. No hay contrato previo que
respetar, así que lo define este sistema.

## Decisión

- La exportación se dispara al confirmar el empaque: `POST /v1/orders/{id}/export`
  (`order/OrderRoutes.kt`, con `order.convert`).
- No se exporta una orden que no está confirmada (`ORDER_NOT_CONVERTED`,
  `order/OrderService.kt`).
- Cada envío lleva `Idempotency-Key`: un reintento no duplica la orden en el ERP.
- Los fallos quedan con estado (`PENDING`, `SENT`, `FAILED`) y se reintentan con backoff.
- `external_id` del ERP (decisión D10) está **pendiente**: no se define hasta conocer el
  identificador que el ERP acepta.

## Estado de implementación

El contrato y el registro del intento existen; el envío efectivo no. La respuesta de
`export` registra el intento y no incluye `nextRetryAt`
([api/surtido.md](../api/surtido.md)). Mientras el envío no exista, este ADR describe el
diseño, no el comportamiento.

## Consecuencias

- Hay que implementar el cliente del ERP y su autenticación, que hoy no existe
  ([ADR-0010](0010-rutas-de-servicio-con-api-key.md)).
- Un fallo del ERP no bloquea el surtido: la orden queda `FAILED` y se reintenta.

## Alternativas descartadas

- **Envío síncrono dentro de la confirmación.** Un ERP caído bloquearía el empaque.
- **Enviar sin idempotencia.** Un reintento duplicaría órdenes en el ERP.
