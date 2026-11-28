#!/usr/bin/env python3
"""PR comment with the before/after collages: new collages replace their screen's block, the others stay.

Usage: screens_comment.py <existing comment file or ""> <collage dir> <image base URL> <short sha> > comment.md
The collage dir contains <screen>.jpg and changes.json from collage.py. Changed screens are listed at the
top and shown expanded; unchanged ones are collapsed.
"""
import json
import os
import re
import sys

MARKER = "<!-- wristcards-screens -->"
EXISTING, COLLAGES, BASE, SHA = sys.argv[1:5]
CONFIG = json.load(open(os.path.join(os.path.dirname(__file__), "screens.json")))
SCREENS = CONFIG["screens"]
GROUPS = sorted({s["group"] for s in SCREENS}, key=[s["group"] for s in SCREENS].index)


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


old = open(EXISTING).read() if EXISTING and os.path.exists(EXISTING) else ""
# key -> (changed sizes or None for "no change", block text)
blocks = {}
for key, sizes, body in re.findall(r"<!-- screen:([a-z0-9-]+) changed:(.*?) -->\n(.*?)<!-- /screen -->", old, re.S):
    blocks[key] = (sizes.split("|") if sizes else [], body)
changes = json.load(open(os.path.join(COLLAGES, "changes.json"))) if os.path.exists(os.path.join(COLLAGES, "changes.json")) else {}
for screen in SCREENS:
    key = slug(screen["name"])
    if key not in changes:
        continue
    sizes = changes[key]
    note = f"changed on {', '.join(sizes)}" if sizes else "no change"
    body = (f"<details{' open' if sizes else ''}><summary>{screen['name']} — {note} ({SHA})</summary>\n\n"
            f"![{screen['name']}]({BASE}/{key}.jpg)\n\n</details>\n")
    blocks[key] = (sizes, body)

names = {slug(s["name"]): s["name"] for s in SCREENS}
changed = [k for k in names if k in blocks and blocks[k][0]]
print(MARKER)
print("### Screenshots before / after")
if changed:
    print(f"**{len(changed)} of {len([k for k in names if k in blocks])} screens changed:** "
          + ", ".join(f"{names[k]} ({', '.join(blocks[k][0])})" for k in changed))
else:
    print("**No visible changes** on any of the screens taken.")
print()
print("Watch sizes: " + ", ".join(d["name"] for d in CONFIG["displays"]) + ". `/screens` takes all screens, "
      "`/screens <group or screen> …` only those (groups: " + ", ".join(GROUPS) + "). Changed areas are red in the "
      "third column; the commit in each title shows when that screen was last taken.")
print()
for group in GROUPS:
    keys = [slug(s["name"]) for s in SCREENS if s["group"] == group and slug(s["name"]) in blocks]
    if not keys:
        continue
    print(f"**{group}**")
    print()
    for key in keys:
        sizes, body = blocks[key]
        print(f"<!-- screen:{key} changed:{'|'.join(sizes)} -->\n{body}<!-- /screen -->")
    print()
