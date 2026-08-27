# WTS SmartScore architecture

## Boundary

SmartScore is a local-first scanning and review client. The Result Portal remains the authoritative source for students, classes, subjects, sessions, terms, assessment configuration and official scores. This phase does not modify `wts-result-system`, synchronize production rosters, write scores, or embed privileged database credentials.

## Two scanner paths

Quick/Normal Scan keeps the Google ML Kit Document Scanner flow for one-to-few pages: boundary guidance, capture, crop, enhancement, multipage review, PDF and JPEG output.

Continuous Scan is a separate capture-first session. CameraX `ImageAnalysis` uses a 1280×720 target for low-latency boundary detection, while `ImageCapture` uses `CAPTURE_MODE_MAXIMIZE_QUALITY` at JPEG quality 95 for the stored master page. The analyzer rotates frames into display coordinates, `PreviewView` uses `FIT_CENTER`, and the overlay applies the same letterbox transform. The accepted page is normalized from the high-resolution still; preview frames are never sent to OCR.

`AutoCaptureController` accepts an ordinary page using a four-corner boundary, modest coverage/size, focus, glare and stability checks. After capture it waits for page exit before re-arming, preventing duplicate captures. The continuous screen remains open until the operator taps Finish. Debug builds can long-press the heading to show polygon, frame orientation, coverage, page size, aspect ratio, blur, glare, stability and the exact blocking reason.

## Smart Broadsheet page model

`TemplateManifest` is the source of truth for a logical `sheet_id`. It contains one or more `SheetPageTemplate` records, each with `page_id`, sequence, optional expected count, layout family/version, row range, subject group and per-row ROI/digit-box geometry. `expected_page_ids` may be null for operator-finished dynamic documents; completion is never inferred from a fixed Side 1/Side 2 rule.

Supported local test layout families include `SECONDARY_SINGLE_SUBJECT` and `PRIMARY_MULTI_SUBJECT`; the latter places four subject groups and separate CA/Exam ROIs on one physical page. QR is compact identity metadata in the top-centre header zone. QR failure retains the high-resolution page and falls back to OCR/template evidence.

Broadsheet processing is: corrected master page → template lookup → high-resolution ROI extraction → border/blank checks and recognition → `CONFIRMED`, `REVIEW_REQUIRED`, `INVALID`, `BLANK`, `UNREADABLE` or `MANUALLY_CORRECTED`. Student names come from the template rows rather than score-cell OCR.

## Continuous session and scripts

The session manifest is the transaction. After Finish it exposes page thumbnails and post-capture delete, rotate, crop, reorder, inspect, add/rescan and process actions. Broadsheet pages are grouped by `sheet_id`/`page_id`; scripts are grouped by strong cover evidence and attach continuation pages to the current script. A random name in an answer is not sufficient to start a new script. Scripts have no QR dependency.

## Export boundary

Every processed page keeps the corrected image as the authoritative source. Derived exports include image packages, regular and searchable PDFs, page-linked TXT/OCR JSON, best-effort DOCX, and structured CSV/XLSX for score rows. AI-ready script ZIPs contain metadata, ordered page JPEGs, OCR text and OCR JSON, with question-paper and marking-scheme references reserved for later review metadata.

## Future Result Portal integration

The future read-only connector should supply generated manifests and authoritative roster context through a secured allow-listed API. It must not expose a Supabase service-role key to SmartScore. Official score submission remains out of scope until the local capture, OCR and review pipeline has been extensively validated.
