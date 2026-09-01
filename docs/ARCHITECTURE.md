# WTS SmartScore architecture

## Boundary

SmartScore is a local-first scanning and review client. The Result Portal remains the authoritative source for students, classes, subjects, sessions, terms, assessment configuration and official scores. This phase does not modify `wts-result-system`, synchronize production rosters, write scores, or embed privileged database credentials.

## Google-backed scanner paths

Quick/Normal Scan keeps the Google ML Kit Document Scanner flow: boundary guidance, capture, perspective correction and JPEG/PDF output. Broadsheet capture is an open-ended local session; each returned corrected page is persisted before any recognition. Script capture uses one 50-page Google multipage session and defers grouping until the full result returns.

The rejected CameraX/OpenCV Continuous Scan implementation is retained only as an internal experimental/debug path. It is not linked from the primary home workflow and is not a V1 acquisition guarantee.


## Smart Broadsheet page model

`TemplateManifest` is the source of truth for a logical `sheet_id`. It contains one or more `SheetPageTemplate` records, each with `page_id`, sequence, optional expected count, layout family/version, row range, subject group and per-row ROI/digit-box geometry. `expected_page_ids` may be null for operator-finished dynamic documents; completion is never inferred from a fixed Side 1/Side 2 rule.

Supported local test layout families include `SECONDARY_SINGLE_SUBJECT` and `PRIMARY_MULTI_SUBJECT`; the latter places four subject groups and separate CA/Exam ROIs on one physical page. QR is compact identity metadata in the top-centre header zone. QR failure retains the high-resolution page and falls back to OCR/template evidence.

Broadsheet processing is: corrected master page → optional identity evidence → known-layout shape match when available or OpenCV table/grid detection → high-resolution score-cell extraction → border/blank checks → per-digit recognition → score assembly and range validation. A generic row/column table is saved even when identity is unknown; identity and score recognition are separate states. Student names come from template rows only when a template is known, otherwise the review table uses row numbers.

Room exposes document states `SCANNED`, `PROCESSING`, `READY`, `REVIEW_REQUIRED`, `UNIDENTIFIED` and `FAILED`. `UNIDENTIFIED` is retained only as legacy identity evidence; it no longer blocks score extraction. Implementation details such as manifest IDs are kept out of the normal Records UI.

## Post-scan session and scripts

The corrected image is the transaction. Broadsheet Overview shows saved thumbnails and lets the operator add pages or finish; it never requires OCR success. Script pages are grouped by `NEW_SCRIPT_START`, `CONTINUATION_PAGE` or `UNCERTAIN_BOUNDARY` using printed cover labels and field-based identity evidence. Scripts have no QR dependency and do not show a mandatory identity dialog after every capture.

## Export boundary

Every processed page keeps the corrected image as the authoritative source. Derived exports include image packages, regular and searchable PDFs, page-linked TXT/OCR JSON, best-effort DOCX, and structured CSV/XLSX for score rows. AI-ready script ZIPs contain metadata, ordered page JPEGs, OCR text and OCR JSON, with question-paper and marking-scheme references reserved for later review metadata.

## Future Result Portal integration

The future read-only connector should supply generated manifests and authoritative roster context through a secured allow-listed API. It must not expose a Supabase service-role key to SmartScore. Official score submission remains out of scope until the local capture, OCR and review pipeline has been extensively validated.
