#!/usr/bin/env python3
"""Which screens a /screens run takes: all, or the groups and screen names given after the command.

Usage: screens_plan.py "<comment or selection>"   e.g. "/screens study image"
Prints a JSON matrix for GitHub Actions: [{"group": "study", "screens": "question,answer,..."}, ...]
Unknown words are reported on stderr and make the run fail, so typos do not silently take everything.
"""
import json
import os
import sys

CONFIG = json.load(open(os.path.join(os.path.dirname(__file__), "screens.json")))
words = [w.lower() for w in sys.argv[1].replace("/screens", " ").split()]
screens = CONFIG["screens"]
groups = sorted({s["group"] for s in screens}, key=[s["group"] for s in screens].index)
names = {s["name"].lower().replace(" ", "-"): s["name"] for s in screens}

chosen = set()
unknown = []
for w in words:
    if w in groups:
        chosen |= {s["name"] for s in screens if s["group"] == w}
    elif w.replace(" ", "-") in names:
        chosen.add(names[w.replace(" ", "-")])
    else:
        unknown.append(w)
if unknown:
    print(f"Unknown screens or groups: {', '.join(unknown)}. Groups: {', '.join(groups)}; "
          f"screens: {', '.join(names)}", file=sys.stderr)
    sys.exit(1)
if not chosen:
    chosen = {s["name"] for s in screens}
matrix = [{"group": g, "screens": ",".join(s["name"] for s in screens if s["group"] == g and s["name"] in chosen)}
          for g in groups]
print(json.dumps([m for m in matrix if m["screens"]]))
