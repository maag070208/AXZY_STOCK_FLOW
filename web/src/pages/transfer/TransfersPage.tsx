import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla **nueva** (Fase 3-bis): traslados entre bodegas.
 *
 * La invariante que la UI tiene que hacer visible: `Σ stock_by_location +
 * Σ stock_in_transit` **se conserva en cada transición**. La mercancía en el
 * camión sigue contando en el inventario; un traslado nunca recibido aparece
 * como deuda visible con su responsable, no como un dato que se pierde.
 */
export default function TransfersPage() {
  return (
    <PhasePlaceholder
      title="Traslados"
      description="Despacho, recepción y mercancía en tránsito entre bodegas."
      breadcrumbLabel="Surtido"
      permission="transfer.view"
      endpoints={[
        "GET /v1/transfers",
        "POST /v1/transfers/{id}/dispatch",
        "POST /v1/transfers/{id}/receive",
        "GET /v1/transfers/in-transit",
      ]}
    />
  );
}
