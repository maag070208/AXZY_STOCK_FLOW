import { ITButton, ITEmptyState } from "@axzydev/axzy_ui_system";
import { FaMapSigns } from "react-icons/fa";
import { useNavigate } from "react-router-dom";
import i18n from "@shared/i18n";

export default function NotFoundPage() {
  const navigate = useNavigate();

  return (
    <div className="flex min-h-screen items-center justify-center bg-[#F2F6FC] px-4">
      <ITEmptyState
        icon={<FaMapSigns size={28} />}
        title={i18n.t("notFound.title")}
        description={i18n.t("notFound.description")}
        action={<ITButton label={i18n.t("common.backHome")} color="primary" onClick={() => navigate("/")} />}
      />
    </div>
  );
}
