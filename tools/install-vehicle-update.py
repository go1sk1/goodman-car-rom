#!/usr/bin/env python3
"""Prepare a reviewed platform delta, or install it while the build locks are free."""
import argparse
import fcntl
import hashlib
import json
import os
import shutil
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('--prepare', action='store_true')
parser.add_argument('--inherited-locks', action='store_true')
options = parser.parse_args()
project = Path(__file__).resolve().parents[1]
root = Path.home() / 'android/lineage'
logs = Path.home() / 'android/logs'
plan_path = project / 'gsi/vehicle-platform-plan.json'

def sha(data): return hashlib.sha256(data).hexdigest()
def inventory(folder):
    return {str(p.relative_to(folder)): sha(p.read_bytes()) for p in sorted(folder.rglob('*')) if p.is_file()}

manager = 'frameworks/base/services/core/java/com/android/server/location/LocationManagerService.java'
services = 'frameworks/base/services/core/Android.bp'
builder = 'build/soong/ui/build/soong.go'
bluetooth = 'packages/modules/Bluetooth/framework/Android.bp'
wrapper = 'frameworks/base/services/core/java/com/android/server/location/provider/VehicleLocationProvider.java'

if options.prepare:
    if plan_path.exists(): raise SystemExit('An update plan already exists; inspect it instead of replacing its baseline')
    manager_before = (root / manager).read_text()
    old = 'addLocationProviderManager(gnssManager, gnssProvider);'
    assert manager_before.count(old) == 1
    manager_after = manager_before.replace(old, 'addLocationProviderManager(gnssManager,\n'
            '                    new com.android.server.location.provider.VehicleLocationProvider(mContext, gnssProvider));')
    services_before = (root / services).read_text()
    start = services_before.index('name: "services.core.unboosted"')
    position = services_before.index('    static_libs: [\n', start) + len('    static_libs: [\n')
    services_after = services_before[:position] + '        "goodman-car-core",\n' + services_before[position:]
    builder_before = (root / builder).read_text()
    old = '\tinvocationEnv := make(map[string]string)\n'
    assert builder_before.count(old) == 1
    builder_after = builder_before.replace(old, old + '\t// This workstation has 16 GB RAM; keep the Go heap growth smaller.\n'
            '\tif os.Getenv("GOODMAN_LOW_MEMORY") == "1" {\n'
            '\t\tinvocationEnv["GOGC"] = "50"\n\t}\n')
    bluetooth_before = (root / bluetooth).read_text()
    old = '    impl_library_visibility: [\n'
    assert bluetooth_before.count(old) == 1
    bluetooth_after = bluetooth_before.replace(old, old + '        "//packages/apps/GoodmanCarBridge",\n')
    changes = []
    for path, before, after in ((manager, manager_before, manager_after),
                                (services, services_before, services_after),
                                (builder, builder_before, builder_after),
                                (bluetooth, bluetooth_before, bluetooth_after)):
        # Baseline files are UTF-8/LF in the checked-out Linux tree.
        assert sha((root / path).read_bytes()) == sha(before.encode())
        changes.append({'path': path, 'before_sha256': sha(before.encode()),
                        'after_sha256': sha(after.encode()), 'after_text': after})
    assert not (root / wrapper).exists()
    changes.append({'path': wrapper, 'before_sha256': None,
                    'after_sha256': sha((project / 'platform' / wrapper).read_bytes()),
                    'source': 'platform/' + wrapper})
    plan_path.write_text(json.dumps({'revision': 'vehicle-features-v1', 'changes': changes}, indent=2) + '\n')
    print('Prepared platform delta for five files; running source tree was not changed')
    raise SystemExit(0)

locks = []
for fd, name in ((8, 'gsi-run.lock'), (9, 'gsi-build.lock')):
    if options.inherited_locks:
        actual, expected = os.fstat(fd), (logs / name).stat()
        assert (actual.st_dev, actual.st_ino) == (expected.st_dev, expected.st_ino), 'Invalid inherited build lock'
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        continue
    lock = (logs / name).open('a')
    try: fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError: raise SystemExit('Build is still running; no source files were changed')
    locks.append(lock)
plan = json.loads(plan_path.read_text())
record = root / 'goodman-sources.json'
previous = json.loads(record.read_text())
for path, expected in previous.items():
    target = (root / path).resolve()
    assert target.is_relative_to(root) and sha(target.read_bytes()) == expected, ('Modified installed source', path)

changes = []
for change in plan['changes']:
    target = (root / change['path']).resolve()
    assert target.is_relative_to(root)
    actual = sha(target.read_bytes()) if target.exists() else None
    assert actual in (change['before_sha256'], change['after_sha256']), ('Platform baseline changed', change['path'])
    data = (project / change['source']).read_bytes() if 'source' in change else change['after_text'].encode()
    assert sha(data) == change['after_sha256'], ('Prepared platform source changed', change['path'])
    if actual != change['after_sha256']: changes.append((target, data))

modules = ('vendor/goodman', 'packages/apps/GoodmanCarBridge', 'device/goodman/gsi', 'transport')
next_record = {p: h for p, h in previous.items() if not any(p.startswith(m + '/') for m in modules)}
next_record.update({item['path']: item['after_sha256'] for item in plan['changes']})
for module in modules:
    actual = inventory(root / module)
    expected_old = {p[len(module) + 1:]: h for p, h in previous.items() if p.startswith(module + '/')}
    assert actual == expected_old, ('Unrecorded files in installed module', module)
    updated = inventory(project / module)
    assert updated and set(actual) <= set(updated), ('Update must not silently delete source files', module)
    for relative, digest in updated.items():
        path = module + '/' + relative
        next_record[path] = digest
        if actual.get(relative) != digest:
            changes.append((root / path, (project / path).read_bytes()))

backup = None
if changes:
    backup = Path(tempfile.mkdtemp(prefix='before-vehicle-source-update-', dir=logs))
    shutil.copy2(record, backup / record.name)
for target, data in changes:
    relative = str(target.relative_to(root))
    if target.exists():
        saved = backup / relative; saved.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(target, saved)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_name(target.name + '.goodman-update')
    with temporary.open('xb') as stream: stream.write(data)
    temporary.replace(target)
    next_record[relative] = sha(data)
record.write_text(json.dumps(next_record, indent=2) + '\n')
platform_record = [{k: v for k, v in item.items() if k != 'after_text'} for item in plan['changes']]
(root / 'goodman-platform-sources.json').write_text(json.dumps(platform_record, indent=2) + '\n')
env = Path.home() / '.config/lineage-build/env.sh'
if 'export GOODMAN_LOW_MEMORY=1' not in env.read_text():
    with env.open('a') as stream: stream.write('\nexport GOODMAN_LOW_MEMORY=1\n')
pending = logs / 'pending-source-update.json'
if pending.exists(): pending.rename(logs / 'applied-vehicle-source-update.json')
print(json.dumps({'installed_files': len(changes), 'platform_files': len(platform_record),
                  'recorded_source_files': len(next_record), 'low_memory_soong_gogc': 50}))
