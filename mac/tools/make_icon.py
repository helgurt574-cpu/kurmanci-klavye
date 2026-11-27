"""Draws the input-source icon (menu bar): a rounded square with "Ku" knocked out, as a template image
(only alpha matters). Writes Resources/icon.png (16 px) and Resources/icon@2x.png (32 px); build.sh joins them
into icon.tiff with macOS's tiffutil. Run: python3 -I tools/make_icon.py (needs Pillow)."""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
out_dir = os.path.join(os.path.dirname(os.path.abspath(sys.argv[0])), "..", "Resources")

for size, name in ((16, "icon.png"), (32, "icon@2x.png")):
    s = size * 8
    mask = Image.new("L", (s, s), 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle((s * 0.03, s * 0.09, s * 0.97, s * 0.91), radius=s * 0.18, fill=255)
    font = ImageFont.truetype(FONT, int(s * 0.58))
    d.text((s / 2, s / 2), "Ku", font=font, fill=0, anchor="mm")
    mask = mask.resize((size, size), Image.LANCZOS)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    img.putalpha(mask)
    img.save(os.path.join(out_dir, name))
    print("wrote", name)
