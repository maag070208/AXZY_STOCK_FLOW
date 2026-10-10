/**
 * El motor ligero de Lottie, sin tipos propios.
 *
 * `lottie-web` publica `index.d.ts` para su entrada principal, pero el motor
 * ligero se importa por ruta directa (`build/player/lottie_light.min.js`), que no
 * trae declaración. Se declara **solo lo que se usa**: la API imperativa
 * (`loadAnimation`) con los parámetros que se le pasan.
 */
declare module "lottie-web/build/player/lottie_light.min.js" {
  interface AnimationItem {
    /** Detiene la animación y libera el contenedor. */
    destroy(): void;
  }

  interface LoadAnimationParams {
    /** El elemento donde se dibuja (lottie crea el SVG dentro). */
    container: Element;
    renderer?: "svg";
    loop?: boolean | number;
    autoplay?: boolean;
    /** La animación ya leída. La alternativa es `path`, una URL que se descarga. */
    animationData?: unknown;
  }

  const lottie: {
    loadAnimation(params: LoadAnimationParams): AnimationItem;
  };

  export default lottie;
}
