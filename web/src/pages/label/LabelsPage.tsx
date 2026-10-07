import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla 12/13 por ruta del legacy (`Ticket.tsx`, 33 LOC y sin funcionalidad).
 *
 * El contrato sí define el flujo completo, y con una regla que el legacy
 * incumplía: **una etiqueta no se marca impresa hasta que la impresora
 * confirma**. El legacy la marcaba sin esperar respuesta, así que si la Zebra
 * estaba apagada la etiqueta se perdía para siempre.
 */
export default function LabelsPage() {
  return (
    <PhasePlaceholder
      title="Etiquetas"
      description="Plantillas ZPL, vista previa sin imprimir y cola de trabajos con su estado real."
      breadcrumbLabel="Almacén"
      permission="label.print"
      endpoints={[
        "GET /v1/label-templates",
        "POST /v1/labels/preview",
        "POST /v1/labels/print",
        "GET /v1/labels/jobs?status=FAILED",
        "POST /v1/labels/jobs/{id}/reprint",
      ]}
    />
  );
}
