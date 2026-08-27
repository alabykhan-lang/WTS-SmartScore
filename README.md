# WTS SMARTSCORE

Smart Scanning • Score Capture • Script Digitization

WTS SmartScore is a local-first Android productivity tool for high-volume document capture, Smart Broadsheet score digitization, examination-script grouping, optional AI handoff and post-scan review.

## Current product boundary

- Quick/Normal Scan uses Google ML Kit for one-to-few pages and manual review.
- Continuous Scan captures a pile of pages without per-page dialogs, then opens a post-finish review session.
- Smart Broadsheets use flexible `sheet_id`/`page_id` template manifests, optional QR identity and layout families including secondary single-subject and primary multi-subject sheets.
- Scripts require no QR. Cover/continuation grouping and labelled OCR identity suggestions are reviewed after capture.
- Exports include PDF, searchable PDF, corrected JPEG packages, TXT, OCR JSON, DOCX and structured CSV/XLSX where appropriate.
- The Result Portal remains authoritative and is not modified by this repository. There are no production score writes or privileged database credentials.

In a debug APK, long-press the Continuous Scan heading to show oriented analysis size, detected polygon, coverage, page size, aspect ratio, blur, glare, stability and the exact capture-block reason. Normal user mode keeps these diagnostics hidden.

## Modules

- `android/smartscore`: Android application.
- `scanner-core`: reusable scanning state/geometry contracts.
- `shared/template-schema`: flexible Smart Broadsheet template schema.
- `shared/score-batch-schema`: reviewed score export schema.
- `shared/script-package-schema`: scanned script package schema.
- `docs`: architecture, test report and known limitations.
- `test-data`: deterministic manifests and example payloads.
- `test-artifacts`: invented paper PDFs and export/grouping fixtures.
