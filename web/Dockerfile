# syntax=docker/dockerfile:1
# La web en producción. Contexto de build: la carpeta web/.
#
#   docker compose build web
#
# Dos etapas: Node compila con pnpm; nginx sirve solo el resultado. Node, el código fuente y las
# dependencias de desarrollo no llegan a la imagen que se ejecuta.

FROM node:22-alpine AS build
WORKDIR /src
RUN corepack enable

# Primero las dependencias: la capa se reutiliza mientras no cambie el lockfile.
COPY package.json pnpm-lock.yaml ./
RUN --mount=type=cache,target=/root/.local/share/pnpm/store \
    pnpm install --frozen-lockfile

COPY . .
# El build lo comprueba todo: tipos (`tsc --noEmit`) y el empaquetado de Vite.
RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /src/dist /usr/share/nginx/html
# La imagen `unprivileged` escucha en 8080 y corre sin root.
USER 101
EXPOSE 8080
