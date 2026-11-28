#!/usr/bin/env python3
"""One tidy artifact per /screens run, sorted by screen instead of by run step.

Usage: screens_bundle.py <shots dir with before/ and after/> <collage dir> <output dir>
Result:
  README.txt
  <group>/<screen>/collage.jpg            before/after side by side for all watch sizes
  <group>/<screen>/before/<size>.png      raw screenshots of the base branch
  <group>/<screen>/after/<size>.png       raw screenshots of the pull request
"""
import json
import os
import re
import shutil
import sys

SHOTS, COLLAGES, OUT = sys.argv[1:4]
CONFIG = json.load(open(os.path.join(os.path.dirname(__file__), "screens.json")))


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


count = 0
for screen in CONFIG["screens"]:
    key = slug(screen["name"])
    target = os.path.join(OUT, screen["group"], key)
    collage = os.path.join(COLLAGES, key + ".jpg")
    if not os.path.exists(collage):
        continue
    os.makedirs(target, exist_ok=True)
    shutil.copy(collage, os.path.join(target, "collage.jpg"))
    for side in ("before", "after"):
        for display in CONFIG["displays"]:
            src = os.path.join(SHOTS, side, slug(display["name"]), key + ".png")
            if os.path.exists(src):
                os.makedirs(os.path.join(target, side), exist_ok=True)
                shutil.copy(src, os.path.join(target, side, slug(display["name"]) + ".png"))
    count += 1
with open(os.path.join(OUT, "README.txt"), "w") as f:
    f.write("Screenshots before (base branch) and after (pull request), one folder per screen:\n"
            "  <group>/<screen>/collage.jpg          both versions side by side, all watch sizes\n"
            "  <group>/<screen>/before/<size>.png    raw screenshots of the base branch\n"
            "  <group>/<screen>/after/<size>.png     raw screenshots of the pull request\n"
            "Sizes: " + ", ".join(d["name"] for d in CONFIG["displays"]) + "\n")
print("screens bundled:", count)
