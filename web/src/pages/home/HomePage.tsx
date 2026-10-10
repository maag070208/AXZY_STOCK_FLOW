import { ITAlert, ITCard, ITFlex, ITGrid, ITText } from "@axzydev/axzy_ui_system";
import { Page } from "@shared/ui/page/Page";

/**
 * Inicio: **qué existe hoy y qué no**, dicho sin adornos.
 *
 * La regla de la casa (docs/arquitectura/web-fsd.md §2.3) es que una pantalla sin
 * endpoint no muestra filas inventadas: una tabla con datos falsos es peor que una
 * pantalla vacía, porque el usuario decide con ellos. Aquí se ve el estado real
 * del backend, que es información útil y verificable.
 */
const MODULES: { name: string; state: "listo" | "pendiente"; detail: string }[] = [
  { name: "Identidad", state: "listo", detail: "Entrar, renovar sesión, cerrar sesión y cambiar contraseña." },
  { name: "Catálogo", state: "listo", detail: "Productos, ubicaciones, bodegas e importación del archivo." },
  { name: "Existencias", state: "pendiente", detail: "Kardex, ajustes, ubicar y mover: fase 5." },
  { name: "Recepción", state: "pendiente", detail: "Facturas, captura y aprobación de discrepancias: fase 6." },
  { name: "Surtido y ERP", state: "pendiente", detail: "Órdenes, empaque y exportación: fase 7." },
  { name: "Traslados", state: "pendiente", detail: "Entre ubicaciones y entre bodegas: fase 8." },
  { name: "Etiquetas y auditoría", state: "pendiente", detail: "Plantillas, cola de impresión y bitácora: fase 9." },
];

export default function HomePage() {
  return (
    <Page
      title="Inicio"
      description="Estado del sistema y accesos del catálogo."
      breadcrumbs={[{ label: "Inicio" }]}
    >
      <ITFlex direction="column" gap={4}>
        <ITAlert variant="info" title="Fase en curso">
          La API cubre identidad y catálogo. El resto de módulos llega en las fases siguientes; sus
          pantallas no se dibujan hasta que exista el endpoint.
        </ITAlert>

        <ITGrid container columns={3}>
          {MODULES.map((module) => (
            <ITCard key={module.name} className="p-4">
              <ITFlex direction="column" gap={1}>
                <ITFlex align="center" justify="between">
                  <ITText className="font-semibold">{module.name}</ITText>
                  <ITText className={module.state === "listo" ? "text-xs text-green-700" : "text-xs text-slate-400"}>
                    {module.state === "listo" ? "Disponible" : "Sin endpoint"}
                  </ITText>
                </ITFlex>
                <ITText muted className="text-xs">
                  {module.detail}
                </ITText>
              </ITFlex>
            </ITCard>
          ))}
        </ITGrid>
      </ITFlex>
    </Page>
  );
}
