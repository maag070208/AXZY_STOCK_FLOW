import BrandAnimation from "@shared/ui/brand-animation/BrandAnimation";
import i18n from "@shared/i18n";

interface Props {
  size?: number;
  /** Texto para quien no ve la animación (o mientras se descarga). Se ve desde el primer instante. */
  label?: string;
}

/** El indicador de espera de la app: la animación de marca con su texto debajo. */
export function Loader({ size = 96, label = i18n.t("common.loading") }: Props) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col items-center gap-1 py-2">
      <BrandAnimation name="loader" size={size} />
      <span className="text-xs text-slate-500">{label}</span>
    </div>
  );
}
