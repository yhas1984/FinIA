# Validación del plan FinAI — 19 de septiembre de 2026

Implementados los 14 puntos. Sin publicación en Google Play ni despliegue del backend. Se preservaron los planes de `.hermes/`. Esta evidencia corresponde al cierre inicial del plan; la preparación de 1.8.0 y la ampliación posterior del respaldo a Flash-Lite se documentan en [la validación de release](release-1.8.0.md).

## Resultado previo a preparar la versión 1.8.0

- **221 pruebas unitarias Android**, 0 fallos y 0 errores; ninguna omitida.
- **5 pruebas del backend**, todas correctas, incluidas respuestas compatibles 403/revocación y 503/indisponibilidad.
- **12 pruebas instrumentadas en FinAI_Test**, todas correctas. [Salida de AndroidJUnitRunner](android-instrumentation.txt).
- **Lint debug: 0 errores**. El informe de la aplicación conserva 23 advertencias (dependencias, recursos y biblioteca de transporte); no se han ocultado con un baseline nuevo.
- `git diff --check`: correcto.
- APK debug y APK de pruebas compiladas. Paquete aislado `com.gastos.ingresos.dev`, versión existente `1.6.0-dev`; no es un artefacto de publicación.
- SHA-256 APK debug: `1143891d68c1679f2417b8b6be7cd88b79544b1d45b7277d62891704dad427e1`.

Comandos utilizados:

```sh
./gradlew testDebugUnitTest lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
./gradlew -p backend/billing test --offline --console=plain
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w -r com.gastos.ingresos.dev.test/androidx.test.runner.AndroidJUnitRunner
```

El lanzador `connectedDebugAndroidTest` de Gradle no pudo arrancar en modo offline por dos dependencias UTP ausentes. Las pruebas se ejecutaron directamente con AndroidJUnitRunner sobre las APK compiladas; el resultado anterior procede del emulador, no de inspección estática.

## Cobertura de los 14 puntos

| Punto | Implementación y comprobación |
|---|---|
| 1. Guardado seguro | Estados Idle/Saving/Success/Error, éxito tras persistencia local, bloqueo de doble pulsación y retención de campos. Regresiones en formularios de gastos e ingresos; fallo remoto posterior conserva éxito. |
| 2. Conversiones incompletas | Resumen con exclusiones, moneda e importe original, fecha de tasas y ausencia distinguida de cero. Dashboard, calendario, gráficos, listados, chat, CSV/PDF y Sheets. Pruebas de importe parcial, todos sin tasa y actualización; actualizar tasas en chat vuelve a calcular la última consulta financiera local. |
| 3. Premium | Prueba firmada y fecha persistidas; ventana limitada por caducidad y siete días; errores no renuevan el plazo. Pruebas de consulta sin parpadeo, reinicio, vencimiento abierto, compra nueva, transición sin fecha y revocación frente a respuesta antigua. |
| 4. Borrado remoto explícito | Foto conservada por defecto. Borrado opcional desmarcado para referencias con cuenta identificada, ligado a archivo/cuenta. La migración descarta borrados heredados y la restauración no los genera. |
| 5. Identidad global | Room 11 → 12 con UUID por documento, tipo EXPENSE/INCOME y metadatos de Drive. Migración real preserva IDs, productos, IVA y enlaces. Subidas tardías solo modifican metadatos de la identidad y foto vigentes; referencias ambiguas requieren revisión. |
| 6. Respaldo solo de datos | Automático DATA_ONLY, manual DATA_ONLY por defecto o COMPLETE. Prueba con 310 MiB de fotos, JSON limitado, contraseña errónea, truncamiento y espacio insuficiente; ninguna imagen bloquea la copia de datos. |
| 7. Restauración | Formatos 1/2 compatibles y nuevo formato 3 autenticado. Restauración sin fotos conserva enlaces y solo reutiliza archivos locales con UUID coincidente. Pruebas de interrupción en todas las fases, IVA preservado y ausencia de borrados remotos. |
| 8. Cola independiente | Continúa Sheets aunque falle una foto; reintentos 30 s, 2 min, 10 min, 1 h y 6 h; después manual. Desconexión/autorización no consumen intentos y autorización queda pendiente sin temporizador infinito propio. |
| 9. Imágenes de ingresos | Subida desde los flujos de guardado/OCR/chat y reconciliación de registros antiguos. Reserva de ID antes de crear, UUID/tipo/cuenta/hash persistentes. Pruebas de IDs numéricos repetidos, respuesta perdida y reserva restaurada sin original local. |
| 10. Números locales | Parser único en pantalla, validación y guardado; coma/punto según locale, agrupaciones válidas, edición formateada y rechazo de texto sobrante/no finitos. |
| 11. IVA OCR 0 % | Cero explícito conservado frente al porcentaje general, ausencia usa porcentaje general y valor fuera de rango exige revisión. Regresión general 21 %, producto 0 % y producto inválido. |
| 12. Respaldo Gemini | Principal `gemini-3.6-flash`, respaldo `gemini-3.8-flash`, una clave, máximo tres solicitudes y límites totales de 90/180 s. Pruebas de cuota diaria/global, errores, clave inválida, seguridad, OCR inválido, streaming interrumpido, cancelación y límites de llamadas. El reintento sustituye la respuesta incompleta sin duplicar al usuario. |
| 13. Estados e imágenes al abrir | Panel independiente con sincronizadas/pendientes/fallidas/sin archivo, errores y reintento individual/conjunto. Descarga al abrir, caché privada por cuenta de 100 MiB, comprobación de contenido y LRU; se preservan originales pendientes. Pruebas de cuenta incorrecta, permisos, desconexión y archivo desaparecido. |
| 14. IVA inicial | Solo el formulario manual nuevo pasa de 21 % a 0 %. Modelos y catálogos fiscales conservan sus valores. Escaneado al 21 %, edición al 10 %, modificación al 4 % y restauración mantienen sus impuestos. |

## Prueba integral simulada

`DataOnlyBackupTest` crea un respaldo cifrado con gastos, ingresos, productos e información fiscal; lo restaura con datos locales vacíos, sin exigir fotografías, y descarga después las imágenes de ambos tipos a través de Drive simulado. Comprueba referencias distintas, IVA 10/4/0 %, descarga solo al abrir y cero borrados de Drive. `DriveIdentityTest` cubre también una subida confirmada en Drive cuya respuesta se pierde antes de restaurar su reserva.

## Revisión visual

[Alta manual con IVA 0 % en la APK final](manual-vat-zero.png). En el emulador se comprobó alta manual en 0 %, importe `12,50`, rechazo de IVA 150 % conservando proveedor/importe/IVA, corrección al 4 %, guardado único por 12,50 € y desglose base 12,02 € + IVA 0,48 €. [Formulario conservado ante validación](validation-error.png). [Estado del respaldo en la APK final](backup-states.png): estado de imágenes separado y «Data only» seleccionado por defecto. La captura inicial precede a la corrección cosmética de las etiquetas `%%` a `%` en la APK final.

## Alcance de la evidencia

Por decisión del usuario se utilizaron **pruebas locales y servicios simulados**. No se conectó una cuenta real de Drive ni una clave real de Gemini; por tanto no se afirma disponibilidad real de los modelos, cuota gratuita adicional, comportamiento con Google Play de producción ni acceso a archivos reales. No se modificaron los catálogos fiscales ni porcentajes existentes para establecer el IVA inicial del formulario. El backend compatible está preparado y probado, pendiente del despliegue cuando se solicite.
