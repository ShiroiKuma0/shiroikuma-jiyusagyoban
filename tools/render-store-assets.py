"""Render RuleDeck's Android and store artwork from the SVG masters in design/logo.

Needs Pillow and Playwright's Chromium:
    pip install pillow playwright
    python -m playwright install chromium
Chromium draws every SVG at its final pixel size (no resampling of a big bitmap), and
Pillow lays out the feature graphic and the launcher mask preview.
"""

from __future__ import annotations

import io
import os
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont
from playwright.sync_api import Page, sync_playwright

INDIGO_DEEP = (15, 12, 41)
INDIGO = (30, 27, 75)
VIOLET = (124, 58, 237)
AMBER = (245, 158, 11)
AMBER_LIGHT = (251, 191, 36)
WHITE = (251, 251, 251)
LAVENDER = (221, 214, 254)
MUTED = (167, 160, 210)

REPO = Path(__file__).resolve().parent.parent
RES = REPO / "app" / "src" / "main" / "res"
DESIGN = REPO / "design" / "logo"
EXPORTS = DESIGN / "exports"
STORE = REPO / "fastlane" / "metadata" / "android" / "en-US" / "images"
ICON_SOURCE = DESIGN / "ruledeck-icon.svg"
FOREGROUND_SOURCE = DESIGN / "ruledeck-foreground.svg"
MONOCHROME_SOURCE = DESIGN / "ruledeck-monochrome.svg"
NOTIFICATION_SOURCE = DESIGN / "ruledeck-notification.svg"

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
# An adaptive icon layer is 108 dp; launchers show the middle 72 dp through their mask.
VISIBLE_FRACTION = 72 / 108

TILE = re.compile(r'<rect id="tile"[^>]*fill="(#[0-9A-Fa-f]{6})"[^>]*/>\s*')


def read_svg(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    if 'viewBox="0 0 1024 1024"' not in text:
        raise ValueError(f"{path} must use a 1024x1024 viewBox")
    return text


def render_svg(page: Page, svg: str, size: int) -> Image.Image:
    page.set_viewport_size({"width": size, "height": size})
    page.set_content(
        "<!doctype html><html><head><style>"
        f"html,body{{margin:0;background:transparent}}svg{{display:block;width:{size}px;height:{size}px}}"
        f"</style></head><body>{svg}</body></html>"
    )
    png = page.screenshot(omit_background=True, clip={"x": 0, "y": 0, "width": size, "height": size})
    return Image.open(io.BytesIO(png)).convert("RGBA")


def alpha_counts(image: Image.Image) -> tuple[int, int]:
    histogram = image.getchannel("A").histogram()
    return histogram[0], histogram[255]


def require_cutout(image: Image.Image, label: str) -> Image.Image:
    transparent, opaque = alpha_counts(image)
    area = image.width * image.height
    if transparent < area * 0.15 or opaque < area * 0.01:
        raise ValueError(f"{label} failed validation: transparent={transparent}, opaque={opaque}")
    return image


def tile_color(icon_svg: str) -> str:
    match = TILE.search(icon_svg)
    if match is None:
        raise ValueError(f"{ICON_SOURCE} has no <rect id=\"tile\"> background")
    return match.group(1).upper()


def without_tile(icon_svg: str) -> str:
    stripped, count = TILE.subn("", icon_svg, count=1)
    if count != 1:
        raise ValueError(f"{ICON_SOURCE} has no <rect id=\"tile\"> background")
    return stripped


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
    image = Image.new("RGB", (width, height), INDIGO_DEEP)
    draw = ImageDraw.Draw(image)
    for x in range(width):
        ratio = x / max(1, width - 1)
        color = tuple(round(a + (b - a) * ratio) for a, b in zip(INDIGO_DEEP, INDIGO))
        draw.line((x, 0, x, height), fill=color)
    glow = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse((-90, -160, 540, 470), fill=VIOLET + (54,))
    glow = glow.filter(ImageFilter.GaussianBlur(90))
    return Image.alpha_composite(image.convert("RGBA"), glow)


def render_feature_graphic(mark: Image.Image) -> Image.Image:
    width, height = 1024, 500
    image = feature_background(width, height)
    draw = ImageDraw.Draw(image)
    fitted_mark = fitted(mark, 310, 310)
    image.alpha_composite(fitted_mark, (72, (height - fitted_mark.height) // 2))
    text_x = 430
    draw.text((text_x, 142), "RuleDeck", font=font(74, bold=True), fill=WHITE)
    draw.text((text_x, 242), "Automation that stays yours", font=font(34), fill=LAVENDER)
    pill_text, pill_font, padding = "OPEN SOURCE  •  LOCAL FIRST", font(18, bold=True), 22
    left, _, right, _ = draw.textbbox((0, 0), pill_text, font=pill_font)
    draw.rounded_rectangle(
        (text_x, 318, text_x + (right - left) + padding * 2, 368),
        radius=10, fill=AMBER, outline=AMBER_LIGHT, width=2,
    )
    draw.text((text_x + padding - left, 327), pill_text, font=pill_font, fill=INDIGO_DEEP)
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


def launcher_view(background: str, foreground: Image.Image) -> Image.Image:
    """What a launcher shows: background plus foreground, cropped to the visible 72 dp."""
    layer = Image.new("RGBA", foreground.size, background)
    layer.alpha_composite(foreground)
    side = round(foreground.width * VISIBLE_FRACTION)
    offset = (foreground.width - side) // 2
    return layer.crop((offset, offset, offset + side, offset + side))


def render_mask_preview(view: Image.Image) -> Image.Image:
    width, height = 1500, 430
    preview = Image.new("RGB", (width, height), (8, 7, 20))
    tile = view.resize((300, 300), Image.Resampling.LANCZOS)
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


def save_assets(page: Page) -> None:
    icon_svg = read_svg(ICON_SOURCE)
    foreground_svg = read_svg(FOREGROUND_SOURCE)
    monochrome_svg = read_svg(MONOCHROME_SOURCE)
    notification_svg = read_svg(NOTIFICATION_SOURCE)
    background = tile_color(icon_svg)

    primary = render_svg(page, icon_svg, 1024)
    if alpha_counts(primary) != (0, 1024 * 1024):
        raise ValueError("the store tile must be fully opaque")
    brand_mark = require_cutout(render_svg(page, without_tile(icon_svg), 1024), "brand mark")
    foreground = require_cutout(render_svg(page, foreground_svg, 1024), "foreground")
    monochrome = require_cutout(render_svg(page, monochrome_svg, 1024), "monochrome layer")

    for density, size in ADAPTIVE_DENSITIES.items():
        save(require_cutout(render_svg(page, foreground_svg, size), f"foreground {density}"),
             RES / f"mipmap-{density}" / "ic_launcher_foreground.png")
        save(require_cutout(render_svg(page, monochrome_svg, size), f"monochrome {density}"),
             RES / f"mipmap-{density}" / "ic_launcher_monochrome.png")

    for density, size in NOTIFICATION_DENSITIES.items():
        save(require_cutout(render_svg(page, notification_svg, size), f"notification {density}"),
             RES / f"drawable-{density}" / "ic_notification.png")

    adaptive = adaptive_xml()
    anydpi = RES / "mipmap-anydpi"
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / "ic_launcher.xml").write_text(adaptive, encoding="utf-8", newline="\n")
    (anydpi / "ic_launcher_round.xml").write_text(adaptive, encoding="utf-8", newline="\n")
    (RES / "values" / "ic_launcher_background.xml").write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<resources>\n'
        f'    <color name="ic_launcher_background">{background}</color>\n'
        '</resources>\n',
        encoding="utf-8",
        newline="\n",
    )

    save(render_svg(page, icon_svg, 512).convert("RGB"), STORE / "icon.png")
    save(render_feature_graphic(brand_mark), STORE / "featureGraphic.png")
    save(primary.convert("RGB"), DESIGN / "ruledeck-icon.png")
    save(brand_mark, DESIGN / "ruledeck-mark.png")
    save(render_mask_preview(launcher_view(background, foreground)), DESIGN / "launcher-mask-preview.png")

    for size in (256, 512, 1024):
        save(render_svg(page, without_tile(icon_svg), size), EXPORTS / f"ruledeck_mark_transparent_{size}.png")
    save(foreground, EXPORTS / "ruledeck_android_foreground_1024.png")
    save(monochrome, EXPORTS / "ruledeck_monochrome_1024.png")


def main() -> None:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        try:
            save_assets(browser.new_page())
        finally:
            browser.close()


if __name__ == "__main__":
    main()
