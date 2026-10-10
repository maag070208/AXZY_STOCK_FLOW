# Verificación: las reglas que impiden el autoengaño
> **Normativo.** No son recomendaciones. Son la respuesta a errores concretos que ya se
> cometieron. Última verificación: 2026-10-09.
> **Nota de reconstrucción (2026-10-10).** Las reglas de verificación siguen valiendo; los comandos citan el backend anterior. El comando vigente es `./gradlew verify`. Las reglas siguen vigentes; las rutas de código que cita pertenecen al sistema anterior y se remapean al código nuevo (ver [plan/PLAN-IMPLEMENTACION.md](../plan/PLAN-IMPLEMENTACION.md)).

**1. Lee los XML de los tests, nunca «BUILD SUCCESSFUL».**
Gradle reporta éxito con 0 tests ejecutados.

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
for mod in ['stock-domain','backend']:
    t=f=0
    for p in glob.glob(mod+'/build/test-results/**/TEST-*.xml', recursive=True):
        r=ET.parse(p).getroot(); t+=int(r.get('tests',0)); f+=int(r.get('failures',0))
    print(f'{mod}: {t} tests, {f} fallos')
"
```

**2. Prueba que una guardia falla antes de creerla.**
Una guardia se verifica quitando a propósito la condición que vigila: si los tests siguen en
verde, la guardia no vigila nada. **Un test que nunca ha fallado no demuestra que vigile
nada.**

**3. Los tests compilan el código; el servidor corre un artefacto.**
Son dos afirmaciones distintas. Tras cambiar código:

```bash
./gradlew :backend:installDist && reiniciar
```

Este error ya produjo `NoClassDefFoundError` en producción con la suite en verde.

**4. Prueba una URL con `curl` antes de escribir la interfaz.**
`tsc` y `eslint` **no validan URLs en ejecución**. El bug `/v1/v1/...` pasó los dos
([web-fsd.md](../arquitectura/web-fsd.md) §2.3).

**5. Un resultado alarmante también se verifica antes de creerlo.**
Una consulta mal formada puede hacer creer que el kardex perdió filas. Antes de investigar
una pérdida de datos, se comprueba que la consulta mide lo que se cree que mide.

**6. Los agentes en paralelo necesitan archivos disjuntos.**
Dos procesos que editan el mismo archivo a la vez rompen el build (`FileNotFoundException`
sobre una clase compilada). **Los que compilan Gradle se serializan**, y **dos agentes no
pueden tocar el mismo archivo**, nunca.

Los niveles de prueba y cómo se ejecutan están en
[estrategia-pruebas.md](../historico/ESTRATEGIA-PRUEBAS-ANTERIOR.md).

## Un `./gradlew test` en verde no significa que se hayan ejecutado todas las pruebas

Comprobado el 2026-10-10, y es la trampa más cara que ha aparecido: **`./gradlew test`
no ejecuta `:stock-domain:jvmTest`**. El proyecto del dominio es multiplataforma
(Kotlin Multiplatform) y su tarea de pruebas se llama `jvmTest`, así que el `test`
agregado —que sí recorre los módulos JVM— lo dejaba fuera.

El síntoma es exactamente el que este documento advierte en otro sitio: el informe
salía `BUILD SUCCESSFUL`, y el recuento de pruebas del dominio se leía de los XML de
una ejecución **anterior**. Durante dos vueltas se informó de «407 pruebas del dominio,
0 fallos» mientras había una prueba del dominio en rojo que nadie ejecutaba
(`AdjustmentTest` esperaba el código de ubicación del legacy, `04BCA`, después de que la
etiqueta visible pasara a ser `04-B-C`).

Lo que se hizo:

- El comando de verificación es **`./gradlew verify`**, una tarea que depende de todas
  las tareas de prueba de todos los módulos (`test` y `jvmTest`) y de las guardias de
  arquitectura. Si un módulo cambia el nombre de su tarea, `verify` hay que actualizarlo.
- **Borrar los XML antes de medir** (`rm -rf */build/test-results`). Un recuento sin
  borrar es un recuento de la última vez que algo se ejecutó.

La regla general que se suma a las de arriba: **un informe de pruebas solo vale si se
sabe qué tarea lo produjo y cuándo**. `BUILD SUCCESSFUL` de una tarea que no incluye el
módulo que se tocó no dice nada sobre ese módulo.

## Una migración tiene que poder re-aplicarse

Comprobado el 2026-10-10, y costó un despliegue en rojo: al escribir la migración `V14`
la apliqué a mano contra la base para comprobar el SQL, y cuando Flyway la ejecutó se
encontró la columna ya creada y **el servicio de migración falló con `exit 1`**. El
síntoma fue peor de lo que parece: el API no arrancó y las comprobaciones por `curl`
devolvían `000` (conexión rechazada) mientras el archivo de la respuesta anterior seguía
en `/tmp`, así que el primer vistazo decía «403, todo bien».

Dos reglas que salen de ahí:

- **El DDL de una migración va con `IF NOT EXISTS`** (`ALTER TABLE … ADD COLUMN IF NOT
  EXISTS`, `CREATE INDEX IF NOT EXISTS`). Flyway garantiza aplicarla una vez, pero un
  humano puede haberla aplicado antes, y poder re-aplicarla no cuesta nada.
- **Cuando una comprobación falla, mirar el código HTTP y no el cuerpo del archivo
  anterior.** Un `000` no es un `403`: el primero significa que nadie contestó.

## Un `docker compose build` puede salir entero de caché y dejar el jar viejo

Comprobado el 2026-10-10, y es la trampa más silenciosa de todas las de este documento:
`docker compose build api` respondió `Image stockflow-api Built` sin ejecutar **ni un paso
de compilación** —todas las capas `CACHED`—, así que la imagen siguió con el jar anterior.
El contenedor arrancó sano, `/v1/health` respondió `UP`, y la ruta nueva contestaba
`401 UNAUTHENTICATED` como si no existiera. Casi lo doy por bueno.

Lo que lo delató: la misma petición **pasaba** en las pruebas de integración, que arrancan
la aplicación desde las clases recién compiladas. Si una prueba y una comprobación por
`curl` dicen cosas distintas, el sospechoso es el artefacto que corre, no la prueba.

Cómo comprobarlo sin adivinar: leer el jar **dentro de la imagen**.

```bash
docker run --rm --entrypoint sh stockflow-api -c \
  'unzip -p /app/app.jar BOOT-INF/classes/dev/axzy/stock/app/config/SecurityConfig.class | strings | grep -c "print-jobs"'
```

Dos detalles que hacen falta para que eso funcione, y que también me costaron un rato:

- Un jar de Spring Boot **no lleva las clases del proyecto sueltas**: van en jars anidados
  (`BOOT-INF/lib/adapters-web-0.1.0.jar`). Un `unzip -l | grep PrintJobController` sobre el
  jar exterior devuelve `0` aunque el código esté dentro, y parece que falta cuando está.
- Por eso hay que mirar la clase del módulo `app` (que sí va suelta en
  `BOOT-INF/classes/`) o extraer primero el jar anidado.

Y lo que se hace cuando pasa: `docker compose up -d --force-recreate api`, y volver a
comprobar la ruta contra el artefacto que corre. La regla de arriba sigue valiendo y ahora
tiene nombre propio: **el artefacto que corre no es el código compilado**, y una imagen que
se construyó «con éxito» sin compilar nada no es una imagen nueva.

## `write` sobre un archivo de pruebas que ya existía borra lo que había

Comprobado el 2026-10-10, y es un error mío que merece quedar escrito: al escribir las
pruebas de la lectura de XLSX, **escribí `CatalogUploadTest.kt` sin comprobar que ya
existía**, y las ocho pruebas del lector de CSV/TSV que había dentro desaparecieron. Lo
delató `git status`, que marcaba el archivo como `M` (modificado) en vez de nuevo, y el
recuento del módulo: la capa web pasó de 13 pruebas a 11 **después** de añadir seis.

Dos reglas que salen de ahí:

- **Antes de escribir un archivo de pruebas, `git status` o un `glob`.** Si existe, se lee y
  se amplía; sobrescribirlo es borrar trabajo que ya estaba verificado.
- **El recuento de pruebas por módulo es una guardia.** Un módulo que gana pruebas y baja de
  número tiene un problema, aunque la suite esté en verde. Se mira el número antes y después.

Lo que se hizo: recuperar el archivo del repositorio y **fusionarlo** con las pruebas nuevas,
después de sustituir la única que quedaba obsoleta (la que comprobaba que el XLSX se
rechazaba, porque ya se lee). El recuento volvió a subir y las ocho originales siguen ahí,
con las cuatro nuevas que no repetían nada.

## Un test que falla una hora al día: «hoy» no es la fecha de la máquina

Comprobado el 2026-10-10, y costó un rato de confusión: dos pruebas de reportes empezaron a
fallar con el resumen de movimientos **vacío**. No era el cambio que acababa de hacer: era la
hora. La máquina estaba en `PDT` (9 de octubre, 23:02) y la zona de negocio de la API es
`America/Mexico_City` (UTC-6), donde ya era **10 de octubre**. La prueba pedía
`from=hoy&to=hoy` con `LocalDate.now()` —la fecha **de la máquina**— mientras la API fecha los
movimientos en la zona de negocio, así que buscaba en el día equivocado.

Es un fallo de una hora al día, la peor clase: invisible casi siempre y desconcertante cuando
aparece. La regla que sale de ahí:

- **Una prueba que pregunta por «hoy» tiene que calcularlo como lo calcula la API**, con la
  misma zona y de la misma configuración. En este proyecto eso es
  `businessToday()` (`LocalDate.now(properties.businessZone)`), que vive en `ApiTest` para que
  no haya dos formas de decirlo.
- Vale para cualquier filtro por fecha: reportes, kardex y auditoría. Si una prueba construye
  una fecha con `LocalDate.now()` a secas, está en hora de la máquina y algún día fallará.

## Una prueba que lee una contraseña de arranque se rompe al primer cambio

Comprobado el 2026-10-10: la prueba de entrada contra la API real falló con «Usuario o contraseña
incorrectos», aunque la contraseña era la de `.env`. No era un fallo de la API: el administrador ya
había cambiado su contraseña, que es lo que la API exige al primer ingreso. `ADMIN_BOOTSTRAP_PASSWORD`
solo se usa al crear la cuenta, y la prueba la tomaba como contraseña vigente.

La regla que sale de ahí: **una prueba necesita su propia credencial**, no la que solo sirve para
arrancar. Ahora es `E2E_ADMIN_PASSWORD`, con `ADMIN_BOOTSTRAP_PASSWORD` solo como respaldo. Si una
prueba falla con una credencial que «debería valer», mira primero si la cuenta cambió la suya.

