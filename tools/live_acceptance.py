#!/usr/bin/env python3
"""Opt-in two-AVD ledger acceptance. Secrets stay in memory and Android Keystore.

Run with utility-sync/.venv/bin/python. Production mode requires explicit endpoint and
SSH host. Only marked synthetic records are tombstoned; no database resets occur.
"""

from __future__ import annotations

import argparse
import contextlib
import http.server
import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def run(command, **kwargs):
    result = subprocess.run(command, text=True, capture_output=True, **kwargs)
    if result.returncode:
        # Commands never include API tokens. Test failures may contain fixture IDs, not credentials.
        raise RuntimeError(
            f"Command failed: {command[0]}\n{result.stdout[-8000:]}\n{result.stderr[-3000:]}"
        )
    return result.stdout


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def remote_python(host, script):
    return run(
        [
            "ssh",
            "-o",
            "BatchMode=yes",
            "-o",
            "ConnectTimeout=10",
            host,
            "sudo -n /opt/utility-sync/.venv/bin/python -",
        ],
        input=script,
    )


REMOTE_INIT = """
import os, sys, json
from pathlib import Path
for line in Path('/etc/utility-sync/environment').read_text().splitlines():
    if line.strip() and not line.startswith('#'):
        key,value=line.split('=',1); os.environ[key]=value
sys.path.insert(0, '/opt/utility-sync/src')
from utility_sync.config import Settings
from utility_sync.service import SyncService
from utility_sync.auth import issue_token, revoke_token
service=SyncService(Settings.from_environment())
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=["local", "production"], required=True)
    parser.add_argument("--endpoint")
    parser.add_argument("--ssh-host")
    parser.add_argument("--serial-a", default="emulator-5554")
    parser.add_argument("--serial-b", default="emulator-5556")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument(
        "--pause-before-cleanup",
        action="store_true",
        help="Wait for Enter for optional visual inspection",
    )
    args = parser.parse_args()
    if not all(s.startswith("emulator-") for s in (args.serial_a, args.serial_b)):
        parser.error("Only explicit emulator serials may be used")
    if args.mode == "production" and (not args.endpoint or not args.ssh_host):
        parser.error("Production mode requires --endpoint and --ssh-host")
    suffix = (
        (".acceptancelocal" if args.mode == "local" else ".acceptanceproduction")
        + ".r"
        + uuid.uuid4().hex[:8]
    )
    package = "com.dowdah.utilitytracker" + suffix
    marker = "acceptance-" + str(uuid.uuid4())
    tariff_time = f"2097-12-{secrets.randbelow(28) + 1:02d}T{secrets.randbelow(24):02d}:{secrets.randbelow(60):02d}:{secrets.randbelow(60):02d}Z"
    report = {
        "mode": args.mode,
        "marker": marker,
        "package": package,
        "steps": [],
        "completed": False,
    }
    work = Path(tempfile.mkdtemp(prefix="utility-ledger-acceptance-"))
    print(f"Acceptance report directory: {work}", flush=True)
    service = None
    backend = None
    tokens = []
    broker = None
    try:
        if not args.skip_build:
            run(
                [
                    str(ROOT / "utility-tracker/gradlew"),
                    f"-PacceptanceSuffix={suffix}",
                    ":app:assembleAcceptance",
                    ":app:assembleAcceptanceAndroidTest",
                ],
                cwd=ROOT / "utility-tracker",
            )
        if args.mode == "local":
            os.environ["UTILITY_SYNC_LOCAL_ROOT"] = str(work / "server")
            os.environ["UTILITY_SYNC_WRITE_MIN_FREE_BYTES"] = "0"
            os.environ["UTILITY_SYNC_BACKUP_MIN_FREE_BYTES"] = "0"
            sys.path.insert(0, str(ROOT / "utility-sync/src"))
            from utility_sync.auth import issue_token
            from utility_sync.cli import _migrate
            from utility_sync.config import Settings
            from utility_sync.service import SyncService

            config = Settings.from_environment()
            _migrate(config)
            service = SyncService(config)
            tokens = [issue_token(service.database), issue_token(service.database)]
            listen = port()
            log = (work / "backend.log").open("w")
            backend = subprocess.Popen(
                [
                    sys.executable,
                    "-m",
                    "uvicorn",
                    "utility_sync.api:app",
                    "--host",
                    "127.0.0.1",
                    "--port",
                    str(listen),
                ],
                cwd=ROOT / "utility-sync",
                stdout=log,
                stderr=log,
            )
            endpoint = f"http://127.0.0.1:{listen}"
            for serial in (args.serial_a, args.serial_b):
                run(["adb", "-s", serial, "reverse", f"tcp:{listen}", f"tcp:{listen}"])
            for _ in range(100):
                try:
                    urllib.request.urlopen(f"http://127.0.0.1:{listen}/healthz", timeout=1).close()
                    break
                except OSError:
                    time.sleep(0.1)
            else:
                raise RuntimeError("Temporary backend failed to start")
        else:
            endpoint = args.endpoint
            tokens = json.loads(
                remote_python(
                    args.ssh_host,
                    REMOTE_INIT
                    + "print(json.dumps([issue_token(service.database),issue_token(service.database)]))\n",
                )
            )
        configs = {}
        for role, token in zip(("a", "b"), tokens, strict=True):
            configs["/" + secrets.token_urlsafe(24)] = dict(
                endpoint=endpoint, token=token, marker=marker, tariff_time=tariff_time, role=role
            )
        paths = list(configs)

        class Broker(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                config = configs.get(self.path)
                if config is None:
                    self.send_error(404)
                    return
                body = json.dumps(config).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                pass

        broker = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Broker)
        threading.Thread(target=broker.serve_forever, daemon=True).start()
        urls = {
            role: f"http://127.0.0.1:{broker.server_port}{path}"
            for role, path in zip(("a", "b"), paths, strict=True)
        }
        serials = {"a": args.serial_a, "b": args.serial_b}
        metadata = json.loads(
            (
                ROOT / "utility-tracker/app/build/outputs/apk/acceptance/output-metadata.json"
            ).read_text()
        )
        if metadata["applicationId"] != package:
            raise RuntimeError(
                "Acceptance APK belongs to another build; do not run concurrent variant builds"
            )
        for serial in serials.values():
            run(
                [
                    "adb",
                    "-s",
                    serial,
                    "reverse",
                    f"tcp:{broker.server_port}",
                    f"tcp:{broker.server_port}",
                ]
            )
            run(
                [
                    "adb",
                    "-s",
                    serial,
                    "install",
                    "-r",
                    str(
                        ROOT / "utility-tracker/app/build/outputs/apk/acceptance/app-acceptance.apk"
                    ),
                ]
            )
            run(
                [
                    "adb",
                    "-s",
                    serial,
                    "install",
                    "-r",
                    str(
                        ROOT
                        / "utility-tracker/app/build/outputs/apk/androidTest/acceptance/app-acceptance-androidTest.apk"
                    ),
                ]
            )

        def step(role, name):
            print(f"Running {role}: {name}", flush=True)
            if name != "verifyRestartB":
                run(["adb", "-s", serials[role], "shell", "am", "force-stop", package])
            output = run(
                [
                    "adb",
                    "-s",
                    serials[role],
                    "shell",
                    "am",
                    "instrument",
                    "-w",
                    "-r",
                    "-e",
                    "class",
                    "com.dowdah.utilitytracker.data.LiveLedgerAcceptanceTest",
                    "-e",
                    "live_config_url",
                    urls[role],
                    "-e",
                    "live_step",
                    name,
                    f"{package}.test/androidx.test.runner.AndroidJUnitRunner",
                ],
                timeout=100,
            )
            # Avoid persisting the ephemeral credential-broker URL if a runner echoes arguments.
            output = output.replace(urls[role], "[ephemeral configuration]")
            (work / f"{len(report['steps']):02d}-{role}-{name}.txt").write_text(output)
            if (
                "LIVE_ACCEPTANCE_OK" not in output
                or "FAILURES!!!" in output
                or "INSTRUMENTATION_FAILED" in output
            ):
                raise RuntimeError(f"Live step failed: {name}\n{output[-6000:]}")
            report["steps"].append(dict(device=role, step=name, passed=True))
            (work / "report.json").write_text(json.dumps(report, indent=2))

        steps = [
            ("a", "setup"),
            ("b", "setup"),
            ("a", "seedElectric"),
            ("b", "verifyElectric"),
            ("b", "offlineCreditB"),
            ("a", "editCreditA"),
            ("b", "overrideB"),
            ("a", "offlineCreditA"),
            ("b", "editCreditB"),
            ("a", "keepA"),
            ("a", "waterA"),
            ("b", "waterB"),
            ("a", "deleteRechargeA"),
            ("b", "verifyDeleteB"),
            ("b", "queueRestartB"),
        ]
        for role, name in steps:
            step(role, name)
        run(["adb", "-s", args.serial_b, "shell", "am", "force-stop", package])
        for network in ("wifi", "data"):
            run(["adb", "-s", args.serial_b, "shell", "svc", network, "enable"])
        run(
            [
                "adb",
                "-s",
                args.serial_b,
                "shell",
                "am",
                "start",
                "-n",
                f"{package}/com.dowdah.utilitytracker.MainActivity",
            ]
        )
        time.sleep(5)
        step("b", "verifyRestartB")
        step("a", "exportA")
        if args.pause_before_cleanup:
            print(
                f"Inspect only synthetic records marked {marker}. Press Enter to clean up.",
                flush=True,
            )
            input()
        step("a", "cleanupA")
        step("b", "verifyCleanupB")
        report["completed"] = True
        print("All two-device live acceptance steps passed.", flush=True)
    finally:
        for network in ("wifi", "data"):
            with contextlib.suppress(Exception):
                run(["adb", "-s", args.serial_b, "shell", "svc", network, "enable"])
        if tokens:
            ids = [token.split("_", 2)[1] for token in tokens]
            if args.mode == "production":
                remote_python(
                    args.ssh_host,
                    REMOTE_INIT
                    + f"for token_id in {ids!r}: revoke_token(service.database, token_id)\n",
                )
            elif service:
                from utility_sync.auth import revoke_token

                for token_id in ids:
                    revoke_token(service.database, token_id)
            report["temporary_tokens_revoked"] = True
        if broker:
            for serial in (args.serial_a, args.serial_b):
                with contextlib.suppress(Exception):
                    run(["adb", "-s", serial, "reverse", "--remove", f"tcp:{broker.server_port}"])
            broker.shutdown()
        if backend:
            backend.terminate()
            backend.wait(timeout=10)
        (work / "report.json").write_text(json.dumps(report, indent=2))
        print(f"Report: {work / 'report.json'}", flush=True)
        if not report["completed"]:
            print(
                f"Incomplete run: preserve and inspect only records tagged {marker}; do not reset the database.",
                flush=True,
            )


if __name__ == "__main__":
    main()
