#!/usr/bin/env python3
"""V1.3.1 navigation/statistics acceptance in an isolated package on an explicit device.

The personal application is never installed, stopped or cleared. Screenshots contain
only the isolated application's synthetic records and are kept in a private temp folder.
"""
import argparse
import hashlib
import json
import re
import shlex
import subprocess
import tempfile
import time
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


def main(*, package=PACKAGE, classes=CLASSES, build_suffix=".acceptancev131",
         report_prefix="utility-v131-navigation-",
         default_classes=("ui.PredictiveBackTest", "ui.NavigationGestureAcceptanceTest"),
         require_gestures=True):
    if not re.fullmatch(r"com\.dowdah\.utilitytracker\.acceptance[a-z0-9]*", package):
        raise ValueError("Only isolated acceptance package names are allowed")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--expected-device-serial", help="Required for a handset: independently verified ro.serialno")
    parser.add_argument("--allow-xiaomi-test-launch", action="store_true",
                        help="Temporarily allow Xiaomi background Activity launch for these two isolated test packages")
    parser.add_argument("--root-test-launch", action="store_true",
                        help="Use owner-authorized root for isolated launch appops only; the runner remains shell-owned")
    parser.add_argument("--root-instrumentation", action="store_true",
                        help="Launch only this isolated instrumentation as owner-authorized root to bypass OEM background-start restrictions")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--class-name", action="append", choices=classes, help="Run selected isolated test classes; default is deterministic and real gestures")
    args = parser.parse_args()
    emulator = bool(re.fullmatch(r"emulator-\d+", args.serial))
    adb = ["adb", "-s", args.serial]
    shell = lambda *parts: run(adb + ["shell", shlex.join(parts)], timeout=600)
    if not emulator and (not args.expected_device_serial or shell("getprop", "ro.serialno") != args.expected_device_serial):
        parser.error("Use an explicit AVD or a handset connection with a matching --expected-device-serial")
    if (args.root_test_launch or args.root_instrumentation) and shell("su", "-c", "id -u") != "0":
        raise RuntimeError("Owner-authorized root is unavailable")
    privileged = lambda *parts: shell("su", "-c", shlex.join(parts)) if args.root_test_launch else shell(*parts)
    if require_gestures and shell("settings", "get", "secure", "navigation_mode") != "2":
        raise RuntimeError("Gesture navigation is required; device navigation settings were not changed")
    if not args.skip_build:
        run([str(ROOT / "utility-tracker/gradlew"), "-p", str(ROOT / "utility-tracker"), "-PacceptanceSuffix=" + build_suffix, ":app:assembleAcceptance", ":app:assembleAcceptanceAndroidTest"], timeout=600)
    apk = ROOT / "utility-tracker/app/build/outputs/apk/acceptance/app-acceptance.apk"
    test = ROOT / "utility-tracker/app/build/outputs/apk/androidTest/acceptance/app-acceptance-androidTest.apk"
    metadata = json.loads((apk.parent / "output-metadata.json").read_text())
    if metadata["applicationId"] != package or json.loads((test.parent / "output-metadata.json").read_text())["applicationId"] != package + ".test":
        raise RuntimeError("Wrong isolated APK namespace")
    if shell("pm", "list", "packages", package):
        raise RuntimeError("Isolated package already exists; inspect prior run before overwriting it")
    work = Path(tempfile.mkdtemp(prefix=report_prefix))
    work.chmod(0o700)
    baseline = shell("dumpsys", "package", "com.dowdah.utilitytracker")
    baseline = [line.strip() for line in baseline.splitlines() if any(k in line for k in ("versionCode=", "versionName=", "lastUpdateTime="))]
    report = {"serial": args.serial, "package": package, "classes": [], "completed": False, "personal_package_before": baseline}
    report["apk_sha256"] = hashlib.sha256(apk.read_bytes()).hexdigest()
    report["test_apk_sha256"] = hashlib.sha256(test.read_bytes()).hexdigest()
    report["version"] = {key: metadata["elements"][0][key] for key in ("versionCode", "versionName")}
    report["expected_tests"] = sum(classes[name] for name in args.class_name or default_classes)
    report["root_test_launch"] = args.root_test_launch
    report["instrumentation_identity"] = "root" if args.root_instrumentation else "shell"
    launch_modes = {}
    print(f"Report directory: {work}", flush=True)
    try:
        run(adb + ["install", "-r", str(apk)], timeout=120)
        run(adb + ["install", "-r", "-t", str(test)], timeout=120)
        # Fixtures with English assertions need a reproducible app-local baseline.
        # Locale-combination tests override it explicitly; device locale is untouched.
        shell("cmd", "locale", "set-app-locales", package, "--user", "0", "--locales", "en")
        report["isolated_locale_baseline"] = "en"
        # Verify cold launch explicitly before instrumentation, including on handset
        # firmware that restricts a freshly installed application's background launch.
        launch = shell("am", "start", "-W", "-n", package + "/com.dowdah.utilitytracker.MainActivity")
        (work / "launch.txt").write_text(launch)
        if "Status: ok" not in launch:
            raise RuntimeError("Isolated application did not launch: " + launch)
        print("Isolated cold launch passed", flush=True)
        # The first cold launch lets OEM package-policy initialization finish before
        # applying temporary test-only permissions.
        if args.allow_xiaomi_test_launch:
            if emulator:
                raise RuntimeError("Xiaomi launch override is only for a verified handset")
            for target in (package, package + ".test"):
                # Xiaomi's get-with-op filter omits vendor operations; inspect the
                # isolated package's complete policy to verify this vendor op.
                previous = privileged("cmd", "appops", "get", target)
                matched = re.search(r"MIUIOP\(10021\): (allow|ignore|deny|default|foreground)", previous)
                # A freshly installed Xiaomi package can have no recorded vendor
                # operation yet. Preserve that inherited default, rather than treating
                # an absent entry as an explicit deny or requesting global changes.
                launch_modes[target] = matched.group(1) if matched else "default"
                privileged("cmd", "appops", "set", target, "10021", "allow")
                allowed = privileged("cmd", "appops", "get", target)
                if not re.search(r"MIUIOP\(10021\): allow\b", allowed):
                    raise RuntimeError("Xiaomi isolated launch permission did not become allowed")
            report["temporary_isolated_launch_modes"] = launch_modes.copy()
        for name in args.class_name or default_classes:
            if not re.fullmatch(r"(?:ui|data)\.[A-Za-z0-9]+Test", name):
                raise ValueError("Unexpected test class")
            command = ["am", "instrument", "-w", "-r", "-e", "class", "com.dowdah.utilitytracker." + name,
                       "-e", "real_gestures", "true", package + ".test/androidx.test.runner.AndroidJUnitRunner"]
            output = shell("su", "-c", shlex.join(command)) if args.root_instrumentation else shell(*command)
            (work / (name + ".txt")).write_text(output)
            matched = re.search(r"OK \((\d+) tests?\)", output)
            if not matched or int(matched.group(1)) != classes[name] or "INSTRUMENTATION_STATUS_CODE: -3" in output:
                raise RuntimeError(output[-7000:])
            report["classes"].append({"name": name, "tests": int(matched.group(1)), "passed": True})
            print(f"Passed {name}: {matched.group(1)}", flush=True)
            # Some handset firmware releases the previous instrumentation's
            # UiAutomation service asynchronously after its final result.
            time.sleep(2)
        report["completed"] = True
    finally:
        subprocess.run(adb + ["pull", f"/sdcard/Android/data/{package}/files", str(work / "screenshots")], capture_output=True)
        for target, previous in launch_modes.items():
            privileged("cmd", "appops", "set", target, "10021", previous)
            restored = privileged("cmd", "appops", "get", target)
            current = re.search(r"MIUIOP\(10021\): (allow|ignore|deny|default|foreground)", restored)
            if (current and current.group(1) != previous) or (not current and previous != "default"):
                report["cleanup_error"] = True
        report["isolated_launch_modes_restored"] = not report.get("cleanup_error", False)
        after = shell("dumpsys", "package", "com.dowdah.utilitytracker")
        report["personal_package_unchanged"] = baseline == [line.strip() for line in after.splitlines() if any(k in line for k in ("versionCode=", "versionName=", "lastUpdateTime="))]
        for target in (package + ".test", package):
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
