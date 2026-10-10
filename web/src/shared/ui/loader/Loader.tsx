import BrandAnimation from "@shared/ui/brand-animation/BrandAnimation";
import i18n from "@shared/i18n";

interface Props {
  size?: number;
  /** Text for whoever cannot see the animation (or while it downloads). It is seen from the first instant. */
  label?: string;
}

/** The app's loading indicator: the brand animation with its text below. */
export function Loader({ size = 96, label = i18n.t("common.loading") }: Props) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col items-center gap-1 py-2">
      <BrandAnimation name="loader" size={size} />
      <span className="text-xs text-slate-500">{label}</span>
    </div>
  );
}
