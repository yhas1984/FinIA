# FinAI 1.8.0 (17) — validación de release

## Artefacto

- Paquete: `com.gastos.ingresos`.
- Manifiesto del AAB: `versionCode=17`, `versionName=1.8.0`, `debuggable=false`.
- AAB: `app/build/outputs/bundle/release/FinAI-1.8.0-v17.aab`, 9 393 475 bytes. El binario queda fuera de Git.
- SHA-256: `57d8bf0ba500f5fbd27f937c818dc66fc3d6dff204cd60b2cf9ed5e805d277aa`.
- Firma verificada mediante `jarsigner -verify -strict`, confiando en el almacén de FinAI. Certificado coincidente con `FinAI-upload-cert.pem`.
- SHA-256 del certificado: `995ade3eadc204324b2df7e7756fb7aa5f68e66b1f63a3dde8d3a444a67f01f6`.
- Estructura y manifiesto comprobados con bundletool oficial 1.18.3; SHA-256 de la herramienta contrastado con el digest de su release en GitHub.
- Backend obligatorio y configuración pública de verificación presentes en la variante release. Credenciales y almacén de firma se mantienen fuera del repositorio.

## Integración y respaldo de IA

Se integró la base `b4b5805` de `main`, conservando sus ajustes de thinking y el detalle de errores en la validación de clave. Por decisión explícita del usuario se conserva `gemini-3.6-flash` como principal. La ampliación posterior activa esta secuencia:

1. `gemini-3.6-flash`.
2. Un reintento de `gemini-3.6-flash` ante fallo recuperable.
3. `gemini-3.8-flash`.
4. `gemini-3.5-flash-lite`, únicamente si fallan los Flash anteriores y el error permite continuar.

Máximo cuatro solicitudes por operación, con los mismos límites totales de 90 segundos para chat y 180 para OCR. Cuota diaria de un modelo, salida inválida o modelo no disponible omiten su reintento. Claves inválidas, peticiones incorrectas, seguridad y cuotas globales detienen la cadena. El OCR de Flash-Lite debe superar la misma validación. Streaming solo cambia automáticamente antes del primer fragmento visible.

## Verificación del código final

- **225 pruebas unitarias Android**, ninguna fallida, errónea ni omitida; incluye 13 pruebas de selección de modelos, errores, OCR, streaming y cancelación.
- **5 pruebas del backend**, correctas.
- **12 pruebas instrumentadas**, correctas en `FinAI_Test`, ejecutadas sobre `1.8.0-dev (17)`. [Salida del runner](release-instrumentation.txt).
- **Lint release: 0 errores, 23 advertencias** en el informe de la aplicación.
- Compilación final `BUILD SUCCESSFUL`; `git diff --check` correcto.

```sh
./gradlew testDebugUnitTest lintRelease :app:bundleRelease :app:assembleDebug :app:assembleDebugAndroidTest --offline --console=plain
./gradlew -p backend/billing test --offline --console=plain
adb -s emulator-5554 shell am instrument -w -r com.gastos.ingresos.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Las variables del backend se cargaron de la configuración local ignorada por Git. La firma se cargó del almacén de FinAI y de las propiedades privadas de Gradle. Se utilizó AndroidJUnitRunner directamente, como en la validación anterior.

## Comprobación de la versión optimizada

Se generó una APK universal a partir del mismo AAB con bundletool, se verificó su certificado y se instaló en el emulador, donde no existía el paquete de producción. El arranque de `com.gastos.ingresos/.MainActivity` devolvió `Status: ok`. Se revisaron dashboard, listado y formulario manual; [el IVA inicial aparece en 0 %](release-vat-zero.png).

También se introdujo `12,50` con IVA 150 %: la validación conservó los campos. Tras corregir a 4 %, el formulario se cerró y quedó [un único gasto sintético](release-saved-expense.png) de 12,50 €, con base 12,02 € e IVA 0,48 €.

## Alcance y avisos

Drive, Gemini y verificación de compras se probaron con servicios simulados y datos sintéticos, según lo acordado. Esta validación no acredita disponibilidad real de los tres modelos ni precisión fiscal de Flash-Lite con documentos reales. No se desplegó el backend ni se publicó el AAB en Google Play.

La combinación existente AGP 8.10.1 / Kotlin 2.3.21 emite avisos de R8 al interpretar metadatos Kotlin. La compilación, firma, validación del bundle y arranque de la versión optimizada finalizaron correctamente. Los avisos no se han ocultado ni se ha cambiado la configuración de optimización. La comprobación de arranque no sustituye una prueba integral de todos los flujos en producción.
