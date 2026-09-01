# Labelled digit-cell dataset

The recovery test generator writes the current labelled crop set to
`output/debug/broadsheet-ocr/digit-dataset/`:

- `images/` — individual digit-cell source crops;
- `labels.csv` — one row per crop with digit/blank label, row and assessment;
- `manifest.json` — counts, source fixture and split policy.

The current set is synthetic marks drawn over the recovered blank physical-style
sheet. It proves ROI extraction and data plumbing; it is not a substitute for
labelled phone captures of real handwriting. Add real crops only with explicit
ground truth and keep train/validation/test splits separated by sheet or
student to avoid leakage.
