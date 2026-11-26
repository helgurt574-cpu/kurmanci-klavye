#!/usr/bin/env python3
"""
Renders the Play Store graphics from the same design as the app's adaptive icon:
  store/icon-512.png         512×512 app icon
  store/feature-graphic.png  1024×500 feature graphic
  store/screenshots/*.png    framed 1080×1920 screenshots from store/raw/*.png (emulator captures)

Run: python3 store/make_assets.py   (needs Pillow and the Noto Sans fonts)
"""
import math
import pathlib

from PIL import Image, ImageDraw, ImageFont

HERE = pathlib.Path(__file__).resolve().parent
FONTS = pathlib.Path("/usr/share/fonts/truetype/noto")
GREEN_A = (46, 125, 50)    # #2E7D32
GREEN_B = (27, 94, 32)     # #1B5E20
SUN = (255, 193, 7)        # #FFC107
WHITE = (255, 255, 255)


def font(weight: str, size: int) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONTS / f"NotoSans-{weight}.ttf"), size)


def gradient(w: int, h: int, a=GREEN_A, b=GREEN_B) -> Image.Image:
    """Diagonal gradient, like ic_launcher_background.xml."""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        for x in range(w):
            t = (x / w + y / h) / 2
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))
    return img


def draw_mark(d: ImageDraw.ImageDraw, cx: float, cy: float, s: float) -> None:
    """The keyboard-under-a-sun mark from ic_launcher_foreground.xml; s = pixels per viewport unit (108 units)."""
    def P(x, y):
        return (cx + (x - 54) * s, cy + (y - 54) * s)

    # Sun rays: 8 small triangles around (54, 33).
    for k in range(8):
        a = k * math.pi / 4
        tip = (54 + 11 * math.sin(a), 33 - 11 * math.cos(a))
        base_c = (54 + 5.8 * math.sin(a), 33 - 5.8 * math.cos(a))
        side = (1.6 * math.cos(a), 1.6 * math.sin(a))
        d.polygon([P(*tip), P(base_c[0] + side[0], base_c[1] + side[1]), P(base_c[0] - side[0], base_c[1] - side[1])], fill=SUN)
    r = 5.5 * s
    c = P(54, 33)
    d.ellipse([c[0] - r, c[1] - r, c[0] + r, c[1] + r], fill=SUN)
    # Keyboard body and keys.
    d.rounded_rectangle([P(30, 48), P(78, 78)], radius=4 * s, fill=WHITE)
    for row_y in (53, 61):
        for x in (35, 43, 51, 59, 67):
            d.rounded_rectangle([P(x, row_y), P(x + 6, row_y + 5)], radius=0.8 * s, fill=GREEN_A)
    d.rounded_rectangle([P(42, 69), P(66, 73)], radius=0.8 * s, fill=GREEN_A)


def icon() -> None:
    big = 1024
    img = gradient(big, big)
    # Store icons aren't cropped as much as launcher icons, so the mark can be larger than the 66dp safe zone.
    draw_mark(ImageDraw.Draw(img), big / 2, big / 2 + 20, big / 108 * 1.3)
    img.resize((512, 512), Image.LANCZOS).save(HERE / "icon-512.png")


def feature_graphic() -> None:
    w, h = 2048, 1000  # drawn at 2× then downscaled for smooth edges
    img = gradient(w, h)
    d = ImageDraw.Draw(img)
    draw_mark(d, 330, 520, 7.2)
    d.text((640, 250), "Kurmancî Klavye", font=font("Bold", 132), fill=WHITE)
    d.text((646, 430), "Türkçe yazın, Kurmancî gönderin", font=font("Medium", 66), fill=(232, 245, 233))
    # A suggestion-chip demo, like the keyboard's strip.
    chips = [("merhaba", "silav"), ("teşekkürler", "spas"), ("güzel", "xweşik")]
    x = 646
    for tr, ku in chips:
        f_ku, f_tr = font("Bold", 56), font("Regular", 34)
        cw = max(d.textlength(ku, font=f_ku), d.textlength(tr, font=f_tr)) + 80
        d.rounded_rectangle([x, 590, x + cw, 760], radius=60, fill=WHITE)
        d.text((x + cw / 2, 645), ku, font=f_ku, fill=GREEN_B, anchor="mm")
        d.text((x + cw / 2, 715), tr, font=f_tr, fill=(110, 110, 110), anchor="mm")
        x += cw + 30
    d.text((646, 830), "İnternetsiz · 65.000+ kelime · 17 tema", font=font("Regular", 46), fill=(200, 230, 201))
    img.resize((1024, 500), Image.LANCZOS).save(HERE / "feature-graphic.png")


def screenshots() -> None:
    """Frames raw 1080×2400 captures into 1080×1920 store shots with a caption (Play wants ≤ 2:1)."""
    raw = HERE / "raw"
    out = HERE / "screenshots"
    out.mkdir(exist_ok=True)
    captions = {}
    for line in (raw / "captions.txt").read_text(encoding="utf-8").splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            captions[k.strip()] = v.strip()
    for shot in sorted(raw.glob("*.png")):
        canvas = gradient(1080, 1920)
        d = ImageDraw.Draw(canvas)
        title, _, sub = captions.get(shot.stem, "").partition("|")
        d.text((540, 120), title.strip(), font=font("Bold", 64), fill=WHITE, anchor="mm")
        if sub:
            d.text((540, 205), sub.strip(), font=font("Regular", 38), fill=(220, 240, 222), anchor="mm")
        phone = Image.open(shot).convert("RGB")
        scale = 1560 / phone.height
        phone = phone.resize((int(phone.width * scale), 1560), Image.LANCZOS)
        px = (1080 - phone.width) // 2
        # Rounded "device" frame.
        frame = Image.new("L", (phone.width, phone.height), 0)
        ImageDraw.Draw(frame).rounded_rectangle([0, 0, phone.width - 1, phone.height - 1], radius=48, fill=255)
        d.rounded_rectangle([px - 10, 290, px + phone.width + 10, 290 + phone.height + 20], radius=58, fill=(20, 20, 22))
        canvas.paste(phone, (px, 300), frame)
        canvas.save(out / f"{shot.stem}.png")


if __name__ == "__main__":
    icon()
    feature_graphic()
    if (HERE / "raw" / "captions.txt").exists():
        screenshots()
    print("ok")
