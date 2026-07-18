from pathlib import Path
from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app" / "src"
INK = (7, 7, 10)
RED_DARK = (169, 28, 42)
RED = (242, 59, 72)
RED_LIGHT = (255, 102, 113)
WHITE = (255, 248, 249)


def add_radial_glow(image, center, radius, color, maximum_alpha):
    overlay = Image.new("RGBA", image.size, (0, 0, 0, 0))
    pixels = overlay.load()
    cx, cy = center
    left = max(0, int(cx - radius))
    right = min(image.width, int(cx + radius))
    top = max(0, int(cy - radius))
    bottom = min(image.height, int(cy + radius))
    for y in range(top, bottom):
        dy = (y - cy) / radius
        for x in range(left, right):
            dx = (x - cx) / radius
            distance = (dx * dx + dy * dy) ** 0.5
            if distance >= 1:
                continue
            alpha = int(maximum_alpha * (1 - distance) ** 2)
            pixels[x, y] = (*color, alpha)
    image.alpha_composite(overlay)


def draw_mark(image, box, line_scale=0.105):
    draw = ImageDraw.Draw(image)
    x, y, size = box
    stroke = max(2, int(size * line_scale))
    radius = int(size * 0.11)
    # Coordinates mirror brand_mark.xml and keep both frames in the adaptive-icon safe zone.
    left = (x + size * 0.195, y + size * 0.232, x + size * 0.584, y + size * 0.769)
    right = (x + size * 0.417, y + size * 0.232, x + size * 0.806, y + size * 0.769)
    draw.rounded_rectangle(left, radius=radius, outline=RED_DARK, width=stroke)
    draw.rounded_rectangle(right, radius=radius, outline=RED, width=stroke)
    play = [
        (x + size * 0.444, y + size * 0.389),
        (x + size * 0.648, y + size * 0.500),
        (x + size * 0.444, y + size * 0.611),
    ]
    draw.polygon(play, fill=RED_LIGHT)


def render_launcher(size, rounded=True):
    scale = 4
    px = size * scale
    image = Image.new("RGBA", (px, px), (0, 0, 0, 0) if rounded else (*INK, 255))
    draw = ImageDraw.Draw(image)
    if rounded:
        draw.rounded_rectangle((px * 0.035, px * 0.035, px * 0.965, px * 0.965), radius=px * 0.17, fill=(*INK, 255))
    add_radial_glow(image, (px * 0.55, px * 0.53), px * 0.42, RED, 58)
    draw_mark(image, (px * 0.06, px * 0.06, px * 0.88), line_scale=0.09)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def font(size, bold=False):
    candidates = [
        "/System/Library/Fonts/PingFang.ttc",
        "/System/Library/Fonts/STHeiti Medium.ttc" if bold else "/System/Library/Fonts/STHeiti Light.ttc",
    ]
    for candidate in candidates:
        try:
            return ImageFont.truetype(candidate, size=size, index=1 if bold else 0)
        except OSError:
            pass
    return ImageFont.load_default()


def render_banner():
    scale = 4
    image = Image.new("RGBA", (320 * scale, 180 * scale), (*INK, 255))
    add_radial_glow(image, (132 * scale, 88 * scale), 150 * scale, RED, 62)
    draw_mark(image, (22 * scale, 38 * scale, 104 * scale), line_scale=0.085)
    draw = ImageDraw.Draw(image)
    draw.text((133 * scale, 57 * scale), "片多多", fill=WHITE, font=font(35 * scale, True))
    draw.text((135 * scale, 104 * scale), "好片汇聚 · 一点即播", fill=(190, 181, 184), font=font(13 * scale))
    return image.resize((320, 180), Image.Resampling.LANCZOS).convert("RGB")


def render_splash():
    scale = 2
    width, height = 1280 * scale, 720 * scale
    image = Image.new("RGBA", (width, height), (*INK, 255))
    add_radial_glow(image, (width * 0.5, height * 0.48), height * 0.72, RED, 72)
    draw = ImageDraw.Draw(image)
    for index, inset in enumerate((95, 155, 220, 290)):
        alpha = 25 - index * 4
        box = (inset * scale, inset * 0.56 * scale, width - inset * scale, height - inset * 0.56 * scale)
        draw.rounded_rectangle(box, radius=42 * scale, outline=(*RED, alpha), width=2 * scale)
    return image.resize((1280, 720), Image.Resampling.LANCZOS).convert("RGB")


def save_assets():
    master = render_launcher(1024, rounded=True)
    master.save(ROOT / "design-assets" / "branding" / "pdd-icon-master.png", optimize=True)
    render_launcher(512, rounded=False).convert("RGB").save(APP / "main" / "ic_launcher-playstore.png", optimize=True)
    for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        target = APP / "main" / "res" / f"mipmap-{density}"
        icon = render_launcher(size, rounded=True)
        icon.save(target / "ic_launcher.png", optimize=True)
        icon.save(target / "ic_launcher_round.png", optimize=True)
    render_banner().save(APP / "leanback" / "res" / "drawable" / "ic_banner.png", optimize=True)
    render_splash().save(APP / "main" / "res" / "drawable-nodpi" / "pdd_splash_background.jpg", quality=92, optimize=True)


if __name__ == "__main__":
    save_assets()
