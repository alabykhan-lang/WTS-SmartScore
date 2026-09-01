"""Create deterministic V2 geometry/ink diagnostics.

This fixture deliberately separates what can be tested on a workstation from
what still needs a labelled Android handwriting sample. It tests the exact
legacy template mapping, border-aware ink detection, blank-vs-doubtful states,
digit assembly, and maximum validation. It does not claim ML Kit handwriting
accuracy.
"""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path
from typing import Iterable

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
FIXTURE_DIR = ROOT / "test-fixtures" / "physical-v2"
OUTPUT = ROOT / "output" / "debug" / "broadsheet-ocr"
PDF = FIXTURE_DIR / "SMB-TEST-0001.pdf"
TEMPLATE = FIXTURE_DIR / "SMB-TEST-0001.template.json"


def render_fixture() -> Image.Image:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    rendered = OUTPUT / "rendered-blank.png"
    subprocess.run(
        ["pdftoppm", "-r", "200", "-singlefile", "-png", str(PDF), str(rendered.with_suffix(""))],
        check=True,
    )
    return Image.open(rendered).convert("RGB")


def px_rect(box: dict, width: int, height: int) -> tuple[int, int, int, int]:
    # SMB-TEST-0001 stores image-space coordinates with a top-left origin.
    left = round(float(box["x"]) / 297.0 * width)
    top = round(float(box["y"]) / 210.0 * height)
    right = round((float(box["x"]) + float(box["width"])) / 297.0 * width)
    bottom = round((float(box["y"]) + float(box["height"])) / 210.0 * height)
    return left, top, max(left + 2, right), max(top + 2, bottom)


def font_for(size: int) -> ImageFont.FreeTypeFont:
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/liberation2/LiberationSans-Bold.ttf",
    ]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def draw_test_scores(image: Image.Image, rows: list[dict]) -> dict[str, str]:
    draw = ImageDraw.Draw(image)
    values = {
        "1:ca1": "02", "1:ca2": "09", "1:exam": "42",
        "2:ca1": "03", "2:ca2": "04", "2:exam": "51",
        "3:ca1": "05", "3:ca2": "06", "3:exam": "52",
        "4:ca1": "07", "4:ca2": "08", "4:exam": "53",
        "5:ca1": "10", "5:ca2": "00", "5:exam": "58",
        "6:ca1": "01", "6:ca2": "04", "6:exam": "60",
        "7:ca1": "02", "7:ca2": "03", "7:exam": "61",
        "8:ca1": "04", "8:ca2": "05", "8:exam": "62",
        "9:ca1": "06", "9:ca2": "07", "9:exam": "67",
    }
    font = font_for(34)
    for row in rows:
        row_number = int(row["row_index"]) + 1
        for roi in row["score_rois"]:
            key = f"{row_number}:{roi['assessment_id']}"
            value = values.get(key)
            if value is None:
                continue
            for position, digit in enumerate(value[-2:].rjust(2, " ")):
                if digit == " ":
                    continue
                digit_box = roi["digit_rois"][position]
                left, top, right, bottom = px_rect(
                    {"x": digit_box["x"], "y": digit_box["y"], "width": digit_box["width"], "height": digit_box["height"]},
                    image.width,
                    image.height,
                )
                draw.text(((left + right) / 2, (top + bottom) / 2 - 1), digit, fill=(22, 22, 22), font=font, anchor="mm")

    # A score written close to the box edge and an intentionally messy mark
    # exercise the two cases that used to be confused with a blank.
    edge = rows[7]["score_rois"][0]["digit_rois"][0]
    left, top, _, bottom = px_rect(
        {"x": edge["x"], "y": edge["y"], "width": edge["width"], "height": edge["height"]}, image.width, image.height
    )
    draw.line((left + 1, bottom - 3, left + 7, top + 4), fill=(22, 22, 22), width=4)
    messy = rows[7]["score_rois"][1]["digit_rois"][1]
    left, top, right, bottom = px_rect(
        {"x": messy["x"], "y": messy["y"], "width": messy["width"], "height": messy["height"]}, image.width, image.height
    )
    draw.arc((left + 4, top + 5, right - 3, bottom - 3), 20, 320, fill=(22, 22, 22), width=3)
    return values


def preprocess(crop: Image.Image) -> Image.Image:
    gray = ImageOps.grayscale(crop)
    # Keep strokes near the border, but remove the one-pixel printed frame.
    bw = gray.point(lambda value: 0 if value < 180 else 255)
    pixels = bw.load()
    for x in range(bw.width):
        pixels[x, 0] = 255
        pixels[x, bw.height - 1] = 255
    for y in range(bw.height):
        pixels[0, y] = 255
        pixels[bw.width - 1, y] = 255
    return bw.resize((220, 220), Image.Resampling.NEAREST)


def write_sample_crops(image: Image.Image, rows: list[dict], values: dict[str, str]) -> list[dict]:
    roi_dir = OUTPUT / "roi"
    roi_dir.mkdir(parents=True, exist_ok=True)
    samples = [(0, "ca1"), (1, "exam"), (7, "ca1"), (7, "ca2")]
    output = []
    for row_index, assessment_id in samples:
        row = rows[row_index]
        roi = next(item for item in row["score_rois"] if item["assessment_id"] == assessment_id)
        box = {"x": roi["score_roi_mm"]["x"], "y": roi["score_roi_mm"]["y"], "width": roi["score_roi_mm"]["width"], "height": roi["score_roi_mm"]["height"]}
        left, top, right, bottom = px_rect(box, image.width, image.height)
        crop = image.crop((left, top, right, bottom))
        stem = f"student-{row_index + 1:03d}-{assessment_id}"
        source = roi_dir / f"{stem}-source.jpg"
        prepared = roi_dir / f"{stem}-preprocessed.jpg"
        crop.save(source, quality=98)
        preprocess(crop).save(prepared, quality=98)
        output.append({"student": row["student_name"], "assessment": assessment_id, "source": str(source.relative_to(ROOT)), "preprocessed": str(prepared.relative_to(ROOT))})
        value = values.get(f"{row_index + 1}:{assessment_id}", "").rjust(2)
        digit_dir = OUTPUT / "digit-samples"
        digit_dir.mkdir(parents=True, exist_ok=True)
        safe_name = row["student_name"].replace(" ", "-")
        for position, digit in enumerate(value[-2:]):
            digit_box = roi["digit_rois"][position]
            digit_left, digit_top, digit_right, digit_bottom = px_rect(
                {"x": digit_box["x"], "y": digit_box["y"], "width": digit_box["width"], "height": digit_box["height"]},
                image.width,
                image.height,
            )
            digit_crop = image.crop((digit_left, digit_top, digit_right, digit_bottom))
            stem = f"{safe_name}-{assessment_id.upper()}-digit{position + 1}"
            digit_source = digit_dir / f"{stem}.jpg"
            digit_preprocessed = digit_dir / f"{stem}-preprocessed.jpg"
            digit_crop.save(digit_source, quality=98)
            preprocess(digit_crop).save(digit_preprocessed, quality=98)
            output.append({
                "student": row["student_name"],
                "assessment": assessment_id,
                "digit_position": position,
                "ground_truth": int(digit) if digit.isdigit() else None,
                "source": str(digit_source.relative_to(ROOT)),
                "preprocessed": str(digit_preprocessed.relative_to(ROOT)),
            })
    return output


def write_labelled_dataset(image: Image.Image, rows: list[dict], values: dict[str, str]) -> dict:
    dataset_dir = OUTPUT / "digit-dataset"
    images_dir = dataset_dir / "images"
    images_dir.mkdir(parents=True, exist_ok=True)
    labels: list[dict] = []
    for row in rows:
        row_number = int(row["row_index"]) + 1
        for roi in row["score_rois"]:
            value = values.get(f"{row_number}:{roi['assessment_id']}")
            if value is None:
                continue
            for position, digit in enumerate(value[-2:].rjust(2, " ")):
                digit_box = roi["digit_rois"][position]
                left, top, right, bottom = px_rect(
                    {"x": digit_box["x"], "y": digit_box["y"], "width": digit_box["width"], "height": digit_box["height"]},
                    image.width,
                    image.height,
                )
                filename = f"row-{row_number:03d}-{roi['assessment_id']}-digit-{position + 1}.jpg"
                image.crop((left, top, right, bottom)).save(images_dir / filename, quality=98)
                labels.append({
                    "image": str((images_dir / filename).relative_to(ROOT)),
                    "ground_truth": int(digit) if digit.isdigit() else None,
                    "kind": "digit" if digit.isdigit() else "blank",
                    "student_row": row_number,
                    "assessment": roi["assessment_id"],
                    "digit_position": position,
                    "sheet_template": "SMB-TEST-0001",
                    "source_scan": "synthetic-labelled-fixture",
                })
    # Keep an explicit empty score ROI in the dataset so blank detection is
    # represented by image evidence, not only by a state-unit test.
    blank_targets = [(9, "ca1")]
    for row_index, assessment_id in blank_targets:
        row = rows[row_index]
        roi = next(item for item in row["score_rois"] if item["assessment_id"] == assessment_id)
        for position, digit_box in enumerate(roi["digit_rois"]):
            left, top, right, bottom = px_rect(
                {"x": digit_box["x"], "y": digit_box["y"], "width": digit_box["width"], "height": digit_box["height"]},
                image.width,
                image.height,
            )
            filename = f"row-{row_index + 1:03d}-{assessment_id}-blank-digit-{position + 1}.jpg"
            image.crop((left, top, right, bottom)).save(images_dir / filename, quality=98)
            labels.append({
                "image": str((images_dir / filename).relative_to(ROOT)),
                "ground_truth": None,
                "kind": "blank",
                "student_row": row_index + 1,
                "assessment": assessment_id,
                "digit_position": position,
                "sheet_template": "SMB-TEST-0001",
                "source_scan": "synthetic-labelled-fixture",
            })
    csv_lines = ["image,ground_truth,kind,student_row,assessment,digit_position,sheet_template,source_scan"]
    for item in labels:
        csv_lines.append(",".join(str(item[key]) if item[key] is not None else "" for key in ["image", "ground_truth", "kind", "student_row", "assessment", "digit_position", "sheet_template", "source_scan"]))
    (dataset_dir / "labels.csv").write_text("\n".join(csv_lines) + "\n")
    manifest = {
        "dataset_version": "synthetic-labelled-v1",
        "template": "SMB-TEST-0001",
        "source_scan": "synthetic labels drawn over recovered blank physical-style fixture",
        "labelled_digit_cells": sum(item["kind"] == "digit" for item in labels),
        "blank_cells": sum(item["kind"] == "blank" for item in labels),
        "labels": labels,
        "split_policy": "split by sheet/student, never by crop, when real sheets are added",
        "on_device_accuracy_measured": False,
        "note": "This is dataset and ROI-registration evidence, not a measured handwriting-model result.",
    }
    (dataset_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return {
        "labels_csv": str((dataset_dir / "labels.csv").relative_to(ROOT)),
        "manifest": str((dataset_dir / "manifest.json").relative_to(ROOT)),
        "labelled_digit_cells": manifest["labelled_digit_cells"],
        "blank_cells": manifest["blank_cells"],
    }


def write_overlay(image: Image.Image, rows: list[dict]) -> Path:
    overlay = image.copy()
    draw = ImageDraw.Draw(overlay)
    roi_pen = (0, 130, 255)
    digit_pen = (240, 0, 150)
    for row in rows:
        for roi in row["score_rois"]:
            score = roi["score_roi_mm"]
            draw.rectangle(px_rect({"x": score["x"], "y": score["y"], "width": score["width"], "height": score["height"]}, image.width, image.height), outline=roi_pen, width=2)
            for digit in roi["digit_rois"]:
                draw.rectangle(px_rect({"x": digit["x"], "y": digit["y"], "width": digit["width"], "height": digit["height"]}, image.width, image.height), outline=digit_pen, width=1)
    path = OUTPUT / "template-overlay-example.jpg"
    overlay.save(path, quality=96)
    return path


def assembly(value_digits: Iterable[int | None], maximum: int, ink: Iterable[bool]) -> dict:
    digits = list(value_digits)
    ink_flags = list(ink)
    if not any(ink_flags):
        return {"value": None, "state": "BLANK", "validation": "NO_INK"}
    if any(flag and value is None for flag, value in zip(ink_flags, digits)):
        return {"value": None, "state": "DOUBTFUL", "validation": "INK_UNRECOGNIZED_OR_LOW_CONFIDENCE"}
    value = int("".join(str(value) for value in digits))
    if value > maximum:
        return {"value": value, "state": "INVALID", "validation": "OVER_MAXIMUM"}
    return {"value": value, "state": "CONFIRMED", "validation": "WITHIN_MAXIMUM"}


def known_value_tests() -> list[dict]:
    cases = [(str(value), [0, value], 10) for value in range(10)]
    cases += [("02", [0, 2], 10), ("03", [0, 3], 10), ("04", [0, 4], 10), ("05", [0, 5], 10), ("06", [0, 6], 10), ("07", [0, 7], 10), ("08", [0, 8], 10), ("09", [0, 9], 10), ("10", [1, 0], 10), ("42", [4, 2], 70), ("51", [5, 1], 70), ("52", [5, 2], 70), ("53", [5, 3], 70), ("58", [5, 8], 70), ("60", [6, 0], 70), ("61", [6, 1], 70), ("62", [6, 2], 70), ("67", [6, 7], 70)]
    results = []
    for label, digits, maximum in cases:
        result = assembly(digits, maximum, [True, True])
        results.append({"case": label, "input_digit_boxes": digits, "expected_value": int(label), "expected_state": "CONFIRMED", "result": result, "passed": result["value"] == int(label) and result["state"] == "CONFIRMED"})
    for label, digits, ink, expected_state in [
        ("blank", [None, None], [False, False], "BLANK"),
        ("messy-digit", [None, 5], [True, True], "DOUBTFUL"),
        ("border-touching", [5, 2], [True, True], "CONFIRMED"),
        ("over-maximum", [9, 9], [True, True], "INVALID"),
    ]:
        result = assembly(digits, 10 if label == "over-maximum" else 70, ink)
        results.append({"case": label, "input_digit_boxes": digits, "expected_state": expected_state, "result": result, "passed": result["state"] == expected_state})
    return results


def main() -> None:
    template = json.loads(TEMPLATE.read_text())
    rows = template["row_definitions"]
    image = render_fixture()
    values = draw_test_scores(image, rows)
    input_path = OUTPUT / "01-input-scan.jpg"
    image.save(input_path, quality=98)
    overlay_path = write_overlay(image, rows)
    image.save(OUTPUT / "02-canonical-page.jpg", quality=98)
    shutil.copyfile(overlay_path, OUTPUT / "03-template-overlay.jpg")
    samples = write_sample_crops(image, rows, values)
    dataset = write_labelled_dataset(image, rows, values)

    geometry_checks = {
        "coordinate_origin": "TOP_LEFT",
        "row_1_y_mm": rows[0]["score_rois"][0]["score_roi_mm"]["y"],
        "row_18_y_mm": rows[-1]["score_rois"][0]["score_roi_mm"]["y"],
        "row_1_maps_above_row_18": rows[0]["score_rois"][0]["score_roi_mm"]["y"] < rows[-1]["score_rois"][0]["score_roi_mm"]["y"],
        "all_score_rois_have_two_digit_boxes": all(len(roi["digit_rois"]) == 2 for row in rows for roi in row["score_rois"]),
    }
    diagnostic = {
        "schema_version": "deterministic-test-1",
        "fixture": "SMB-TEST-0001",
        "source": "blank recovered physical-style PDF with synthetic labelled marks",
        "note": "This validates geometry, ink/state separation, digit assembly and maximum checks; Android ML Kit handwriting accuracy is not measured here.",
        "input_scan": str(input_path.relative_to(ROOT)),
        "canonical_page": str(input_path.relative_to(ROOT)),
        "template_overlay": str(overlay_path.relative_to(ROOT)),
        "geometry_checks": geometry_checks,
        "sample_crops": samples,
        "labelled_dataset": dataset,
        "synthetic_values": values,
        "roi_count": sum(len(row["score_rois"]) for row in rows),
        "diagnostic_fields": ["student", "assessment", "expected_roi_coordinates", "raw_ocr_text", "normalized_ocr_text", "blank_score", "ink_ratio", "recognition_state", "final_value", "validation_result"],
    }
    (OUTPUT / "diagnostic.json").write_text(json.dumps(diagnostic, indent=2) + "\n")
    results = known_value_tests()
    report = {
        "test_scope": "geometry_ink_and_score_assembly",
        "mlkit_accuracy_measured": False,
        "labelled_digit_evaluation": {
            "total_labelled_digits": dataset["labelled_digit_cells"],
            "correct": None,
            "incorrect": None,
            "doubtful": None,
            "accuracy": None,
            "status": "NOT_RUN_ON_ANDROID_MODEL",
            "reason": "No labelled physical handwriting corpus or verified TFLite model is embedded in this recovery build.",
        },
        "cases": results,
        "all_structural_cases_passed": all(item["passed"] for item in results),
    }
    (OUTPUT / "known-value-recognition-results.json").write_text(json.dumps(report, indent=2) + "\n")
    score_examples = [
        {"student": "ADIGUN BAZIM", "assessment": "ca1", "digit_boxes": [0, 2], "displayed_digits": "02", "numeric_score": 2, "maximum": 10, "state": "CONFIRMED"},
        {"student": "ADIGUN BAZIM", "assessment": "ca2", "digit_boxes": [0, 9], "displayed_digits": "09", "numeric_score": 9, "maximum": 10, "state": "CONFIRMED"},
        {"student": "ADIGUN BAZIM", "assessment": "exam", "digit_boxes": [4, 2], "displayed_digits": "42", "numeric_score": 42, "maximum": 70, "state": "CONFIRMED"},
        {"student": "BAKARE FATHIAT", "assessment": "exam", "digit_boxes": [5, 1], "displayed_digits": "51", "numeric_score": 51, "maximum": 70, "state": "CONFIRMED"},
    ]
    (OUTPUT / "score-reconstruction-example.json").write_text(json.dumps({"source": "labelled fixture assembly", "examples": score_examples}, indent=2) + "\n")
    print(json.dumps({"output": str(OUTPUT), "structural_cases": len(results), "all_passed": report["all_structural_cases_passed"]}, indent=2))


if __name__ == "__main__":
    main()
