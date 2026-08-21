"""Render the Izzy/F-Droid store icon and feature graphic from the shipped adaptive icon.

The store icon must be the icon that actually installs, so the mark drawn here is a direct
transcription of app/src/main/res/drawable/ic_opentasker_mark.xml (viewport 108x108) over the
adaptive icon's background colour, @color/ic_launcher_background.

Rendered with PIL at 4x supersampling rather than an SVG library so the only dependency is one
already used elsewhere on this machine.
"""
import os

from PIL import Image, ImageDraw, ImageFont

BACKGROUND = (9, 12, 18)
PURPLE = (139, 92, 246)
GREEN = (99, 230, 166)
VIEWPORT = 108.0
SUPERSAMPLE = 4

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(REPO, "fastlane", "metadata", "android", "en-US", "images")


def cubic(p0, p1, p2, p3, steps=64):
    points = []
    for index in range(steps + 1):
        t = index / steps
        u = 1 - t
        x = u * u * u * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t * t * t * p3[0]
        y = u * u * u * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t * t * t * p3[1]
        points.append((x, y))
    return points


def stroke(draw, points, width, colour, scale):
    """Round-capped, round-joined stroke drawn by stamping a circular brush along the path.

    ImageDraw's joint="curve" leaves visible spikes where thick segments meet at an angle, which
    is exactly what the arc in this mark is made of.
    """
    scaled = [(x * scale, y * scale) for x, y in points]
    radius = max(0.5, width * scale / 2)
    step = 0.4

    def stamp(x, y):
        draw.ellipse([x - radius, y - radius, x + radius, y + radius], fill=colour)

    stamp(*scaled[0])
    for (x0, y0), (x1, y1) in zip(scaled, scaled[1:]):
        distance = ((x1 - x0) ** 2 + (y1 - y0) ** 2) ** 0.5
        for index in range(1, int(distance / step) + 1):
            t = min(1.0, index * step / distance)
            stamp(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t)
        stamp(x1, y1)


def render_mark(size):
    """Draw ic_opentasker_mark.xml into a transparent RGBA image of the given edge length."""
    scale = size * SUPERSAMPLE / VIEWPORT
    canvas = Image.new("RGBA", (size * SUPERSAMPLE, size * SUPERSAMPLE), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)

    arc = (
        cubic((70, 33), (62, 24), (48, 21), (37, 25))
        + cubic((37, 25), (23, 30), (17, 45), (20, 59))[1:]
        + cubic((20, 59), (23, 74), (36, 84), (51, 84))[1:]
        + cubic((51, 84), (65, 84), (76, 76), (80, 65))[1:]
    )
    stroke(draw, arc, 10, PURPLE, scale)

    chevron = [(45, 42), (55, 42), (68, 55), (55, 68), (45, 68), (58, 55)]
    draw.polygon([(x * scale, y * scale) for x, y in chevron], fill=PURPLE)

    stroke(draw, [(69, 55), (78, 55)], 7, GREEN, scale)

    cx, cy, r = 83 * scale, 55 * scale, 5 * scale
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=GREEN)

    return canvas.resize((size, size), Image.LANCZOS)


def write(image, name):
    path = os.path.join(OUT, name)
    image.convert("RGB").save(path, "PNG", optimize=True)
    print(f"{name}: {image.size[0]}x{image.size[1]} -> {os.path.getsize(path)} bytes")


def pick_font(size):
    for candidate in (
        r"C:\Windows\Fonts\segoeuisb.ttf",
        r"C:\Windows\Fonts\seguisb.ttf",
        r"C:\Windows\Fonts\segoeui.ttf",
        r"C:\Windows\Fonts\arialbd.ttf",
    ):
        if os.path.isfile(candidate):
            return ImageFont.truetype(candidate, size)
    return ImageFont.load_default()


def build_icon():
    size = 512
    icon = Image.new("RGBA", (size, size), BACKGROUND + (255,))
    icon.alpha_composite(render_mark(size))
    write(icon, "icon.png")


def build_feature_graphic():
    width, height = 1024, 500
    graphic = Image.new("RGBA", (width, height), BACKGROUND + (255,))
    draw = ImageDraw.Draw(graphic)

    # A faint vertical lift keeps the flat background from reading as a rendering failure where
    # the listing shows the graphic against white.
    for y in range(height):
        blend = int(12 * (1 - y / height))
        draw.line(
            [(0, y), (width, y)],
            fill=(BACKGROUND[0] + blend, BACKGROUND[1] + blend, BACKGROUND[2] + blend, 255),
        )

    margin = 104
    mark_size = 300
    graphic.alpha_composite(render_mark(mark_size), (margin, (height - mark_size) // 2))

    text_x = margin + mark_size + 76
    available = width - margin - text_x

    def fit(text, start_size):
        """Largest size at or below start_size whose rendered width clears the right margin."""
        for size in range(start_size, 11, -2):
            font = pick_font(size)
            if draw.textlength(text, font=font) <= available:
                return font
        return pick_font(12)

    title_font = fit("OpenTasker", 94)
    tagline_font = fit("Local-first Android automation", 36)

    draw.text((text_x, 168), "OpenTasker", font=title_font, fill=(242, 240, 255))
    draw.rectangle([text_x, 306, text_x + 88, 310], fill=GREEN)
    draw.text((text_x, 340), "Local-first Android automation", font=tagline_font, fill=(156, 134, 240))

    write(graphic, "featureGraphic.png")


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    build_icon()
    build_feature_graphic()
