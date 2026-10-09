# Verificación: las reglas que impiden el autoengaño

> **Normativo.** No son recomendaciones. Son la respuesta a errores concretos que ya se
> cometieron. Última verificación: 2026-10-09.

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
[estrategia-pruebas.md](estrategia-pruebas.md).
