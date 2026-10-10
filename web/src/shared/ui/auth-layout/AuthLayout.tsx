import type { ReactNode } from "react";
import BrandAnimation from "@shared/ui/brand-animation/BrandAnimation";
import i18n from "@shared/i18n";

interface Props {
  children: ReactNode;
}

/**
 * The frame of the access screens, just like in CYC: the brand animation on top, the name and the
 * form in a thin-border card over a soft background. No menu or bar: nothing distracts from signing in.
 */
export default function AuthLayout({ children }: Props) {
  return (
    <div className="relative flex min-h-screen items-center justify-center bg-gradient-to-br from-[#E8F0FC] via-white to-[#F2F6FC] px-4 py-10">
      <div className="flex w-full max-w-sm flex-col items-center gap-5">
        {/* Se reproduce una vez y se queda en el último cuadro, el logotipo completo: en bucle, la mayor parte del tiempo se ve el arranque vacío. */}
        <BrandAnimation name="intro" size={150} loop={false} />
        <div className="text-center">
          <h1 className="text-2xl font-bold text-[#16315F]">{i18n.t("app.name")}</h1>
          <p className="mt-1 text-sm text-slate-500">{i18n.t("app.tagline")}</p>
        </div>
        <div className="w-full rounded-2xl border border-slate-200 bg-white p-6">{children}</div>
      </div>
    </div>
  );
}
