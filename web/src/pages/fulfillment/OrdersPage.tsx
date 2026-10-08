import { usePermissionScope } from "@shared/auth";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ÓRDENES DE SURTIDO — **maqueta honesta** (Fase 3, sin empezar)
 *
 * **El endpoint no existe.** `GET /v1/orders` responde **404**, comprobado con
 * `curl` contra el backend y a través del proxy de la web. Por eso esta pantalla
 * **no hace ninguna petición**: sin estados de carga, sin estados de error, sin
 * botón de reintentar y sin listado paginado.
 *
 * **Por qué no se pone una tabla de ejemplo.** Con filas inventadas, un
 * «pendiente de exportar» falso se leería como una orden real atascada y alguien
 * iría a buscarla al ERP. Una pantalla vacía genera una pregunta; unos datos
 * falsos generan una decisión equivocada.
 *
 * **Lo que sí es verdad:** las tablas `orders` y `order_lines` existen desde la
 * migración `V3__inventory_domain.sql`. **El esquema está listo, faltan los
 * endpoints** y todo el flujo de surtido y exportación al ERP; el plan marca la
 * Fase 3 como «sin empezar».
 * ═══════════════════════════════════════════════════════════════════════════
 */
export default function OrdersPage() {
  const scope = usePermissionScope("order.view");

  return (
    <PageShell
      title="Órdenes"
      description="Surtido: listado paginado, estado de empaque y exportación al ERP — todavía no disponible."
      breadcrumbLabel="Surtido"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="order.view" scope={scope} />
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
          existe</strong>: <code>GET /v1/orders</code> responde <strong>404</strong>. No se
          muestra ningún listado porque no hay ninguna orden real que mostrar.
        </p>
      </section>

      <h2 style={{ fontSize: 17, color: "#16315F" }}>Qué hará cuando exista</h2>
      <p style={{ color: "#4A5A72" }}>
        Listar las órdenes de surtido con paginación y filtro por estado, con el avance del
        empaque y —la parte nueva— el estado de exportación al ERP de cada orden:{" "}
        <code>exportStatus</code>, <code>exportAttempts</code> y{" "}
        <code>exportLastError</code>.
      </p>
      <p style={{ color: "#4A5A72" }}>
        Esa columna es lo que el legacy no tenía: la exportación al ERP{" "}
        <strong>nunca existió</strong>, así que un envío fallido no se veía en ninguna
        parte y una orden se quedaba «confirmada» sin haber llegado nunca al ERP. Con el
        estado visible, un reintento es una decisión y no una apuesta.
      </p>

      <h2 style={{ fontSize: 17, color: "#16315F" }}>Qué falta para que exista</h2>
      <ul style={{ color: "#4A5A72", lineHeight: 1.7 }}>
        <li>
          <strong>El esquema está listo</strong>: las tablas <code>orders</code> y{" "}
          <code>order_lines</code> ya existen en la base de datos (migración{" "}
          <code>V3__inventory_domain.sql</code>).
        </li>
        <li>
          <strong>Faltan los endpoints</strong>: <code>GET /v1/orders</code> y{" "}
          <code>POST /v1/orders</code> no están publicados (responden 404), y tampoco el
          flujo de empaque, confirmación y descuento de stock.
        </li>
        <li>
          Falta además la exportación al ERP (Fase 3 del plan), que necesita definir antes
          su contrato con idempotencia: un reintento no puede duplicar la orden.
        </li>
      </ul>
    </PageShell>
  );
}
