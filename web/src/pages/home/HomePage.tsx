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
  { name: "Existencias", state: "listo", detail: "Existencias por ubicación y el kardex, el libro de movimientos." },
  { name: "Recepción", state: "listo", detail: "Facturas y la bandeja de las que esperan aprobación." },
  { name: "Surtido y ERP", state: "listo", detail: "Órdenes y su exportación. Sin ERP, el intento queda fallido con su motivo." },
  { name: "Traslados", state: "listo", detail: "Traslados entre bodegas y la mercancía en camino." },
  { name: "Etiquetas y auditoría", state: "listo", detail: "Cola de impresión y la bitácora de todo lo que se escribió." },
];

export default function HomePage() {
  return (
    <Page
      title="Inicio"
      description="Estado del sistema y accesos del catálogo."
      breadcrumbs={[{ label: "Inicio" }]}
    >
      <ITFlex direction="column" gap={4}>
        <ITAlert variant="info" title="Web en construcción">
          Cada pantalla usa el endpoint de su módulo. Lo que aún no tiene pantalla se indica como pendiente.
        </ITAlert>

        <ITGrid container columns={3}>
          {MODULES.map((module) => (
            <ITCard key={module.name} className="p-4">
              <ITFlex direction="column" gap={1}>
                <ITFlex align="center" justify="between">
                  <ITText className="font-semibold">{module.name}</ITText>
                  <ITText className={module.state === "listo" ? "text-xs text-green-700" : "text-xs text-slate-400"}>
                    {module.state === "listo" ? "Disponible" : "Pendiente"}
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
