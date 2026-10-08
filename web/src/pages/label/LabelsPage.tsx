import { usePermissionScope } from "@shared/auth";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * ETIQUETAS — **maqueta honesta** (Fase 4, sin empezar en el backend)
 *
 * **El endpoint no existe.** `GET /v1/labels` responde **404**, y también el
 * resto de rutas de etiquetas del contrato: comprobado con `curl` contra el
 * backend y a través del proxy de la web (que es el camino del navegador, y no
 * siempre coincide). Por eso esta pantalla **no hace ninguna petición**: no hay
 * estados de carga, ni de error, ni botón de reintentar, y tampoco una tabla.
 *
 * **Por qué no se pone una tabla de ejemplo.** Unas filas inventadas se leen como
 * datos reales —el usuario no tiene forma de distinguirlas— y es peor que una
 * pantalla vacía: con una pantalla vacía se pregunta, con datos falsos se decide.
 * La versión anterior de este archivo era peor todavía: listaba los endpoints del
 * contrato como si existieran, y quien la leyera daría por hecho que la
 * impresión estaba a un clic.
 *
 * **Lo que sí es verdad, y por eso se dice:** las tablas `label_templates` y
 * `print_jobs` ya están creadas en la base (migración `V3__inventory_domain.sql`).
 * Es decir, **el esquema está listo y solo faltan los endpoints**; el trabajo
 * pendiente es de rutas y de la máquina de estados de impresión, no de modelado.
 * ═══════════════════════════════════════════════════════════════════════════
 */
export default function LabelsPage() {
  const scope = usePermissionScope("label.print");

  return (
    <PageShell
      title="Etiquetas"
      description="Plantillas ZPL, vista previa e impresión confirmada por la impresora — todavía no disponible."
      breadcrumbLabel="Almacén"
    >
      <p style={{ display: "flex", alignItems: "center", gap: 8, color: "#4A5A72" }}>
        Permiso de la pantalla:
        <PermissionScopeTag permission="label.print" scope={scope} />
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
          existe</strong>: <code>GET /v1/labels</code> responde <strong>404</strong>. No se
          muestra ninguna tabla porque no hay ningún dato real que mostrar.
        </p>
      </section>

      <h2 style={{ fontSize: 17, color: "#16315F" }}>Qué hará cuando exista</h2>
      <p style={{ color: "#4A5A72" }}>
        Imprimir etiquetas a partir de las plantillas ZPL, previsualizarlas sin enviarlas
        a la impresora y seguir la cola de trabajos con el estado real de cada una
        (<code>PENDING</code> → <code>SENT</code> → <code>PRINTED</code> o{" "}
        <code>FAILED</code>), con su número de intentos y la opción de reimprimir.
      </p>
      <p style={{ color: "#4A5A72" }}>
        La regla que el legacy incumplía y que este diseño corrige: <strong>una etiqueta
        no se marca impresa hasta que la impresora confirma</strong>. El legacy la marcaba
        sin esperar respuesta, así que si la Zebra estaba apagada la etiqueta se perdía
        para siempre y nadie se enteraba.
      </p>

      <h2 style={{ fontSize: 17, color: "#16315F" }}>Qué falta para que exista</h2>
      <ul style={{ color: "#4A5A72", lineHeight: 1.7 }}>
        <li>
          <strong>El esquema está listo</strong>: las tablas <code>label_templates</code> y{" "}
          <code>print_jobs</code> ya existen en la base de datos (migración{" "}
          <code>V3__inventory_domain.sql</code>).
        </li>
        <li>
          <strong>Faltan los endpoints</strong>: el contrato prevé{" "}
          <code>POST /v1/labels/preview</code>, <code>POST /v1/labels/print</code> y la
          consulta de la cola de trabajos. Hoy <strong>ninguna ruta de etiquetas está
          publicada</strong>: todas responden 404.
        </li>
        <li>
          Falta la <strong>máquina de estados de impresión</strong> (Fase 4 del plan). Sin
          ella no se puede saber si una etiqueta llegó a imprimirse, que es justo el dato
          que hace falta para poder reimprimir con criterio.
        </li>
      </ul>
    </PageShell>
  );
}
