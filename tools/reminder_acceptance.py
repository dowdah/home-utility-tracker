#!/usr/bin/env python3
"""Observe a real offline WorkManager notification in one isolated AVD package."""
import argparse
import json
import re
import shlex
import subprocess
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.dowdah.utilitytracker.acceptancereminder"


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout[-4000:] + result.stderr[-2000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use an explicit AVD")
    if not args.skip_build:
        run([str(ROOT / "utility-tracker/gradlew"), "-p", str(ROOT / "utility-tracker"), "-PacceptanceSuffix=.acceptancereminder", ":app:assembleAcceptance", ":app:assembleAcceptanceAndroidTest"], timeout=600)
    apk = ROOT / "utility-tracker/app/build/outputs/apk/acceptance/app-acceptance.apk"
    test = ROOT / "utility-tracker/app/build/outputs/apk/androidTest/acceptance/app-acceptance-androidTest.apk"
    if json.loads((apk.parent / "output-metadata.json").read_text())["applicationId"] != PACKAGE:
        raise RuntimeError("Wrong isolated APK namespace")
    adb = ["adb", "-s", args.serial]
    shell = lambda *parts: run(adb + ["shell", shlex.join(parts)])
    report_dir = Path(tempfile.mkdtemp(prefix="utility-v13-reminders-"))
    report = {"serial": args.serial, "completed": False, "steps": []}
    airplane = shell("cmd", "connectivity", "airplane-mode").strip()
    wifi = shell("settings", "get", "global", "wifi_on").strip()
    print(f"Report directory: {report_dir}", flush=True)
    def step(name):
        output = shell("am", "instrument", "-w", "-r", "-e", "class", "com.dowdah.utilitytracker.data.ReminderLiveAcceptanceTest", "-e", "reminderStep", name, PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
        (report_dir / (name + ".txt")).write_text(output)
        if "OK (1 test)" not in output:
            raise RuntimeError(output[-6000:])
        report["steps"].append(name)
        print(f"Passed: {name}", flush=True)
    try:
        run(adb + ["install", "-r", str(apk)])
        run(adb + ["install", "-r", "-t", str(test)])
        shell("pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        step("denied")
        shell("pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        shell("cmd", "connectivity", "airplane-mode", "enable")
        shell("svc", "wifi", "disable")
        step("seed")
        shell("am", "start", "-W", "-n", PACKAGE + "/com.dowdah.utilitytracker.MainActivity")
        time.sleep(1)
        (report_dir / "forecast-home.png").write_bytes(subprocess.check_output(adb + ["exec-out", "screencap", "-p"]))
        shell("input", "keyevent", "KEYCODE_HOME")
        start = time.monotonic()
        while time.monotonic() - start < 300:
            state = json.loads(shell("run-as", PACKAGE, "cat", "no_backup/balance-reminder-device.json"))
            if state.get("dates"):
                break
            time.sleep(5)
        else:
            raise RuntimeError("Background notification not observed within 5 minutes")
        report["background_wait_seconds"] = round(time.monotonic() - start, 1)
        print(f"Observed offline background notification after {report['background_wait_seconds']} seconds", flush=True)
        step("verify")
        shell("am", "force-stop", PACKAGE)
        step("restart")
        report["completed"] = True
    finally:
        shell("cmd", "connectivity", "airplane-mode", "enable" if airplane == "enabled" else "disable")
        shell("svc", "wifi", "enable" if wifi in ("1", "2") else "disable")
        for target in (PACKAGE + ".test", PACKAGE):
            subprocess.run(adb + ["uninstall", target], capture_output=True)
        (report_dir / "report.json").write_text(json.dumps(report, indent=2))
        print(f"Report: {report_dir / 'report.json'}", flush=True)


if __name__ == "__main__":
    main()
