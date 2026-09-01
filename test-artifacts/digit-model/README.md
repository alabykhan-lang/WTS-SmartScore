# Handwritten digit model contract

SmartScore ships a model-backed `DigitRecognizer` at
`android/smartscore/app/src/main/assets/models/digit_classifier.tflite`.
The asset is the official TensorFlow Lite Flutter digit-classification example
model (`mnist_metadata.tflite`), downloaded from:

https://storage.googleapis.com/download.tensorflow.org/models/tflite/task_library/digit_classifier/flutter/mnist_metadata.tflite

Asset SHA-256:

`199fa9722eb18d7b03efa4c51859972ef7250eddd103645cac6855038e90ddee`

The model contract is:

- input: `[1, 28, 28, 3]` float RGB tensor, with white ink on black normalized to `0..1`;
- output: `[1, 10]` float tensor, classes ordered `0` through `9`;
- the Android wrapper returns the highest-scoring digit plus confidence, with
  the score assembler applying the configured assessment maximum.

The TensorFlow example and model metadata are Apache-2.0. The Android
recognizer converts its normalized black-on-white crop into the model's
white-on-black convention, and keeps ML Kit as a per-digit fallback when the
model confidence is low.

The crop generator creates labelled samples from the invented
`SMB-TEST-0001` fixture. Real handwriting should be added with the same
metadata, split by sheet/student rather than by crop, and evaluated before a
claiming teacher-handwriting accuracy. This MNIST model is a constrained
digit baseline, not a promise of arbitrary handwriting accuracy.
