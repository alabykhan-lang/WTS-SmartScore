# Known limitations

- No APK was produced in this environment because Android SDK/Build Tools/Gradle are absent and external downloads are blocked.
- Runtime camera/OpenCV behavior has not been re-validated on a physical phone in this build session.
- The numeric recognizer contract is present, but V1 source package does not bundle a trained handwritten digit model. A provider/model must be plugged into `NumericRecognizer`; uncertain values must remain review-required.
- Perspective normalization/enhancement contracts and detector are implemented at source level; final production tuning needs device images.
- General scanner OCR/searchable PDF is optional and not implemented in this source package.
- AI provider connectors are abstracted but no secret-bearing provider is bundled.
- Result Portal read-only endpoint `/api/smartscore-read/*` is future-facing and has not been added to production.
- No official Result Portal score writes exist.
