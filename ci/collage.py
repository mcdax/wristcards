#!/usr/bin/env python3
"""Before/after collages from two screens.py runs: one image per screen, rows = displays, columns = before/after.

Usage: collage.py <before dir> <after dir> <output dir>   (needs Pillow)
"""
import json
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

BEFORE, AFTER, OUT = sys.argv[1:4]
CONFIG = json.load(open(os.path.join(os.path.dirname(__file__), "screens.json")))
SIZE, LABEL, PAD = 300, 190, 14


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def font(size, bold=False):
    path = f"/usr/share/fonts/truetype/dejavu/DejaVuSans{'-Bold' if bold else ''}.ttf"
    return ImageFont.truetype(path, size) if os.path.exists(path) else ImageFont.load_default()


def round_watch(path):
    """The screenshot cut to the round display, as on the watch; a grey disc if it is missing."""
    disc = Image.new("RGB", (SIZE, SIZE), (30, 30, 30))
    mask = Image.new("L", (SIZE * 4, SIZE * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, SIZE * 4 - 1, SIZE * 4 - 1), fill=255)
    mask = mask.resize((SIZE, SIZE), Image.LANCZOS)
    shot = Image.open(path).convert("RGB").resize((SIZE, SIZE), Image.LANCZOS) if os.path.exists(path) \
        else Image.new("RGB", (SIZE, SIZE), (90, 40, 40))
    disc.paste(shot, (0, 0), mask)
    return disc


os.makedirs(OUT, exist_ok=True)
displays = CONFIG["displays"]
made = 0
for screen in CONFIG["screens"]:
    # Only screens taken in this run (a /screens run may cover just some groups).
    if not any(os.path.exists(os.path.join(AFTER, slug(d["name"]), slug(screen["name"]) + ".png")) for d in CONFIG["displays"]):
        continue
    made += 1
    width = LABEL + 2 * (SIZE + PAD) + PAD
    height = 64 + len(displays) * (SIZE + PAD) + PAD
    sheet = Image.new("RGB", (width, height), (30, 30, 30))
    draw = ImageDraw.Draw(sheet)
    draw.text((PAD, 8), screen["name"], fill=(255, 255, 255), font=font(24, bold=True))
    for col, title in enumerate(("before", "after")):
        draw.text((LABEL + PAD + col * (SIZE + PAD) + SIZE // 2 - 40, 36), title, fill=(255, 220, 0), font=font(22, bold=True))
    for row, display in enumerate(displays):
        y = 64 + row * (SIZE + PAD)
        draw.multiline_text((PAD, y + SIZE // 2 - 12), display["name"].replace(", ", "\n"), fill=(230, 230, 230), font=font(20))
        for col, base in enumerate((BEFORE, AFTER)):
            sheet.paste(round_watch(os.path.join(base, slug(display["name"]), slug(screen["name"]) + ".png")),
                        (LABEL + PAD + col * (SIZE + PAD), y))
    sheet.save(os.path.join(OUT, slug(screen["name"]) + ".jpg"), quality=85)
print("collages:", made)
