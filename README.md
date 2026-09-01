# WTS SMARTSCORE

Smart Scanning • Score Capture • Script Digitization

WTS SmartScore is a local-first Android productivity tool for high-volume document capture, Smart Broadsheet score digitization, examination-script grouping, optional AI handoff and post-scan review.

## Current product boundary

- Broadsheet and Script capture both use Google ML Kit Document Scanner. Capture and processing are separate stages.
- A broadsheet scan is saved page-by-page into Room immediately. The operator can add any number of pages, tap Done, and leave while local processing continues.
- Script capture accepts a multipage pile in one scanner session. Covers and continuation pages are grouped after capture; only uncertain groups appear as exceptions.
- Smart Broadsheets use flexible template manifests, optional QR evidence and layout-aware heading evidence. Unbranded pages are retained as generic score sheets instead of being rejected.
- Exports include PDF, searchable PDF, corrected JPEG packages, TXT, OCR JSON, DOCX and structured CSV/XLSX where appropriate.
- The Result Portal remains authoritative and is not modified by this repository. There are no production score writes or privileged database credentials.

## Local processing model

Room stores the captured page and its workflow state before any OCR or score recognition is attempted. A durable WorkManager queue then runs document identification, template registration, score reading, script identity extraction, grouping and transcription. Queue tasks remain in Room so an app restart, phone lock, network failure or recognition failure cannot remove the source scan.

Score cells are handled as known digit boxes rather than arbitrary page OCR. When a known page is unavailable, OpenCV detects the printed table and creates generic row/column score cells. The primary recognizer is the bundled, provenance-documented TensorFlow Lite MNIST digit classifier; ML Kit is retained only as a per-digit fallback. Score crops, predictions and corrections are stored under the local digit dataset for future evaluation.

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
