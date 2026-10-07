import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla **nueva** en la web: el catálogo se editaba desde el móvil o a mano.
 *
 * El listado incluye `stock` porque es el dato más consultado y evitar una
 * petición por producto importa con 1.398 productos. La importación CSV/XLSX
 * exige `rejections` con `rowNumber`: sin el número de fila el usuario no puede
 * corregir el archivo.
 */
export default function ProductsPage() {
  return (
    <PhasePlaceholder
      title="Productos"
      description="Catálogo de productos con existencias totales e importación CSV/XLSX."
      breadcrumbLabel="Almacén"
      permission="product.view"
      endpoints={[
        "GET /v1/products?page=&size=&sort=productCode,asc&q=",
        "POST /v1/products",
        "DELETE /v1/products/{id}",
        "POST /v1/products/import",
      ]}
    />
  );
}
