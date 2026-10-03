#!/usr/bin/env python3
"""V1.3.1 navigation/statistics acceptance in an isolated package on an explicit device.

The personal application is never installed, stopped or cleared. Screenshots contain
only the isolated application's synthetic records and are kept in a private temp folder.
"""
import argparse
import json
import re
import shlex
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.dowdah.utilitytracker.acceptancev131"
CLASSES = {
    "ui.PredictiveBackTest": 4,
    "ui.NavigationGestureAcceptanceTest": 1,
    "ui.StatisticsScreenChartsTest": 3,
    "ui.IntervalAverageTrendUiTest": 5,
    "ui.DailyRemainingUiTest": 3,
    "ui.StatisticsLongHistoryTest": 1,
}


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout[-5000:] + result.stderr[-2000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-device-serial", help="Required for a handset: independently verified ro.serialno")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--class-name", action="append", choices=CLASSES, help="Run selected isolated test classes; default is deterministic and real gestures")
    args = parser.parse_args()
    emulator = bool(re.fullmatch(r"emulator-\d+", args.serial))
    adb = ["adb", "-s", args.serial]
    shell = lambda *parts: run(adb + ["shell", shlex.join(parts)], timeout=600)
    if not emulator and (not args.expected_device_serial or shell("getprop", "ro.serialno") != args.expected_device_serial):
        parser.error("Use an explicit AVD or a handset connection with a matching --expected-device-serial")
    if shell("settings", "get", "secure", "navigation_mode") != "2":
        raise RuntimeError("Gesture navigation is required; device navigation settings were not changed")
    if not args.skip_build:
        run([str(ROOT / "utility-tracker/gradlew"), "-p", str(ROOT / "utility-tracker"), "-PacceptanceSuffix=.acceptancev131", ":app:assembleAcceptance", ":app:assembleAcceptanceAndroidTest"], timeout=600)
    apk = ROOT / "utility-tracker/app/build/outputs/apk/acceptance/app-acceptance.apk"
    test = ROOT / "utility-tracker/app/build/outputs/apk/androidTest/acceptance/app-acceptance-androidTest.apk"
    if json.loads((apk.parent / "output-metadata.json").read_text())["applicationId"] != PACKAGE:
        raise RuntimeError("Wrong isolated APK namespace")
    if shell("pm", "list", "packages", PACKAGE):
        raise RuntimeError("Isolated package already exists; inspect prior run before overwriting it")
    work = Path(tempfile.mkdtemp(prefix="utility-v131-navigation-"))
    work.chmod(0o700)
    baseline = shell("dumpsys", "package", "com.dowdah.utilitytracker")
    baseline = [line.strip() for line in baseline.splitlines() if any(k in line for k in ("versionCode=", "versionName=", "lastUpdateTime="))]
    report = {"serial": args.serial, "package": PACKAGE, "classes": [], "completed": False, "personal_package_before": baseline}
    print(f"Report directory: {work}", flush=True)
    try:
        run(adb + ["install", "-r", str(apk)], timeout=120)
        run(adb + ["install", "-r", "-t", str(test)], timeout=120)
        for name in args.class_name or ["ui.PredictiveBackTest", "ui.NavigationGestureAcceptanceTest"]:
            if not re.fullmatch(r"(?:ui|data)\.[A-Za-z0-9]+Test", name):
                raise ValueError("Unexpected test class")
            output = shell("am", "instrument", "-w", "-r", "-e", "class", "com.dowdah.utilitytracker." + name,
                           "-e", "real_gestures", "true", PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")
            (work / (name + ".txt")).write_text(output)
            matched = re.search(r"OK \((\d+) tests?\)", output)
            if not matched or int(matched.group(1)) != CLASSES[name] or "INSTRUMENTATION_STATUS_CODE: -3" in output:
                raise RuntimeError(output[-7000:])
            report["classes"].append({"name": name, "tests": int(matched.group(1)), "passed": True})
            print(f"Passed {name}: {matched.group(1)}", flush=True)
        report["completed"] = True
    finally:
        subprocess.run(adb + ["pull", f"/sdcard/Android/data/{PACKAGE}/files", str(work / "screenshots")], capture_output=True)
        after = shell("dumpsys", "package", "com.dowdah.utilitytracker")
        report["personal_package_unchanged"] = baseline == [line.strip() for line in after.splitlines() if any(k in line for k in ("versionCode=", "versionName=", "lastUpdateTime="))]
        for target in (PACKAGE + ".test", PACKAGE):
            result = subprocess.run(adb + ["uninstall", target], capture_output=True, text=True)
            if result.returncode and shell("pm", "list", "packages", target):
                report["cleanup_error"] = True
        (work / "report.json").write_text(json.dumps(report, indent=2))
        for path in work.rglob("*"):
            path.chmod(0o700 if path.is_dir() else 0o600)
        print(f"Report: {work / 'report.json'}", flush=True)
    if not report["personal_package_unchanged"] or report.get("cleanup_error"):
        raise RuntimeError("Package preservation/cleanup verification failed")


if __name__ == "__main__":
    main()
