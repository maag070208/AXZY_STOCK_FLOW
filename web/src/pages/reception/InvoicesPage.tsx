import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 3/13 del legacy (`Invoices.tsx`): listado y alta de facturas.
 *
 * El listado paginado sustituye a la tabla plana del legacy, que traía todos los
 * registros y no tenía estados de carga ni de error por página.
 *
 * Nuevo en el contrato: `POST /v1/products/import` no aplica aquí, pero el alta
 * ya no guarda nada en el cliente; el estado del documento lo decide el servidor.
 */
export default function InvoicesPage() {
  return (
    <PhasePlaceholder
      title="Facturas"
      description="Recepción de mercancía: listado paginado y alta de facturas."
      breadcrumbLabel="Recepción"
      permission="invoice.view"
      endpoints={[
        "GET /v1/invoices?page=&size=&sort=&q=",
        "POST /v1/invoices",
        "GET /v1/invoices/{id}",
      ]}
    />
  );
}
