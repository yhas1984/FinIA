# Video preview 30s — Storyboard FinAI

Video vertical (9:16, 1080×1920), máx. 30 segundos, con subtítulos y música ligera.
Objetivo: mostrar el diferenciador (IA + escaneo) y cerrar con CTA.

## Secuencia

| Tiempo | Plano | Qué se ve | Subtítulo |
|--------|-------|-----------|-----------|
| 0–5s | Hook | Escaneo de una factura con la cámara; la IA extrae campos en tiempo real | "Escanea facturas en segundos" |
| 5–15s | Chat IA | Escribir "gasté 20€ en café" → se registra; pregunta "¿cuánto gasté este mes?" → respuesta al instante | "Registra y pregunta con IA" |
| 15–25s | Dashboard | Balance del mes, donut de categorías, calendario financiero, cambio de mes | "Tus finanzas, claras de un vistazo" |
| 25–30s | Cierre | Logo + nombre en pantalla | "FinAI — Controla tus gastos" |

## Notas de producción

- Capturar con `adb screenrecord` (1080×1920, 30 fps) sobre el emulador o dispositivo real.
- Recortar las tomas a 5s las de demostración; el total debe ser ≤ 30s.
- Añadir subtítulos quemados (no confiar solo en audio).
- Cerrar con pantalla de logo 3–5s (reutilizar `docs/finai-logo.png` o `play_assets/play_store_icon.png`).
- Music: pista libre de derechos, volumen bajo (−20 dB aprox. bajo la voz si hay locución).
- No usar texto ilegible a tamaño reducido: fuente ≥ 60px en 1080p.

## Herramientas sugeridas

- Grabación: `adb shell screenrecord --size 1080x1920 --bit-rate 8000000 /sdcard/xxx.mp4`
- Edición: CapCut / DaVinci Resolve (gratis) / ffmpeg
- Subir: Play Console → Store presence → Main store listing → Video (se aplica a todos los locales)
