import { APP_SCREENS, isScreenVisible, type AppScreen, type PermissionMap } from "@shared/auth";
import { useSession } from "@shared/auth";
import { ITCard, ITChip, ITFlex, ITGrid, ITText } from "@axzydev/axzy_ui_system";
import { useTranslation } from "react-i18next";
import { useNavigate } from "react-router-dom";
import { PageShell } from "@shared/ui/page-shell";

/**
 * Inicio (1 de las 13 pantallas del legacy: `Home.tsx`).
 *
 * El legacy duplicaba aquí el menú del `Layout`, con las mismas rutas escritas
 * a mano en dos archivos. Esta pantalla **se deriva del mismo catálogo**
 * (`APP_SCREENS`) que el sidebar, así que los dos no pueden divergir: si una
 * pantalla no es visible para la sesión, tampoco aparece su tarjeta.
 */
const screenCards = (
  screens: readonly AppScreen[],
  permissions: PermissionMap | undefined,
  role: string | null
): AppScreen[] => {
  const leaves: AppScreen[] = [];
  const walk = (list: readonly AppScreen[]): void => {
    for (const screen of list) {
      if (!isScreenVisible(permissions, screen, role)) continue;
      if (screen.children?.length) walk(screen.children);
      else if (screen.path && screen.path !== "/") leaves.push(screen);
    }
  };
  walk(screens);
  return leaves;
};

export default function HomePage() {
  const { t } = useTranslation(["common"]);
  const navigate = useNavigate();
  const session = useSession();

  const user = session.user;
  const cards = screenCards(APP_SCREENS, user?.scopes, user?.roles[0] ?? null);

  return (
    <PageShell
      title={t("nav.home")}
      description={user ? `${user.name} · ${user.roles.join(", ")}` : undefined}
      showBack={false}
    >
      <ITFlex direction="column" gap={4}>
        {user && (
          <ITFlex direction="row" gap={2} wrap="wrap">
            {Object.entries(user.scopes).map(([permission, scope]) => (
              <ITChip
                key={permission}
                size="sm"
                variant="soft"
                color="secondary"
                label={`${permission} · ${t(`scope.${scope}`)}`}
              />
            ))}
          </ITFlex>
        )}

        <ITGrid container columns={12} spacing={4}>
          {cards.map((screen) => (
            <ITGrid key={screen.id} item xs={12} sm={6} lg={4}>
              <ITCard title={t(screen.labelKey)} onClick={() => navigate(screen.path as string)}>
                <ITText as="p" muted className="text-[12px]">
                  {screen.path}
                </ITText>
              </ITCard>
            </ITGrid>
          ))}
        </ITGrid>
      </ITFlex>
    </PageShell>
  );
}
