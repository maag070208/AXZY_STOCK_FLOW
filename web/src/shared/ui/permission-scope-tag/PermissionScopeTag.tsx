import { ITChip } from "@axzydev/axzy_ui_system";
import { useTranslation } from "react-i18next";
import type { PermissionScope } from "@shared/api";

/**
 * Etiqueta del alcance de un permiso.
 *
 * Existe porque el modelo de alcances **no es decorativo**: `invoice.approve`
 * con `AREA` no es lo mismo que con `ALL`, y el usuario tiene que poder verlo
 * sin abrir la consola de roles. Se apoya en `ITChip` del UI kit en lugar de
 * inventar un badge propio.
 */
const SCOPE_COLOR: Record<PermissionScope, "success" | "info" | "warning" | "gray"> = {
  ALL: "success",
  AREA: "info",
  OWN: "warning",
  NONE: "gray",
};

export const PermissionScopeTag = ({
  permission,
  scope,
}: {
  permission: string;
  scope: PermissionScope;
}): React.ReactNode => {
  const { t } = useTranslation(["common"]);
  return (
    <ITChip
      size="sm"
      variant="soft"
      color={SCOPE_COLOR[scope]}
      label={`${permission} · ${t(`scope.${scope}`)}`}
    />
  );
};
