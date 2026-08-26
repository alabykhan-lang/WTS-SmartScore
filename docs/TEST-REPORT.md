# V1 Test Report

## Static/source verification completed
- Separate repository structure created; no Result Portal files modified.
- Android manifest requests camera/vibration/internet only; no storage/service-role secrets.
- Room persistence covers broadsheets, sides, scans, readings, corrections, scripts, script pages, AI marks and exports.
- AutoCapture state machine requires stable frames and page exit before re-arming.
- General scanner architecture has no QR dependency.
- Smart Broadsheet identity supports QR as optional metadata; fallback contract remains template/sheet selection.
- Reviewed values are separated from raw OCR values.
- AI marks are proposal-only by contract.
- Result Portal client is read-only; no write method is present.

## Runtime acceptance status
This execution environment has Java but no Android SDK, Build Tools, Gradle distribution, emulator, or Android device. Network access from the build container is disabled. Therefore an APK cannot be compiled or runtime-installed here, and camera/OpenCV/Room runtime acceptance cannot truthfully be marked PASS.

Prior Gate 2B material likewise explicitly required a real phone before declaring completion.

## Required device validation before production use
1. Install compiled debug/release APK on an Android 8+ ARM64 phone.
2. Verify camera permission and live preview.
3. Validate automatic capture/re-arm on ordinary A4 documents under tilt/glare/low-light.
4. Validate perspective-correct PDF output.
5. Validate duplex side order and fallback identity when QR is hidden.
6. Populate handwritten test cells and measure OCR confirmed/review/invalid rates.
7. Capture 5+ page scripts, reorder/delete/rescan and export.
8. Configure an AI provider server-side and verify structured proposal + mandatory review.

## Deterministic desktop scanner fixture
- Generated an ordinary document with no QR/fiducials, perspective-warped it into a simulated camera scene, detected the largest four-corner contour, and rectified it back to A4-like geometry. Output: `test-artifacts/ordinary-document-corrected.png`.
- Exported the corrected page to `ordinary-document-scan.pdf`.
- Generated a filled Smart Broadsheet fixture plus a review-table artifact showing confirmed, doubtful and blank states.
- Generated a 3-page script package PDF and ordered script-package JSON.
- These are deterministic desktop fixtures, not a substitute for the pending physical Android camera test.
