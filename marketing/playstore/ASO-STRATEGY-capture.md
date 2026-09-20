# FinAI ASO strategy — document capture release

This is a strategy document, not a published listing change. Current local marketing files are drafts; the live Play listing, conversion rates and search demand have not been audited in this implementation. Existing text, screenshots and store publication are intentionally deferred until product/device acceptance.

## Audience and promise

Primary audience: individuals and self-employed people tracking personal expenses, income and supporting documents. Lead with financial control and less repetitive entry. Explain the connection between document capture, categorized records, balances and questions answered from actual stored movements.

The new benefit is silent validation, automatic saving of coherent readings and recoverable pending documents. Do not advertise perfect recognition, no hallucinations, guaranteed automatic tax accuracy, instant scanning or a measured speed improvement before the phone comparison supports it.

## Feature truth table

| Capability | Accurate positioning | Disclosure / evidence needed |
|---|---|---|
| Manual expenses and income | Keep and edit personal financial records | Usable without Gemini |
| Document recognition | Read receipts, invoices and payslips, saving coherent data automatically and retaining doubtful reads as pending | Requires the user's Google Gemini key; external service availability/quota apply |
| Duplicate warnings | Identify repeated files and matching document identities | Possible matches require confirmation; not every duplicate can be identified |
| Financial chat | Ask about recorded expenses/income and products | Partial currency conversion and excluded records must remain visible |
| Drive / Sheets | Synchronize supported records and images | Premium, Google account and permissions required |
| Data backup | Encrypted backup of data and image references | Explain recovery password and separate photo synchronization |

## Search hypotheses to validate later

These are proposed intents, not claims of keyword volume or ranking:

| Locale | Core intent | Supporting intents |
|---|---|---|
| es-ES | control de gastos e ingresos | escanear tickets, facturas, nóminas, finanzas personales |
| es-419 | control de gastos e ingresos | registrar recibos, facturas, finanzas personales |
| en-US | expense and income tracker | receipt scanner, invoice tracking, payslip records, personal finance |

Do not position FinAI as compliant accounting software, a tax filing service, a banking aggregator, a financial adviser or a budget-planning feature that is not implemented. Localize terminology, not merely individual words. Validate any business-oriented positioning against actual self-employed workflows.

## Future listing and screenshot sequence

1. A clear expense/income overview with synthetic but coherent values.
2. A real document-reading flow and the resulting stored record.
3. A duplicate warning that opens the existing record.
4. A compact pending-document notice with retry and access to the retained original photo, without a review form.
5. A question answered from actual sample ledger records.
6. Premium Drive/Sheets and encrypted data backup with accurate labels.

Capture actual app screens on the accepted build. Avoid implying that every document requires manual confirmation; equally avoid promising hands-free error-free entry. Show a concise explanation of the user's Gemini key before an AI feature can be mistaken for an unlimited included service.

## Existing copy to revisit in the next ASO task

- Remove unsupported “instant” or “no typing ever” promises.
- Correct “data exclusively on device”: Gemini receives selected document/chat data, while authorized Drive/Sheets features transmit additional records.
- Check budgeting/adviser/bidirectional-sync claims against actual functionality.
- Avoid exact model-version names and fixed free-quota promises in evergreen marketing.
- Update stale release/checklist metadata only when preparing the next store submission.
- Keep store text, privacy policy/Data safety disclosures, onboarding and paid-feature descriptions consistent.

Google Play metadata limits to check during copy production: title 30 characters, short description 80, full description 4000. See [Google's listing guidance](https://support.google.com/googleplay/android-developer/answer/13393723?hl=en) and [metadata policy](https://support.google.com/googleplay/android-developer/answer/9898842?hl=en).

## Measurement and activation

First pass the functional/device checks. Then baseline store visitors → installs by locale, early abandonment during API-key setup, first successful record, reading duration/review rate and duplicate-warning outcomes with appropriate privacy choices. No new analytics collection is introduced in this change.

Test one listing hypothesis at a time using Play Console experiments when available. Keep install conversion separate from activation and successful financial use. Decide later whether a measured speed improvement is strong enough for store copy; the current implementation does not establish it.

## 1.8.5 messaging boundary

Describe support for invoices with several tax rates and named taxes, silent consistency checks, retained original photos and duplicate detection. Do not promise worldwide tax compliance, automatic tax advice, perfect OCR accuracy or measured speed gains. The country catalogs are unchanged; financial data printed on the document takes precedence.

Suggested Spanish feature copy: "Registra facturas con varios impuestos y conserva su desglose."
Suggested English feature copy: "Capture invoices with multiple taxes and keep their breakdown."
