#!/usr/bin/env python3
"""Sample the Any Listen MediaSession for a lock-screen endurance check.

Does not print media URLs. Writes JSONL under captures/private/.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "io.github.nutea.anylisten.debug"
STATE_RE = re.compile(r"state=PlaybackState \{state=([A-Z]+)\((\d+)\)")
DESC_RE = re.compile(r"description=([^,\n]+), ([^,\n]+),")


def adb() -> str:
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidate = Path(sdk) / "platform-tools" / "adb.exe"
        if candidate.is_file():
            return str(candidate)
        candidate = Path(sdk) / "platform-tools" / "adb"
        if candidate.is_file():
            return str(candidate)
    return "adb"


def run(args: list[str]) -> str:
    return subprocess.check_output([adb(), *args], text=True, encoding="utf-8", errors="replace")


def snapshot() -> dict[str, str | int | None]:
    dump = run(["shell", "dumpsys", "media_session"])
    block = ""
    parts = dump.split("package=")
    for part in parts:
        if part.startswith(PACKAGE):
            block = part
            break
    state_name = None
    state_code = None
    title = None
    artist = None
    match = STATE_RE.search(block)
    if match:
        state_name, state_code = match.group(1), int(match.group(2))
    desc = DESC_RE.search(block)
    if desc:
        title, artist = desc.group(1).strip(), desc.group(2).strip()
    return {
        "ts": datetime.now(timezone.utc).isoformat(),
        "state": state_name,
        "state_code": state_code,
        "title": title,
        "artist": artist,
        "media_button": PACKAGE in dump and "Media button session is" in dump,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--minutes", type=int, default=30)
    parser.add_argument("--interval", type=int, default=30)
    parser.add_argument("--skip-at", default="600,1200", help="seconds to dispatch next, comma-separated")
    args = parser.parse_args()
    out_dir = ROOT / "captures" / "private"
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / "playback_watch.jsonl"
    skips = {int(item) for item in args.skip_at.split(",") if item.strip()}
    deadline = time.time() + args.minutes * 60
    started = time.time()
    titles: list[str] = []
    print(f"watching {PACKAGE} for {args.minutes}m -> {out}")
    while time.time() < deadline:
        elapsed = int(time.time() - started)
        if elapsed in skips or any(elapsed >= mark and elapsed < mark + args.interval for mark in skips):
            if elapsed in skips:
                run(["shell", "cmd", "media_session", "dispatch", "next"])
                print(f"t={elapsed}s dispatched next")
        sample = snapshot()
        title = sample.get("title")
        if isinstance(title, str) and title and (not titles or titles[-1] != title):
            titles.append(title)
        with out.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(sample, ensure_ascii=False) + "\n")
        print(f"t={elapsed}s state={sample.get('state')} title={title} songs={len(titles)}")
        time.sleep(args.interval)
    summary = {
        "minutes": args.minutes,
        "distinct_titles": titles,
        "song_count": len(titles),
        "ended": datetime.now(timezone.utc).isoformat(),
    }
    (out_dir / "playback_watch_summary.json").write_text(
        json.dumps(summary, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"done songs={len(titles)}")
    return 0 if len(titles) >= 3 else 2


if __name__ == "__main__":
    raise SystemExit(main())
