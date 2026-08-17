# 💰 FinAI

**Personal finance assistant for Android** with built-in artificial intelligence (Gemini), invoice scanning from the chat, voice commands and conversational chat. Built for expense, income and invoicing management, defaulting to Spain (EUR, 21% VAT, IRPF, NIF) with multi-country extraction (MX, AR, CO, CL, PE, US…).

> 🌐 **Languages** — The app is available in **English** and **Spanish** and follows the system language. | Léelo en [**Español**](README.md).

---

## ✨ Key features

- 🤖 **AI Assistant (Gemini)** — conversational chat with local memory; streaming only on Premium and customizable instructions.
- 📷 **Invoice and payroll scanning** — from the chat itself (camera or gallery): extracts vendor, date, invoice number, total, tax base, VAT, IRPF, NIF and product lines, telling paychecks apart from invoices.
- 🎙️ **Voice commands** — record expenses, income or query your balance by talking (Android SpeechRecognizer, es-ES).
- 💬 **Streaming chat** — the assistant responds in real time, remembering the conversation context.
- 🛒 **Smart queries** — understands periods ("this week", "in July"), specific products ("water", "coffee") and resolves ambiguity by showing the exact matches before deciding.
- 📊 **Dashboard** — income, expenses, balance, last-7-days activity, category breakdown and a configurable monthly financial calendar.
- 🏷️ **Categories and subcategories** — expenses and income with default or custom labels, suggested automatically by the AI from text, voice and OCR. They filter lists, feed the Dashboard and travel to Sheets.
- 🧾 **Full management** of invoices/expenses, products and income (CRUD).
- ☁️ **Google Sheets: multi-currency export + sync** — Sheet with Received Invoices, unified Income, Products and Summary, with amounts converted to your local currency.
- 🔄 **Two-way sync** — creates, edits and deletes in the app are reflected automatically (upsert/delete by record ID). "Force sync" re-exports the whole Sheet.
- ☁️ **Google Drive** — automatic upload of the photo after scanning an invoice if Premium and Google are connected, with retry and camera temp cleanup.
- 🗑️ **Remote photos** — deleting a local invoice does not automatically remove its Drive photo; the remote copy is kept and can be deleted directly from Google Drive.
- 💎 **Premium** (one-time purchase via Google Play Billing, with separate debug flag) — extends assistant memory from 3 to 10 turns and unlocks Sheets/Drive.
- 🔐 **Recoverable backup** — encrypted `.finai` file for everyone; automatic versioned copy on Google Drive for Premium.
- 📄 **CSV and PDF export** for reports and external use.
- 🌓 **Light/dark/system theme**.

---

## 🛠️ Tech stack

| Layer | Technology |
|---|---|
| **UI** | Jetpack Compose (BOM `2026.06.01`), Material 3, Navigation Compose `2.9.8` |
| **DI** | Hilt `2.52` |
| **Persistence** | Room `2.7.2` (SQLite), DataStore Preferences `1.2.1`, EncryptedSharedPreferences (API key) |
| **AI** | Google Generative AI SDK `0.9.0` (Gemini) |
| **Voice** | Android SpeechRecognizer (es-ES) |
| **Camera** | `ActivityResultContracts.TakePicture` (system camera app) + FileProvider |
| **Sheets** | Google Sheets API v4 + Google Sign-In (Play Services Auth `21.6.0`), limited `drive.file` scope |
| **Backup** | WorkManager `2.11.2`, Drive `appDataFolder` (`drive.appdata`), AES-256-GCM + PBKDF2 |
| **Monetization** | Google Play Billing `9.1.0` |
| **Concurrency** | Kotlin Coroutines `1.10.2` |

---

## 🏗️ Architecture

**Modular multi-module** architecture in 3 layers (clean-ish), with dependency injection via Hilt and the repository pattern:

```
┌──────────────────────────────────────────────────────────┐
│  :app   → MainActivity, navigation (NavHost + BottomBar), │
│           DI modules, theme, Room migrations             │
├──────────────────────────────────────────────────────────┤
│  :feature:*  (8 modules)                                 │
│   dashboard · invoices · incomes · chatbot · voice · ai   │
│   · settings · backup                                     │
├──────────────────────────────────────────────────────────┤
│  :core:domain   → domain models + repository interfaces   │
│  :core:data     → Room (entities, DAOs), repo impls       │
│  :core:common   → shared utilities (dates, SafeLog)       │
└──────────────────────────────────────────────────────────┘
```

### Main domain models
- **`Invoice`** — invoice/expense (date, vendor, type, invoice number, total, tax base, VAT, IRPF, issuer/recipient NIF, category, subcategory, image, OCR).
- **`Income`** — income (concept, amount, gross/net, source, category, subcategory, VAT/IRPF).
- **`Product`** — invoice line (description, quantity, unit price, subtotal).
- **`CountryFiscalConfig`** — per-country fiscal config (VAT, IRPF, NIF format).

---

## 🤖 Artificial Intelligence (Gemini)

FinAI uses **Gemini** through the **free Google AI Studio API**.

### Setup
1. Get a free API key at **[Google AI Studio](https://aistudio.google.com/apikey)**.
2. In the app: **Settings → AI → Configure API Key**.
3. The key is validated automatically on save and applied instantly (no restart). It is stored encrypted with EncryptedSharedPreferences.

### Assistant capabilities
- **Conversational chat** with memory (3 turns free, 10 with Premium) and streaming responses.
- **Customizable instructions** — define the tone, default currency, behavior, etc.
- **Natural entry** — *"I spent €20 on coffee"*, *"I got paid €1,500"*.
- **Queries** — *"How much did I spend this month?"*, *"my week's balance"*, *"How much did I spend on Groceries / Supermarket?"*. The AI classifies the query and the calculation happens locally, with filters by merchant, product, category and subcategory; the financial result is excluded from the model's later context.
- **Products** — list products by merchant, group variants like «bread» and ask for exact matches when the description is specific.
- **Document OCR** — photo of an invoice, receipt or paycheck from the chat. Uses a structured JSON response, resized up to 2048 px, JPEG quality 88 and high multimedia resolution. Unreadable optional fiscal fields are kept as `null` instead of being invented or zeroed.

> ⚠️ Your messages are sent to the Gemini API for processing. The chat history is stored locally and included in the app backup.

---

## ☁️ Google Sheets: export and sync

From **Backup** you can link your Google account:

- **Permission requested** — the only OAuth scope FinAI requests is `https://www.googleapis.com/auth/drive.file`, which lets the Sheets API create and maintain exclusively the spreadsheet generated by the app. We do not use the sensitive `spreadsheets` scope.
- **Export to Sheets** — creates (or rewrites) a spreadsheet with 4 tabs: *Received Invoices*, *Income*, *Products* and *Summary*. Paychecks and other income share a tab with optional payroll fields.
- **Multi-currency** — each row keeps the original amount and currency; the **amount converted to your local currency** is added using the current rate. The *Summary* tab aggregates those converted amounts to show the real balance.
- **Background sync** — from then on, every create, **edit or delete** in the app is reflected in the Sheet:
   - Each tab has a stable **ID** column (record ID / InvoiceID in products).
   - Create/edit → *upsert* by ID (updates the row if it exists, adds it if not).
   - Expense delete → removes its row and its products' rows.
   - The *Summary* refreshes after each operation.
- **Force sync** — re-exports the whole database to the linked Sheet (automatic migration to schema v7). Use it the first time or if the Sheet was created with an old app version.

---

## 🔐 Backup and recovery

FinAI uses a portable `.finai` format so a backup survives uninstall and can be recovered on another device:

1. **Free manual backup** — set a recovery password and export the file with the Android picker to Drive, Downloads, USB or any location you choose.
2. **Content** — keeps invoices, products, income, fiscal categories, chat history, managed images and non-sensitive settings.
3. **Excluded data** — does not copy the Gemini API key, OAuth credentials, Premium state or exchange-rate cache.
4. **Encryption** — content is encrypted with AES-256-GCM; the data key is protected with PBKDF2 and the password you choose.
5. **Restore** — select the file, review the summary and confirm. The operation validates and decrypts the whole backup before replacing current data in a single Room transaction.
6. **Premium Drive** — creates an automatic copy roughly every 24 hours when there is network and enough battery, keeping the five most recent versions in Google Drive's private `appDataFolder` (scope `drive.appdata`).
7. **New install** — activate Premium, connect the same Google account, pick a backup and enter the recovery password.

> ⚠️ FinAI does not store the password on Drive and cannot recover it. Without it, the backup cannot be decrypted after uninstalling the app.

---

## 📱 Screens and navigation

**Main navigation (Bottom Bar):**
| Tab | Description |
|---|---|
| 📊 **Dashboard** | Financial summary with KPIs and 7-day chart |
| 🧾 **Invoices** | List, filter and edit invoices/expenses |
| 💵 **Income** | List and edit income |

**Secondary screens:** Chat (with document scanning and voice built in) · Settings · Premium · Backup · Edit invoice/income.

---

## ⚙️ Requirements

- **Android 8.0 (API 26)+**
- compileSdk / targetSdk: **API 36**
- Java 17
- A Google AI Studio API key (free)

### Permissions
- `CAMERA` — photograph invoices from the chat
- `RECORD_AUDIO` — voice commands
- `INTERNET` — Gemini API and Sheets/Drive API calls

---

## 🚀 Building

The project uses the **Gradle Wrapper** (Java 17 required):

```bash
# Debug APK
./gradlew :app:assembleDebug

# Install on a connected device
./gradlew :app:installDebug
# or with adb:
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release APK
./gradlew :app:assembleRelease

# Android App Bundle (.aab) — for Play Store / distribution
./gradlew :app:bundleDebug      # → app/build/outputs/bundle/debug/app-debug.aab
./gradlew :app:bundleRelease    # → app/build/outputs/bundle/release/app-release.aab

# Lint
./gradlew :app:lintDebug

# Unit tests
./gradlew testDebugUnitTest
```

### Release with purchase verification (billing)

The release build compiles in *fail-closed* mode: it requires the verification
backend environment variables (`FINAI_BILLING_BACKEND_URL`,
`FINAI_BILLING_ENTITLEMENT_ISSUER`, `FINAI_BILLING_ENTITLEMENT_KEY_ID`,
`FINAI_BILLING_ENTITLEMENT_PUBLIC_KEY_PEM`). If they are missing, `bundleRelease`
fails with a clear error to avoid shipping an unverified AAB.

The recommended flow uses the prepared script (reads `scripts/.env`, template in
`scripts/.env.example`):

```bash
cp scripts/.env.example scripts/.env   # fill in the real values (URL, issuer, key id, public key)
./scripts/build_release.sh             # validates the env vars and builds the AAB
```

> The public key is derived from `finai-entitlement-private-key` (Secret Manager,
> project `finai-501616`) with `openssl pkey -pubout`. It is never committed.

### Recent functional validation

The development build has been validated with:

```bash
# Unit tests, targeted lint and debug APK
./gradlew testDebugUnitTest \
  :feature:ai:lintDebug \
  :feature:invoices:lintDebug \
  :feature:chatbot:lintDebug \
  :app:assembleDebug

# Instrumented tests on an Android 16 / API 36 device
./gradlew :app:connectedDebugAndroidTest
```

The current instrumented suite passes **10/10 tests**. Natural-language expense and income entry, product queries and category/subcategory filters were also verified on-device, plus OCR of a real invoice from the gallery. Room uses schema v11 (with migrations from v9/v10) and the remote sync outbox is at v3.

Targeted lint shows no issues of its own in `feature:ai`; `feature:chatbot` and `feature:invoices` only show warnings from `google-http-client` (`TrustAllX509TrustManager`).

> The `signingConfigs.release` reads credentials from environment variables (`FINAI_KEYSTORE_FILE`, `FINAI_KEYSTORE_PASSWORD`, `FINAI_KEY_ALIAS`, `FINAI_KEY_PASSWORD`) or `gradle.properties`; they are never hardcoded in the repo.
>
> If you use Android Studio, open the project and press **Run ▶**. Make sure the **Android SDK** is configured (`local.properties` with `sdk.dir`).

---

## 💎 Premium and purchase verification

The Premium state is validated against a dedicated backend deployed on **Cloud Run**
(`backend/billing/`), in addition to Google Play Billing:

- The client requests a purchase (`finai_premium`) and sends the *purchase token*
  to the backend (`POST /v1/entitlements:verify`), which validates it against the
  Google Play Android Publisher API, acknowledges the purchase and returns an
  RSA-signed JWT (issuer `finai-billing`, key ID `2026-01`).
- The app verifies that JWT with the embedded public key before enabling
  Premium (persistent revocations via `/v1/entitlements:reconcile` with Cloud
  Scheduler and OIDC).
- **Play Integrity** is implemented but disabled
  (`FINAI_BILLING_PLAY_INTEGRITY_ENABLED=false`) until the end-to-end link with
  Play Console is validated.
- Debug builds keep the local `debugSetPremium` flag and do not require the
  backend; release builds fail if the backend configuration is missing.

---

## 📂 Project structure

```
FinAI/
├── app/                      # Application (MainActivity, nav, DI, theme, migrations)
├── core/
│   ├── domain/               # Domain models + repository interfaces
│   ├── data/                 # Room: entities, DAOs, repo impls
│   └── common/               # Shared utilities (dates, SafeLog)
├── feature/
│   ├── dashboard/            # Main screen
│   ├── invoices/             # Invoices/expenses (list + edit)
│   ├── incomes/              # Income (list + edit)
│   ├── chatbot/              # Assistant chat (text, voice and scanning)
│   ├── voice/                # Speech recognition
│   ├── ai/                   # AI service (Gemini): prompts, parsing, OCR
│   ├── settings/             # Settings, API key (encrypted), Premium/Billing
│   └── backup/               # Encrypted manual/Drive backup, CSV/PDF and Sheets
├── backend/
│   └── billing/              # Cloud Run purchase verification + runbook
├── marketing/
│   └── playstore/            # ASO copy (es-ES/en-US/es-419), checklist, storyboard
├── scripts/
│   ├── build_release.sh      # Builds the AAB with billing config (.env)
│   └── .env.example          # Backend variables template (do not commit .env)
├── gradle/
│   └── libs.versions.toml    # Version catalog
└── settings.gradle.kts       # Module definitions
```

---

## 🔐 Privacy

- Financial data is stored **locally** on your device (Room/SQLite).
- **Assistant messages** are sent to the Gemini API for processing.
- Images chosen for scanning are also sent to Gemini; with Premium and Google connected, the saved photo is uploaded to Drive.
- Google Sheets export/sync uses the limited `drive.file` scope: it can only access the spreadsheet created by FinAI and never your other Drive sheets.
- Google permissions can be revoked at any time from [myaccount.google.com/permissions](https://myaccount.google.com/permissions). FinAI keeps working with local storage.
- The Gemini API key is stored **encrypted** on the device (EncryptedSharedPreferences) and is never shared.
- The manual `.finai` backup is encrypted; Premium can keep up to five encrypted copies in Drive's private space and delete them from the app.
- Logs with financial data are only emitted in debug builds (`SafeLog`).

---

## 📜 License

Private project. All rights reserved.

---

**FinAI** · v1.6.0 (build 15) · Made with ❤️ in Kotlin + Jetpack Compose
