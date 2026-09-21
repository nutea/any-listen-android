#!/usr/bin/env python3
"""Opt-in playback acceptance against .env on the project's dedicated emulator."""
import argparse
from datetime import datetime
import os
import re
import subprocess
import time

from emulator import AVD, PACKAGE, ROOT, environment, executable


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=5580)
    parser.add_argument("--phase", choices=("online", "offline", "all"), default="all")
    args = parser.parse_args()
    sdk = environment()
    adb = str(sdk / "platform-tools" / executable("adb"))
    serial = f"emulator-{args.port}"
    out = ROOT / "captures/private" / ("real-playback-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    out.mkdir(parents=True, exist_ok=True)
    os.chmod(out, 0o700)

    def device(*command, input=None, timeout=60):
        result = subprocess.run([adb, "-s", serial, *command], input=input,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        if result.returncode:
            # Device output can contain private server details; retain locally only.
            (out / "device-error.txt").write_bytes(result.stdout + result.stderr)
            raise RuntimeError(f"Device command failed; inspect {out / 'device-error.txt'}")
        return result.stdout

    if device("emu", "avd", "name").decode().splitlines()[0].strip() != AVD:
        raise RuntimeError("Refusing to run against a different AVD")

    def instrument(phase, method):
        result = device("shell", "am", "instrument", "-w", "-r", "-e", "real_server", phase,
                        "-e", "class", f"io.github.nutea.anylisten.RealServerPlaybackTest#{method}",
                        PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner", timeout=1200)
        (out / f"{phase}-instrumentation.txt").write_bytes(result)
        if not re.search(rb"OK \(1 test\)", result) or b"FAILURES!!!" in result:
            raise RuntimeError(f"{phase} failed; inspect private instrumentation output in {out}")
        print(f"PASS {phase}", flush=True)

    def capture():
        summary = device("shell", "run-as", PACKAGE, "cat", "files/real-server-results.txt")
        (out / "results.txt").write_bytes(summary)
        print(summary.decode(), flush=True)
        (out / "screen.png").write_bytes(device("exec-out", "screencap", "-p"))
        (out / "logcat.txt").write_bytes(device("logcat", "-d", "-t", "3000"))
        (out / "media-session.txt").write_bytes(device("shell", "dumpsys", "media_session"))

    if args.phase in ("online", "all"):
        # The test reuses an existing authenticated session, if present.
        values = {}
        for raw in (ROOT / ".env").read_text().splitlines():
            line = raw.strip()
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                values[key.strip()] = value.strip().strip('"').strip("'")
        for key in ("ANYLISTEN_BASE_URL", "ANYLISTEN_PASSWORD"):
            if not values.get(key):
                raise RuntimeError(f"Missing {key} in .env")
        payload = "\n".join(f"{key}={values[key]}" for key in ("ANYLISTEN_BASE_URL", "ANYLISTEN_PASSWORD"))
        device("shell", "run-as", PACKAGE, "sh", "-c", "'umask 077; cat > files/real-server.env'", input=payload.encode())
        print("Running online playback acceptance (includes one full track with screen asleep)…", flush=True)
        try:
            instrument("online", "onlinePlaybackAndDownload")
        finally:
            device("shell", "run-as", PACKAGE, "rm", "-f", "files/real-server.env")
    if args.phase in ("offline", "all"):
        wifi = device("shell", "settings", "get", "global", "wifi_on").strip()
        data = device("shell", "settings", "get", "global", "mobile_data").strip()
        try:
            device("shell", "am", "force-stop", PACKAGE)
            device("shell", "svc", "wifi", "disable")
            device("shell", "svc", "data", "disable")
            time.sleep(3)
            instrument("offline", "downloadedTrackPlaysWithNetworkDisabled")
        finally:
            device("shell", "svc", "wifi", "enable" if wifi == b"1" else "disable")
            device("shell", "svc", "data", "enable" if data == b"1" else "disable")
    device("shell", "am", "start", "-n", PACKAGE + "/io.github.nutea.anylisten.MainActivity")
    capture()
    print(f"Private evidence: {out}")


if __name__ == "__main__":
    main()
