import { ITButton, ITEmptyState, ITFlex } from "@axzydev/axzy_ui_system";
import { FaCompass } from "react-icons/fa6";
import { useNavigate } from "react-router-dom";
import { PageShell } from "@shared/ui/page-shell";

/**
 * 404.
 *
 * El legacy **no tenía ruta 404** (cualquier URL desconocida dejaba la pantalla
 * en blanco). Aquí se envuelve en el layout, así que el usuario no pierde el
 * menú ni la sesión.
 */
export default function NotFoundPage() {
  const navigate = useNavigate();

  return (
    <PageShell title="Pantalla no encontrada" showBack={false}>
      <ITFlex direction="column" align="center" gap={4}>
        <ITEmptyState
          icon={<FaCompass size={36} />}
          title="Esta ruta no existe"
          description="Revisa el enlace o vuelve al inicio: el menú sigue disponible."
        />
        <ITButton variant="filled" color="primary" label="Ir al inicio" onClick={() => navigate("/")} />
      </ITFlex>
    </PageShell>
  );
}
