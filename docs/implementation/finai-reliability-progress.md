# FinAI: implementación del plan de fiabilidad

Implementación en `codex/finai-1.8.5-document-taxes`. Se conserva la versión 1.8.7 (24) y el trabajo previo del icono y la captura. Esta entrega no incorpora nuevas funciones bancarias.

## Cambios implementados

- [x] Texto/voz: impuestos desconocidos sin IVA heredado, sin productos ni nóminas inventados, fechas estrictas y conservación de fechas explícitas. Los componentes fiscales usan la misma validación que los documentos.
- [x] Room 15: operación persistente, movimiento, productos y recibo de chat atómicos. El reintento conserva la identidad incluso después de recortar el historial. Metadatos opcionales en respaldos.
- [x] Ingresos históricos: lectura y edición desde Ingresos sin trasladar sus filas originales ni perder UUID, impuestos o fotografías; una sola inclusión en informes.
- [x] Cola 5: UUID, cuenta y libro concretos; intención de borrado preparada y recuperación tras interrupciones. Un fallo de confirmación remota no invalida el borrado local.
- [x] Sheets 9: seis hojas, mismo enlace, copia verificada antes de migrar/reconstruir, escrituras por documento, columnas personales conservadas y resumen calculado desde las filas remotas. Se mantienen los filtros del usuario.
- [x] Sincronización incremental: huella de los datos confirmados por cuenta/libro/documento. Las ediciones y cambios de tasas vuelven a sincronizar; los documentos sin cambios no se reescriben.
- [x] Un teléfono escritor identificado en el libro y transferencia explícita. La app comprueba el propietario de la sincronización antes de escribir.
- [x] Interfaz: un acceso principal a Sheets, reconstrucción en opciones avanzadas, acceso persistente al libro, un diálogo CSV/PDF con Guardar/Compartir, sin selector Todas/Solo gastos ni tarjeta duplicada del asistente.
- [x] Respaldo creado y consulta de la lista tienen resultados independientes.
- [x] Informes en segundo plano: lectura transaccional, tasas fijadas durante el informe, progreso/cancelación, productos vinculados por UUID/fecha, texto PDF ajustado y paginado, caché privada con retención de siete días.
- [x] README y borradores ASO es-ES/es-419/en-US ajustados a la sincronización FinAI → Sheets y a Drive. No se modifican las imágenes aprobadas.

## Regresiones por problema

| Problema | Evidencia automatizada |
|---|---|
| IVA o productos inventados por texto | `CommandDocumentParserTest` |
| Desglose salarial inventado | `CommandDocumentParserTest`, `MappersTest`, `SheetsSchemaTest` |
| Fecha imposible sustituida por hoy | `CommandDocumentParserTest`: fechas imposibles, ausentes, ES y US |
| Doble registro tras fallo de confirmación | `CommandOperationDatabaseTest`: rollback, ocho reintentos concurrentes, dos envíos legítimos e historial borrado |
| Borrado previo de Sheets/columnas personales | `SheetsWorkbookPlanTest`, `SheetsWorkbookEngineTest`: escritura conjunta, columnas insertadas, reintento por UUID y ausencia de borrado de filas |
| Resumen incluye datos todavía locales | `SheetsWorkbookEngineTest`: fórmulas sobre rangos remotos, total parcial/no disponible |
| Borrado remoto sin destino durable | `SheetsSyncSafetyTest`, `RemoteSyncOutboxMigrationTest`: cuenta/libro, UUID reciclado, fases preparadas y fallo de confirmación |
| Ingresos históricos inconsistentes | `CommandOperationDatabaseTest`, `FinancialReportTest`, `SheetsSchemaTest` |
| Impuestos múltiples reducidos a suposiciones | `CommandDocumentParserTest`, `SheetsSchemaTest`, `TaxMigrationTest` y pruebas fiscales existentes |
| Filtro repetido deja Gastos cargando | `InvoicesViewModelTest` |
| Copia correcta se presenta fallida al listar | `BackupViewModelReliabilityTest` |
| Abrir Sheets exige exportar otra vez | `BackupViewModelReliabilityTest` |
| CSV/PDF bloquean o recortan | `FinancialReportTest`: hilo, 500 registros CSV, PDF de varias páginas, cancelación y retención; `BackupReportUiTest` ES/EN |
| Tipos/formatos erróneos de Sheets | `SheetsWorkbookPlanTest`: fechas numéricas y formatos por columnas mapeadas en ES/EN |
| Documento y productos de ediciones diferentes | `CommandOperationDatabaseTest`: lectura transaccional durante ediciones concurrentes |
| Regresiones ya corregidas | Pruebas de captura silenciosa, recibos de chat, impuestos, cifrado/restauración antiguos y copia completa superior a 300 MiB |

## Validación inicial con servicios simulados

Resultados de esa primera comprobación, registros y capturas: `../validation/2026-09-21/reliability/`. La comprobación posterior con Google real se describe debajo.

- Compilación de desarrollo y APK de pruebas: correctos.
- Pruebas unitarias Android: **344 aprobadas, 0 fallos**.
- Pruebas instrumentadas en Android 8/API 26: **40 aprobadas, 0 fallos, 1 omitida** (Gemini real, sin credenciales de pruebas).
- Lint: **0 errores, 34 advertencias**. Desglose en `lint-results.xml`.
- Inspección visual: diálogo de informes ES/EN y primera página del PDF largo. La captura inglesa se repitió en un proceso aislado para evitar artefactos del renderizador al cambiar el idioma durante la suite.
- `git diff --check`: correcto.

Comandos ejecutados sobre el árbol de esa comprobación:

```sh
./gradlew testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon --max-workers=1 -Dorg.gradle.jvmargs=-Xmx1024m -Pkotlin.compiler.execution.strategy=in-process --console=plain
adb -s emulator-5554 shell am instrument -w -r com.gastos.ingresos.dev.test/androidx.test.runner.AndroidJUnitRunner
```

Los APK de desarrollo y de pruebas quedan identificados por SHA-256 en `validation-summary.json`. Las capturas contienen documentos sintéticos.

## Ampliación: teléfono físico y Google real, 21 de septiembre de 2026

Después de que el usuario configurara una cuenta de Google y una clave Gemini de pruebas en FinAI Dev, se completó la [validación real](../validation/2026-09-21/live-account/README.md) en el OnePlus NE2213. Se corrigieron tres fallos reproducidos: comandos JSON envueltos en Markdown, streaming de comandos interrumpido y separadores de fórmulas del resumen de Sheets según el idioma del libro.

El APK corregido quedó instalado. Resultados del árbol posterior a esas correcciones: **352 pruebas unitarias Android, 5 del backend y 40 regresiones instrumentadas aprobadas**; además, **24/24 casos del recorrido real, 9/9 casos de Sheets y copia completa de 320 MiB aprobados**. Lint sigue en 0 errores y 34 advertencias. Se validaron migración real 8→9, columnas personales, Drive, restauración cifrada y fallback real a Gemini 3.5 Flash-Lite. Las claves y tokens permanecieron dentro de Android.

## Límites de la comprobación actual

- Las pruebas Google reales usan documentos sintéticos. La pérdida de una respuesta de Sheets después de escribir y los cambios de cuenta/permisos de Drive tienen cobertura simulada, sin reproducción forzada contra Google. Quedan pendientes la transferencia simultánea entre dos teléfonos, compras reales, reconocimiento por micrófono y fotografías físicas con desenfoque/reflejos.
- El control del teléfono escritor usa metadatos de Drive y comprobaciones previas. No es un bloqueo distribuido del servidor ni edición concurrente. La transferencia indica restaurar los datos y detener la sincronización anterior antes de continuar.
- Las filas antiguas que no se pueden asociar con seguridad y las estructuras remotas desconocidas detienen la sincronización para revisión; no se fusionan ni se eliminan silenciosamente.
- La revisión inicial se hizo en un emulador Android 8/API 26 y la posterior en el teléfono físico conectado, siempre en FinAI Dev. No se modificó la instalación de producción.
- Lint conserva advertencias de dependencias, recursos/icono y código de bibliotecas. La compilación de desarrollo no equivale a una publicación o a un AAB firmado.

## Empaquetado 1.8.8 (25)

Los cambios acumulados se han incorporado al AAB firmado [1.8.8](../validation/2026-09-21/release-1.8.8/README.md). Validación del árbol final: 395 pruebas unitarias y 5 pruebas instrumentadas de presentación e informes, sin fallos; lint release sin errores. Se cerraron los marcadores de categoría «null» y los porcentajes repetidos, y se incluyó la corrección del cierre del streaming de Gemini. No se ha publicado el AAB.
