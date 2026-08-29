"""Render OpenTasker's Android and store artwork from checked-in source PNGs."""

from __future__ import annotations

import os
from collections import Counter
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

NAVY_DEEP = (5, 10, 22)
NAVY_LIGHT = (18, 38, 70)
CYAN = (19, 168, 213)
WHITE = (251, 251, 251)
MUTED = (157, 174, 199)

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app" / "src" / "main" / "res"
DESIGN = REPO / "design" / "logo"
EXPORTS = DESIGN / "exports"
STORE = REPO / "fastlane" / "metadata" / "android" / "en-US" / "images"
PRIMARY_SOURCE = DESIGN / "source-user-logo-2026-08-29.png"
FOREGROUND_SOURCE = DESIGN / "source-android-foreground-2026-08.png"
MONOCHROME_SOURCE = DESIGN / "source-monochrome-2026-08.png"

ADAPTIVE_DENSITIES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
NOTIFICATION_DENSITIES = {
    "mdpi": 24,
    "hdpi": 36,
    "xhdpi": 48,
    "xxhdpi": 72,
    "xxxhdpi": 96,
}


def load_source(path: Path) -> Image.Image:
    image = Image.open(path).convert("RGBA")
    if image.size != (1024, 1024):
        raise ValueError(f"{path} is {image.size}, expected 1024x1024")
    return image


def alpha_counts(image: Image.Image) -> tuple[int, int]:
    counts = Counter(image.getchannel("A").getdata())
    return counts[0], counts[255]


def remove_connected_background(image: Image.Image, tolerance: int = 14) -> Image.Image:
    result = image.copy()
    for point in ((0, 0), (1023, 0), (0, 1023), (1023, 1023)):
        ImageDraw.floodfill(result, point, (0, 0, 0, 0), thresh=tolerance)
    transparent, opaque = alpha_counts(result)
    area = result.width * result.height
    if transparent < area * 0.15 or opaque < area * 0.01:
        raise ValueError(
            f"background removal failed validation: transparent={transparent}, opaque={opaque}"
        )
    return result


def monochrome_layer(image: Image.Image) -> Image.Image:
    pixels = []
    for red, green, blue, source_alpha in image.getdata():
        alpha = max(red, green, blue) * source_alpha // 255
        pixels.append((255, 255, 255, alpha))
    result = Image.new("RGBA", image.size)
    result.putdata(pixels)
    transparent, opaque = alpha_counts(result)
    area = result.width * result.height
    if transparent < area * 0.15 or opaque < area * 0.01:
        raise ValueError(
            f"monochrome conversion failed validation: transparent={transparent}, opaque={opaque}"
        )
    return result


def sample_corner_color(image: Image.Image) -> tuple[int, int, int]:
    samples = []
    for x, y in ((0, 0), (1023, 0), (0, 1023), (1023, 1023)):
        crop = image.crop((max(0, x - 5), max(0, y - 5), min(1024, x + 6), min(1024, y + 6)))
        samples.extend(crop.getdata())
    return tuple(sum(pixel[channel] for pixel in samples) // len(samples) for channel in range(3))


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, "PNG", optimize=True)


def fitted(image: Image.Image, width: int, height: int) -> Image.Image:
    bbox = image.getbbox()
    if bbox is None:
        raise ValueError("source artwork has no visible pixels")
    result = image.crop(bbox)
    result.thumbnail((width, height), Image.Resampling.LANCZOS)
    return result


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    names = ("segoeuib.ttf", "seguisb.ttf") if bold else ("segoeui.ttf", "seguisb.ttf")
    for name in names:
        candidate = Path(os.environ.get("WINDIR", "C:/Windows")) / "Fonts" / name
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def feature_background(width: int, height: int) -> Image.Image:
    image = Image.new("RGB", (width, height), NAVY_DEEP)
    draw = ImageDraw.Draw(image)
    for x in range(width):
        ratio = x / max(1, width - 1)
        color = tuple(round(a + (b - a) * ratio) for a, b in zip(NAVY_DEEP, NAVY_LIGHT))
        draw.line((x, 0, x, height), fill=color)
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-90, -160, 540, 470), fill=CYAN + (42,))
    glow = glow.filter(ImageFilter.GaussianBlur(90))
    return Image.alpha_composite(image.convert("RGBA"), glow)


def render_feature_graphic(mark: Image.Image) -> Image.Image:
    width, height = 1024, 500
    image = feature_background(width, height)
    draw = ImageDraw.Draw(image)
    fitted_mark = fitted(mark, 310, 310)
    image.alpha_composite(fitted_mark, (72, (height - fitted_mark.height) // 2))
    text_x = 430
    draw.text((text_x, 142), "OpenTasker", font=font(74, bold=True), fill=WHITE)
    draw.text((text_x, 242), "Automation that stays yours", font=font(34), fill=(204, 220, 240))
    draw.rounded_rectangle((text_x, 318, 754, 368), radius=10, fill=CYAN, outline=(79, 211, 239), width=2)
    draw.text((text_x + 22, 327), "OPEN SOURCE  •  LOCAL FIRST", font=font(18, bold=True), fill=NAVY_DEEP)
    return image.convert("RGB")


def launcher_mask(kind: str, size: int) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    inset = 3
    if kind == "Square":
        draw.rectangle((inset, inset, size - inset, size - inset), fill=255)
    elif kind == "Rounded":
        draw.rounded_rectangle((inset, inset, size - inset, size - inset), radius=size // 5, fill=255)
    elif kind == "Circle":
        draw.ellipse((inset, inset, size - inset, size - inset), fill=255)
    elif kind == "Squircle":
        radius = (size - inset * 2) / 2
        center = (size - 1) / 2
        mask.putdata([
            255
            if abs((x - center) / radius) ** 4 + abs((y - center) / radius) ** 4 <= 1
            else 0
            for y in range(size)
            for x in range(size)
        ])
    else:
        raise ValueError(f"Unknown launcher mask: {kind}")
    return mask


def render_mask_preview(primary: Image.Image) -> Image.Image:
    width, height = 1500, 430
    preview = Image.new("RGB", (width, height), (4, 8, 17))
    tile = primary.resize((300, 300), Image.Resampling.LANCZOS)
    label_font = font(24, bold=True)
    for index, label in enumerate(("Square", "Rounded", "Squircle", "Circle")):
        x = 55 + index * 365
        y = 40
        shadow = Image.new("RGBA", (320, 320), (0, 0, 0, 0))
        shadow_mask = launcher_mask(label, 300).filter(ImageFilter.GaussianBlur(14))
        shadow.paste((0, 0, 0, 155), (10, 14), shadow_mask)
        preview.paste(shadow, (x - 10, y - 10), shadow)
        masked = Image.new("RGBA", (300, 300), (0, 0, 0, 0))
        masked.paste(tile, (0, 0), launcher_mask(label, 300))
        preview.paste(masked, (x, y), masked)
        text_box = ImageDraw.Draw(preview).textbbox((0, 0), label, font=label_font)
        text_width = text_box[2] - text_box[0]
        ImageDraw.Draw(preview).text((x + (300 - text_width) / 2, 365), label, font=label_font, fill=MUTED)
    return preview


def adaptive_xml() -> str:
    return (
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background"/>\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n'
        '    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n'
        '</adaptive-icon>\n'
    )


def save_assets() -> None:
    primary = load_source(PRIMARY_SOURCE)
    foreground_source = load_source(FOREGROUND_SOURCE)
    monochrome_source = load_source(MONOCHROME_SOURCE)
    if alpha_counts(primary) != (0, 1024 * 1024):
        raise ValueError("primary source must be fully opaque")

    foreground = remove_connected_background(foreground_source)
    monochrome = monochrome_layer(monochrome_source)
    brand_mark = remove_connected_background(primary)
    background = sample_corner_color(foreground_source)

    for density, size in ADAPTIVE_DENSITIES.items():
        save(
            foreground.resize((size, size), Image.Resampling.LANCZOS),
            RES / f"mipmap-{density}" / "ic_launcher_foreground.png",
        )
        save(
            monochrome.resize((size, size), Image.Resampling.LANCZOS),
            RES / f"mipmap-{density}" / "ic_launcher_monochrome.png",
        )

    for density, size in NOTIFICATION_DENSITIES.items():
        save(
            monochrome.resize((size, size), Image.Resampling.LANCZOS),
            RES / f"drawable-{density}" / "ic_notification.png",
        )

    adaptive = adaptive_xml()
    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(adaptive, encoding="utf-8", newline="\n")
    (anydpi / "ic_launcher_round.xml").write_text(adaptive, encoding="utf-8", newline="\n")
    color = "#{:02X}{:02X}{:02X}".format(*background)
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        f'    <color name="ic_launcher_background">{color}</color>\n'
        '</resources>\n',
        encoding="utf-8",
        newline="\n",
    )

    save(primary.resize((512, 512), Image.Resampling.LANCZOS).convert("RGB"), STORE / "icon.png")
    save(render_feature_graphic(brand_mark), STORE / "featureGraphic.png")
    save(brand_mark, DESIGN / "opentasker-mark.png")
    save(primary.convert("RGB"), DESIGN / "opentasker-mark-concept.png")
    save(primary.convert("RGB"), DESIGN / "opentasker-app-tile.png")
    save(render_mask_preview(primary), DESIGN / "launcher-mask-preview.png")

    for size in (256, 512, 1024):
        save(
            brand_mark.resize((size, size), Image.Resampling.LANCZOS),
            EXPORTS / f"opentasker_emblem_true_transparent_{size}.png",
        )
    save(brand_mark, EXPORTS / "opentasker_emblem_true_transparent.png")


if __name__ == "__main__":
    save_assets()
