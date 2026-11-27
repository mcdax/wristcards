#!/usr/bin/env python3
"""End-to-end swipe/gesture regression test against a running Wear OS emulator (454x454, debug build installed).

Usage: swipe_test.py <adb serial> <deck name> [quick]
Expects the test collection at /data/local/tmp/ci/collection.anki2; every case starts from a fresh copy of it.
The debug build logs each gesture decision under the tag AnkiBack ("result ..."), which is what we assert on.
"""
import collections
import re
import subprocess
import sys
import time

SERIAL, DECK = sys.argv[1], sys.argv[2]
QUICK = len(sys.argv) > 3 and sys.argv[3] == "quick"
PKG = "de.mcdax.wristcards"
W = 454
RUN_AS = f"run-as {PKG} sh -c"
PREFS = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>"
         "<string name=\"swipeLeft\">AGAIN</string><string name=\"swipeRight\">GOOD</string>"
         "<boolean name=\"swipeToRate\" value=\"true\" /><boolean name=\"syncOnOpen\" value=\"false\" />"
         "<boolean name=\"syncOnDeckExit\" value=\"false\" /></map>")
EXPECTED = {"back": "back", "good": "rate GOOD", "again": "rate AGAIN", "none": "none", "good+back": "rate GOOD+back",
            "reveal": "reveal", "again+none": "rate AGAIN", "hide": "hide", "good+hide": "rate GOOD+hide"}


def sh(cmd, timeout=40):
    return subprocess.run(["adb", "-s", SERIAL, "shell", cmd], stdin=subprocess.DEVNULL,
                          capture_output=True, text=True, timeout=timeout).stdout


subprocess.run(["adb", "-s", SERIAL, "shell", "cat > /data/local/tmp/settings.xml; chmod 644 /data/local/tmp/settings.xml"],
               input=PREFS, text=True, timeout=20, check=True)
sh(f"am force-stop {PKG}; {RUN_AS} 'mkdir -p shared_prefs files; cp /data/local/tmp/settings.xml shared_prefs/settings.xml';"
   " input keyevent WAKEUP; svc power stayon true")
print("Android", sh("getprop ro.build.version.release").strip(), "| deck", DECK, "| mode", "quick" if QUICK else "full", flush=True)


def setup(revealed):
    sh(f"am force-stop {PKG}; {RUN_AS} 'rm -f files/collection.anki2-wal files/collection.anki2-shm;"
       f" cp /data/local/tmp/ci/collection.anki2 files/'; input keyevent WAKEUP;"
       f" am start -W -n {PKG}/.MainActivity --es deck '{DECK}' >/dev/null")
    time.sleep(3.5)
    if revealed:
        sh("input tap 227 180")
        time.sleep(0.9)


results = collections.Counter()


def run(group, name, revealed, cmd, want):
    setup(revealed)
    sh("logcat -c")
    sh(cmd)
    time.sleep(1.5)
    log = re.findall(r"AnkiBack: (.*)", sh("logcat -d -s AnkiBack:D"))
    got = "+".join(line[7:] for line in log if line.startswith("result ")) or "none"
    ok = got == EXPECTED[want]
    detail = "" if ok else " | " + " | ".join(line for line in log if not line.startswith("result "))
    print("OK  " if ok else "FAIL", group, name, "| want", want, "| got", got + detail, flush=True)
    results[group, ok] += 1


EDGE, CENTER = "input swipe 5 200 265 200 150", "input swipe 230 200 445 200 150"
LEFT = "input swipe 400 200 120 200 {}"
SCROLL_UP = "input swipe 227 300 235 150 300"
# Second swipe right after a rating, sent in the same shell so that it surely falls into the rating lock.
SECOND = lambda x: (f"input motionevent DOWN {x} 200; input motionevent MOVE {x + 120} 200;"
                    f" input motionevent MOVE {x + 240} 200; input motionevent UP {x + 240} 200")

if QUICK:
    run("revealed", "edge", True, EDGE, "hide")
    run("revealed", "center", True, CENTER, "good")
    run("covered", "edge", False, EDGE, "back")
    run("covered", "center", False, CENTER, "reveal")
    run("left", "revealed", True, LEFT.format(150), "again")
    run("left", "covered", False, LEFT.format(150), "reveal")
    run("key", "back", True, "input keyevent BACK", "back")
    run("scroll", "up", True, SCROLL_UP, "none")
    run("double", "center+center", True, "input swipe 200 200 440 200 80; " + SECOND(200), "good")
else:
    for dur in (40, 80, 150, 300, 700):
        for x in (5, 45, 85, 97, 130, 230, 300):
            run("revealed", f"x={x} {dur}ms", True, f"input swipe {x} 200 {min(x + 260, 445)} 200 {dur}",
                "hide" if x < W * .2 else "good")
        for x in (5, 120, 230):
            run("covered", f"x={x} {dur}ms", False, f"input swipe {x} 200 {min(x + 260, 445)} 200 {dur}",
                "back" if x < W * .2 else "reveal")
        run("left", f"revealed {dur}ms", True, LEFT.format(dur), "again")
        run("left", f"covered {dur}ms", False, LEFT.format(dur), "reveal")
    for r in (True, False):
        side = "revealed" if r else "covered"
        run("key", side, r, "input keyevent BACK", "back")
        run("short", side, r, "input swipe 200 200 225 200 150", "none")
        run("scroll", side, r, SCROLL_UP, "none")
        run("pulled-back", side, r, "input motionevent DOWN 200 200; for x in 240 300 350 300 250 205;"
            " do input motionevent MOVE $x 200; done; input motionevent UP 205 200", "none")
    # Diagonal scrolling (thumb in an arc) must not count as a left swipe.
    for angle, cmd in (("30deg", "input swipe 330 330 260 150 300"), ("40deg", "input swipe 330 330 200 150 300"),
                       ("arc", "input motionevent DOWN 330 340; for p in '320 300' '305 260' '290 220' '270 190'"
                               " '245 160' '215 140'; do input motionevent MOVE $p; done; input motionevent UP 215 140")):
        for r in (True, False):
            run("diagonal-scroll", f"{angle} {'revealed' if r else 'covered'}", r, cmd, "none")
    run("diagonal", "20deg", True, "input swipe 200 150 420 230 200", "good")
    run("double", "center+center", True, "input swipe 200 200 440 200 80; " + SECOND(200), "good")
    run("double", "center+edge", True, "input swipe 200 200 440 200 80; " + SECOND(5), "good")
    run("double", "left+left", True, "input swipe 400 200 120 200 80; input motionevent DOWN 400 200;"
        " input motionevent MOVE 280 200; input motionevent MOVE 120 200; input motionevent UP 120 200", "again+none")
    run("double", "center, pause, edge", True, "input swipe 200 200 440 200 80; sleep 1.2; input swipe 5 200 440 200 150",
        "good+back")

failed = sum(v for (_, ok), v in results.items() if not ok)
print(f"SUMMARY {sum(results.values()) - failed} passed, {failed} failed")
sys.exit(1 if failed else 0)
