# Checklist ASO — FinAI 1.8.5

Estado a 20/09/2026. Los archivos locales están preparados; los cambios públicos siguen pendientes de confirmación.

## 1. Metadatos

- [x] Mantener `FinAI: Control de Gastos` para es-ES y es-419.
- [x] Mantener `FinAI: Expense Tracker` para en-US.
- [x] Reescribir las descripciones breves dentro del límite de 80 caracteres.
- [x] Eliminar presupuestos, asesor financiero, garantías de velocidad y modelos concretos.
- [x] Añadir captura de documentos, duplicados, varios impuestos, Google Sheets y Google Drive.
- [x] Generar `translations.csv` desde los textos revisados.
- [ ] Publicar los tres idiomas en Play Console.

## 2. Recursos gráficos

- [x] Definir una secuencia de 8 mensajes localizada.
- [x] Preparar un editor reproducible en `marketing/playstore-screenshot-editor/`.
- [ ] Sustituir cualquier dato personal por ejemplos sintéticos.
- [ ] Revisar cada captura a tamaño pequeño y en un teléfono real.
- [ ] Exportar 1080 × 1920 para es-ES, es-419 y en-US.
- [ ] Exportar el gráfico destacado de 1024 × 500.
- [ ] Subir recursos en el mismo orden en los tres idiomas.

Orden de capturas:

1. Una foto. Gasto registrado.
2. Tickets, facturas y nóminas.
3. Aviso de duplicados.
4. Varios tipos de IVA o impuestos.
5. Balance mensual.
6. Consultas sobre movimientos.
7. Datos en Google Sheets y fotos en Google Drive.
8. Backup cifrado y recuperable.

## 3. Confianza y políticas

- [x] Confirmar en el repositorio que no hay SDK de anuncios, `AD_ID` ni interfaz publicitaria.
- [ ] Cambiar la declaración `Contiene anuncios` a `No` en Play Console.
- [ ] Revisar el formulario completo de seguridad de datos; incluir el procesamiento de Gemini y los flujos voluntarios de Drive/Sheets según corresponda.
- [ ] Verificar la URL pública de la política de privacidad y que describa IA, Drive, Sheets, fotos y copias.
- [ ] Confirmar categoría Finanzas, correo de soporte, audiencia y clasificación.

## 4. Conversión y medición

- [x] Guardar la línea base de los últimos 28 días en `ASO-AUDIT-2026-09-20.md`.
- [ ] Registrar cada lunes impresiones, visitas, adquisiciones, fuente, país y conversión.
- [ ] No mezclar cambios de textos y gráficos en el primer experimento.
- [ ] Probar primero la captura principal cuando haya tráfico suficiente.
- [ ] Crear una ficha personalizada para intención `escanear facturas/recibos` cuando Play Console muestre una palabra elegible.

## 5. Producto que influye en ASO

- [ ] Añadir Google Play In-App Review después de varias acciones satisfactorias, con límites de frecuencia.
- [ ] Revisar bloqueos, ANR, tiempo de inicio y fallos de captura antes de cada lanzamiento.
- [ ] Responder todas las reseñas y convertir patrones repetidos en tareas de producto.
- [ ] No pedir una reseña tras un error, un documento pendiente o el primer inicio.

## 6. Publicación

- [ ] Revisar la vista previa de los tres idiomas.
- [ ] Guardar los cambios de ficha y la declaración de anuncios.
- [ ] Revisar el resumen de publicación.
- [ ] Enviar a revisión.
- [ ] Anotar fecha y métricas de inicio para comparar 7, 14, 28 y 60 días.
