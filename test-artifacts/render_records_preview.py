"""Render a static preview of the Records screen for the recovery handoff."""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "output" / "ui" / "records-preview.png"


def font(size: int, bold: bool = False):
    name = "DejaVuSans-Bold.ttf" if bold else "DejaVuSans.ttf"
    return ImageFont.truetype(f"/usr/share/fonts/truetype/dejavu/{name}", size)


def main() -> None:
    image = Image.new("RGB", (1080, 1920), "#F5F8FC")
    draw = ImageDraw.Draw(image)
    draw.text((72, 62), "RECORDS", fill="#667085", font=font(25, True), spacing=5)
    draw.text((72, 116), "Your scans, together", fill="#172033", font=font(48, True))
    draw.text((72, 178), "Find a broadsheet, script or document from one simple local library.", fill="#667085", font=font(21))
    draw.text((72, 242), "3 saved records", fill="#667085", font=font(20))
    draw.rounded_rectangle((72, 280, 1008, 340), 30, fill="#155EEF")
    draw.text((116, 299), "All", fill="white", font=font(20))
    for x, label in [(250, "Broadsheets"), (510, "Scripts"), (680, "Documents")]:
        draw.text((x, 299), label, fill="#667085", font=font(20))
    draw.rounded_rectangle((72, 364, 1008, 432), 14, fill="white", outline="#DCE3EE", width=2)
    draw.ellipse((94, 388, 120, 414), outline="#667085", width=3)
    draw.line((116, 410, 132, 426), fill="#667085", width=3)
    draw.text((150, 385), "Search records", fill="#98A2B3", font=font(22))

    cards = [
        (470, "BROADSHEET", "TEST SS1", "Economics", "2 pages  •  2.0-prototype  •  Today, 10:42 PM", "Review required", "#155EEF", "#E8F0FF"),
        (744, "SCRIPT", "Adebayo Khalid", "Economics Examination", "4 pages  •  Today, 10:48 PM", "OCR ready", "#00A67E", "#E3F7F1"),
        (1018, "DOCUMENT", "Staff Meeting Notes", "PDF and OCR package", "6 pages  •  Today, 11:03 PM", "Ready", "#7057D3", "#F0ECFF"),
    ]
    for top, kind, title, subtitle, detail, status, accent, status_bg in cards:
        draw.rounded_rectangle((72, top, 1008, top + 250), 24, fill="white", outline="#DCE3EE", width=2)
        draw.text((106, top + 26), kind, fill=accent, font=font(15, True))
        draw.text((945, top + 21), "⋮", fill="#667085", font=font(32))
        draw.text((106, top + 70), title, fill="#172033", font=font(29, True))
        draw.text((106, top + 111), subtitle, fill="#172033", font=font(22))
        draw.text((106, top + 156), detail, fill="#667085", font=font(18))
        status_box = (730, top + 136, 890, top + 174) if status == "Review required" else (762, top + 136, 890, top + 174)
        draw.rounded_rectangle(status_box, 19, fill=status_bg)
        draw.text((status_box[0] + 20, status_box[1] + 8), status, fill=accent, font=font(16))
        draw.rounded_rectangle((106, top + 202, 962, top + 236), 17, fill=accent)
        draw.text((493, top + 208), "OPEN", fill="white", font=font(15, True))
    draw.text((72, 1320), "Local-first  •  searchable  •  easy to review", fill="#98A2B3", font=font(18))
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.save(OUTPUT)


if __name__ == "__main__":
    main()
