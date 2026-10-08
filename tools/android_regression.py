#!/usr/bin/env python3
"""Mandatory Android regression on one explicitly selected AVD and isolated package.

Each class is invoked separately and its executed count verified. Opt-in transport/live
classes are exercised by their own harnesses, never counted as skipped passing tests.
"""
import argparse
import json
import re
import shlex
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.dowdah.utilitytracker.acceptanceregression"
CLASSES = {
    "data.DatabaseMigrationTest": 2,
    "data.LedgerRepositoryTest": 8,
    "data.BackupRecoveryTest": 2,
    "data.OfflineExportTest": 1,
    "data.WorkSchedulerTest": 1,
    "data.RechargeFormTest": 2,
    "ui.ReadingRevealTest": 8,
    "ui.ReadingActivityTest": 2,
    "ui.LauncherIconTest": 2,
    "ui.StatisticsScreenChartsTest": 3,
    "ui.DailyRemainingUiTest": 3,
    "ui.IntervalAverageTrendUiTest": 5,
    "ui.ExportUiAcceptanceTest": 1,
    "ui.ConflictCardTest": 1,
    "ui.ForecastCardTest": 1,
    "data.ForecastRepositoryTest": 1,
    "data.ReminderRuntimeTest": 1,
    "ui.ReminderSettingsTest": 1,
    "ui.PredictiveBackTest": 4,
    "ui.StatisticsLongHistoryTest": 1,
}


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout[-5000:] + result.stderr[-2000:])
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--class-name", action="append", choices=CLASSES,
                        help="Run only selected classes after a failed check; default is the complete suite")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Use an explicit emulator serial")
    if not args.skip_build:
        run([str(ROOT / "utility-tracker/gradlew"), "-p", str(ROOT / "utility-tracker"),
             "-PacceptanceSuffix=.acceptanceregression", ":app:assembleAcceptance", ":app:assembleAcceptanceAndroidTest"], timeout=600)
    apk = ROOT / "utility-tracker/app/build/outputs/apk/acceptance/app-acceptance.apk"
    if json.loads((apk.parent / "output-metadata.json").read_text())["applicationId"] != PACKAGE:
        raise RuntimeError("APK namespace mismatch; rebuild this variant")
    test = ROOT / "utility-tracker/app/build/outputs/apk/androidTest/acceptance/app-acceptance-androidTest.apk"
    adb = ["adb", "-s", args.serial]
    directory = Path(tempfile.mkdtemp(prefix="utility-v12-regression-"))
    selected = args.class_name or list(CLASSES)
    report = {"serial": args.serial, "package": PACKAGE, "classes": [], "completed": False,
              "expected_tests": sum(CLASSES[name] for name in selected)}
    print(f"Report directory: {directory}", flush=True)
    try:
        run(adb + ["install", "-r", str(apk)])
        run(adb + ["install", "-r", "-t", str(test)])
        for name in selected:
            count = CLASSES[name]
            command = ["am", "instrument", "-w", "-r", "-e", "class", "com.dowdah.utilitytracker." + name,
                       PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
            output = run(adb + ["shell", shlex.join(command)], timeout=300)
            (directory / (name + ".txt")).write_text(output)
            if f"OK ({count} test{'s' if count != 1 else ''})" not in output or "INSTRUMENTATION_STATUS_CODE: -3" in output:
                raise RuntimeError(f"Expected {count} executed tests in {name}:\n{output[-7000:]}")
            report["classes"].append({"name": name, "tests": count, "passed": True})
            print(f"Passed {name}: {count}", flush=True)
        report["completed"] = True
    finally:
        for target in (PACKAGE + ".test", PACKAGE):
            result = subprocess.run(adb + ["uninstall", target], capture_output=True, text=True)
            if result.returncode and report["completed"]:
                raise RuntimeError("Isolated package cleanup failed")
        (directory / "report.json").write_text(json.dumps(report, indent=2))
        print(f"Report: {directory / 'report.json'}", flush=True)


if __name__ == "__main__":
    main()
