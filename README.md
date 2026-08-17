# 💰 FinAI

**Asistente financiero personal para Android** con inteligencia artificial integrada (Gemini), escaneo de facturas desde el chat, comandos por voz y chat conversacional. Pensado para gestión de gastos, ingresos y facturación, con orientación por defecto a España (EUR, IVA 21%, IRPF, NIF) pero con extracción multi-país (MX, AR, CO, CL, PE, US…).

> 🌐 **Idiomas** — La app está disponible en **español** e **inglés** y sigue el idioma del sistema. | Read this in [**English**](README.en.md).

---

## ✨ Características principales

- 🤖 **Asistente IA (Gemini)** — chat conversacional con memoria local; streaming solo en Premium e instrucciones personalizables.
- 📷 **Escaneo de facturas y nóminas** — desde el propio chat (cámara o galería): extrae proveedor, fecha, número de factura, total, base imponible, IVA, IRPF, NIF y líneas de producto, distinguiendo nóminas de facturas.
- 🎙️ **Comandos por voz** — registra gastos, ingresos o consulta tu balance hablando (SpeechRecognizer de Android, es-ES).
- 💬 **Chat con streaming** — el asistente responde en tiempo real, recordando el contexto de la conversación.
- 🛒 **Consultas inteligentes** — entiende periodos ("esta semana", "en julio"), productos concretos ("agua", "café") y aclara ambigüedades mostrando las coincidencias exactas antes de decidir.
- 📊 **Dashboard** — resumen de ingresos, gastos, balance, actividad de los últimos 7 días, desglose por categoría y calendario financiero mensual configurable.
- 🏷️ **Categorías y subcategorías** — gastos e ingresos con etiquetas predeterminadas o personalizadas, propuestas automáticamente por la IA desde texto, voz y OCR. Filtran listas, alimentan el Dashboard y viajan a Sheets.
- 🧾 **Gestión completa** de facturas/gastos, productos e ingresos (CRUD).
- ☁️ **Google Sheets: exportación + sincronización multimoneda** — Sheet con Facturas Recibidas, Ingresos unificados, Productos y Resumen, con importes convertidos a tu moneda local.
- 🔄 **Sincronización bidireccional** — altas, ediciones y borrados en la app se reflejan automáticamente (upsert/delete por ID de registro). “Forzar sincronización” reexporta el Sheet completo.
- ☁️ **Google Drive** — subida automática de la foto tras escanear una factura si Premium y Google están conectados, con reintento y limpieza del temporal de cámara.
- 🗑️ **Fotos remotas** — borrar una factura local no elimina automáticamente su foto de Drive; la copia remota se conserva y puede eliminarse directamente desde Google Drive.
- 💎 **Premium** (pago único vía Google Play Billing, con flag debug independiente) — amplía la memoria del asistente de 3 a 10 turnos y desbloquea Sheets/Drive.
- 🔐 **Backup recuperable** — archivo `.finai` cifrado para todos; copia automática versionada en Google Drive para Premium.
- 📄 **Exportación CSV y PDF** para informes y uso externo.
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
| **Sheets** | Google Sheets API v4 + Google Sign-In (Play Services Auth `21.6.0`), scope limitado `drive.file` |
| **Backup** | WorkManager `2.11.2`, Drive `appDataFolder` (`drive.appdata`), AES-256-GCM + PBKDF2 |
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
- **`Invoice`** — factura/gasto (fecha, proveedor, tipo, número de factura, total, base imponible, IVA, IRPF, NIF emisor/receptor, categoría, subcategoría, imagen, OCR).
- **`Income`** — ingreso (concepto, monto, devengado/neto, fuente, categoría, subcategoría, IVA/IRPF).
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
- **Consultas** — *"¿cuánto gasté este mes?"*, *"mi balance de la semana"*, *"¿cuánto gasté en Alimentación / Supermercado?"*. La IA clasifica la consulta y el cálculo se hace localmente, con filtros por comercio, producto, categoría y subcategoría; el resultado financiero se excluye del contexto posterior del modelo.
- **Productos** — permite listar productos por comercio, agrupar variantes como «pan» y solicitar coincidencias exactas cuando la descripción es específica.
- **OCR de documentos** — foto de factura, ticket o nómina desde el chat. Usa una respuesta JSON estructurada, redimensionado hasta 2048 px, JPEG de calidad 88 y resolución multimedia alta. Los campos fiscales opcionales ilegibles se conservan como `null` en lugar de inventarse o convertirse en cero.

> ⚠️ Tus mensajes se envían a la API de Gemini para procesarse. El historial del chat se guarda localmente y se incluye en la copia de seguridad de la app.

---

## ☁️ Google Sheets: exportación y sincronización

Desde **Backup** puedes vincular tu cuenta de Google:

- **Permiso solicitado** — el único scope OAuth de Google solicitado por FinAI es `https://www.googleapis.com/auth/drive.file`, que autoriza a la API de Sheets para crear y mantener exclusivamente el spreadsheet generado por la app. No usamos el scope sensible `spreadsheets`.
- **Exportar a Sheets** — crea (o reescribe) un spreadsheet con 4 hojas: *Facturas Recibidas*, *Ingresos*, *Productos* y *Resumen*. Nóminas y otros ingresos comparten una hoja con campos salariales opcionales.
2. **Multimoneda** — cada fila conserva el importe original y su moneda; se añade además el **importe convertido a tu moneda local** usando la tasa vigente. La hoja *Resumen* agrega esos importes convertidos para mostrar el balance real.
3. **Sincronización en segundo plano** — a partir de ahí, cada alta, **edición o borrado** en la app se refleja en el Sheet:
   - Cada hoja lleva una columna de **ID** estable (ID del registro / InvoiceID en productos).
   - Alta/edición → *upsert* por ID (actualiza la fila si existe, la añade si no).
   - Borrado de gasto → elimina su fila y las de sus productos.
   - El *Resumen* se refresca tras cada operación.
4. **Forzar sincronización** — reexporta toda la base de datos al Sheet vinculado (migración automática al esquema v7). Úsalo la primera vez o si el Sheet se creó con una versión antigua de la app.

---

## 🔐 Backup y recuperación

FinAI usa un formato portable `.finai` para que una copia sobreviva a la desinstalación y pueda recuperarse en otro dispositivo:

1. **Backup manual gratuito** — configura una contraseña de recuperación y exporta el archivo con el selector de Android a Drive, Descargas, USB u otra ubicación elegida por ti.
2. **Contenido** — conserva facturas, productos, ingresos, categorías fiscales, historial del chat, imágenes gestionadas y ajustes no sensibles.
3. **Datos excluidos** — no copia la API key de Gemini, credenciales OAuth, estado Premium ni caché de tipos de cambio.
4. **Cifrado** — el contenido se cifra con AES-256-GCM; la clave de datos se protege mediante PBKDF2 y la contraseña elegida por el usuario.
5. **Restauración** — selecciona el archivo, revisa el resumen y confirma. La operación valida y descifra toda la copia antes de reemplazar los datos actuales en una única transacción de Room.
6. **Premium Drive** — crea una copia automática aproximadamente cada 24 horas cuando hay red y batería suficiente, y conserva las cinco versiones más recientes en el espacio privado `appDataFolder` de Google Drive (scope `drive.appdata`).
7. **Nueva instalación** — activa Premium, conecta la misma cuenta Google, elige una copia e introduce la contraseña de recuperación.

> ⚠️ FinAI no guarda la contraseña en Drive y no puede recuperarla. Sin ella no es posible descifrar el backup después de desinstalar la app.

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
- `INTERNET` — llamadas a la API de Gemini y a la API de Sheets/Drive

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

# Android App Bundle (.aab) — para Play Store / distribución
./gradlew :app:bundleDebug      # → app/build/outputs/bundle/debug/app-debug.aab
./gradlew :app:bundleRelease    # → app/build/outputs/bundle/release/app-release.aab

# Lint
./gradlew :app:lintDebug

# Tests unitarios
./gradlew testDebugUnitTest
```

### Release con verificación de compras (billing)

El release compila en modo *fail-closed*: exige las variables de entorno del
backend de verificación (`FINAI_BILLING_BACKEND_URL`,
`FINAI_BILLING_ENTITLEMENT_ISSUER`, `FINAI_BILLING_ENTITLEMENT_KEY_ID`,
`FINAI_BILLING_ENTITLEMENT_PUBLIC_KEY_PEM`). Si faltan, `bundleRelease` falla
con un error claro para evitar publicar un AAB sin verificación.

El flujo recomendado usa el script preparado (lee `scripts/.env`, plantilla en
`scripts/.env.example`):

```bash
cp scripts/.env.example scripts/.env   # rellena los valores reales (URL, issuer, key id, clave pública)
./scripts/build_release.sh             # valida las env vars y compila el AAB
```

> La clave pública se deriva de `finai-entitlement-private-key` (Secret Manager,
> proyecto `finai-501616`) con `openssl pkey -pubout`. Nunca se commitea.

### Validación funcional reciente

La build de desarrollo se ha validado con:

```bash
# Unit tests, lint dirigido y APK debug
./gradlew testDebugUnitTest \
  :feature:ai:lintDebug \
  :feature:invoices:lintDebug \
  :feature:chatbot:lintDebug \
  :app:assembleDebug

# Tests instrumentados en un dispositivo Android 16 / API 36
./gradlew :app:connectedDebugAndroidTest
```

La suite instrumentada actual pasa **10/10 tests**. También se ha comprobado en el dispositivo el registro de gastos e ingresos por lenguaje natural, consultas de productos y filtros de categoría/subcategoría, además del OCR de una factura real desde la galería. Room utiliza el esquema v11 (con migraciones desde v9/v10) y el outbox de sincronización remota está en v3.

El lint dirigido no presenta incidencias propias en `feature:ai`; `feature:chatbot` y `feature:invoices` solo muestran avisos procedentes de `google-http-client` (`TrustAllX509TrustManager`).

> El `signingConfigs.release` lee credenciales de variables de entorno (`FINAI_KEYSTORE_FILE`, `FINAI_KEYSTORE_PASSWORD`, `FINAI_KEY_ALIAS`, `FINAI_KEY_PASSWORD`) o de `gradle.properties`; nunca se hardcodean en el repo.
>
> Si usas Android Studio, abre el proyecto y pulsa **Run ▶**. Asegúrate de tener el **Android SDK** configurado (`local.properties` con `sdk.dir`).

---

## 💎 Premium y verificación de compras

El estado Premium se valida contra un backend propio desplegado en **Cloud Run**
(`backend/billing/`), además de Google Play Billing:

- El cliente solicita una compra (`finai_premium`) y envía el *purchase token* al
  backend (`POST /v1/entitlements:verify`), que lo valida contra la API de
  Google Play Android Publisher, reconoce la compra y devuelve un JWT firmado
  con RSA (issuer `finai-billing`, key ID `2026-01`).
- La app verifica ese JWT con la clave pública embebida antes de activar
  Premium (revocaciones persistentes vía `/v1/entitlements:reconcile` con Cloud
  Scheduler y OIDC).
- **Play Integrity** está implementado pero desactivado
  (`FINAI_BILLING_PLAY_INTEGRITY_ENABLED=false`) hasta validar el vínculo
  extremo a extremo con Play Console.
- Los builds de debug conservan el flag local `debugSetPremium` y no exigen el
  backend; los de release fallan si la configuración del backend falta.

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
│   └── backup/               # Backup cifrado manual/Drive, CSV/PDF y Sheets
├── backend/
│   └── billing/              # Cloud Run de verificación de compras + runbook
├── marketing/
│   └── playstore/            # Textos ASO (es-ES/en-US/es-419), checklist, storyboard
├── scripts/
│   ├── build_release.sh      # Compila el AAB con la config de billing (.env)
│   └── .env.example          # Plantilla de variables del backend (no commitear .env)
├── gradle/
│   └── libs.versions.toml    # Catálogo de versiones
└── settings.gradle.kts       # Definición de módulos
```

---

## 🔐 Privacidad

- Los datos financieros se almacenan **localmente** en tu dispositivo (Room/SQLite).
- Los **mensajes al asistente** se envían a la API de Gemini para su procesamiento.
- Las imágenes elegidas para escanear también se envían a Gemini; con Premium y Google conectado, la foto guardada se sube a Drive.
- La exportación/sincronización con Google Sheets usa el scope limitado `drive.file`: solo puede acceder al spreadsheet creado por FinAI y nunca a otras hojas de tu Drive.
- Los permisos de Google pueden revocarse en cualquier momento desde [myaccount.google.com/permissions](https://myaccount.google.com/permissions). FinAI sigue funcionando con almacenamiento local.
- La API key de Gemini se guarda **cifrada** en el dispositivo (EncryptedSharedPreferences) y no se comparte.
- El backup manual `.finai` está cifrado; Premium puede guardar hasta cinco copias cifradas en el espacio privado de Drive y eliminarlas desde la app.
- Los logs con datos financieros solo se emiten en builds de debug (`SafeLog`).

---

## 📜 Licencia

Proyecto privado. Todos los derechos reservados.

---

**FinAI** · v1.6.0 (build 15) · Hecho con ❤️ en Kotlin + Jetpack Compose
