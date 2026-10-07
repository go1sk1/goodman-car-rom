#!/usr/bin/env python3
"""Freeze reviewed local sources for a dependent build; never touch the live ROM tree."""
import hashlib
import json
import shutil
from pathlib import Path

project = Path(__file__).resolve().parent.parent
target = Path.home() / 'android/updates/rom-updates-20261007'
if target.exists(): raise SystemExit('Existing frozen update; do not replace it')
target.mkdir(parents=True)
for relative in ('vendor/goodman', 'packages/apps/GoodmanCarBridge', 'device/goodman/gsi', 'transport', 'platform'):
    shutil.copytree(project / relative, target / relative)
for relative in ('tools/install-vehicle-update.py', 'gsi/vehicle-platform-plan.json', 'gsi/export-image.py',
                 'gsi/disk-guard.py', 'gsi/compile-rom-update.sh'):
    out = target / relative
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(project / relative, out)
    if out.suffix == '.sh': out.write_bytes(out.read_bytes().replace(b'\r\n', b'\n'))
plan = json.loads((target / 'gsi/vehicle-platform-plan.json').read_text())
for change in plan['changes']:
    if 'source' in change:
        assert hashlib.sha256((target / change['source']).read_bytes()).hexdigest() == change['after_sha256'], change['source']
manifest = {str(f.relative_to(target)): hashlib.sha256(f.read_bytes()).hexdigest()
            for f in sorted(target.rglob('*')) if f.is_file()}
(target / 'bundle-sources.json').write_text(json.dumps(manifest, indent=2)+'\n')
print(f'Frozen {len(manifest)} files: {target}')
