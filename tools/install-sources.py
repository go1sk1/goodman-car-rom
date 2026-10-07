#!/usr/bin/env python3
"""Install our source modules into a clean, synced LineageOS checkout."""
import argparse
import hashlib
import json
import shutil
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('lineage_root', type=Path)
args = parser.parse_args()
source = Path(__file__).resolve().parent.parent
target = args.lineage_root.resolve()
if not (target / 'build/envsetup.sh').is_file() or not (target / 'vendor/lineage').is_dir():
    parser.error('Target must be a synced LineageOS source tree')
paths = ('vendor/goodman', 'packages/apps/GoodmanCarBridge', 'device/goodman/gsi', 'transport')

def inventory(folder):
    return {str(path.relative_to(folder)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in sorted(folder.rglob('*')) if path.is_file()}

record_path = target / 'goodman-sources.json'
records = json.loads(record_path.read_text()) if record_path.exists() else {}
for relative, expected_hash in records.items():
    installed = (target / relative).resolve()
    if not installed.is_relative_to(target) or hashlib.sha256(installed.read_bytes()).hexdigest() != expected_hash:
        parser.error('Previously installed custom source changed: ' + relative)
for relative in paths:
    expected = inventory(source / relative)
    if not expected:
        parser.error(f'Empty source module: {relative}')
    if (target / relative).exists():
        if inventory(target / relative) != expected:
            parser.error(f'{relative} differs from supplied sources; review changes instead of overwriting')
    records.update({relative + '/' + name: digest for name, digest in expected.items()})
for relative in paths:
    if not (target / relative).exists():
        shutil.copytree(source / relative, target / relative)
(target / 'goodman-sources.json').write_text(json.dumps(records, indent=2) + '\n')
print('Installed source modules. Inherit vendor/goodman/config/car.mk from the reviewed target product.')
print('Then run: source build/envsetup.sh; lunch <reviewed-target>; m GoodmanCarBridge')
