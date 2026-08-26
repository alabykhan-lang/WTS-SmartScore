# SmartScore Personal App V1

## Boundary
SmartScore is a private operational client. The Result Portal remains authoritative. V1 has no official-result mutation API.

## Reusable scanner
CameraX preview + ImageAnalysis feeds OpenCV page detection. `AutoCaptureController` arms after a configurable run of stable high-quality frames. Capture is internal, followed by beep/vibration. The controller refuses another capture until the prior page leaves frame, which prevents duplicate-page bursts.

## General mode
Page quadrilateral -> high-resolution capture -> perspective normalization -> enhancement -> ordered local pages -> PDF/image export. QR is not used.

## Smart Broadsheet mode
The same page detector is followed by optional QR/fiducial identity, template selection fallback, canonical normalization, exact ROI cropping and numeric recognition. Student names come from template rows and are never OCR targets.

## Script mode
The same scanner stores ordered pages under `script_id`. Pages can be deleted, rescanned, reordered and exported. AI is optional.

## AI marker
`AiMarkerProvider` abstracts remote multimodal providers. Secrets are never embedded in the APK. Responses remain proposals with mandatory review.

## Future Result Portal integration
`ResultPortalReadOnlyClient` contains only allow-listed GET resources. There is deliberately no score mutation method.
