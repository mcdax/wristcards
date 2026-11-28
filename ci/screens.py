#!/usr/bin/env python3
"""Screenshots of the screens in ci/screens.json on every display configuration, for the /screens PR collage.

Usage: screens.py <adb serial> <apk> <demo collection.anki2> <output dir> [screen names, comma-separated]
Installs the APK fresh, loads the demo collection (sync off) and stores <output>/<display>/<screen>.png.
Without names all screens are taken.
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL, APK, DEMO, OUT = sys.argv[1:5]
ONLY = set(filter(None, sys.argv[5].split(","))) if len(sys.argv) > 5 else set()
PKG = "de.mcdax.wristcards"
CONFIG = json.load(open(os.path.join(os.path.dirname(__file__), "screens.json")))
PREFS = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>"
         "<boolean name=\"syncOnOpen\" value=\"false\" /><boolean name=\"syncOnDeckExit\" value=\"false\" /></map>")
# State after "Sign out" (SyncCredentials.clear): the debug build's built-in account is not used either.
SIGNED_OUT = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><boolean name=\"signedOut\" value=\"true\" /></map>"


def adb(*args, binary=False, input_text=None):
    r = subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=not binary, timeout=120,
                       **({"input": input_text} if input_text is not None else {"stdin": subprocess.DEVNULL}))
    if r.returncode != 0:
        raise RuntimeError(f"adb {args!r}: {r.stderr!r}")
    return r.stdout


def sh(cmd):
    return adb("shell", cmd)


def slug(name):
    return re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")


def tap_text(pattern, size):
    """Taps the first node whose text matches; scrolls down a bit at a time until it shows up."""
    for _ in range(10):
        sh("uiautomator dump /sdcard/screens.xml >/dev/null")
        for n in ET.fromstring(sh("cat /sdcard/screens.xml")).iter("node"):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            # Only nodes well inside the round display, so the tap does not land on the edge.
            if re.search(pattern, n.get("text", "")) and size * 0.15 < (y1 + y2) // 2 < size * 0.85:
                sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
                return
        sh(f"input swipe {size // 2} {int(size * 0.7)} {size // 2} {int(size * 0.4)} 300")
        time.sleep(0.8)
    raise RuntimeError(f"no node with text {pattern!r}")


def run_step(step, size):
    kind, *a = step
    px = lambda f: int(f * size)  # noqa: E731
    if kind == "tap":
        sh(f"input tap {px(a[0])} {px(a[1])}")
    elif kind == "swipe":
        sh(f"input swipe {px(a[0])} {px(a[1])} {min(px(a[2]), size - 2)} {px(a[3])} {a[4]}")
    elif kind == "tap_text":
        tap_text(a[0], size)
    elif kind == "back":
        sh("input keyevent BACK")
    elif kind == "sleep":
        time.sleep(a[0])
        return
    else:
        raise ValueError(f"unknown step {kind}")
    time.sleep(1.2)


# Fresh install with the demo collection and English UI, so before and after see identical data.
subprocess.run(["adb", "-s", SERIAL, "uninstall", PKG], capture_output=True)
adb("install", "-r", APK)
adb("push", DEMO, "/data/local/tmp/screens.anki2")
adb("shell", "cat > /data/local/tmp/screens-prefs.xml", input_text=PREFS)
adb("shell", "cat > /data/local/tmp/screens-signed-out.xml", input_text=SIGNED_OUT)
sh("chmod 644 /data/local/tmp/screens.anki2 /data/local/tmp/screens-prefs.xml /data/local/tmp/screens-signed-out.xml")
sh(f"cmd locale set-app-locales {PKG} --locales en-US")
sh(f"run-as {PKG} sh -c 'mkdir -p files shared_prefs && cp /data/local/tmp/screens-prefs.xml shared_prefs/settings.xml'")
media = DEMO.replace(".anki2", ".media")
if os.path.isdir(media):
    adb("push", media + "/.", "/data/local/tmp/screens-media/")
    sh(f"chmod -R 755 /data/local/tmp/screens-media; run-as {PKG} sh -c 'mkdir -p files/collection.media && cp /data/local/tmp/screens-media/* files/collection.media/'")
# Keep the display fully on: otherwise it dims after a few seconds and the screenshots come out grey,
# and the first tap would only wake it instead of reaching the app.
sh("svc power stayon true; settings put system screen_off_timeout 1800000; input keyevent WAKEUP")
try:
    for display in CONFIG["displays"]:
        size = display["size"]
        sh(f"wm size {size}x{size}; wm density {display['density']}; settings put system font_scale {display['font']}")
        time.sleep(2)
        folder = os.path.join(OUT, slug(display["name"]))
        os.makedirs(folder, exist_ok=True)
        for screen in CONFIG["screens"]:
            if ONLY and screen["name"] not in ONLY:
                continue
            sh(f"am force-stop {PKG}; run-as {PKG} sh -c 'rm -f files/collection.anki2-wal files/collection.anki2-shm"
               f" shared_prefs/sync_auth.xml; cp /data/local/tmp/screens.anki2 files/collection.anki2'")
            if screen.get("signed_out"):
                sh(f"run-as {PKG} sh -c 'cp /data/local/tmp/screens-signed-out.xml shared_prefs/sync_auth.xml'")
            extras = (f" --es deck '{screen['deck']}'" if "deck" in screen else "") + " " + screen.get("extras", "")
            sh(f"input keyevent WAKEUP; am start -W -n {PKG}/.MainActivity{extras} >/dev/null")
            time.sleep(3.5)
            try:
                for step in screen["steps"]:
                    run_step(step, size)
            except Exception as e:  # noqa: BLE001 - the collage marks the screen as failed
                print(f"{display['name']} / {screen['name']}: {e}", flush=True)
            open(os.path.join(folder, slug(screen["name"]) + ".png"), "wb").write(adb("exec-out", "screencap", "-p", binary=True))
        print("done", display["name"], flush=True)
finally:
    sh("wm size reset; wm density reset; settings put system font_scale 1.0")
