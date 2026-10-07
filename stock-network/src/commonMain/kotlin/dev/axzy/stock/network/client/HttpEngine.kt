package dev.axzy.stock.network.client

import io.ktor.client.engine.HttpClientEngine

/**
 * El motor HTTP de cada plataforma.
 *
 * **Por qué `expect/actual` y no el motor por defecto de Ktor:** el motor por
 * defecto se resuelve en tiempo de compilación por artefacto, y en un módulo
 * multiplataforma eso deja el motor fuera del control del módulo. Declarándolo
 * aquí, la decisión es explícita y verificable:
 *
 *   · JVM y Android → **OkHttp**. Es el que ya usa la app de referencia en
 *     producción, y en Android respeta el proxy y el almacén de certificados del
 *     sistema.
 *   · iOS → **Darwin**, que delega en `NSURLSession` y por tanto usa el ATS, el
 *     Keychain de confianza corporativa y el modo avión como el resto del
 *     sistema.
 *
 * Un motor elegido a mano en `commonMain` (CIO) habría funcionado en las tres
 * plataformas y habría ignorado la configuración de red del dispositivo, que es
 * justo lo que hace falta en una bodega con proxy y certificado interno.
 */
internal expect fun platformHttpEngine(): HttpClientEngine
