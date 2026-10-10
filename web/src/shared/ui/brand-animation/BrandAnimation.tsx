import { Suspense, lazy, useEffect, useRef, type ComponentType } from "react";

/**
 * The StockFlow brand animation (docs/guia/identidad-visual.md).
 *
 * **Light engine, loaded separately.** It uses `lottie-web` with its `lottie_light` build (SVG only)
 * instead of `lottie-react`, which drags in the full engine: 164 kB versus 762 kB uncompressed. The
 * engine and the animation are loaded with a dynamic `import()`, so the initial bundle does not grow.
 *
 * The `lottie-web` API is imperative: the animation is created in a `useEffect` and must be **destroyed** on
 * unmount. Without `destroy`, each appearance would leave a live animation running.
 */

const animations = {
  /** Loading indicator: to wait for data. */
  loader: () => import("@shared/assets/lottie/stockflow-loader-light.json"),
  /** Entry animation: the access frame. */
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

/** Reserves the size while the engine loads: there is no layout jump when the animation appears. */
export default function BrandAnimation({ name, size = 96, loop = true }: Props) {
  const Player = players[name];
  return (
    <Suspense fallback={<div style={{ height: size, width: size }} aria-hidden="true" />}>
      <Player size={size} loop={loop} />
    </Suspense>
  );
}
