import { useCallback, useEffect, useState } from "react";
import { ApiError } from "@shared/api";
import { usePermissionScope } from "@shared/auth";
import type { AuditLogEntry } from "@shared/api";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";
import { fetchAuditLog } from "@entities/access";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * BITÁCORA DE AUDITORÍA — pantalla REAL contra la API
 *
 * Esta pantalla era un `PhasePlaceholder`, que **no afirmaba que el endpoint
 * faltara** —a diferencia de las maquetas de Traslados, Surtido y Etiquetas— pero
 * tampoco lo usaba: era una pantalla sin construir con el endpoint disponible.
 *
 * ── LO QUE ESTA PANTALLA TIENE QUE HACER VISIBLE ───────────────────────────
 *
 * El contrato presenta la auditoría como la respuesta a «quién hizo qué». El
 * legacy no podía responderlo porque **no guardaba el canal ni el identificador de
 * la petición**. Aquí hay tres columnas que existen solo por eso:
 *
 *   · **CANAL** (`channel`) — si fue la web, el móvil o un servicio. Un movimiento
 *     sin usuario y con canal `SERVICE` no es lo mismo que uno hecho por una
 *     persona, y confundirlos es cómo una impresora acaba «firmando» un ajuste.
 *   · **RESULTADO** (`result`) — un intento DENEGADO se registra. Filtrar solo lo
 *     que salió bien es lo que hace que un abuso de permisos pase inadvertido.
 *   · **REQUEST-ID** — cruza una entrada con la petición HTTP que la produjo, que
 *     es lo que permite pasar de «algo falló» a «esta llamada falló».
 *
 * ── POR QUÉ SE DICE EL NIVEL ───────────────────────────────────────────────
 *
 * `actionLevel` ordena lo que importa. Sin él, un rechazo de permiso y un alta de
 * producto se leen igual, y la bitácora se vuelve una lista que nadie mira.
 * ═══════════════════════════════════════════════════════════════════════════
 */

/** El color del nivel: lo que exige atención va en rojo o ámbar. */
function colorNivel(nivel: string): string {
  switch ((nivel ?? "").toUpperCase()) {
    case "CRITICAL":
      return "#C0392B";
    case "WARN":
    case "WARNING":
      return "#F28C28";
    default:
      return "#16315F";
  }
}

function colorResultado(resultado: string): string {
  const r = (resultado ?? "").toUpperCase();
  if (r === "SUCCESS" || r === "OK" || r === "ALLOWED") return "#2E7D5B";
  if (r === "DENIED" || r === "FAILED" || r === "ERROR") return "#C0392B";
  return "#4A5A72";
}

export default function AuditPage() {
  const scope = usePermissionScope("audit.view");

  const [entradas, setEntradas] = useState<AuditLogEntry[] | null>(null);
  const [total, setTotal] = useState(0);
  const [cargando, setCargando] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [sinPermiso, setSinPermiso] = useState(false);

  // Filtros: los que la ruta acepta.
  const [accion, setAccion] = useState("");
  const [entidad, setEntidad] = useState("");
  const [resultado, setResultado] = useState("");
  const [detalle, setDetalle] = useState<AuditLogEntry | null>(null);

  const cargar = useCallback(async () => {
    setCargando(true);
    setError(null);
    setSinPermiso(false);
    try {
      const pagina = await fetchAuditLog({
        ...(accion.trim() ? { action: accion.trim() } : {}),
        ...(entidad.trim() ? { entity: entidad.trim() } : {}),
        ...(resultado ? { result: resultado } : {}),
      });
      setEntradas(pagina.items);
      // El total lo cuenta el SERVIDOR sobre el filtro: usar `items.length` daría
      // el tamaño de la página y el usuario creería que la bitácora tiene 100
      // entradas cuando puede tener cien mil.
      setTotal(pagina.totalItems);
    } catch (e) {
      // Se descarta lo anterior: tras un fallo, dejar la lista vieja haría creer
      // que la bitácora está comprobada y al día.
      setEntradas(null);
      setSinPermiso(e instanceof ApiError && e.status === 403);
      setError(e instanceof Error ? e.message : "No se pudo cargar la bitácora.");
    } finally {
      setCargando(false);
    }
  }, [accion, entidad, resultado]);

  useEffect(() => {
    void cargar();
  }, [cargar]);

  return (
    <PageShell
      title="Bitácora"
      description="Quién, cuándo, desde qué canal y con qué permiso se hizo cada operación."
      breadcrumbLabel="Configuración"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="audit.view" scope={scope} />
      </p>

      <div style={{ display: "flex", gap: 12, flexWrap: "wrap", margin: "16px 0" }}>
        <label style={{ display: "flex", flexDirection: "column" }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Acción</span>
          <input
            type="text"
            aria-label="Filtrar por acción"
            value={accion}
            onChange={(e) => setAccion(e.target.value)}
            placeholder="p. ej. login"
          />
        </label>
        <label style={{ display: "flex", flexDirection: "column" }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Entidad</span>
          <input
            type="text"
            aria-label="Filtrar por entidad"
            value={entidad}
            onChange={(e) => setEntidad(e.target.value)}
            placeholder="p. ej. products"
          />
        </label>
        <label style={{ display: "flex", flexDirection: "column" }}>
          <span style={{ fontSize: 13, color: "#4A5A72" }}>Resultado</span>
          <select
            aria-label="Filtrar por resultado"
            value={resultado}
            onChange={(e) => setResultado(e.target.value)}
          >
            <option value="">Todos</option>
            <option value="SUCCESS">Correctos</option>
            <option value="DENIED">Denegados</option>
            <option value="FAILED">Fallidos</option>
          </select>
        </label>
        {!cargando && !error && <span style={{ alignSelf: "end", color: "#4A5A72" }}>{total} entrada(s)</span>}
      </div>

      {cargando && <p style={{ color: "#4A5A72" }}>Cargando la bitácora…</p>}

      {!cargando && error && (
        <div
          role="alert"
          style={{ background: "#FDECEA", borderLeft: "6px solid #C0392B", padding: 16, margin: "16px 0" }}
        >
          <div style={{ marginBottom: 8 }}>{error}</div>
          {sinPermiso && (
            <div style={{ marginBottom: 8 }}>
              Esta pantalla exige el permiso <code>audit.view</code>. Pídeselo a un
              administrador.
            </div>
          )}
          <button type="button" onClick={() => void cargar()}>
            Reintentar
          </button>
        </div>
      )}

      {!cargando && !error && entradas && entradas.length === 0 && (
        <p style={{ color: "#4A5A72" }}>
          No hay entradas que cumplan el filtro. Si acabas de hacer una operación, la bitácora
          la registra de forma **inmutable**: se escribe con la operación, no después.
        </p>
      )}

      {!cargando && !error && entradas && entradas.length > 0 && (
        <table style={{ width: "100%", borderCollapse: "collapse" }}>
          <thead>
            <tr style={{ background: "#E4EBF5", color: "#16315F", textAlign: "left" }}>
              <th style={{ padding: 8 }}>Cuándo</th>
              <th style={{ padding: 8 }}>Quién</th>
              <th style={{ padding: 8 }}>Acción</th>
              <th style={{ padding: 8 }}>Entidad</th>
              <th style={{ padding: 8 }}>Canal</th>
              <th style={{ padding: 8 }}>Permiso</th>
              <th style={{ padding: 8 }}>Resultado</th>
              <th style={{ padding: 8 }}>Nivel</th>
              <th style={{ padding: 8 }}>Detalle</th>
            </tr>
          </thead>
          <tbody>
            {entradas.map((e) => (
              <tr key={e.id} style={{ borderBottom: "1px solid #E4EBF5" }}>
                <td style={{ padding: 8 }}>{e.occurredAt?.replace("T", " ").slice(0, 19)}</td>
                <td style={{ padding: 8 }}>
                  {e.userName ?? e.username ?? <span style={{ color: "#8A94A6" }}>sistema</span>}
                </td>
                <td style={{ padding: 8 }}>{e.action}</td>
                <td style={{ padding: 8 }}>
                  {e.entity}
                  {e.entityId ? ` #${e.entityId}` : ""}
                </td>
                <td style={{ padding: 8 }}>
                  {/*
                    El canal es la dimensión que el legacy no tenía. Un movimiento sin
                    usuario y con canal de servicio NO es una acción de una persona, y
                    esa distinción es la que permite confiar en la bitácora.
                  */}
                  {e.channel}
                  {e.device ? ` · ${e.device}` : ""}
                </td>
                <td style={{ padding: 8 }}>
                  {/*
                    **El permiso va en la TABLA y no solo en el detalle.** Una
                    entrada que dice «acceso denegado» sin decir a qué permiso
                    obliga a abrirla una por una para saber qué se intentó — y con
                    una lista de intentos, eso es justo lo que no se hace. El
                    detalle queda para la IP, el `requestId` y el hash.
                  */}
                  {e.permission ?? "—"}
                </td>
                <td style={{ padding: 8, color: colorResultado(e.result), fontWeight: 600 }}>
                  {e.result}
                  {e.errorCode ? ` · ${e.errorCode}` : ""}
                </td>
                <td style={{ padding: 8, color: colorNivel(e.actionLevel) }}>{e.actionLevel}</td>
                <td style={{ padding: 8 }}>
                  <button type="button" onClick={() => setDetalle(e)}>
                    Ver
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {detalle && (
        <section
          style={{ background: "#F2F6FC", borderLeft: "6px solid #16315F", padding: 20, margin: "24px 0" }}
        >
          <h2 style={{ margin: 0, fontSize: 17, color: "#16315F" }}>
            Entrada #{detalle.sequenceNo}
          </h2>
          <dl style={{ display: "grid", gridTemplateColumns: "auto 1fr", gap: "4px 16px", marginTop: 12 }}>
            <dt>Decisión</dt>
            <dd>{detalle.decision ?? "—"}</dd>
            <dt>Campos cambiados</dt>
            <dd>{detalle.changedFields?.join(", ") || "—"}</dd>
            <dt>IP</dt>
            <dd>{detalle.ip ?? "—"}</dd>
            {/*
              El `requestId` permite pasar de «algo falló» a «ESTA llamada falló»:
              es lo que cruza una pantalla con su movimiento.
            */}
            <dt>Request-Id</dt>
            <dd>{detalle.requestId ?? "—"}</dd>
            <dt>Correlación</dt>
            <dd>{detalle.correlationId ?? "—"}</dd>
            <dt>Fecha de negocio</dt>
            <dd>{detalle.businessDate}</dd>
            <dt>Error</dt>
            <dd>{detalle.errorMessage ?? detalle.errorCode ?? "—"}</dd>
            <dt>Motivo</dt>
            <dd>{detalle.reason ?? "—"}</dd>
            <dt>Hash</dt>
            <dd style={{ wordBreak: "break-all", fontSize: 12 }}>{detalle.recordHash}</dd>
          </dl>
          <button type="button" style={{ marginTop: 12 }} onClick={() => setDetalle(null)}>
            Cerrar
          </button>
        </section>
      )}
    </PageShell>
  );
}
