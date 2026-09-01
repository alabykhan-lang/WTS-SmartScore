# Implementation status

## Implemented in WTS SmartScore

- Quick/Normal Scan, Smart Broadsheet and Script capture use the Google ML Kit Document Scanner path. Broadsheet capture is an open-ended overview session; Script capture accepts a multipage pile before grouping.
- The rejected CameraX/OpenCV Continuous Scan remains internal/experimental and is not linked from the primary home workflow.
- Smart Broadsheet uses flexible `sheet_id`/`page_id` manifests with dynamic or known page counts; no fixed duplex completion rule remains.
- Local invented templates cover secondary single-subject, one-page, large three-page and primary four-subject layouts. QR payloads carry page identity and sit in the generated test header's top-centre zone; QR failure preserves the page.
- Compact broadsheet grid review shows score-level values (`03`, `52`, `10`, `?3`, `5?`) and opens per-digit evidence only on demand.
- Script pages are QR-free. Printed labels anchor field extraction; `NEW_SCRIPT_START`, `CONTINUATION_PAGE` and `UNCERTAIN_BOUNDARY` drive grouping after capture, with no mandatory identity popup.
- Post-session review supports page thumbnails, inspect, rotate, crop, reorder, delete, add/rescan and reprocess.
- Exports include regular/searchable PDF, corrected JPEG packages, TXT, OCR JSON, DOCX and score CSV/XLSX where structured extraction is available. Script AI ZIPs preserve ordered pages, metadata and OCR.
- Room migration preserves the existing local database while adding page/layout/manifest metadata, workflow states, per-digit evidence and a durable WorkManager task queue.
- Result Portal code and production data are untouched; no score-write path or privileged credential is present.

## Verification status

Desktop artifact QA passed for the generated PDF, DOCX, OCR JSON/TXT and XLSX examples. The Android source is built by GitHub Actions on push to `main`. Physical camera, handwritten OCR and full-device interaction remain pending installation of the resulting ARM64 APK; CI success alone is not a physical acceptance claim.
