# Handwritten digit model contract

SmartScore now selects a model-backed `DigitRecognizer` when the Android asset
`models/digit_classifier.tflite` is present. The model contract is:

- input: `[1, 28, 28, 1]` float tensor, grayscale ink normalized to `0..1`;
- output: `[1, 10]` float tensor, classes ordered `0` through `9`;
- output: digit plus confidence, with the score assembler applying the
  configured assessment maximum.

The recovery build intentionally does not embed an arbitrary downloaded model.
The TensorFlow Lite runtime is Apache-2.0; a future model must have its own
documented dataset, licence, provenance and held-out evaluation before being
copied into the asset directory.

The crop generator creates labelled samples from the invented
`SMB-TEST-0001` fixture. Real handwriting should be added with the same
metadata, split by sheet/student rather than by crop, and evaluated before a
model is enabled. Until then, ML Kit Text Recognition is used only as the
explicit fallback and its handwriting accuracy is not claimed.
