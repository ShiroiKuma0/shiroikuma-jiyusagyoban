"""Render OpenTasker's launcher, brand, and store artwork from one geometry source."""

from __future__ import annotations

import math
import os
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

NAVY = (12, 23, 46)
NAVY_DEEP = (5, 10, 22)
NAVY_LIGHT = (18, 38, 70)
CYAN = (19, 168, 213)
WHITE = (251, 251, 251)
MUTED = (157, 174, 199)
VIEWPORT = 108.0
SUPERSAMPLE = 8

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app" / "src" / "main" / "res"
DESIGN = REPO / "design" / "logo"
EXPORTS = DESIGN / "exports"
STORE = REPO / "fastlane" / "metadata" / "android" / "en-US" / "images"


def _coord(value: float, size: int) -> float:
    return value * size * SUPERSAMPLE / VIEWPORT


def _arc_edge(y: float) -> float:
    center_x, center_y, radius = 41.3, 52.0, 13.5
    return center_x + math.sqrt(radius * radius - (y - center_y) ** 2)


def render_mark(size: int, monochrome: bool = False) -> Image.Image:
    """Return the O/T foreground on a truly transparent RGBA canvas."""
    large = size * SUPERSAMPLE
    image = Image.new("RGBA", (large, large), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    outer = tuple(_coord(value, size) for value in (20.3, 31.0, 62.3, 73.0))
    inner = tuple(_coord(value, size) for value in (27.8, 38.5, 54.8, 65.5))
    draw.ellipse(outer, fill=WHITE + (255,))
    draw.ellipse(inner, fill=(0, 0, 0, 0))

    top, bottom = 42.8, 50.2
    points = [
        (_arc_edge(top), top),
        (87.0, top),
        (87.0, bottom),
        (75.2, bottom),
        (75.2, 78.2),
        (67.8, 73.4),
        (67.8, bottom),
        (_arc_edge(bottom), bottom),
    ]
    for step in range(1, 49):
        y = bottom + (top - bottom) * step / 48
        points.append((_arc_edge(y), y))
    scaled = [(_coord(x, size), _coord(y, size)) for x, y in points]
    draw.polygon(scaled, fill=(WHITE if monochrome else CYAN) + (255,))

    return image.resize((size, size), Image.Resampling.LANCZOS)


def render_tile(size: int) -> Image.Image:
    tile = Image.new("RGB", (size, size), NAVY)
    mark = render_mark(size)
    tile.paste(mark, (0, 0), mark)
    return tile


def fitted_mark(width: int, height: int) -> Image.Image:
    mark = render_mark(1024)
    bbox = mark.getbbox()
    if bbox is None:
        raise RuntimeError("The rendered mark is empty")
    mark = mark.crop(bbox)
    mark.thumbnail((width, height), Image.Resampling.LANCZOS)
    return mark


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
    glow_draw = ImageDraw.Draw(glow)
    glow_draw.ellipse((-90, -160, 540, 470), fill=CYAN + (42,))
    glow = glow.filter(ImageFilter.GaussianBlur(90))
    return Image.alpha_composite(image.convert("RGBA"), glow).convert("RGB")


def render_feature_graphic() -> Image.Image:
    width, height = 1024, 500
    image = feature_background(width, height).convert("RGBA")
    draw = ImageDraw.Draw(image)
    mark = fitted_mark(310, 310)
    image.alpha_composite(mark, (72, (height - mark.height) // 2))

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
        pixels = []
        for y in range(size):
            for x in range(size):
                nx = abs((x - center) / radius)
                ny = abs((y - center) / radius)
                pixels.append(255 if nx**4 + ny**4 <= 1 else 0)
        mask.putdata(pixels)
    else:
        raise ValueError(f"Unknown launcher mask: {kind}")
    return mask


def render_mask_preview() -> Image.Image:
    width, height = 1500, 430
    preview = Image.new("RGB", (width, height), (4, 8, 17))
    tile = render_tile(300).convert("RGBA")
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


def save_assets() -> None:
    STORE.mkdir(parents=True, exist_ok=True)
    DESIGN.mkdir(parents=True, exist_ok=True)
    EXPORTS.mkdir(parents=True, exist_ok=True)

    render_tile(512).save(STORE / "icon.png", optimize=True)
    render_feature_graphic().save(STORE / "featureGraphic.png", optimize=True)

    render_mark(1024).save(DESIGN / "opentasker-mark.png", optimize=True)
    render_tile(1024).save(DESIGN / "opentasker-mark-concept.png", optimize=True)
    render_tile(1024).save(DESIGN / "opentasker-app-tile.png", optimize=True)
    render_mask_preview().save(DESIGN / "launcher-mask-preview.png", optimize=True)
    render_mark(432).save(RES / "mipmap" / "ic_launcher_foreground.png", optimize=True)

    for size in (256, 512, 1024):
        render_mark(size).save(EXPORTS / f"opentasker_emblem_true_transparent_{size}.png", optimize=True)
    render_mark(1024).save(EXPORTS / "opentasker_emblem_true_transparent.png", optimize=True)

    densities = {
        "mipmap-ldpi": 36,
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192,
    }
    for directory, size in densities.items():
        render_tile(size).save(RES / directory / "ic_launcher.png", optimize=True)


if __name__ == "__main__":
    save_assets()
