# Direct document capture — FinAI 1.8.9 (26)

The scan path no longer requires accounting reconciliation or complete product, tax or payroll breakdowns before saving. The extracted invoice total or payroll net is authoritative. Optional details and the original extraction remain available, including mixed taxes and incomplete payroll rows. The chat no longer presents a pending-document review notice.

Duplicate protection and the transaction that saves the movement and its chat confirmation remain active. Save retries reuse the existing extraction. Existing retained captures can be resubmitted without repeating OCR when their amount was already read. An unreadable amount or a provider/storage failure still produces an ordinary retry error; no amount is manufactured. When a scan has no readable date, its capture date is used for the ledger while the missing original date remains missing in its evidence. Missing currency uses the configured currency for new OCR responses.

## Validation

- 402 unit tests passed, with no failures, errors or skips.
- 10 database/capture tests passed on the USB OnePlus NE2213 with the final debug APK. They cover incomplete and inconsistent receipts, payroll, duplicate protection, atomic chat confirmation, rollback and unfamiliar currency formatting.
- 5 chat UI tests passed on the FinAI_Test emulator in Spanish and English. They assert that the pending-document notice is absent, failures retain retry actions in the chat, and saved confirmations survive recreation.
- Three synthetic documents were read through the Gemini key already configured on the phone and saved in an isolated in-memory database. All retained the printed amount and rejected a repeated copy: a simple receipt (30,560 ms), a three-rate VAT invoice (3,850 ms), and a payslip without detailed rows (3,492 ms). These timings are individual observations, not guaranteed latency. No test movements were sent to Sheets or Drive.
- Release lint: 0 errors, 21 warnings. Existing Kotlin/R8 metadata warnings remain; the release build completed successfully.
- The emulator host process crashed after the five UI tests completed, before screenshots could be copied out. The phone remained locked, so manual visual inspection on the physical device was not completed. The UI test assertions passed independently of that later emulator failure.

## Release

- Artifact: `app/build/outputs/bundle/release/FinAI-1.8.9-v26.aab`
- Size: 9,271,069 bytes.
- SHA-256: `19a9db2aa6fd78f2c7a644dddd9547d8ed5fc46d9fdf01050b3230aedb696b48`.
- Verified production manifest: `com.gastos.ingresos`, version 26 / 1.8.9, not debuggable.
- JAR signature and bundle structure verified; signing certificate matches 1.8.8.
- FinAI Dev 1.8.9-dev was installed on the phone. Production installation was not replaced.
- No Play upload, commit or PR was performed for this correction.

Evidence is recorded in the adjacent build, device, live-Gemini, UI, lint, signature and manifest files. The narrated demo videos remain recordings of 1.8.7; this fix does not update their footage or narration.
