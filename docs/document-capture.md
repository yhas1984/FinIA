# Document capture, validation and duplicate protection

## Product contract

FinAI remains a personal/self-employed finance ledger: expenses and income are stored locally, financial queries use ledger records, and authorized Premium synchronization follows a successful local commit. Gemini reads documents using the user's own key. A successful model response is not proof that a financial value is correct.

The capture path now keeps unreviewed documents outside the ledger. Valid reads can be saved automatically. Missing, invalid, inconsistent or ambiguous values produce a private durable draft, retaining its original photo and extracted evidence. Validation runs silently, with no review screen or automatically opened dialog. A compact chat notice links to pending documents; users can retry the reading, view the original photo or discard the submission. The draft never affects balances, chat totals, exports or cloud synchronization.

## Reading and speed

- As of 1.8.2, `FAST` (`low` thinking) is the default in response to the user's request for a simpler, faster automated flow. An explicit detailed reread of existing extracted data uses `THOROUGH` (`medium`). Both run the same validation and duplicate checks. There is no claim of measured real-world speed or accuracy improvement yet.
- Both profiles use the same schema, high media resolution, original EXIF orientation, 2048 maximum dimension and JPEG quality 88. Originals are retained untouched. No automatic cropping.
- OCR tries Gemini 3.6 Flash, 3.8 Flash and 3.5 Flash-Lite once each, at most three HTTP attempts and 135 seconds total. Each attempt retains the previous 45-second ceiling. OCR skips recently unavailable models for the same key, respects Google retry delays and does not add an artificial delay when switching models. Chat retains its existing retry policy.
- The shared client applies the same model chain to every AI entry point: initial reading, detailed rereading, commands, ledger queries, key validation and streaming. Text operations may retry 3.6 once before 3.8 and 3.5 Flash-Lite. Streaming only switches before the first visible fragment; an interrupted visible answer is preserved for an explicit retry. Invalid keys, bad requests, safety blocks and known account-wide quota exhaustion stop the chain. The final Lite response must pass the same document parser and financial checks as either Flash response.
- Malformed JSON can consume a fallback attempt. Financial inconsistency in parseable JSON returns a draft without an automatic reread. An explicit retry consumes a new operation and replaces the draft reading. Existing evidence remains stored if that request fails; the notice reports the failure.
- Logs contain profile, preparation/network/parsing durations, payload size and existing per-model failure category/duration. They do not contain keys, document text or financial amounts.

The debug-only comparison tool sends each selected photo through both profiles, alternating order. It does not insert documents or queue remote work. Users compare both results against the photograph, including critical fields and line coverage. Its share action exports timing and assessment booleans only. Comparison readings remain in memory for the current session.

The comparison indicator still requires at least 25 representative documents, at least 20% lower median latency, no lower critical-field accuracy or line coverage, and no higher review rate under the same validator. It is an evidence report, not a feature switch. The 1.8.2 default change follows the user's subsequent usability request; the comparison threshold has not been demonstrated with real documents. Mock/unit results cannot establish accuracy or latency with Google.

Reading stays in the chat with a cancellable progress indicator. Successful coherent documents save automatically. Failed or uncertain reads show an inline pending notice retaining the photo, without editable document fields or a blocking dialog. Dismissing the notice does not discard the draft. The pending list, photograph and duplicate details open only on user action. A valid draft recovered after an interrupted save is validated and checked for duplicates again before saving, without another model request. Printed totals may prove a missing product tax-basis label only when exactly one interpretation reconciles; explicit labels and amounts are never overwritten.

## Evidence and arithmetic

`ScannedDocument` keeps nullable observed data. `DocumentEvidence` retains original extraction, corrected/derived fields, invalid numeric text and source SHA-256. Absent dates, currencies, quantities and payroll net amounts are never supplied from defaults. Unknown quantities do not become one. Invalid product rows remain in the draft. A line's explicit zero tax is preserved; a missing line rate inherits only from a demonstrated uniform rate.

Validation uses decimal arithmetic and two minor currency units of tolerance. It compares line quantities/prices/subtotals and comparable tax bases, tax amounts, withholding and payable totals. Tax-included and tax-excluded product values have an explicit persisted flag. The original product prices remain available while spending queries use amounts including tax. Suggestions such as category are labeled separately from document facts.

Validation catches detectable problems, not every plausible-but-incorrect reading. Users can still inspect and edit saved records.

## International tax breakdown (1.8.5)

A document or product can contain multiple named tax components with optional rate, base and amount, explicit treatment (taxable, zero-rated, exempt, outside scope or unknown), and charge/withholding effect. No country catalog or current statutory rate replaces a printed value. Recognized ISO currencies are accepted without claiming that an exchange-rate source covers every currency.

A single VAT percentage is optional. Multiple components are never reduced to an average or zero. The printed tax summary is extracted in the existing OCR request, not a second model call. Line taxes remain unknown when the summary cannot identify their allocation. Complete summaries reconcile decimal component amounts with the document total; component bases can overlap and are never blindly summed. Withholding also present in the old header is subtracted once. Invalid or incomplete summaries remain pending, outside balances.

Saved expenses and issued income expose a collapsed tax section. Manual editing preserves component identity and permits localized rate/base/amount corrections; an empty value remains unknown. The new manual expense form still starts at 0%. Net-priced products with unknown tax have an unknown gross total; product queries warn that their totals are partial rather than silently counting a zero. CSV and Sheets append original-currency tax JSON without duplicating document totals; PDF and chat context retain the components.

Room 13 → 14 makes legacy percentage/line-tax fields nullable and adds tax JSON to invoices, incomes and products. The migration copies historical columns without recalculation, preserves product foreign keys, document/Drive identities and autoincrement sequences. Backup formats 1–3 remain readable; new payloads preserve nulls and tax components and declare database version 14 so older installations reject them rather than silently losing taxes.

## Duplicate policy

The original file is hashed while copied into private draft storage. An identical saved file is reported before calling Gemini; a matching pending draft is resumed. Historical local originals are hashed in the background without changing financial values or Drive references.

Invoice identity compares full number (including series, punctuation and leading zeros), issuer tax ID/country where available, issue year, date, currency and amount. Missing tax identity or O/0 confusion produces only a possible match. Same numbers from different issuers or series do not match. Cross-income/expense matches require review.

Payroll identity uses printed employer, worker ID, period, reference and payment kind. No pay period is inferred from a payment date. Equal amounts in different periods are not duplicates. Without an identifying reference, a matching payroll is only a possible duplicate. Changed payroll values require review; extra/arrears payments are not silently merged.

Strong matches cannot be saved again. Possible matches remain pending and require explicit confirmation that the document is distinct from the user-opened match details. Confirmation is bound to candidate UUID/version and cleared by subsequent draft edits. No overwrite, merge or deletion occurs. Repository-level guards also protect manual creation and edits; edits exclude their own UUID.

The final check, document/products insertion and draft removal share a Room transaction. Remote operations start only after success. Cancellation or a lost response does not cause a second insert when the draft is resumed.

## Persistence and compatibility

- Room 12 → 13 adds evidence JSON, indexed identity/hash columns, product tax-basis flag and `document_drafts`. The schema export is tracked.
- Existing IDs, UUIDs, tax percentages, product relationships and Drive references are unchanged. Historical duplicates are preserved.
- Backups keep saved evidence, identities, hashes and product tax basis using optional fields with defaults. Older backup readers/fixtures remain compatible; drafts are excluded.
- Restore rebuilds indexed identity fields through normal entity mapping and preserves pending drafts. Draft photos live in a separate private directory outside the image directory swapped during restore.
- Issued-invoice conversion to income retains fiscal identity and complete extracted lines in evidence. Income CSV rows include invoice number, tax base and tax amount where known.

## Manual phone acceptance

Install the isolated `.dev` APK alongside the production app. Configure a test Gemini key in that installation, never in chat. Open Chat → Compare reading speed and select a varied set: receipts, invoices with zero/mixed tax, issued invoices, payrolls, rotated/blurry/long images, and missing fields. Check both readings against actual photos. Share only the metrics report if desired.

Separately verify: same photo twice, another photo of the same invoice, a manual record followed by its scan, repeated payslip, same payroll amount in a different month, dismiss/reopen a pending notice, preserved missing values/photo, no automatic review screen, cancellation followed by retry, and totals after saving exactly once. Drive/Gemini production behavior is not certified by local simulated tests.
