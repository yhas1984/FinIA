# 💰 FinAI

**Asistente financiero personal para Android** con inteligencia artificial integrada (Gemini), escaneo de facturas desde el chat, comandos por voz y chat conversacional. Pensado para gestión de gastos, ingresos y facturación, con orientación por defecto a España (EUR, IVA 21%, IRPF, NIF) pero con extracción multi-país (MX, AR, CO, CL, PE, US…).

> **Nota:** La app está en **español**.

---

## ✨ Características principales

- 🤖 **Asistente IA (Gemini)** — chat conversacional con memoria, streaming de respuestas e instrucciones personalizables.
- 📷 **Escaneo de facturas y nóminas** — desde el propio chat (cámara o galería): extrae proveedor, fecha, total, IVA, IRPF, NIF y líneas de producto, distinguiendo nóminas de facturas.
- 🎙️ **Comandos por voz** — registra gastos, ingresos o consulta tu balance hablando (SpeechRecognizer de Android, es-ES).
- 💬 **Chat con streaming** — el asistente responde en tiempo real, recordando el contexto de la conversación.
- 📊 **Dashboard** — resumen de ingresos, gastos, balance y actividad de los últimos 7 días.
- 🧾 **Gestión completa** de facturas/gastos, productos e ingresos (CRUD).
- ☁️ **Google Sheets: exportación + sincronización** — exporta a un Sheet con estructura AEAT (Facturas Recibidas, Nóminas, Productos, Resumen con fórmulas) y mantiene el Sheet sincronizado en segundo plano: **altas, ediciones y borrados** se reflejan automáticamente (upsert/delete por ID de registro).
- 📄 **Exportación CSV y PDF** y copia local de la base de datos.
- 💎 **Premium** (pago único vía Google Play Billing) — amplía la memoria del asistente de 3 a 10 turnos.
- 🌓 **Tema claro/oscuro/sistema**.

---

## 🛠️ Stack técnico

| Capa | Tecnología |
|---|---|
| **UI** | Jetpack Compose (BOM `2026.06.01`), Material 3, Navigation Compose `2.9.8` |
| **DI** | Hilt `2.52` |
| **Persistencia** | Room `2.7.2` (SQLite), DataStore Preferences `1.2.1`, EncryptedSharedPreferences (API key) |
| **IA** | Google Generative AI SDK `0.9.0` (Gemini) |
| **Voz** | SpeechRecognizer de Android (es-ES) |
| **Cámara** | `ActivityResultContracts.TakePicture` (app de cámara del sistema) + FileProvider |
| **Sheets** | Google Sheets API v4 + Google Sign-In (Play Services Auth `21.6.0`) |
| **Monetización** | Google Play Billing `9.1.0` |
| **Asincronía** | Kotlin Coroutines `1.10.2` |

---

## 🏗️ Arquitectura

Arquitectura **modular multi-módulo** en 3 capas (clean-ish), con inyección de dependencias vía Hilt y patrón repositorio:

```
┌──────────────────────────────────────────────────────────┐
│  :app   → MainActivity, navegación (NavHost + BottomBar), │
│           DI modules, tema, migraciones Room              │
├──────────────────────────────────────────────────────────┤
│  :feature:*  (8 módulos)                                  │
│   dashboard · invoices · incomes · chatbot · voice · ai   │
│   · settings · backup                                     │
├──────────────────────────────────────────────────────────┤
│  :core:domain   → modelos de dominio + interfaces repo    │
│  :core:data     → Room (entidades, DAOs), impl repos      │
│  :core:common   → utilidades (fechas, SafeLog)            │
└──────────────────────────────────────────────────────────┘
```

### Modelos de dominio principales
- **`Invoice`** — factura/gasto (fecha, proveedor, tipo, total, IVA, IRPF, NIF emisor/receptor, imagen, OCR).
- **`Income`** — ingreso (concepto, monto, devengado/neto, fuente, IVA/IRPF).
- **`Product`** — línea de factura (descripción, cantidad, precio unitario, subtotal).
- **`CountryFiscalConfig`** — configuración fiscal por país (IVA, IRPF, formato NIF).

---

## 🤖 Inteligencia Artificial (Gemini)

FinAI usa **Gemini** a través de la **API gratuita de Google AI Studio**.

### Configuración
1. Obtén una API key gratuita en **[Google AI Studio](https://aistudio.google.com/apikey)**.
2. En la app: **Ajustes → IA → Configurar API Key**.
3. La key se valida automáticamente al guardarla y se aplica al instante (sin reiniciar). Se almacena cifrada con EncryptedSharedPreferences.

### Capacidades del asistente
- **Chat conversacional** con memoria (3 turnos gratis, 10 con Premium) y respuestas en streaming.
- **Instrucciones personalizables** — define el tono, la moneda por defecto, el comportamiento, etc.
- **Registro natural** — *"gasté 20€ en café"*, *"cobré 1500€ de nómina"*.
- **Consultas** — *"¿cuánto gasté este mes?"*, *"mi balance de la semana"*. La IA solo clasifica la consulta; los cálculos se hacen localmente (tus cifras nunca se envían al modelo).
- **OCR de documentos** — foto de factura, ticket o nómina desde el chat. Detección multi-país de moneda, IVA e identificación fiscal.

> ⚠️ Tus mensajes se envían a la API de Gemini para procesarse. No se almacenan fuera de tu dispositivo.

---

## ☁️ Google Sheets: exportación y sincronización

Desde **Backup** puedes vincular tu cuenta de Google:

1. **Exportar a Sheets** — crea (o reescribe) un spreadsheet con 4 hojas: *Facturas Recibidas*, *Nóminas*, *Productos* y *Resumen* (con fórmulas SUM que se recalculan solas).
2. **Sincronización en segundo plano** — a partir de ahí, cada alta, **edición o borrado** en la app se refleja en el Sheet:
   - Cada hoja lleva una columna de **ID** al final (ID del registro / InvoiceID en productos).
   - Alta/edición → *upsert* por ID (actualiza la fila si existe, la añade si no).
   - Borrado de gasto → elimina su fila y las de sus productos.
3. **Sincronizar todo** — reexporta toda la base de datos. Úsalo una vez si tu Sheet se creó con una versión antigua de la app (filas sin ID) o para reparar divergencias.

---

## 📱 Pantallas y navegación

**Navegación principal (Bottom Bar):**
| Tab | Descripción |
|---|---|
| 📊 **Dashboard** | Resumen financiero con KPIs y gráfico de 7 días |
| 🧾 **Facturas** | Listado, filtro y edición de facturas/gastos |
| 💵 **Ingresos** | Listado y edición de ingresos |

**Pantallas secundarias:** Chat (con escaneo de documentos y voz integrados) · Ajustes · Premium · Backup · Editar factura/ingreso.

---

## ⚙️ Requisitos

- **Android 8.0 (API 26)+**
- compileSdk / targetSdk: **API 36**
- Java 17
- Una API key de Google AI Studio (gratuita)

### Permisos
- `CAMERA` — fotografiar facturas desde el chat
- `RECORD_AUDIO` — comandos por voz
- `INTERNET` — llamadas a la API de Gemini y Google Sheets

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

# Tests unitarios
./gradlew testDebugUnitTest
```

> Si usas Android Studio, abre el proyecto y pulsa **Run ▶**. Asegúrate de tener el **Android SDK** configurado (`local.properties` con `sdk.dir`).

---

## 📂 Estructura del proyecto

```
FinAI/
├── app/                      # Aplicación (MainActivity, nav, DI, theme, migraciones)
├── core/
│   ├── domain/               # Modelos + interfaces de repositorio + use cases
│   ├── data/                 # Room: entidades, DAOs, impl de repositorios
│   └── common/               # Utilidades compartidas (fechas, SafeLog)
├── feature/
│   ├── dashboard/            # Pantalla principal
│   ├── invoices/             # Facturas/gastos (lista + edición)
│   ├── incomes/              # Ingresos (lista + edición)
│   ├── chatbot/              # Chat con el asistente (texto, voz y escaneo)
│   ├── voice/                # Reconocimiento de voz
│   ├── ai/                   # Servicio IA (Gemini): prompts, parseo, OCR
│   ├── settings/             # Ajustes, API key (cifrada), Premium/Billing
│   └── backup/               # Backup local, export CSV/PDF, Sheets (export + sync)
├── gradle/
│   └── libs.versions.toml    # Catálogo de versiones
└── settings.gradle.kts       # Definición de módulos
```

---

## 🔐 Privacidad

- Los datos financieros se almacenan **localmente** en tu dispositivo (Room/SQLite).
- Los **mensajes al asistente** se envían a la API de Gemini para su procesamiento.
- La exportación/sincronización con Google Sheets es **opcional** y requiere tu cuenta y tu acción explícita.
- La API key de Gemini se guarda **cifrada** en el dispositivo (EncryptedSharedPreferences) y no se comparte.
- Los logs con datos financieros solo se emiten en builds de debug (`SafeLog`).

---

## 📜 Licencia

Proyecto privado. Todos los derechos reservados.

---

**FinAI** · v1.0.3 · Hecho con ❤️ en Kotlin + Jetpack Compose
