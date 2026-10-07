import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 8/13 del legacy (`Inventory.tsx`): existencias.
 *
 * El legacy recalculaba el saldo desde movimientos mutados
 * (`GET /get-location-content`, que ya no existe). Aquí se lee la proyección
 * `stock_by_location`, que es el saldo real.
 */
export default function InventoryPage() {
  return (
    <PhasePlaceholder
      title="Existencias"
      description="Saldo por producto y ubicación, con filtro de «sólo con existencia»."
      breadcrumbLabel="Almacén"
      permission="inventory.view"
      endpoints={[
        "GET /v1/inventory/stock?productId=&locationId=&warehouseId=&onlyWithStock=",
        "POST /v1/inventory/move",
        "POST /v1/inventory/adjustments",
      ]}
    />
  );
}
