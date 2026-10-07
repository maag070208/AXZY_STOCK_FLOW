import react from "@vitejs/plugin-react-swc";
import tailwindcss from "@tailwindcss/vite";
import path from "path";
import { defineConfig } from "vite";
import pkg from "./package.json";

export default defineConfig({
  define: {
    // La versión se inyecta en build: la barra superior y el diagnóstico de salud
    // muestran exactamente lo desplegado, no lo que dice el package.json del repo.
    __APP_VERSION__: JSON.stringify(pkg.version),
  },
  plugins: [tailwindcss(), react()],
  build: {
    // `esnext` a propósito: la web sólo corre en navegadores con soporte de
    // módulos modernos y el bundle se sirve por nginx junto al backend.
    target: "esnext",
    minify: true,
    cssCodeSplit: false,
    sourcemap: true,
  },
  resolve: {
    alias: {
      // Los alias replican `tsconfig.json`; si uno cambia, el otro también.
      "@app": path.resolve(__dirname, "./src/app"),
      "@shared": path.resolve(__dirname, "./src/shared"),
      "@entities": path.resolve(__dirname, "./src/entities"),
      "@features": path.resolve(__dirname, "./src/features"),
      "@widgets": path.resolve(__dirname, "./src/widgets"),
      "@pages": path.resolve(__dirname, "./src/pages"),
    },
  },
  server: {
    proxy: {
      // En desarrollo se evita CORS: la web pide `/v1/...` al mismo origen y
      // Vite lo reenvía al backend. En producción lo hace nginx.
      "/v1": {
        target: process.env.VITE_DEV_API_TARGET ?? "http://localhost:8080",
        changeOrigin: true,
      },
    },
  },
});
