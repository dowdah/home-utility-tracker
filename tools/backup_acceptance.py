#!/usr/bin/env python3
"""Exercise Android local backup transports on isolated AVD packages only.

Encrypted and D2D flags emulate transport policy, not a real Google cloud upload.
All transport/enabled/settings changes are restored in finally, including failures.
"""
import argparse
import json
import re
import shlex
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOCAL = "com.android.localtransport/.LocalTransport"
PACKAGE = "com.dowdah.utilitytracker.acceptancebackup"
TEST = PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"


def run(command, timeout=180):
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(result.stdout[-4000:] + result.stderr[-2000:])
    return result.stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--previous-apk", type=Path, help="same isolated namespace, signed baseline APK for a real version upgrade")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only explicit emulator serials may be used")
    adb = ["adb", "-s", args.serial]
    shell = lambda *parts: run(adb + ["shell", shlex.join(parts)])
    if not args.skip_build:
        run([str(ROOT / "utility-tracker/gradlew"), "-p", str(ROOT / "utility-tracker"), "-PacceptanceSuffix=.acceptancebackup",
             ":app:assembleAcceptance", ":app:assembleAcceptanceAndroidTest"], timeout=600)
    app = ROOT / "utility-tracker/app/build/outputs/apk/acceptance/app-acceptance.apk"
    test = ROOT / "utility-tracker/app/build/outputs/apk/androidTest/acceptance/app-acceptance-androidTest.apk"
    metadata = json.loads((app.parent / "output-metadata.json").read_text())
    if args.previous_apk:
        previous_metadata = json.loads((args.previous_apk.parent / "output-metadata.json").read_text())
        if previous_metadata["elements"][0]["versionCode"] >= metadata["elements"][0]["versionCode"]:
            raise RuntimeError("Baseline must have a lower versionCode than the upgrade APK")
        if previous_metadata["applicationId"] != PACKAGE:
            raise RuntimeError("Baseline APK must use the same isolated backup namespace")
    if metadata["applicationId"] != PACKAGE:
        raise RuntimeError("APK namespace differs; rebuild before testing")
    transports = shell("bmgr", "list", "transports")
    selected = next(line.strip()[2:] for line in transports.splitlines() if line.strip().startswith("* "))
    enabled = "enabled" in shell("bmgr", "enabled")
    parameters = shell("settings", "get", "secure", "backup_local_transport_parameters")
    report = {"serial": args.serial, "transport": "local only", "modes": []}
    if args.previous_apk:
        report["upgrade"] = {"from": previous_metadata["elements"][0]["versionName"], "to": metadata["elements"][0]["versionName"]}

    def step(name, identity=""):
        output = shell("am", "instrument", "-w", "-r", "-e", "class",
                       "com.dowdah.utilitytracker.data.BackupTransportAcceptanceTest",
                       "-e", "backupStep", name, "-e", "oldIdentity", identity, TEST)
        if "OK (1 test)" not in output:
            raise RuntimeError(output[-5000:])
        return output

    try:
        run(adb + ["install", "-r", str(app)])
        run(adb + ["install", "-r", "-t", str(test)])
        shell("bmgr", "enable", "true")
        shell("bmgr", "transport", LOCAL)
        for mode, flags in (("encrypted", "is_encrypted=true"),
                            ("device-transfer", "is_encrypted=false,is_device_transfer=true"),
                            ("unencrypted-blocked", "is_encrypted=false,is_device_transfer=false")):
            shell("bmgr", "wipe", LOCAL, PACKAGE)
            shell("pm", "clear", PACKAGE)
            if args.previous_apk:
                run(adb + ["install", "-r", "-d", str(args.previous_apk)])
            output = step("seed")
            identity = re.search(r"old_identity=([^\s]+)", output).group(1)
            if args.previous_apk:
                assert f"seed_version={report['upgrade']['from']}" in output, "Fixture was not seeded by the old APK"
            run(adb + ["install", "-r", str(app)])
            step("upgrade", identity)
            shell("settings", "put", "secure", "backup_local_transport_parameters", flags)
            output = shell("bmgr", "backupnow", "--non-incremental", "@pm@", PACKAGE)
            expected = "Transport rejected package" if mode == "unencrypted-blocked" else f"Package {PACKAGE} with result: Success"
            if expected not in output:
                raise RuntimeError(output)
            shell("pm", "clear", PACKAGE)
            output = shell("bmgr", "restore", "1", PACKAGE)
            if "restoreFinished" not in output:
                raise RuntimeError(output)
            step("empty" if mode == "unencrypted-blocked" else "restored", identity)
            report["modes"].append({"mode": mode, "passed": True})
            print(json.dumps(report["modes"][-1]), flush=True)
    finally:
        shell("bmgr", "wipe", LOCAL, PACKAGE)
        shell("bmgr", "transport", selected)
        shell("bmgr", "enable", "true" if enabled else "false")
        if parameters == "null":
            shell("settings", "delete", "secure", "backup_local_transport_parameters")
        else:
            shell("settings", "put", "secure", "backup_local_transport_parameters", parameters)
        run(adb + ["uninstall", PACKAGE + ".test"])
        run(adb + ["uninstall", PACKAGE])
    print(json.dumps(report), flush=True)


if __name__ == "__main__":
    main()
