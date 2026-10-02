#!/usr/bin/env python3
"""Deploy one committed utility-sync revision, preserving production ledger and identity.

The SSH account must have administrator rights. Rehearses on a SQLite online copy,
backs up under the old service code, retains old source, migrates additively, verifies
all pre-existing row hashes, then starts only Utility Sync and its dedicated monitor.
No automatic destructive rollback is attempted after new writes.
"""

from __future__ import annotations

import argparse
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

REMOTE = r'''
import os, sys, pathlib, sqlite3, subprocess, json, hashlib, shutil, urllib.request, time
revision = REVISION
base = pathlib.Path('/opt/utility-sync')
stage = pathlib.Path('/opt/utility-sync-stage-' + revision[:12])
checkpoint = pathlib.Path('/srv/utility-meter/deployment-' + revision[:12])
checkpoint.mkdir(mode=0o700, exist_ok=True)
env = os.environ.copy()
for line in pathlib.Path('/etc/utility-sync/environment').read_text().splitlines():
    if line.strip() and not line.startswith('#'):
        key, value = line.split('=', 1); env[key] = value
source = pathlib.Path(env.get('UTILITY_SYNC_DATABASE', str(pathlib.Path(env['UTILITY_SYNC_DATA_DIR']) / 'utility.sqlite3')))
# Keep monitor state independent of the database directory in production.
monitor_dir = pathlib.Path('/srv/utility-meter/monitor')
env['UTILITY_SYNC_MONITOR_DIR'] = str(monitor_dir)
old_python = str(base / '.venv/bin/python')

def hashes(path, original=None):
    with sqlite3.connect(f'file:{path}?mode=ro', uri=True) as c:
        result = {}
        for table in ('metadata', 'meters', 'readings', 'tariffs', 'recharges', 'changes', 'operations', 'tokens', 'atomic_groups'):
            available = [r[1] for r in c.execute(f'PRAGMA table_info({table})')]
            if not available or (original is not None and table not in original): continue
            columns = original[table]['columns'] if original is not None else available
            rows = sorted(c.execute(f"SELECT {','.join(columns)} FROM {table}").fetchall(), key=repr)
            result[table] = dict(columns=columns,count=len(rows), sha256=hashlib.sha256(repr(rows).encode()).hexdigest())
        return result

def migrate(path):
    script = """
import sys
from pathlib import Path
sys.path.insert(0, STAGE + '/src')
from utility_sync.config import Settings
from utility_sync.cli import _migrate
p=Path(DATABASE)
s=Settings(p,p.parent,p.parent/'exports',p.parent/'backups',0,0,134217728)
_migrate(s)
""".replace('STAGE', repr(str(stage))).replace('DATABASE', repr(str(path)))
    subprocess.run([old_python, '-c', script], env=env, check=True)

# Live read-only rehearsal: never copy the running database as a normal file.
rehearsal = checkpoint / 'rehearsal.sqlite3'
with sqlite3.connect(source) as src, sqlite3.connect(rehearsal) as dst:
    src.backup(dst)
before = hashes(rehearsal)
migrate(rehearsal)
assert hashes(rehearsal, before) == before, 'Rehearsal changed existing rows'
print('Migration rehearsal: all original rows and identity preserved', flush=True)

# Retain exact old source, excluding runtime/dependencies and all credentials.
archive = checkpoint / 'previous-source.tar'
paths = [name for name in ('src','alembic','ops','pyproject.toml','uv.lock','alembic.ini','README.md','.deployed-revision') if (base/name).exists()]
subprocess.run(['tar','-cf',str(archive),'-C',str(base),*paths],check=True)
subprocess.run(['systemctl','stop','utility-sync.service'],check=True)
try:
    # The backup uses the old schema/code and validates a consistent stopped baseline.
    backup = subprocess.run([old_python,'-m','utility_sync.cli','backup'],cwd=base,env=env,capture_output=True,text=True,check=True)
    print('Pre-upgrade verified backup:', backup.stdout.strip(), flush=True)
    baseline = hashes(source)
    (checkpoint/'before.json').write_text(json.dumps(baseline,indent=2))
    for item in stage.iterdir():
        if item.name == '.venv': continue
        target = base/item.name
        if item.is_dir(): shutil.copytree(item,target,dirs_exist_ok=True)
        else: shutil.copy2(item,target)
    subprocess.run(['/usr/local/bin/uv','sync','--frozen','--no-dev'],cwd=base,env=env,check=True)
    subprocess.run([old_python,'-m','utility_sync.cli','migrate'],cwd=base,env=env,check=True)
    assert hashes(source, baseline) == baseline, 'Production migration changed original rows'
    (base/'.deployed-revision').write_text(revision+'\n')
    environment_path = pathlib.Path('/etc/utility-sync/environment')
    environment_lines = [line for line in environment_path.read_text().splitlines() if not line.startswith('UTILITY_SYNC_MONITOR_DIR=')]
    environment_path.write_text('\n'.join(environment_lines) + '\nUTILITY_SYNC_MONITOR_DIR=' + str(monitor_dir) + '\n')
    import grp
    monitor_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chown(monitor_dir, __import__('pwd').getpwnam('utility-sync').pw_uid, grp.getgrnam('utility-sync').gr_gid)
    for name in ('utility-sync-monitor.service','utility-sync-monitor.timer'):
        shutil.copy2(base/'ops'/name, pathlib.Path('/etc/systemd/system')/name)
    subprocess.run(['systemctl','daemon-reload'],check=True)
    subprocess.run(['systemctl','start','utility-sync.service','utility-sync-tunnel.service'],check=True)
    for _ in range(30):
        try:
            health=json.load(urllib.request.urlopen('http://127.0.0.1:8088/healthz',timeout=2))
            assert health['status']=='ok'; break
        except Exception: time.sleep(1)
    else: raise RuntimeError('Service did not become healthy')
    subprocess.run(['systemctl','start','utility-sync-backup.service'],check=True)
    subprocess.run(['systemctl','enable','--now','utility-sync-monitor.timer'],check=True)
    subprocess.run(['systemctl','start','utility-sync-monitor.service'],check=True)
    print('Production original-row verification: PASS', flush=True)
    print('Health:',json.dumps(health), flush=True)
    print('Checkpoint:',checkpoint, flush=True)
except Exception:
    print('Deployment incomplete. Preserve the current database and checkpoint; do not restore an older database after new writes.',flush=True)
    raise
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ssh-host", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument(
        "--notifications-config",
        type=Path,
        help="private SMTP file, transported over stdin",
    )
    args = parser.parse_args()
    revision = subprocess.check_output(
        ["git", "rev-parse", args.revision], cwd=ROOT, text=True
    ).strip()
    if not re.fullmatch(r"[0-9a-f]{40}", revision):
        parser.error("revision must resolve to a committed SHA")
    if args.notifications_config:
        import sys

        sys.path.insert(0, str(ROOT / "utility-sync/src"))
        from utility_sync.notifications import NotificationError, SMTPSettings

        try:
            config = SMTPSettings.load(args.notifications_config)
            if not config.enabled:
                raise NotificationError("notifications_disabled")
            config.validate()
        except NotificationError as error:
            raise SystemExit(error.code) from None
        import shlex

        install = "import os,sys,grp; from pathlib import Path; p=Path('/etc/utility-sync/notifications.env'); t=p.with_suffix('.tmp'); fd=os.open(t,os.O_WRONLY|os.O_CREAT|os.O_TRUNC,0o640); os.fchmod(fd,0o640); os.fchown(fd,0,grp.getgrnam('utility-sync').gr_gid); f=os.fdopen(fd,'wb'); f.write(sys.stdin.buffer.read()); f.flush(); os.fsync(f.fileno()); f.close(); os.replace(t,p)"
        subprocess.run(
            ["ssh", args.ssh_host, "sudo -n python3 -c " + shlex.quote(install)],
            input=args.notifications_config.read_bytes(),
            check=True,
        )
    stage = "/opt/utility-sync-stage-" + revision[:12]
    tar = subprocess.check_output(
        ["git", "archive", revision + ":utility-sync"], cwd=ROOT
    )
    subprocess.run(
        [
            "ssh",
            args.ssh_host,
            f"sudo -n install -d -m 755 {stage} && sudo -n tar -xf - -C {stage}",
        ],
        input=tar,
        check=True,
    )
    script = REMOTE.replace("REVISION", repr(revision))
    subprocess.run(
        ["ssh", args.ssh_host, "sudo -n python3 -"], input=script, text=True, check=True
    )


if __name__ == "__main__":
    main()
