#!/usr/bin/env python3
"""End-to-end gesture test against a running Wear OS emulator (454x454, debug build installed).

Usage: swipe_test.py <adb serial> <deck name>
Expects the test collection at /data/local/tmp/ci/collection.anki2; every case starts from a fresh copy of it.

Only what needs the real system is tested here: delivery of fast swipes, interception of the back gesture,
keys and the rating lock under real timing. The full grid of start points, angles and distances is covered
by the JVM test GesturesTest. The debug build logs each gesture decision under the tag AnkiBack
("result ..."); a case passes only if that matches and the app is still alive in the foreground.
"""
import re
import subprocess
import sys
import time

SERIAL, DECK = sys.argv[1], sys.argv[2]
PKG = "de.mcdax.wristcards"
RUN_AS = f"run-as {PKG} sh -c"
PREFS = ("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>"
         "<string name=\"swipeLeft\">AGAIN</string><string name=\"swipeRight\">GOOD</string>"
         "<boolean name=\"swipeToRate\" value=\"true\" /><boolean name=\"syncOnOpen\" value=\"false\" />"
         "<boolean name=\"syncOnDeckExit\" value=\"false\" /></map>")
EXPECTED = {"back": "back", "good": "rate GOOD", "again": "rate AGAIN", "none": "none", "good+back": "rate GOOD+back",
            "reveal": "reveal", "hide": "hide"}


class AdbError(Exception):
    pass


def sh(cmd, timeout=40):
    """Runs a shell command on the device; any failure aborts the whole test instead of passing silently."""
    r = subprocess.run(["adb", "-s", SERIAL, "shell", cmd], stdin=subprocess.DEVNULL,
                       capture_output=True, text=True, timeout=timeout)
    if r.returncode != 0:
        raise AdbError(f"adb shell {cmd!r} failed ({r.returncode}): {r.stderr.strip() or r.stdout.strip()}")
    return r.stdout


def wait_log(pattern, timeout=10.0):
    end = time.time() + timeout
    while time.time() < end:
        if re.search(pattern, sh("logcat -d -s AnkiResume:D")):
            return True
        time.sleep(0.3)
    return False


def app_alive():
    """App process running and its activity in the foreground (also true after 'back' to the deck list)."""
    pid = subprocess.run(["adb", "-s", SERIAL, "shell", f"pidof {PKG}"], capture_output=True, text=True).stdout.strip()
    top = sh("dumpsys activity activities | grep -i resumedactivity || true")
    return bool(pid) and PKG in top


SDK = int(sh("getprop ro.build.version.sdk").strip())
subprocess.run(["adb", "-s", SERIAL, "shell", "cat > /data/local/tmp/settings.xml; chmod 644 /data/local/tmp/settings.xml"],
               input=PREFS, text=True, timeout=20, check=True)
sh(f"am force-stop {PKG}; {RUN_AS} 'mkdir -p shared_prefs files; cp /data/local/tmp/settings.xml shared_prefs/settings.xml';"
   " input keyevent WAKEUP; svc power stayon true")
print("Android", sh("getprop ro.build.version.release").strip(), f"(SDK {SDK}) | deck", DECK, flush=True)


def setup(revealed):
    # A few tries: on emulators the system sometimes pushes the freshly started app into the background
    # (e.g. while it updates the Play Store on first boot); then the card never shows up.
    for attempt in range(3):
        sh("logcat -c")
        sh(f"am force-stop {PKG}; {RUN_AS} 'rm -f files/collection.anki2-wal files/collection.anki2-shm;"
           f" cp /data/local/tmp/ci/collection.anki2 files/'; input keyevent WAKEUP;"
           f" am start -W -n {PKG}/.MainActivity --es deck '{DECK}' >/dev/null")
        # Generous: the very first start after installing (dex optimization) can take a while on slow emulators.
        if wait_log(r"side ready .*revealed=false", timeout=30 if attempt == 0 else 15):
            break
        print(f"  (question side did not appear, restarting the app, try {attempt + 2})", flush=True)
    else:
        raise AdbError("question side did not appear")
    time.sleep(0.5)
    if revealed:
        sh("input tap 227 180")
        if not wait_log(r"side ready .*revealed=true"):
            raise AdbError("answer side did not appear")
        time.sleep(0.5)


passed, failed, skipped = 0, 0, 0


def run(name, revealed, cmd, want, min_sdk=0):
    global passed, failed, skipped
    if SDK < min_sdk:
        print("SKIP", name, f"(needs SDK {min_sdk})", flush=True)
        skipped += 1
        return
    setup(revealed)
    sh("logcat -c")
    sh(cmd)
    time.sleep(1.5)
    log = re.findall(r"AnkiBack: (.*)", sh("logcat -d -s AnkiBack:D"))
    got = "+".join(line[7:] for line in log if line.startswith("result ")) or "none"
    alive = app_alive()
    ok = got == EXPECTED[want] and alive
    detail = "" if ok else " | " + ("" if alive else "APP NOT ALIVE | ") + " | ".join(l for l in log if not l.startswith("result "))
    print("OK  " if ok else "FAIL", name, "| want", want, "| got", got + detail, flush=True)
    if ok:
        passed += 1
    else:
        failed += 1


def swipe(x0, y0, x1, y1, ms):
    return f"input swipe {x0} {y0} {x1} {y1} {ms}"


# Second swipe right after a rating, sent in the same shell so that it surely falls into the rating lock.
def second(x):
    return (f"input motionevent DOWN {x} 200; input motionevent MOVE {x + 120} 200;"
            f" input motionevent MOVE {x + 240} 200; input motionevent UP {x + 240} 200")


# Right swipe on a revealed answer: edge zone (< 20 % = 90.8 px) covers it, further inside rates.
run("revealed edge x=5 150ms", True, swipe(5, 200, 265, 200, 150), "hide")
# Wear OS 5 still uses window swipe-to-dismiss and swallows extremely fast edge swipes.
run("revealed edge x=5 40ms", True, swipe(5, 200, 265, 200, 40), "hide", min_sdk=36)
run("revealed edge zone x=85", True, swipe(85, 200, 345, 200, 150), "hide")
run("revealed just outside x=97", True, swipe(97, 200, 357, 200, 150), "good")
run("revealed center 40ms", True, swipe(230, 200, 445, 200, 40), "good")
run("revealed center 150ms", True, swipe(230, 200, 445, 200, 150), "good")
run("revealed center 700ms", True, swipe(230, 200, 445, 200, 700), "good")
# Right swipe on a hidden answer: edge = back, inside = reveal.
run("covered edge 40ms", False, swipe(5, 200, 265, 200, 40), "back")
run("covered edge 150ms", False, swipe(5, 200, 265, 200, 150), "back")
run("covered center 150ms", False, swipe(230, 200, 445, 200, 150), "reveal")
# Left swipe: rates a revealed answer, reveals a hidden one.
run("left revealed 40ms", True, swipe(400, 200, 120, 200, 40), "again")
run("left revealed 150ms", True, swipe(400, 200, 120, 200, 150), "again")
run("left covered 150ms", False, swipe(400, 200, 120, 200, 150), "reveal")
# Back key.
run("key back revealed", True, "input keyevent BACK", "back")
run("key back covered", False, "input keyevent BACK", "back")
# Must not trigger anything.
run("too short", True, swipe(200, 200, 225, 200, 150), "none")
run("scroll up", True, swipe(227, 300, 235, 150, 300), "none")
run("pulled back", True, "input motionevent DOWN 200 200; for x in 240 300 350 300 250 205;"
    " do input motionevent MOVE $x 200; done; input motionevent UP 205 200", "none")
run("thumb arc scroll", True, "input motionevent DOWN 330 340; for p in '320 300' '305 260' '290 220' '270 190'"
    " '245 160' '215 140'; do input motionevent MOVE $p; done; input motionevent UP 215 140", "none")
run("diagonal scroll 40deg", False, swipe(330, 330, 200, 150, 300), "none")
run("diagonal swipe 20deg", True, swipe(200, 150, 420, 230, 200), "good")
# Rating lock: a second swipe right after a rating belongs to it.
run("double center+center", True, swipe(200, 200, 440, 200, 80) + "; " + second(200), "good")
run("double center+edge", True, swipe(200, 200, 440, 200, 80) + "; " + second(5), "good")
run("double left+left", True, swipe(400, 200, 120, 200, 80) + "; input motionevent DOWN 400 200;"
    " input motionevent MOVE 280 200; input motionevent MOVE 120 200; input motionevent UP 120 200", "again")
run("center, pause, edge", True, swipe(200, 200, 440, 200, 80) + "; sleep 1.2; " + swipe(5, 200, 440, 200, 150), "good+back")

print(f"SUMMARY {passed} passed, {failed} failed, {skipped} skipped")
sys.exit(1 if failed else 0)
