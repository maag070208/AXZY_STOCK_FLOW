import { usePermissionScope } from "@shared/auth";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * TRASLADOS — **maqueta honesta** (Fase 3-bis)
 *
 * **El endpoint no existe.** `GET /v1/transfers` responde **404**, comprobado con
 * `curl` contra el backend y a través del proxy de la web. Por eso esta pantalla
 * **no hace ninguna petición**: sin estados de carga, sin estados de error, sin
 * botón de reintentar y sin tabla.
 *
 * **Por qué no se inventan traslados en tránsito.** Un traslado es dinero en un
 * camión: si la tabla mostrara filas de ejemplo, alguien podría reclamar por una
 * mercancía que nunca existió. Con la pantalla vacía se pregunta; con datos falsos
 * se decide.
 *
 * **Lo que sí es verdad:** el motor de dominio y el esquema están listos —las
 * tablas `transfers` y `stock_in_transit` existen desde la migración
 * `V3__inventory_domain.sql`, y el plan marca la fase como «motor y esquema
 * listos, sin rutas». Faltan los endpoints y la UI de despacho y recepción.
 * ═══════════════════════════════════════════════════════════════════════════
 */
export default function TransfersPage() {
  const scope = usePermissionScope("transfer.view");

  return (
    <PageShell
      title="Traslados"
      description="Despacho, recepción y mercancía en tránsito entre bodegas — todavía no disponible."
      breadcrumbLabel="Surtido"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="transfer.view" scope={scope} />
      </p>

      <section
        style={{
          background: "#F2F6FC",
          borderLeft: "6px solid #F28C28",
          padding: 20,
          margin: "16px 0",
        }}
      >
        <h2 style={{ margin: 0, fontSize: 20, color: "#16315F" }}>
          Funcionalidad no disponible todavía
        </h2>
        <p style={{ margin: "8px 0 0", color: "#16315F" }}>
          Esta pantalla todavía no se puede conectar porque <strong>el endpoint no
          existe</strong>: <code>GET /v1/transfers</code> responde <strong>404</strong>. No
          se muestra ninguna tabla porque no hay ningún traslado real que mostrar.
        </p>
      </section>

      <h2 style={{ fontSize: 17, color: "#16315F" }}>Qué hará cuando exista</h2>
      <p style={{ color: "#4A5A72" }}>
        Despachar mercancía desde una bodega y recibirla en otra, con el traslado en dos
        fases: <code>DISPATCHED</code> → <code>IN_TRANSIT</code> → <code>RECEIVED</code>,
        con <code>TRANSFER_OUT</code> y <code>TRANSFER_IN</code> escritos en el kardex.
      </p>
      <p style={{ color: "#4A5A72" }}>
        La invariante que la pantalla tiene que hacer visible es que{" "}
        <strong>
          Σ stock_by_location + Σ stock_in_transit se conserva en cada transición
        </strong>
        : la mercancía que va en el camión sigue contando en el inventario. Un traslado
        que nunca se recibió tiene que aparecer como <strong>deuda visible con su
        responsable</strong>, no como un dato que se pierde por el camino.
      </p>

      <h2 style={{ fontSize: 17, color: "#16315F" }}>Qué falta para que exista</h2>
      <ul style={{ color: "#4A5A72", lineHeight: 1.7 }}>
        <li>
          <strong>El esquema y el motor están listos</strong>: las tablas{" "}
          <code>transfers</code> y <code>stock_in_transit</code> ya existen en la base de
          datos (migración <code>V3__inventory_domain.sql</code>).
        </li>
        <li>
          <strong>Faltan los endpoints</strong>: el contrato prevé el listado, el despacho
          y la recepción de traslados, más la consulta de lo que está en tránsito. Hoy{" "}
          <strong>ninguna ruta de traslados está publicada</strong>: todas responden 404.
        </li>
        <li>
          Por eso esta pantalla no tiene botones de despachar ni de recibir: sin rutas,
          serían botones que devuelven 404.
        </li>
      </ul>
    </PageShell>
  );
}
