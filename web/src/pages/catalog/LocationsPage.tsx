import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 7/13 del legacy (`Locations.tsx`, 645 LOC): la peor del frontend
 * anterior, porque concentraba cinco flujos en un archivo (lista, alta/edición,
 * borrado, buscar, mover ubicación, mover producto y etiquetas).
 *
 * Aquí se parte: ubicaciones (esta pantalla) y existencias (`InventoryPage`).
 * El contrato además elimina la pseudo-ubicación `ADMIN`: la ubicación de
 * recepción es real, con `id` real y `kind = RECEIVING`.
 */
export default function LocationsPage() {
  return (
    <PhasePlaceholder
      title="Ubicaciones"
      description="Catálogo físico de ubicaciones: pasillo, código, posición y niveles."
      breadcrumbLabel="Almacén"
      permission="location.view"
      endpoints={[
        "GET /v1/locations",
        "POST /v1/locations",
        "PUT /v1/locations/{id}",
        "DELETE /v1/locations/{id}",
        "GET /v1/warehouses",
      ]}
    />
  );
}
