import { ITEmptyState, ITFlex, ITText } from "@axzydev/axzy_ui_system";
import { FaScrewdriverWrench } from "react-icons/fa6";
import { useTranslation } from "react-i18next";
import { PageShell } from "@shared/ui/page-shell";
import { PermissionScopeTag } from "@shared/ui/permission-scope-tag";
import { usePermissionScope, type Permission } from "@shared/auth";

/**
 * Marcador de las pantallas que quedan para la Fase 5.
 *
 * Se implementa como componente reutilizable —y no como 16 archivos con el mismo
 * texto— por la misma razón por la que el legacy acabó con el menú duplicado en
 * dos sitios: lo que se repite se desincroniza. Cuando una pantalla se
 * implemente de verdad, se reemplaza **su** contenido y este componente queda
 * para las que falten.
 *
 * El alcance del permiso se lee del propio componente (el usuario tiene que ver
 * si puede ver la pantalla, pero sólo de su bodega) en lugar de pedirlo por
 * props: es el mismo dato en las 16 pantallas y no debe poder desincronizarse.
 */
export const PhasePlaceholder = ({
  title,
  description,
  /** Endpoints del contrato que alimentarán esta pantalla. */
  endpoints,
  /** Permiso con el que el sidebar muestra esta pantalla. */
  permission,
  breadcrumbLabel,
}: {
  title: string;
  description: string;
  endpoints: readonly string[];
  permission: Permission;
  breadcrumbLabel?: string;
}): React.ReactNode => {
  const { t } = useTranslation(["common"]);
  const scope = usePermissionScope(permission);

  return (
    <PageShell
      title={title}
      description={description}
      {...(breadcrumbLabel ? { breadcrumbLabel } : {})}
    >
      <ITFlex direction="column" gap={4}>
        <ITFlex direction="row" gap={2} align="center" wrap="wrap">
          <ITText as="span" muted className="text-[12px]">
            {t("placeholder.permission")}:
          </ITText>
          <PermissionScopeTag permission={permission} scope={scope} />
        </ITFlex>

        <ITEmptyState
          icon={<FaScrewdriverWrench size={36} />}
          title={t("placeholder.title")}
          description={t("placeholder.body")}
        />

        <ITFlex direction="column" gap={1}>
          <ITText as="span" muted className="text-[12px]">
            {t("contractEndpoints")}
          </ITText>
          {endpoints.map((endpoint) => (
            <ITText key={endpoint} as="code" className="text-[12px]">
              {endpoint}
            </ITText>
          ))}
        </ITFlex>
      </ITFlex>
    </PageShell>
  );
};
