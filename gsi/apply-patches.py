#!/usr/bin/env python3
"""Apply pinned GSI patches; stop on conflicts instead of silently skipping."""
import json
import hashlib
import subprocess
import sys
from pathlib import Path

root = Path(sys.argv[1]).resolve()
patch_root = root / 'LineageOS_gsi'
expected = '7bf212f449d1aa253ebc97b1ab3c4924be88effa'
actual = subprocess.check_output(['git', '-C', str(patch_root), 'rev-parse', 'HEAD'], text=True).strip()
if actual != expected:
    raise SystemExit(f'Patch revision differs: {actual}')
mapping = {'build': 'build/make', 'testing': 'platform_testing',
           'vendor/hardware/overlay': 'vendor/hardware_overlay',
           'treble/app': 'treble_app', 'vendor/partner/gms': 'vendor/partner_gms'}
report = []
for group in ('trebledroid', 'trebledroid-staging', 'personal'):
    folder = patch_root / 'patches' / group
    if not folder.is_dir():
        continue
    for tree in sorted(folder.iterdir()):
        if not tree.is_dir():
            continue
        relative = tree.name.replace('_', '/').removeprefix('platform/')
        target = (root / mapping.get(relative, relative)).resolve()
        if not target.is_relative_to(root) or not target.is_dir():
            raise SystemExit(f'Invalid/missing patch target: {target}')
        for patch in sorted(tree.glob('*.patch')):
            override = Path(__file__).resolve().parent / 'patch-overrides' / patch.relative_to(patch_root / 'patches')
            effective = override if override.is_file() else patch
            command = ['git', '-C', str(target), 'apply']
            check = subprocess.run(command + ['--check', str(effective)], capture_output=True, text=True)
            if check.returncode == 0:
                subprocess.run(command + [str(effective)], check=True)
                status = 'applied'
            elif subprocess.run(command + ['--reverse', '--check', str(effective)], capture_output=True).returncode == 0:
                status = 'already-present'
            else:
                print(check.stderr, file=sys.stderr)
                raise SystemExit(f'PATCH CONFLICT: {patch.relative_to(patch_root)}')
            entry = {'patch': str(patch.relative_to(patch_root)), 'target': str(target.relative_to(root)), 'status': status,
                     'local_override': override.is_file(), 'sha256': hashlib.sha256(effective.read_bytes()).hexdigest()}
            report.append(entry)
            print(json.dumps(entry), flush=True)
(root / 'goodman-patches.json').write_text(json.dumps(report, indent=2) + '\n')
