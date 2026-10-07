import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 5/13 del legacy (`Orders.tsx`): listado de órdenes de surtido.
 *
 * La novedad del contrato es la columna de exportación al ERP
 * (`exportStatus`/`exportAttempts`/`exportLastError`), que el legacy no tenía:
 * la exportación nunca existió y por eso no había forma de ver un envío fallido.
 */
export default function OrdersPage() {
  return (
    <PhasePlaceholder
      title="Órdenes"
      description="Surtido: listado paginado, estado de empaque y de exportación al ERP."
      breadcrumbLabel="Surtido"
      permission="order.view"
      endpoints={["GET /v1/orders?page=&size=&status=", "POST /v1/orders"]}
    />
  );
}
