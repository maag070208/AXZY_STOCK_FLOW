import { ITButton, ITEmptyState } from "@axzydev/axzy_ui_system";
import { FaMapSigns } from "react-icons/fa";
import { useNavigate } from "react-router-dom";

export default function NotFoundPage() {
  const navigate = useNavigate();

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#F2F6FC] px-4">
      <ITEmptyState
        icon={<FaMapSigns size={28} />}
        title="Esta ruta no existe"
        description="Puede que el enlace esté mal escrito o que la pantalla todavía no exista."
        action={<ITButton label="Volver al inicio" color="primary" onClick={() => navigate("/")} />}
      />
    </div>
  );
}
