# Implementation status

## Implemented in WTS SmartScore

- Quick/Normal Scan and Batch Scan use the Google ML Kit Document Scanner path; Batch Scan returns multiple corrected JPEG pages for post-scan processing.
- The rejected CameraX/OpenCV Continuous Scan remains internal/experimental and is not linked from the primary home workflow.
- Smart Broadsheet uses flexible `sheet_id`/`page_id` manifests with dynamic or known page counts; no fixed duplex completion rule remains.
- Local invented templates cover secondary single-subject, one-page, large three-page and primary four-subject layouts. QR payloads carry page identity and sit in the generated test header's top-centre zone; QR failure preserves the page.
- Compact broadsheet grid review shows normal values quietly and emphasizes only review/invalid/unreadable states with source-crop correction dialogs.
- Script pages are QR-free. Labelled OCR fields and cover structure drive cover/continuation/uncertain classification; continuous sessions group covers and continuations after capture.
- Post-session review supports page thumbnails, inspect, rotate, crop, reorder, delete, add/rescan and reprocess.
- Exports include regular/searchable PDF, corrected JPEG packages, TXT, OCR JSON, DOCX and score CSV/XLSX where structured extraction is available. Script AI ZIPs preserve ordered pages, metadata and OCR.
- Room migration preserves the existing local database while adding page/layout/manifest metadata.
- Result Portal code and production data are untouched; no score-write path or privileged credential is present.

## Verification status

Desktop artifact QA passed for the generated PDF, DOCX, OCR JSON/TXT and XLSX examples. The Android source is built by GitHub Actions on push to `main`. Physical camera, handwritten OCR and full-device interaction remain pending installation of the resulting ARM64 APK; CI success alone is not a physical acceptance claim.
