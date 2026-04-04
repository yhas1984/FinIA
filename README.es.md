# FinAI (FinIA)

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API%2026+-3DDC84?logo=android)](https://developer.android.com/)

**Idiomas / Languages:** [Español](README.es.md) · [English](README.md)

FinAI es una aplicación Android para finanzas personales y de pequeño negocio: **gastos**, **ingresos** (con campos adaptados a nóminas), **líneas de producto**, **panel resumen**, **copia de seguridad/exportación** y un **asistente de IA** usable con **Google Gemini (nube)** o **Gemma (en el dispositivo, LiteRT)**. Incluye **OCR por cámara** (ML Kit) para tickets y facturas, con reglas para reconciliar totales y líneas cuando el modelo se equivoca en tickets españoles (supermercado, IVA, peso €/kg, etc.).

## Funcionalidades

- **Facturas y gastos:** Proveedor, fecha, totales, base y cuota de IVA, categoría, NIF/CIF si constan.
- **Ingresos / nómina:** Líquido, devengado, deducciones, concepto y tipo; pistas OCR para nóminas en formato español.
- **Productos:** Descripción, cantidad, precio unitario, subtotal, comercio, fecha de compra; asociados al gasto.
- **Escanear ticket:** Reconocimiento local de texto más extracción estructurada con IA; mejoras para tickets tipo comercio (líneas a peso, total en pie de ticket).
- **Chat y voz:** Comandos y consultas en lenguaje natural sobre tus datos (motor configurable en Ajustes).
- **Copia de seguridad:** Exportación CSV estructurada (gastos, productos, ingresos).
- **Arquitectura modular:** `:app`, `:core:*`, `:feature:*` (panel, facturas, productos, ingresos, OCR, IA, ajustes, copia, fiscal, chatbot, voz).

## Requisitos

- **Android Studio** Koala (2024.1.1) o superior recomendado (AGP 8.x, compile SDK 35).
- **JDK 17**
- **API de Gemini** (opcional): configura la clave en **Ajustes → IA** para inferencia en la nube.
- **Gemma local** (opcional): modelo en dispositivo y espacio según lo indicado en la app.

## Compilar

```bash
./gradlew :app:assembleDebug
```

Instalar en dispositivo o emulador conectado:

```bash
./gradlew :app:installDebug
```

Las compilaciones `release` usan ofuscación; configura el firmado en `app/build.gradle.kts` según tus necesidades.

## Estructura del proyecto

| Módulo | Función |
|--------|---------|
| `:app` | Shell de la aplicación, navegación, DI |
| `:core:domain` | Modelos de dominio |
| `:core:data` | Room, repositorios, mapeos |
| `:feature:ai` | AIService, parsers, Gemini/Gemma |
| `:feature:ocr` | Flujo de escaneo de facturas |
| `:feature:chatbot` | UI de chat y ViewModel |
| Otros `:feature:*` | Panel, facturas, productos, ingresos, ajustes, copia, fiscal, voz |

## Permisos

Cámara (escaneo), internet (API Gemini), micrófono (voz). Almacenamiento según reglas de versiones antiguas de Android cuando aplica.

## Contribuir

Issues y pull requests son bienvenidos en [FinIA](https://github.com/yhas1984/FinIA).

## Licencia

Añade un archivo `LICENSE` si publicas el repositorio como open source.

---

*Repositorio:* [https://github.com/yhas1984/FinIA](https://github.com/yhas1984/FinIA)
