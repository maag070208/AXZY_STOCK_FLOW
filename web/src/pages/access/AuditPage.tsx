import { PhasePlaceholder } from "@shared/ui/phase-placeholder";

/**
 * Pantalla **nueva**: bitácora de auditoría.
 *
 * La auditoría registra el canal (`X-Client-Channel`) y el `X-Request-Id` de la
 * petición, que es lo que permite cruzar una pantalla con su movimiento. En el
 * legacy este dato no existía, así que no se podía reconstruir quién hizo qué.
 */
export default function AuditPage() {
  return (
    <PhasePlaceholder
      title="Bitácora"
      description="Quién, cuándo, desde qué canal y con qué permiso se hizo cada operación."
      breadcrumbLabel="Configuración"
      permission="audit.view"
      endpoints={["GET /v1/audit/log?userId=&action=&from=&to="]}
    />
  );
}
