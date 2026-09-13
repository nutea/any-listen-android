#!/usr/bin/env python3
"""Windows Android emulator automation. Uses only Python's standard library."""
import argparse
from datetime import datetime
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
AVD = "AnyListen_API_36"
IMAGE = "system-images;android-36;google_apis;x86_64"
PACKAGE = "io.github.nutea.anylisten.debug"


def run(args, *, capture=False, timeout=900, check=True, input=None):
    result = subprocess.run([str(a) for a in args], cwd=ROOT, input=input,
                            capture_output=capture, text=True, encoding="utf-8",
                            errors="replace", timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {args[0]}\n"
                           f"{result.stdout or ''}{result.stderr or ''}")
    return result


def environment():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    local = ROOT / "local.properties"
    if not sdk and local.exists():
        match = re.search(r"^sdk\.dir=(.+)$", local.read_text(), re.M)
        if match:
            sdk = match[1].strip().replace("\\:", ":").replace("\\\\", "\\")
    sdk = Path(sdk or str(Path.home() / "AppData/Local/Android/Sdk"))
    candidates = [os.environ.get("ANYLISTEN_JAVA_HOME"), os.environ.get("JAVA_HOME"),
                  "D:/DEV/Java/jdk-17", "C:/Program Files/Android/Android Studio/jbr"]
    java_on_path = shutil.which("java")
    if java_on_path:
        candidates.append(str(Path(java_on_path).parent.parent))
    for candidate in filter(None, candidates):
        java = Path(candidate) / "bin/java.exe"
        if not java.exists():
            continue
        version = run([java, "-version"], capture=True, check=False)
        match = re.search(r'version "(\d+)', version.stderr + version.stdout)
        if match and int(match[1]) >= 17:
            os.environ["JAVA_HOME"] = str(candidate)
            os.environ["PATH"] = str(java.parent) + os.pathsep + os.environ["PATH"]
            break
    else:
        raise RuntimeError("JDK 17+ required; set ANYLISTEN_JAVA_HOME.")
    os.environ["ANDROID_HOME"] = str(sdk)
    for relative in ["platform-tools/adb.exe", "emulator/emulator.exe"]:
        if not (sdk / relative).exists():
            raise RuntimeError(f"Missing SDK tool: {sdk / relative}")
    return sdk


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["setup", "start", "run", "test", "capture", "stop"])
    parser.add_argument("--show", action="store_true", help="Show emulator window on startup")
    parser.add_argument("--port", type=int, default=5580)
    args = parser.parse_args()
    if args.port % 2 or not 5554 <= args.port <= 5682:
        parser.error("--port must be even and between 5554 and 5682")
    sdk = environment()
    adb = sdk / "platform-tools/adb.exe"
    emulator = sdk / "emulator/emulator.exe"
    serial = f"emulator-{args.port}"
    out = ROOT / "captures/private" / ("emulator-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f"))
    out.mkdir(parents=True, exist_ok=True)

    def device(*command, **kwargs):
        return run([adb, "-s", serial, *command], **kwargs)

    def verify_device():
        result = device("emu", "avd", "name", capture=True, timeout=15)
        if result.stdout.splitlines()[0].strip() != AVD:
            raise RuntimeError(f"{serial} is not {AVD}; choose another --port.")

    def capture():
        for name, command in {
            "logcat.txt": ("logcat", "-d", "-v", "threadtime", "-t", "3000"),
            "activity.txt": ("shell", "dumpsys", "activity", "activities"),
            "media-session.txt": ("shell", "dumpsys", "media_session"),
        }.items():
            result = device(*command, capture=True, check=False, timeout=30)
            (out / name).write_text(result.stdout + result.stderr, encoding="utf-8")
        with (out / "screen.png").open("wb") as image:
            subprocess.run([str(adb), "-s", serial, "exec-out", "screencap", "-p"],
                           stdout=image, check=True, timeout=30)

    if args.action == "setup":
        cli = sdk / "cmdline-tools/latest/bin"
        run([cli / "sdkmanager.bat", IMAGE], timeout=3600)
        names = run([emulator, "-list-avds"], capture=True).stdout.splitlines()
        if AVD not in names:
            run([cli / "avdmanager.bat", "create", "avd", "--name", AVD,
                 "--package", IMAGE, "--device", "pixel_7"], input="no\n")
        print(f"Ready: {AVD}")
        return

    run([adb, "start-server"], capture=True)
    connected = run([adb, "devices"], capture=True).stdout
    present = any(line.split() and line.split()[0] == serial for line in connected.splitlines())
    process = None
    if args.action in ("capture", "stop"):
        verify_device()
        if args.action == "stop":
            device("emu", "kill")
        else:
            capture()
            print(out)
        return

    if not present:
        run([emulator, "-accel-check"], capture=True)
        if AVD not in run([emulator, "-list-avds"], capture=True).stdout.splitlines():
            raise RuntimeError("AVD missing; run this script with setup first.")
        command = [str(emulator), "-avd", AVD, "-port", str(args.port), "-memory", "2048",
                   "-cores", "2", "-no-boot-anim", "-no-snapshot", "-gpu", "software",
                   "-camera-back", "none", "-camera-front", "none"]
        if not args.show:
            command.append("-no-window")
        with (out / "emulator.log").open("wb") as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
        print(f"Starting {AVD}, PID {process.pid}, logs: {out}", flush=True)
    deadline = time.monotonic() + 240
    while time.monotonic() < deadline:
        if process is not None and process.poll() is not None:
            raise RuntimeError(f"Emulator exited ({process.returncode}); inspect {out / 'emulator.log'}")
        result = device("shell", "getprop", "sys.boot_completed", capture=True, check=False, timeout=15)
        if result.returncode == 0 and result.stdout.strip() == "1":
            break
        time.sleep(2)
    else:
        raise RuntimeError(f"Emulator boot timed out; inspect {out}")
    verify_device()
    for setting in ["window_animation_scale", "transition_animation_scale", "animator_duration_scale"]:
        device("shell", "settings", "put", "global", setting, "0", capture=True)
    device("shell", "input", "keyevent", "82", capture=True)
    print(f"Ready: {serial} ({AVD})", flush=True)
    if args.action == "start":
        return

    tasks = [":app:assembleDebug"]
    if args.action == "test":
        tasks.append(":app:assembleDebugAndroidTest")
    run([ROOT / "gradlew.bat", *tasks, "--console=plain"], timeout=1800)
    device("install", "-r", "-g", ROOT / "app/build/outputs/apk/debug/app-debug.apk")
    result_summary = {"serial": serial, "avd": AVD, "action": args.action, "passed": False}
    try:
        if args.action == "test":
            device("install", "-r", ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk")
            result = device("shell", "am", "instrument", "-w", "-r",
                            PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner",
                            capture=True, timeout=600, check=False)
            (out / "instrumentation.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
            print(result.stdout)
            if result.returncode or not re.search(r"OK \([1-9]\d* tests?\)", result.stdout) or "FAILURES!!!" in result.stdout:
                raise RuntimeError("Instrumentation tests failed; inspect instrumentation.txt")
        launched = device("shell", "am", "start", "-W", "-n",
                          PACKAGE + "/io.github.nutea.anylisten.MainActivity", capture=True)
        if "Status: ok" not in launched.stdout:
            raise RuntimeError(f"App launch failed: {launched.stdout}")
        time.sleep(3)
        if not device("shell", "pidof", PACKAGE, capture=True, check=False).stdout.strip():
            raise RuntimeError("App exited after launch")
        result_summary["passed"] = True
    finally:
        (out / "result.json").write_text(json.dumps(result_summary, indent=2), encoding="utf-8")
        capture()
        print(f"Artifacts: {out}")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, subprocess.SubprocessError, OSError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
