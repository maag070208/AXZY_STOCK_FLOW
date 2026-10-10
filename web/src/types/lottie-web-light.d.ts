/**
 * The Lottie light engine, without its own types.
 *
 * `lottie-web` publishes `index.d.ts` for its main entry, but the engine
 * lightweight is imported by direct path (`build/player/lottie_light.min.js`), which does not
 * comes with a declaration. Only **what is used** is declared: the imperative API
 * (`loadAnimation`) with the parameters it is given.
 */
declare module "lottie-web/build/player/lottie_light.min.js" {
  interface AnimationItem {
    /** Stops the animation and releases the container. */
    destroy(): void;
  }

  interface LoadAnimationParams {
    /** The element where it is drawn (lottie creates the SVG inside). */
    container: Element;
    renderer?: "svg";
    loop?: boolean | number;
    autoplay?: boolean;
    /** The animation already read. The alternative is `path`, a URL that is downloaded. */
    animationData?: unknown;
  }

  const lottie: {
    loadAnimation(params: LoadAnimationParams): AnimationItem;
  };

  export default lottie;
}
