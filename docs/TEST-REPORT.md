# SmartScore test report

## Static and artifact verification

- `git diff --check` is clean after source edits.
- All committed JSON manifests and OCR fixtures parse successfully.
- Generated broadsheet PDFs contain the requested invented test populations: one-page secondary (8 students), large secondary (36 students across 3 pages), and primary four-subject (12 students on one page).
- The generated QR is in the top-centre header, carries page identity metadata only, and is outside score cells.
- PDF page counts were checked with `pdfinfo`: 1, 3, 1 and 2 pages for the secondary single, secondary large, primary multi-subject and script examples respectively.
- DOCX and XLSX packages pass ZIP integrity checks; DOCX rendering was visually checked over both pages and XLSX rendering was visually checked for both sheets.
- `test-artifacts/script-grouping-test.json` covers cover → continuation → continuation → next cover, with an uncertain boundary flagged for review.
- `python3 test-artifacts/generate_broadsheet_ocr_diagnostics.py` passes 23 deterministic known-value/state cases and produces a visually aligned SMB-TEST-0001 template overlay plus labelled source/preprocessed ROI samples. This is a structural test, not an ML Kit accuracy benchmark.

## Runtime architecture checks

- Quick/Normal Scan remains Google ML Kit Document Scanner.
- Continuous Scan uses low-latency analysis plus high-resolution still capture, then stays open until Finish.
- Continuous document candidates are ranked with paper-shape, edge-contrast, containment and internal-structure evidence; debug captures include candidate diagnostics and a complete selected quadrilateral.
- The continuous action bar applies runtime system-bar/display-cutout insets rather than a device-specific bottom padding.
- The recovered physical V2 sheet is selected with its frozen legacy geometry and explicit coordinate origin; populated ink is not converted to BLANK when digit recognition fails.
- The session manifest and review screen support dynamic page counts, post-capture page operations and template-driven broadsheet grouping.
- Scripts have no QR dependency and preserve original/corrected pages alongside derived OCR.
- Result Portal production code is outside this repository and was not modified.

## Physical acceptance still pending

This environment cannot install an APK on the test phone. The new ARM64 build must still be physically tested for:

1. Page 1 → beep → READY FOR NEXT → Page 2 → beep without a dialog.
2. Duplicate suppression while a page remains under the camera, followed by re-arm after it leaves.
3. Ordinary paper at realistic angle, distance, lighting and mild curvature.
4. One-, two- and three-page manifest completion, plus the primary four-subject ROI alignment.
5. Script cover/continuation grouping and uncertain-boundary review.
6. Handwritten score recognition, especially the smaller primary cells.

CI success confirms source compilation and packaging; it does not prove these physical behaviors.
