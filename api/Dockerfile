# syntax=docker/dockerfile:1
# La imagen de la API. Contexto de build: la raíz del repositorio (ver .dockerignore).
#
#   docker compose build api
#
# Dos etapas: la primera compila con el JDK; la segunda solo lleva el JRE y el jar. El JDK, Gradle
# y el código fuente no llegan a la imagen que se ejecuta.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src

# Primero lo que cambia poco: así Docker reutiliza la capa de dependencias cuando solo cambia el código.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY stock-domain/build.gradle.kts stock-domain/
COPY api/application/build.gradle.kts api/application/
COPY api/adapters-persistence/build.gradle.kts api/adapters-persistence/
COPY api/adapters-security/build.gradle.kts api/adapters-security/
COPY api/adapters-web/build.gradle.kts api/adapters-web/
COPY api/app/build.gradle.kts api/app/

COPY stock-domain stock-domain
COPY api api

# Las pruebas no corren aquí: necesitan Docker (Testcontainers) y se ejecutan antes, en CI.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :api:app:bootJar --no-daemon --console=plain

# Alpine: la imagen final pesa menos de la mitad que la basada en Ubuntu. No hay código nativo
# que dependa de glibc: Argon2id es Java puro (BouncyCastle).
FROM eclipse-temurin:25-jre-alpine
# Un usuario sin privilegios: la API no necesita ser root para nada.
RUN adduser -S -u 10001 -H stock
WORKDIR /app
COPY --from=build /src/api/app/build/libs/stock-api.jar app.jar
USER stock
EXPOSE 8080

# El 75 % de la memoria del contenedor para el heap: la JVM respeta el límite que le ponga Compose.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
