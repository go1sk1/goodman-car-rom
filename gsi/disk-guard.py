#!/usr/bin/env python3
"""Stop only the recorded GSI process groups before E: runs out of space."""
import os
import shutil
import signal
import sys
import time
from datetime import datetime
from pathlib import Path

def identity(pid):
    try:
        stat = Path(f'/proc/{pid}/stat').read_text().rsplit(') ', 1)[1].split()
        return stat[19], os.getpgid(pid)
    except (OSError, IndexError):
        return None

tracked = {}
for value in sys.argv[1:]:
    pid = int(value)
    current = identity(pid)
    if current and current[1] == pid:
        tracked[pid] = current
if not tracked:
    raise SystemExit('No build process groups to monitor')
while tracked:
    tracked = {pid: expected for pid, expected in tracked.items() if identity(pid) == expected}
    free = shutil.disk_usage('/mnt/e').free
    if free < 20 * 1024**3:
        print(f'{datetime.now().astimezone().isoformat()} STOP: E: free space {free / 1024**3:.1f} GiB < 20 GiB', flush=True)
        for pid in tracked:
            try:
                os.killpg(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        break
    time.sleep(20)
