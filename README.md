# FinAI (FinIA)

[![Kotlin](https://img.shields.io/badge/Kotlin-1.9+-7F52FF?logo=kotlin)](https://kotlinlang.org/)
[![Android](https://img.shields.io/badge/Android-API%2026+-3DDC84?logo=android)](https://developer.android.com/)

**Languages / Idiomas:** [English](README.md) · [Español](README.es.md)

FinAI is an Android app for personal and small-business finance: **expenses**, **income** (including payroll-aware fields), **product line items**, **dashboard**, **backup/export**, and an **AI assistant** that can run on **Google Gemini (cloud)** or **Gemma (on-device, LiteRT)**. It includes **camera OCR** (ML Kit) for tickets and invoices, with heuristics to reconcile totals and line items when the model misreads Spanish retail receipts.

## Features

- **Invoices & expenses:** Store provider, date, totals, VAT base and amount, category, NIF/CIF when available.
- **Income / payroll:** Net, accrued (devengado), deductions, concept and type; OCR hints for Spanish payslips.
- **Products:** Per-line description, quantity, unit price, subtotal, store (`comercio`), purchase date; tied to expense documents.
- **OCR scan:** Local text recognition plus AI-assisted structured extraction; improved handling for supermarket-style tickets (e.g. €/kg lines, footer totals).
- **Chatbot & voice:** Natural-language commands and queries over your data (engine configurable in Settings).
- **Backup:** Structured CSV export (expenses, products, incomes).
- **Modular architecture:** `:app`, `:core:*`, `:feature:*` (dashboard, invoices, products, incomes, OCR, AI, settings, backup, fiscal, chatbot, voice).

## Requirements

- **Android Studio** Koala (2024.1.1) or newer recommended (AGP 8.x, compile SDK 35).
- **JDK 17**
- **Gemini API** (optional): set your key in **Settings → AI** for cloud inference.
- **Gemma local** (optional): on-device model and storage as described in the app settings.

## Build

```bash
./gradlew :app:assembleDebug
```

Install on a connected device or emulator:

```bash
./gradlew :app:installDebug
```

Release builds use minification; configure signing in `app/build.gradle.kts` as needed.

## Project layout

| Module | Role |
|--------|------|
| `:app` | Application shell, navigation, DI |
| `:core:domain` | Domain models |
| `:core:data` | Room, repositories, mappers |
| `:feature:ai` | AIService, parsers, Gemini/Gemma integration |
| `:feature:ocr` | Invoice scanning flow |
| `:feature:chatbot` | Chat UI and ViewModel |
| Other `:feature:*` | Dashboard, invoices, products, incomes, settings, backup, fiscal, voice |

## Permissions

Camera (scan), internet (Gemini API), microphone (voice). Storage permissions follow legacy Android rules where applicable.

## Contributing

Issues and pull requests are welcome on [FinIA](https://github.com/yhas1984/FinIA).

## License

Specify your license in a `LICENSE` file if you open-source this repository publicly.

---

*Repository:* [https://github.com/yhas1984/FinIA](https://github.com/yhas1984/FinIA)
