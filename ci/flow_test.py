#!/usr/bin/env python3
"""End-to-end flow test: sync with a real Anki sync server, card rendering, rating, undo and a sync conflict.

Usage: flow_test.py <adb serial> <server endpoint> <user:password> <demo collection.anki2>
Needs the anki Python package (server-side checks act as a second Anki client) and a running sync server
(`python -m anki.syncserver`) that the debug build reaches with its built-in account (local.properties).
The app's own collection is read by pulling it from the device and querying it with sqlite3.
Checks use the UI tree (uiautomator dump), never screenshots: shown texts and image descriptions.
"""
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

from anki.collection import Collection
from anki.sync_pb2 import SyncCollectionResponse

SERIAL, ENDPOINT, CREDENTIALS, DEMO = sys.argv[1:5]
USER, PASSWORD = CREDENTIALS.split(":", 1)
PKG = "de.mcdax.wristcards"
TMP = tempfile.mkdtemp(prefix="flow-")
# Real answers only; the demo collection also has manual rescheduling entries (type 4).
REVIEWS = "type between 0 and 3"
passed, failed = 0, 0


class Failure(Exception):
    pass


def adb(*args, timeout=60, binary=False):
    r = subprocess.run(["adb", "-s", SERIAL, *args], stdin=subprocess.DEVNULL, capture_output=True,
                       text=not binary, timeout=timeout)
    if r.returncode != 0:
        raise Failure(f"adb {' '.join(args)!r} failed: {r.stderr!r}")
    return r.stdout


def sh(cmd, timeout=60):
    return adb("shell", cmd, timeout=timeout)


def ui():
    """Visible nodes as (text, content description, center)."""
    sh("uiautomator dump /sdcard/flow.xml >/dev/null")
    nodes = []
    for n in ET.fromstring(sh("cat /sdcard/flow.xml")).iter("node"):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds", "[0,0][0,0]")))
        nodes.append((n.get("text", ""), n.get("content-desc", ""), ((x1 + x2) // 2, (y1 + y2) // 2)))
    return nodes


def wait_for(pattern, timeout=30, desc=False):
    """First node whose text (or content description) matches; fails with the visible texts otherwise."""
    end = time.time() + timeout
    seen = []
    while time.time() < end:
        seen = ui()
        for node in seen:
            if re.search(pattern, node[1] if desc else node[0]):
                return node
        time.sleep(1)
    raise Failure(f"{pattern!r} not shown; visible: {[n[0] or n[1] for n in seen if n[0] or n[1]]}")


def tap(node):
    sh(f"input tap {node[2][0]} {node[2][1]}")


def start(deck=None):
    sh(f"am force-stop {PKG}")
    sh(f"am start -W -n {PKG}/.MainActivity" + (f" --es deck '{deck}'" if deck else "") + " >/dev/null")


def device_revlog():
    """(count, last ease) of the review log in the app's collection; the app is stopped first."""
    sh(f"am force-stop {PKG}")
    local = os.path.join(TMP, "device.anki2")
    for suffix in ("", "-wal"):
        path = local + suffix
        if os.path.exists(path):
            os.remove(path)
        try:
            data = adb("exec-out", "run-as", PKG, "cat", f"files/collection.anki2{suffix}", binary=True)
        except Failure:
            continue
        if data:
            open(path, "wb").write(data)
    db = sqlite3.connect(local)
    count, ease = db.execute(f"select count(*), (select ease from revlog where {REVIEWS} order by id desc limit 1)"
                             f" from revlog where {REVIEWS}").fetchone()
    db.close()
    return count, ease


def server_client():
    """Fresh second client with the server's collection (full download)."""
    path = os.path.join(TMP, f"client-{time.time_ns()}.anki2")
    col = Collection(path)
    auth = col.sync_login(username=USER, password=PASSWORD, endpoint=ENDPOINT)
    r = col.sync_collection(auth, sync_media=False)
    if r.required != SyncCollectionResponse.NO_CHANGES:
        col.close_for_full_sync()
        col.full_upload_or_download(auth=auth, server_usn=r.server_media_usn, upload=False)
        col.reopen(after_full_sync=True)
    return col, auth


def server_revlog_count():
    col, _ = server_client()
    count = col.db.scalar(f"select count() from revlog where {REVIEWS}")
    col.close()
    return count


def step(name, fn):
    global passed, failed
    t = time.time()
    try:
        fn()
        print(f"OK   {name} ({time.time() - t:.0f}s)", flush=True)
        passed += 1
    except Exception as e:  # noqa: BLE001 - every failure is reported and the next step still runs
        print(f"FAIL {name}: {e}", flush=True)
        failed += 1
        try:  # screenshot of the failing state for the artifact, for humans only
            open(f"flow-fail-{passed + failed}.png", "wb").write(adb("exec-out", "screencap", "-p", binary=True))
        except Exception:  # noqa: BLE001
            pass


def upload_demo():
    path = os.path.join(TMP, "upload.anki2")
    shutil.copy(DEMO, path)
    shutil.copytree(DEMO.replace(".anki2", ".media"), path.replace(".anki2", ".media"), dirs_exist_ok=True)
    col = Collection(path)
    auth = col.sync_login(username=USER, password=PASSWORD, endpoint=ENDPOINT)
    r = col.sync_collection(auth, sync_media=False)
    col.close_for_full_sync()
    col.full_upload_or_download(auth=auth, server_usn=r.server_media_usn, upload=True)
    col.reopen(after_full_sync=True)
    col.sync_media(auth)
    while col.media_sync_status().active:
        time.sleep(0.5)
    col.close()


def first_sync():
    sh(f"pm clear {PKG} >/dev/null")
    sh(f"cmd locale set-app-locales {PKG} --locales en-US")
    start()
    wait_for(r"Languages", timeout=120)
    wait_for(r"Synced", timeout=60)


def cloze():
    start("Biology")
    wait_for(r"\[(\.\.\.|…)\]")
    sh("input tap 227 180")
    wait_for(r"mitochondria|photosynthesis|guanine|four")


def lists():
    start("Physics")
    text = wait_for(r"inertia")[0]
    # Inline "A · B · C" lists become bullet paragraphs (the UI tree drops paragraph breaks, so check the items).
    if not all(f"• {item}" in text for item in ("inertia", "acceleration", "action and reaction")) or "·" in text:
        raise Failure(f"list not turned into bullets: {text!r}")


def image():
    start("Flags")
    wait_for(r"^Image$", timeout=60, desc=True)


def rate_and_sync():
    start("Spanish")
    wait_for(r".")
    sh("input tap 227 180")
    tap(wait_for(r"^Good"))
    time.sleep(1.5)
    sh("input keyevent BACK")  # back to the deck list syncs
    wait_for(r"Languages")
    count, ease = device_revlog()
    if (count, ease) != (1, 3):
        raise Failure(f"device review log {count} entries, last ease {ease}; want 1 entry with ease 3 (Good)")
    end = time.time() + 60
    while (server := server_revlog_count()) != 1:
        if time.time() > end:
            raise Failure(f"server review log has {server} entries, want 1")
        time.sleep(3)


def undo():
    start("Spanish")
    wait_for(r".")
    sh("input tap 227 180")
    tap(wait_for(r"^Good"))
    tap(wait_for(r"^Undo$", timeout=5, desc=True))
    time.sleep(1.5)
    count, _ = device_revlog()
    if count != 1:
        raise Failure(f"device review log has {count} entries after undo, want 1")


def conflict():
    # Local change that is not synced yet: rate, then stop the app before it leaves the deck.
    start("Spanish")
    wait_for(r".")
    sh("input tap 227 180")
    tap(wait_for(r"^Good"))
    time.sleep(1.5)
    sh(f"am force-stop {PKG}")
    # Meanwhile another client changes the schema on the server: now only a full sync is possible.
    col, auth = server_client()
    col.set_schema_modified()
    r = col.sync_collection(auth, sync_media=False)
    col.close_for_full_sync()
    col.full_upload_or_download(auth=auth, server_usn=r.server_media_usn, upload=True)
    col.reopen(after_full_sync=True)
    col.close()
    start()
    wait_for(r"can't be merged|can.t be merged", timeout=90)
    tap(wait_for(r"^Use server version$"))
    wait_for(r"Synced", timeout=90)
    count, _ = device_revlog()
    if count != 1:
        raise Failure(f"after using the server version the device has {count} reviews, want the server's 1")


sh("cmd wifi set-wifi-enabled enabled >/dev/null 2>&1; cmd wifi connect-network AndroidWifi open >/dev/null 2>&1; true")
print("Android", sh("getprop ro.build.version.release").strip(), "| server", ENDPOINT, flush=True)
step("upload demo collection to the server", upload_demo)
step("first start downloads the collection", first_sync)
step("cloze shows a gap, then the answer", cloze)
step("inline lists are stacked", lists)
step("image arrives via media sync and is shown", image)
step("rating lands in the collection and on the server", rate_and_sync)
step("undo removes the rating", undo)
step("conflict asks which version wins", conflict)
shutil.rmtree(TMP, ignore_errors=True)
print(f"SUMMARY {passed} passed, {failed} failed")
sys.exit(1 if failed else 0)
