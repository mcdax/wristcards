#!/usr/bin/env python3
"""Before/after collages from two screens.py runs, with automatic change detection.

Usage: collage.py <before dir> <after dir> <output dir>   (needs Pillow)
Writes one <screen>.jpg per screen (rows = watch sizes; columns = before, after, changes) and changes.json:
{"<screen>": ["<size>", ...]} with the watch sizes on which the screen looks different.
"""
import json
import os
import re
import sys

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

BEFORE, AFTER, OUT = sys.argv[1:4]
CONFIG = json.load(open(os.path.join(os.path.dirname(__file__), "screens.json")))
SIZE, LABEL, PAD = 300, 190, 14
# A pixel counts as changed if a colour channel differs by more than this; a size counts as changed if more
# than this share of the round display changed. Both keep anti-aliasing noise out.
PIXEL_THRESHOLD, AREA_THRESHOLD = 40, 0.0015


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def font(size, bold=False):
    path = f"/usr/share/fonts/truetype/dejavu/DejaVuSans{'-Bold' if bold else ''}.ttf"
    return ImageFont.truetype(path, size) if os.path.exists(path) else ImageFont.load_default()


def disc_mask(size):
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    return mask.resize((size, size), Image.LANCZOS)


def load(path):
    return Image.open(path).convert("RGB") if os.path.exists(path) else None


def round_watch(image):
    """The screenshot cut to the round display, as on the watch; a dark red disc if it is missing."""
    disc = Image.new("RGB", (SIZE, SIZE), (30, 30, 30))
    shot = image.resize((SIZE, SIZE), Image.LANCZOS) if image else Image.new("RGB", (SIZE, SIZE), (90, 40, 40))
    disc.paste(shot, (0, 0), disc_mask(SIZE))
    return disc


def changes(before, after):
    """(changed?, mask of changed pixels) for two screenshots, only inside the round display."""
    if before is None or after is None:
        return before is not after, None
    if before.size != after.size:
        after = after.resize(before.size)
    diff = ImageChops.difference(before, after).convert("L").point(lambda v: 255 if v > PIXEL_THRESHOLD else 0)
    diff = ImageChops.multiply(diff, disc_mask(before.size[0]).point(lambda v: 255 if v > 128 else 0))
    share = sum(diff.histogram()[255:]) / (before.size[0] * before.size[1] * 0.785)
    return share > AREA_THRESHOLD, diff


def highlight(after, mask):
    """The new screenshot, dimmed, with the changed areas in red."""
    base = Image.blend(after, Image.new("RGB", after.size, (0, 0, 0)), 0.55)
    if mask is not None:
        grown = mask.filter(ImageFilter.MaxFilter(9))
        base.paste(Image.new("RGB", after.size, (235, 60, 60)), (0, 0), grown.point(lambda v: 150 if v else 0))
    return base


os.makedirs(OUT, exist_ok=True)
displays = CONFIG["displays"]
changed = {}
for screen in CONFIG["screens"]:
    key = slug(screen["name"])
    # Only screens taken in this run (a /screens run may cover just some groups).
    if not any(os.path.exists(os.path.join(AFTER, slug(d["name"]), key + ".png")) for d in displays):
        continue
    width = LABEL + 3 * (SIZE + PAD) + PAD
    height = 64 + len(displays) * (SIZE + PAD) + PAD
    sheet = Image.new("RGB", (width, height), (30, 30, 30))
    draw = ImageDraw.Draw(sheet)
    draw.text((PAD, 8), screen["name"], fill=(255, 255, 255), font=font(24, bold=True))
    for col, title in enumerate(("before", "after", "changes")):
        draw.text((LABEL + PAD + col * (SIZE + PAD) + SIZE // 2 - 45, 36), title, fill=(255, 220, 0), font=font(22, bold=True))
    changed[key] = []
    for row, display in enumerate(displays):
        y = 64 + row * (SIZE + PAD)
        before = load(os.path.join(BEFORE, slug(display["name"]), key + ".png"))
        after = load(os.path.join(AFTER, slug(display["name"]), key + ".png"))
        is_changed, mask = changes(before, after)
        if is_changed:
            changed[key].append(display["name"])
        label = display["name"].replace(", ", "\n") + ("\nchanged" if is_changed else "")
        draw.multiline_text((PAD, y + SIZE // 2 - 20), label, fill=(255, 110, 110) if is_changed else (230, 230, 230), font=font(20))
        sheet.paste(round_watch(before), (LABEL + PAD, y))
        sheet.paste(round_watch(after), (LABEL + PAD + SIZE + PAD, y))
        third = round_watch(highlight(after, mask) if (is_changed and after is not None) else
                            (Image.blend(after, Image.new("RGB", after.size), 0.55) if after else None))
        sheet.paste(third, (LABEL + PAD + 2 * (SIZE + PAD), y))
        if not is_changed:
            draw.text((LABEL + PAD + 2 * (SIZE + PAD) + SIZE // 2 - 55, y + SIZE // 2 - 12), "no change", fill=(200, 200, 200), font=font(20))
    sheet.save(os.path.join(OUT, key + ".jpg"), quality=85)
json.dump(changed, open(os.path.join(OUT, "changes.json"), "w"), indent=1)
print("collages:", len(changed), "changed:", sum(1 for v in changed.values() if v))
