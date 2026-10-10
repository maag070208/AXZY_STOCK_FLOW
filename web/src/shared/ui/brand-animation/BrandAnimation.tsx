import { Suspense, lazy, useEffect, useRef, type ComponentType } from "react";

/**
 * La animación de marca de StockFlow (docs/guia/identidad-visual.md).
 *
 * **Motor ligero, y cargado aparte.** Se usa `lottie-web` con su compilación `lottie_light` (solo SVG)
 * en lugar de `lottie-react`, que arrastra el motor completo: 164 kB frente a 762 kB sin comprimir. El
 * motor y la animación entran con `import()` dinámico, así que el paquete inicial no crece.
 *
 * La API de `lottie-web` es imperativa: la animación se crea en un `useEffect` y se **destruye** al
 * desmontar. Sin el `destroy`, cada aparición dejaría una animación viva.
 */

const animations = {
  /** Indicador de carga: para esperar datos. */
  loader: () => import("@shared/assets/lottie/stockflow-loader-light.json"),
  /** Animación de entrada: el marco de acceso. */
  intro: () => import("@shared/assets/lottie/stockflow-intro-light.json"),
} as const;

export type BrandAnimationName = keyof typeof animations;

interface PlayerProps {
  size: number;
  loop: boolean;
}

function createPlayer(load: () => Promise<{ default: object }>): ComponentType<PlayerProps> {
  return lazy(async () => {
    const [{ default: lottie }, { default: animationData }] = await Promise.all([
      import("lottie-web/build/player/lottie_light.min.js"),
      load(),
    ]);

    return {
      default: function BrandPlayer({ size, loop }: PlayerProps) {
        const container = useRef<HTMLDivElement>(null);

        useEffect(() => {
          const host = container.current;
          if (!host) return;

          const instance = lottie.loadAnimation({
            container: host,
            renderer: "svg",
            loop,
            autoplay: true,
            animationData,
          });

          return () => instance.destroy();
        }, [loop]);

        return <div ref={container} style={{ height: size, width: size }} aria-hidden="true" />;
      },
    };
  });
}

const players: Record<BrandAnimationName, ComponentType<PlayerProps>> = {
  loader: createPlayer(animations.loader),
  intro: createPlayer(animations.intro),
};

interface Props {
  name: BrandAnimationName;
  size?: number;
  loop?: boolean;
}

/** Reserva el tamaño mientras baja el motor: no hay salto de diseño al aparecer la animación. */
export default function BrandAnimation({ name, size = 96, loop = true }: Props) {
  const Player = players[name];
  return (
    <Suspense fallback={<div style={{ height: size, width: size }} aria-hidden="true" />}>
      <Player size={size} loop={loop} />
    </Suspense>
  );
}
