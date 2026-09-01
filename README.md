# WTS SMARTSCORE

Smart Scanning • Score Capture • Script Digitization

WTS SmartScore is a local-first Android productivity tool for high-volume document capture, Smart Broadsheet score digitization, examination-script grouping, optional AI handoff and post-scan review.

## Current product boundary

- Quick/Normal Scan uses Google ML Kit for one-to-few pages and manual review.
- Batch Scan uses Google's multipage document scanner to capture a pile of pages in one review session, then opens post-scan processing.
- Smart Broadsheets use flexible `sheet_id`/`page_id` template manifests, optional QR identity and layout families including secondary single-subject and primary multi-subject sheets.
- Scripts require no QR. Cover/continuation grouping and labelled OCR identity suggestions are reviewed after capture.
- Exports include PDF, searchable PDF, corrected JPEG packages, TXT, OCR JSON, DOCX and structured CSV/XLSX where appropriate.
- The Result Portal remains authoritative and is not modified by this repository. There are no production score writes or privileged database credentials.

The former CameraX/OpenCV Continuous Scan remains available only as an internal experimental/debug path. Normal user mode uses Google ML Kit for reliable Quick/Batch acquisition.

## Modules

- `android/smartscore`: Android application.
- `scanner-core`: reusable scanning state/geometry contracts.
- `shared/template-schema`: flexible Smart Broadsheet template schema.
- `shared/score-batch-schema`: reviewed score export schema.
- `shared/script-package-schema`: scanned script package schema.
- `docs`: architecture, test report and known limitations.
- `test-data`: deterministic manifests and example payloads.
- `test-artifacts`: invented paper PDFs and export/grouping fixtures.
