# WTS SmartScore

Personal Android operational tool for automatic document scanning, Smart Broadsheet score digitization, full-script capture, AI-assisted marking proposals, offline review, and future read-only Result Portal integration.

## V1 boundaries
- Separate from the Result Portal repository.
- No official Result Portal score writes.
- No direct Supabase/database credentials in the APK.
- No teacher onboarding/multi-tenant deployment.
- AI marks are proposals requiring human review.
- Result Portal connector is read-only and disabled until a secured endpoint is configured.

## Modules
- `android/smartscore`: Android application.
- `scanner-core`: reusable scanning state/geometry contracts.
- `shared/template-schema`: Smart Broadsheet template schema.
- `shared/score-batch-schema`: reviewed score export schema.
- `shared/script-package-schema`: scanned script package schema.
- `docs`: architecture, test plan and known limitations.
- `test-data`: deterministic templates and example payloads.
- `test-artifacts`: sample exports and visual verification material.
