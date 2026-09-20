# FinAI 1.8.5 (22): international taxes and capture validation

Validation date: 2026-09-20. Branch: `codex/finai-1.8.5-document-taxes`, based on `5993fa7` (`main`). This candidate also contains the previously local capture, deduplication, fallback, full-backup and photo-button fixes.

## Automated evidence

- `testDebugUnitTest`: 311 tests, zero failures/errors/skips. Per-suite counts are in `unit-tests.json`.
- Android API 26, isolated `FinAI_Test` emulator: 24 app instrumentation tests and 5 AI instrumentation tests. Results are in `app-instrumented.txt` and `ai-instrumented.txt`.
- USB-connected OnePlus NE2213, Android 16 / API 36: the same 24 app and 5 AI tests passed against FinAI Dev 1.8.5. Synthetic records were cleaned up; the production installation was not updated. Evidence and screenshots are in `phone/`.
- The explicitly enabled `LiveGeminiSmokeTest` passed with the existing device-only Gemini key and two synthetic invoices. Spanish 21%/10%/4% extraction took 6,962 ms; Canadian GST/PST on a shared base took 7,495 ms. Both preserved the expected total, net subtotal, currency, quantities, product subtotals, tax bases/rates/amounts and absence of a global rate. No ledger writes. See `phone/live-gemini-taxes.json`.
- Debug and release lint: zero errors, 23 warnings in each variant. See `lint-summary.json`.
- Debug APK, instrumentation APK and minified signed release bundle build successfully with Java 17 and the existing private release configuration. Premium backend verification remains required; no release guard was disabled.

Regression coverage includes:

- Spanish invoices with 21%, 10% and 4% components; mixed 21% and explicit 0% on Android; absent global/line rates stay nullable rather than becoming zero or a blended rate.
- Canadian GST/PST and Indian CGST/SGST sharing the same base; compound printed bases; historical rates; exempt, zero-rated, outside-scope and unknown treatments; withholding counted once.
- Component arithmetic, unknown labels, missing or inconsistent amounts, incomplete summaries and malformed JSON. Uncertain documents remain outside the ledger.
- Localized manual editing and the existing 0% initial manual form; encrypted COMPLETE and DATA_ONLY tax roundtrips; CSV/Sheets component preservation without duplicate totals.
- Room 13 to 14 preserves every historical column, product relationship, image/Drive reference and autoincrement identity. New nullable rates and tax components survive Room editing and dataset restoration. Earlier migration and outbox tests also pass.
- Capture deduplication, atomic save/draft removal, concurrent insert protection, silent pending/error notices in Spanish and English, and no automatic review dialog.
- Complete backup streaming and restoration with 320 MiB of synthetic photographs, insufficient storage, encryption/password/truncation protections, and data-only copies without photo-size checks.

## Visual and release evidence

`tax-editor-es.png` and `tax-editor-en.png` show expense/income editors with synthetic records on the emulator, before the final label wording change. `phone/tax-editor-es.png` and `phone/tax-editor-en.png` show the final UI on the physical phone. The detailed tax section starts collapsed and expands on request; a multi-component document has no single VAT percentage input. Amounts, rate, base, treatment and effect remain editable.

The initial emulator boot left Android launcher/System UI ANR dialogs, unrelated to the FinAI process, over the first screenshots. Those captures were discarded. The final screenshots and instrumentation run were repeated after removing the system dialogs.

`release-verification.json` records the final AAB/APK hashes, package, version, release configuration checks and signing certificate fingerprint. Bundletool validates the AAB, JAR signature verification succeeds, and the universal APK is generated from that same bundle with the existing FinAI certificate. `release-smoke.txt` records installation and launch of the release APK in the emulator without clearing application data.

## Scope of the evidence

Automated responses and documents are synthetic except for the explicitly enabled Gemini smoke test, which sent only synthetic images to the real service. Its first run returned a pending Spanish document because the invoice direction was ambiguous, while the Canadian fixture passed. The initial test incorrectly combined readiness and value fidelity in one indicator. The revised report separates them and the fixtures explicitly identify a received purchase invoice; both then passed without changing or weakening production validation. Initial results are retained in `phone/live-gemini-initial*.{json,txt}`.

No live Drive, real purchase or Play Console upload was performed. Two synthetic images do not establish general OCR accuracy, real-world median latency or fiscal compliance in every country. PDF component text is implemented but has not received a separate rendered multi-page export review.

The build still reports Gradle deprecations and R8/Kotlin metadata compatibility warnings from the existing toolchain. They do not fail the build; the minified candidate is covered by the release installation/launch smoke check, not a complete release-mode functional suite. JAR verification reports the normal self-signed Android signing certificate/no timestamp notices.

## Research informing the data contract

- [Spanish invoice content and separate tax rates/bases, Royal Decree 1619/2012](https://www.boe.es/buscar/act.php?id=BOE-A-2012-14696#a6).
- [Canada Revenue Agency: GST/HST registrants](https://www.canada.ca/en/revenue-agency/services/forms-publications/publications/rc4022/general-information-gst-hst-registrants.html).
- [HMRC: VAT exemption and partial exemption](https://www.gov.uk/guidance/vat-exemption-and-partial-exemption).

The implementation preserves printed evidence; it does not infer current statutory rates from these references.

## Optional live check

`LiveGeminiSmokeTest` is skipped unless instrumentation is invoked with `-e liveGemini true`. The normal 24-test device run excludes that class. It reads the existing FinAI Dev key inside Android secure storage, uses in-memory data and temporary synthetic images, and exports only result categories/timings/check outcomes. It never exports the key or saves financial records.
