# Known limitations

- The ARM64 APK still requires the user's physical phone test; camera boundary acceptance and capture cadence are not claimed as physically proven by CI.
- Local manifests are invented test data. Result Portal roster/template connectivity is intentionally not enabled in this phase.
- The score recognizer is a dedicated digit-cell pipeline behind `DigitRecognizer`, using the bundled provenance-documented TensorFlow Lite MNIST digit model and ML Kit only as a per-digit fallback. MNIST is a constrained digit baseline, not a measured teacher-handwriting accuracy claim. Blank is based on measured post-border-suppression ink, while ink with failed recognition remains doubtful; corrected page images remain authoritative.
- The labelled fixture currently measures geometry, blank/state separation, digit assembly and maximum validation. It does not report Android handwriting accuracy because no verified real-handwriting model/corpus is bundled yet.
- Google ML Kit Document Scanner is the acquisition foundation. The former CameraX/OpenCV continuous session remains only as an internal experimental/legacy path and is not the recommended workflow.
- Debug builds preserve continuous-page detection evidence and broadsheet template/ROI evidence under the local session directory. The deterministic fixture validates geometry, ink/state separation, digit assembly and maximum checks, but does not measure Android handwriting accuracy.
- QR is optional identity evidence. A QR miss preserves the page; heading OCR and known layout evidence are attempted, and generic pages remain in Records for later review.
- Generic score sheets now run OpenCV grid detection and persist table geometry, cell crops, digit predictions and an editable row/column table. Sheets without a printed grid still need a separate layout detector; arbitrary teacher handwriting remains a reviewable exception when the digit model is uncertain.
- WorkManager queue persistence is implemented locally. A recognition task that fails is marked failed and leaves its source page available for a later Process Again attempt.
- DOCX and searchable PDF exports are best-effort OCR/layout reconstructions. Searchable PDF text is a derived layer; it does not replace source images.
- CSV/XLSX exports are reliable for structured score rows, not arbitrary handwritten document reconstruction.
- Crop/rotate/reorder/delete edits are local post-capture operations and should be followed by Process before relying on derived OCR/grouping artifacts.
- No official Result Portal write API, production roster sync, service-role key or autonomous score submission exists.
