#!/usr/bin/env python3
"""PR comment with the before/after collages: new collages replace their screen's block, the others stay.

Usage: screens_comment.py <existing comment file or -> <collage dir> <image base URL> <short sha> > comment.md
"""
import json
import os
import re
import sys

MARKER = "<!-- wristcards-screens -->"
EXISTING, COLLAGES, BASE, SHA = sys.argv[1:5]
CONFIG = json.load(open(os.path.join(os.path.dirname(__file__), "screens.json")))


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


old = open(EXISTING).read() if EXISTING != "-" and os.path.exists(EXISTING) else ""
blocks = dict(re.findall(r"<!-- screen:([a-z0-9-]+) -->\n(.*?)<!-- /screen -->", old, re.S))
for screen in CONFIG["screens"]:
    key = slug(screen["name"])
    if os.path.exists(os.path.join(COLLAGES, key + ".jpg")):
        blocks[key] = (f"<details><summary>{screen['name']} ({SHA})</summary>\n\n"
                       f"![{screen['name']}]({BASE}/{key}.jpg)\n\n</details>\n")

print(MARKER)
print("### Screenshots before / after")
print("Watch sizes 454 / 396 / 384 / 320 px and 384 px with 130 % font. `/screens` takes all screens, "
      "`/screens <group or screen> …` only those (groups: "
      + ", ".join(sorted({s['group'] for s in CONFIG['screens']}, key=[s['group'] for s in CONFIG['screens']].index))
      + "). The commit in each title shows when that screen was last taken.")
print()
for group in sorted({s["group"] for s in CONFIG["screens"]}, key=[s["group"] for s in CONFIG["screens"]].index):
    keys = [slug(s["name"]) for s in CONFIG["screens"] if s["group"] == group and slug(s["name"]) in blocks]
    if not keys:
        continue
    print(f"**{group}**")
    print()
    for key in keys:
        print(f"<!-- screen:{key} -->\n{blocks[key]}<!-- /screen -->")
    print()
