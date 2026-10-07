#!/usr/bin/env python3
"""Read-only host checks before syncing/building the full ROM."""
import os
import platform
import shutil
import sys
from pathlib import Path

root = Path(sys.argv[1] if len(sys.argv) > 1 else '.').resolve()
errors = []
if platform.system() != 'Linux':
    errors.append('Run in x86_64 Linux (WSL2 Ubuntu on an ext4 volume is a possible host).')
if platform.machine() not in ('x86_64', 'AMD64'):
    errors.append('An x86_64 build host is required.')
free = shutil.disk_usage(root).free / 1024**3
print(f'Workspace: {root}\nFree disk: {free:.1f} GiB')
backing_volume = os.environ.get('LINEAGE_BACKING_VOLUME')
if backing_volume:
    backing_free = shutil.disk_usage(backing_volume).free / 1024**3
    print(f'Backing volume: {backing_volume}; free: {backing_free:.1f} GiB')
    free = min(free, backing_free)
elif 'microsoft' in platform.release().lower():
    errors.append('Set LINEAGE_BACKING_VOLUME to the Windows drive mount containing this WSL VHD (this PC: /mnt/e); virtual disk free space alone is insufficient.')
if free < 400:
    errors.append('Reserve at least 400 GiB for source + build, or configure separate source/output volumes.')
for command in ('git', 'git-lfs', 'repo', 'python3', 'rsync'):
    if shutil.which(command) is None:
        errors.append(f'Missing command: {command}')
if Path('/proc/meminfo').exists():
    memory = dict(line.split(':', 1) for line in Path('/proc/meminfo').read_text().splitlines())
    ram = int(memory['MemTotal'].split()[0]) / 1024**2
    swap = int(memory['SwapTotal'].split()[0]) / 1024**2
    print(f'RAM: {ram:.1f} GiB; swap: {swap:.1f} GiB')
    if ram < 32:
        print('LOW MEMORY: restrict parallel jobs; swap is slower and does not guarantee a successful build.')
for error in errors:
    print('BLOCKED: ' + error)
sys.exit(bool(errors))
