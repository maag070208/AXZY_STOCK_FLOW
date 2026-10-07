import { ITAlert, ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { useEffect, useState } from "react";
import { fetchHealth } from "@entities/health";
import type { HealthResponseDto } from "@entities/health";
import { useSession } from "@shared/auth";
import { ApiError } from "@shared/api";
import { PageShell } from "@shared/ui/page-shell";

/**
 * Diagnóstico del sistema (§10). **Endpoint público**: funciona incluso cuando
 * el problema es la sesión, que es exactamente cuando hace falta.
 *
 * Es la única pantalla implementada de verdad en esta entrega: sirve de ejemplo
 * del patrón completo (llamada a la API, estados de carga y error por `code`,
 * y `requestId` para cruzar con la auditoría).
 */
export default function HealthPage() {
  const session = useSession();
  const [health, setHealth] = useState<HealthResponseDto | null>(null);
  const [errorCode, setErrorCode] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    fetchHealth()
      .then((data) => {
        if (!cancelled) {
          setHealth(data);
          setErrorCode(null);
        }
      })
      .catch((error: unknown) => {
        // Se decide con `code` (§1.3): un `503` de health también trae cuerpo de
        // error, así que no hace falta mirar el mensaje.
        if (!cancelled) setErrorCode(error instanceof ApiError ? error.code : "UNKNOWN");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <PageShell
      title="Salud del sistema"
      description="Estado del backend, esquema de base de datos y versión desplegada."
      breadcrumbLabel="Configuración"
    >
      <ITFlex direction="column" gap={3}>
        {errorCode && <ITAlert variant="error">{`No se pudo leer /v1/health (code: ${errorCode})`}</ITAlert>}

        {loading && !health && <ITText as="p" muted>Consultando…</ITText>}

        {health && (
          <ITFlex direction="column" gap={1}>
            <ITText as="p">{`Estado: ${health.status}`}</ITText>
            <ITText as="p">{`Versión del backend: ${health.version}`}</ITText>
            <ITText as="p">{`Versión de la web: ${__APP_VERSION__}`}</ITText>
            <ITText as="p">{`Esquema: ${health.schemaVersion ?? "no disponible"}`}</ITText>
            <ITText as="p">{`Base de datos: ${health.database.status} (${health.database.latencyMs ?? "—"} ms)`}</ITText>
            {health.database.error && (
              <ITText as="p" muted>{`Motivo: ${health.database.error}`}</ITText>
            )}
            <ITText as="p" muted>
              {`Sesión: ${session.user ? session.user.username : "anónima"}`}
            </ITText>
          </ITFlex>
        )}
      </ITFlex>
    </PageShell>
  );
}
