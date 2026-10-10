import { Suspense, lazy, useEffect, useRef } from "react";

/**
 * El indicador de carga de marca (docs/guia/identidad-visual.md).
 *
 * **El motor, medido.** Se usa `lottie-web` con su compilación **ligera**
 * (`lottie_light`, solo SVG) en lugar de `lottie-react`, que es lo que usa CYC. El
 * motivo es un número, no una preferencia: `vite build` deja el fragmento de
 * `lottie-react` en 762 kB (204 kB comprimidos) porque arrastra el motor completo
 * con expresiones, controles y superposiciones; el motor ligero son 164 kB (45 kB
 * comprimidos). **159 kB comprimidos menos para el mismo resultado visible.**
 *
 * **Y se carga aparte.** El motor y los 33 kB de la animación entran con
 * `import()` dinámico, así que solo se descargan cuando el loader aparece: el
 * paquete inicial no crece (el envoltorio son ~2 kB).
 *
 * El precio de usar el motor directo es que la API es imperativa: hay que crear la
 * animación en un `useEffect` y **destruirla** al desmontar. Sin el `destroy`, cada
 * apertura del diálogo dejaría una animación viva.
 */
const Animation = lazy(async () => {
  const [{ default: lottie }, animation] = await Promise.all([
    import("lottie-web/build/player/lottie_light.min.js"),
    import("@shared/assets/lottie/stockflow-loader-light.json"),
  ]);

  return {
    default: function StockFlowLoaderAnimation({ size }: { size: number }) {
      const container = useRef<HTMLDivElement>(null);

      useEffect(() => {
        const host = container.current;
        if (!host) return;

        const instance = lottie.loadAnimation({
          container: host,
          renderer: "svg",
          loop: true,
          autoplay: true,
          animationData: animation.default,
        });

        return () => instance.destroy();
      }, []);

      return <div ref={container} style={{ height: size, width: size }} aria-hidden="true" />;
    },
  };
});

interface Props {
  size?: number;
  /** Texto para quien no ve la animación (o mientras se descarga). */
  label?: string;
}

export function Loader({ size = 96, label = "Cargando…" }: Props) {
  return (
    <div role="status" aria-live="polite" className="flex flex-col items-center gap-1 py-2">
      {/* El texto se ve desde el primer instante: el `fallback` evita el hueco
          mientras baja el motor. */}
      <Suspense fallback={<span className="text-xs text-slate-500">{label}</span>}>
        <Animation size={size} />
      </Suspense>
      <span className="text-xs text-slate-500">{label}</span>
    </div>
  );
}
