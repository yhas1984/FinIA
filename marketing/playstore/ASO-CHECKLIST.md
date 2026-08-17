# ASO Checklist — FinAI (Google Play)

Estado de la ficha a 2026-08-16. Marca cada item al completarlo.

## 1. Textos es-ES (ficha principal)

- [ ] **Nombre** → `FinAI: Control de Gastos` (23/30)
- [ ] **Descripción breve** → `Controla gastos e ingresos, escanea facturas y sincroniza con Sheets.` (70/80)
- [ ] **Descripción completa** → ✅ pegada en Play Console (2.846/4.000)
- [ ] Guardar y enviar para revisión (los metadatos no requieren nueva versión de app)

## 2. Locales (traducciones)

- [ ] en-US → ✅ importado vía `marketing/playstore/translations.csv`
- [ ] es-419 → ✅ importado vía `marketing/playstore/translations.csv`
- [ ] Revisar en Play Console que ambos locales muestren los textos correctos (Store presence → Main store listing → selector de idioma)

## 3. Capturas — orden de subida (conversión, no cronológico)

En Play Console cada locale permite hasta 8 capturas; subir el mismo orden en los 3 locales.

| Pos | Archivo | Mensaje / overlay sugerido |
|-----|---------|---------------------------|
| 1 | `03-balance-dashboard.png` | "Tus gastos e ingresos, claros" |
| 2 | `02-escaneo-inteligente.png` | "Escanea facturas en segundos" |
| 3 | `01-conversacion-ia.png` | "Pregunta a tu asistente con IA" |
| 4 | `04-registro-detallado.png` | "Registro en lenguaje natural" |
| 5 | `05-asistente-personal.png` | "IA, voz y chat en español" (si duplica a 3, sustituir por 06) |
| 6 | `06-drive-sheets.png` | "Sincronizado con Google Sheets" |

- [ ] Revisión visual humana: legibilidad a tamaño reducido, sin recortes, overlay bien posicionado
- [ ] Opcional: añadir 2 capturas más hasta 8 (p. ej. categorías/subcategorías y backup cifrado)
- [ ] Las capturas de la ficha van en **los 3 locales** (en-US puede usar las mismas)

## 4. Video preview (opcional, recomendado — factor de conversión alto)

- [ ] Storyboard listo: `marketing/playstore/video-preview.md` (0-5s escaneo · 5-15s chat IA · 15-25s dashboard · 25-30s logo + CTA)
- [ ] Grabar con `adb screenrecord` 1080×1920 y editar ≤ 30 s con subtítulos quemados
- [ ] Subir en Store presence → Main store listing → Video (se reutiliza en todos los locales)

## 5. Ficha técnica (Play Console → Policy / App content)

- [ ] **Data safety form**: datos financieros en local, sin compartir; declarar envío de mensajes a la API de Gemini (procesamiento remoto)
- [ ] **Clasificación de contenido (IARC)** completada
- [ ] **Categoría**: Finanzas
- [ ] **URL de política de privacidad** publicada y enlazada
- [ ] Contacto (email) de desarrollador correcto

## 6. Release

- [ ] Subir `app/build/outputs/bundle/release/app-release.aab` (1.6.0, build 14) a Internal Testing → producción
- [ ] Release notes v1.6.0: usar `marketing/playstore-screenshots/release-notes-v1.6.0.txt`

## 7. Experimentos (tras publicación, con tráfico real)

- [ ] Grow → Store experiments → A/B test:
  - Título: `FinAI: Control de Gastos` vs `FinAI: Gastos e Ingresos`
  - Descripción breve: 2 variantes
  - Icono y primera captura
- [ ] Medir durante 2-4 semanas; aplicar la variante ganadora

## 8. Reseñas (a medio plazo, tarea de código)

- [ ] Implementar in-app review prompt (junto a notificaciones, próxima release)
- [ ] Pedir reseña tras uso positivo (no al abrir por primera vez)

## Assets locales (para regenerar capturas si se necesitan)

- Capturas: `marketing/playstore-screenshots/android/1080x1920/`
- Icono: `play_assets/play_store_icon.png` (512×512 ✅)
- Feature graphic conforme: `play_assets/feature_graphic_1024x500.png` (1024×500 ✅)
  - ⚠️ El original `play_assets/feature_graphic.png` es 1376×768, NO válido; subir la versión conforme
- OAuth: `play_assets/oauth/finai_oauth_1200.png`
