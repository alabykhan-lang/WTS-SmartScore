# Known limitations

- The ARM64 APK still requires the user's physical phone test; camera boundary acceptance and capture cadence are not claimed as physically proven by CI.
- Local manifests are invented test data. Result Portal roster/template connectivity is intentionally not enabled in this phase.
- The current score recognizer is a best-effort ML Kit numeric OCR path behind a digit-recognizer interface. Blank is now based on measured post-border-suppression ink, while ink with failed recognition remains doubtful; corrected page images remain authoritative.
- Debug builds preserve continuous-page detection evidence and broadsheet template/ROI evidence under the local session directory. The deterministic fixture validates geometry, ink/state separation, digit assembly and maximum checks, but does not measure Android handwriting accuracy.
- QR is optional identity evidence. A QR miss preserves the page, but unknown page identity still requires post-session review.
- DOCX and searchable PDF exports are best-effort OCR/layout reconstructions. Searchable PDF text is a derived layer; it does not replace source images.
- CSV/XLSX exports are reliable for structured score rows, not arbitrary handwritten document reconstruction.
- Crop/rotate/reorder/delete edits are local post-capture operations and should be followed by Process before relying on derived OCR/grouping artifacts.
- No official Result Portal write API, production roster sync, service-role key or autonomous score submission exists.
