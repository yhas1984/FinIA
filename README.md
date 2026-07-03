# 💰 FinAI

**Asistente financiero personal para Android** con inteligencia artificial integrada (Gemini 3.5 Flash), escaneo de facturas por cámara, comandos por voz y chat conversacional. Pensado para gestión de gastos, ingresos y facturación, con orientación por defecto a España (EUR, IVA 21%, IRPF, NIF) pero configurable por país.

> **Nota:** La app está en **español**.

---

## ✨ Características principales

- 🤖 **Asistente IA (Gemini 3.5 Flash)** — chat conversacional con memoria, streaming de respuestas e instrucciones personalizables.
- 📷 **Escaneo de facturas por OCR** — extrae proveedor, fecha, total, IVA y líneas de producto desde una foto.
- 🎙️ **Comandos por voz** — registra gastos, ingresos o consulta tu balance hablando.
- 💬 **Chat con streaming** — el asistente responde en tiempo real, recordando el contexto de la conversación.
- 📊 **Dashboard** — resumen de ingresos, gastos, balance y productos más comprados.
- 🧾 **Gestión completa** de facturas, productos e ingresos (CRUD).
- 🌍 **Configuración fiscal por país** — IVA, IRPF, formato de NIF.
- ☁️ **Backup en Google Drive** y exportación de datos.
- 🌓 **Tema claro/oscuro/sistema**.

---

## 🛠️ Stack técnico

| Capa | Tecnología |
|---|---|
| **UI** | Jetpack Compose (BOM `2024.12.01`), Material 3, Navigation Compose |
| **DI** | Hilt `2.52` |
| **Persistencia** | Room `2.6.1` (SQLite), DataStore Preferences `1.1.1` |
| **IA** | Google Generative AI SDK `0.7.0` (Gemini 3.5 Flash) |
| **Cámara** | CameraX `1.4.1` |
| **Voz** | MediaPipe `0.10.22` |
| **Imágenes** | Coil 3 `3.0.4` |
| **Backup** | Google Drive vía Play Services Auth `21.3.0` |
| **Monetización** | Google Play Billing `7.0.0` |
| **Asincronía** | Kotlin Coroutines `1.9.0` |

---

## 🏗️ Arquitectura

Arquitectura **modular multi-módulo** en 3 capas (clean-ish), con inyección de dependencias vía Hilt y patrón repositorio:

```
┌──────────────────────────────────────────────────────────┐
│  :app   → MainActivity, navegación (NavHost + BottomBar), │
│           DI modules, tema                                │
├──────────────────────────────────────────────────────────┤
│  :feature:*  (11 módulos)                                 │
│   dashboard · invoices · products · incomes · ocr · voice │
│   ai · settings · backup · fiscal · chatbot               │
├──────────────────────────────────────────────────────────┤
│  :core:domain   → modelos de dominio + interfaces repo    │
│  :core:data     → Room (entidades, DAOs), impl repos      │
│  :core:common   → utilidades (fechas)                     │
└──────────────────────────────────────────────────────────┘
```

### Modelos de dominio principales
- **`Invoice`** — factura/gasto (fecha, proveedor, tipo, total, IVA, IRPF, NIF emisor/receptor, imagen, OCR).
- **`Income`** — ingreso (concepto, monto, devengado/neto, fuente, IVA/IRPF).
- **`Product`** — línea de factura (descripción, cantidad, precio unitario, subtotal).
- **`Category`** — categoría de gasto (nombre, icono, color).
- **`CountryFiscalConfig`** — configuración fiscal por país (IVA, IRPF, formato NIF).

---

## 🤖 Inteligencia Artificial (Gemini)

FinAI usa **Gemini 3.5 Flash** a través de la **API gratuita de Google AI Studio**.

### Configuración
1. Obtén una API key gratuita en **[Google AI Studio](https://aistudio.google.com/apikey)**.
2. En la app: **Ajustes → IA → Configurar API Key**.
3. La key se valida automáticamente al guardarla.

### Capacidades del asistente
- **Chat conversacional** con memoria (10 turnos) y respuestas en streaming.
- **Instrucciones personalizables** — define el tono, la moneda por defecto, el comportamiento, etc.
- **Registro natural** — *"gasté 20€ en café"*, *"cobré 1500€ de nómina"*.
- **Consultas** — *"¿cuánto gasté este mes?"*, *"mi balance de la semana"*.
- **OCR de facturas** — extrae datos desde una foto del recibo.

> ⚠️ Tus mensajes se envían a la API de Gemini para procesarse. No se almacenan fuera de tu dispositivo.

---

## 📱 Pantallas y navegación

**Navegación principal (Bottom Bar):**
| Tab | Descripción |
|---|---|
| 📊 **Dashboard** | Resumen financiero con KPIs |
| 🧾 **Facturas** | Listado y edición de facturas/gastos |
| 📦 **Productos** | Catálogo de productos |
| 💵 **Ingresos** | Listado y edición de ingresos |

**Pantallas secundarias:** Chat · Escaneo (cámara) · Voz · Ajustes · Backup · Config fiscal · Editar factura/ingreso.

---

## ⚙️ Requisitos

- **Android 8.0 (API 26)+**
- compileSdk / targetSdk: **API 35**
- Java 17
- Una API key de Google AI Studio (gratuita)

### Permisos
- `CAMERA` — escaneo de facturas
- `RECORD_AUDIO` — comandos por voz
- `INTERNET` — llamadas a la API de Gemini
- `READ/WRITE_EXTERNAL_STORAGE` — almacenamiento (hasta API 28)

---

## 🚀 Compilación

El proyecto usa el **Gradle Wrapper** (Java 17 requerido):

```bash
# Debug APK
./gradlew :app:assembleDebug

# Instalar en dispositivo conectado
./gradlew :app:installDebug
# o con adb:
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release APK
./gradlew :app:assembleRelease
```

> Si usas Android Studio, abre el proyecto y pulsa **Run ▶**. Asegúrate de tener el **Android SDK** configurado (`local.properties` con `sdk.dir`).

---

## 📂 Estructura del proyecto

```
FinAI/
├── app/                      # Aplicación (MainActivity, nav, DI, theme)
├── core/
│   ├── domain/               # Modelos + interfaces de repositorio
│   ├── data/                 # Room: entidades, DAOs, impl de repositorios
│   └── common/               # Utilidades compartidas
├── feature/
│   ├── dashboard/            # Pantalla principal
│   ├── invoices/             # Facturas/gastos (lista + edición)
│   ├── products/             # Productos
│   ├── incomes/              # Ingresos (lista + edición)
│   ├── ocr/                  # Escaneo de facturas con cámara
│   ├── voice/                # Comandos por voz
│   ├── ai/                   # Servicio IA (Gemini)
│   ├── chatbot/              # Chat con el asistente
│   ├── settings/             # Ajustes + licencia
│   ├── backup/               # Backup/exportación (Drive)
│   └── fiscal/               # Configuración fiscal por país
├── gradle/
│   └── libs.versions.toml    # Catálogo de versiones
└── settings.gradle.kts       # Definición de módulos
```

---

## 🔐 Privacidad

- Los datos financieros se almacenan **localmente** en tu dispositivo (Room/SQLite).
- Los **mensajes al asistente** se envían a la API de Gemini para su procesamiento.
- El backup opcional en Google Drive requiere tu cuenta y tu acción explícita.
- La API key de Gemini se guarda en el dispositivo (DataStore) y no se comparte.

---

## 📜 Licencia

Proyecto privado. Todos los derechos reservados.

---

**FinAI** · v1.0.0 · Hecho con ❤️ en Kotlin + Jetpack Compose
