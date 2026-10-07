#!/usr/bin/env python3
"""Export a raw EXT4 GSI only after inspecting required contents in the image."""
import hashlib
import argparse
import re
import json
import shutil
import struct
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('root', type=Path)
parser.add_argument('product', type=Path)
parser.add_argument('--variant', default='', help='Save a reviewed follow-up build in its own artifact subdirectory')
args = parser.parse_args()
if args.variant and not re.fullmatch(r'[a-z][a-z0-9-]{0,63}', args.variant):
    parser.error('Invalid artifact variant')
root, product = args.root.resolve(), args.product.resolve()
pending_update = Path.home() / 'android/logs/pending-source-update.json'
if pending_update.exists():
    raise SystemExit('New vehicle features are queued; install and build the reviewed update before exporting')
source = product / 'system.img'
source_record = root / 'goodman-sources.json'
source_hashes = json.loads(source_record.read_text())
if not source_hashes:
    raise SystemExit('Custom ROM source record is empty')
for relative, expected in source_hashes.items():
    installed = (root / relative).resolve()
    if not installed.is_relative_to(root) or hashlib.sha256(installed.read_bytes()).hexdigest() != expected:
        raise SystemExit('Custom source changed after installation: ' + relative)
artifacts = Path.home() / 'android/artifacts'
if args.variant:
    artifacts = artifacts / args.variant
artifacts.mkdir(exist_ok=True)
image = artifacts / 'GoodmanCar-lineage23.2-arm64-GAPPS-EXT4-development-system.img'
if image.exists():
    raise SystemExit('Existing artifact: archive it explicitly before exporting another build')
temporary = image.with_suffix('.img.partial')
if temporary.exists():
    raise SystemExit('Previous partial artifact requires inspection')
with source.open('rb') as stream:
    sparse = stream.read(4) == struct.pack('<I', 0xED26FF3A)
if sparse:
    subprocess.run([str(root / 'out/host/linux-x86/bin/simg2img'), str(source), str(temporary)], check=True)
else:
    shutil.copyfile(source, temporary)
with temporary.open('rb') as stream:
    stream.seek(1024 + 56)
    if stream.read(2) != b'\x53\xef':
        raise SystemExit('Artifact is not an EXT4 filesystem')
# /proc/partitions reports KiB. This is the attached SM-N976N, not all variants.
note10_limit = 6328320 * 1024
if temporary.stat().st_size > note10_limit:
    raise SystemExit('Image exceeds observed Note10+ system partition (6480199680 bytes)')
subprocess.run(['e2fsck', '-fn', str(temporary)], check=True)

def debug(command):
    return subprocess.run(['debugfs', '-R', command, str(temporary)], text=True, capture_output=True, check=True).stdout

prefix = '/system' if 'Type: directory' in debug('stat /system') else ''
with tempfile.TemporaryDirectory(prefix='gsi-audit-') as scratch:
    scratch = Path(scratch)
    allowlist = scratch / 'GoodmanCarBridge.xml'
    debug(f'dump {prefix}/system_ext/etc/permissions/GoodmanCarBridge.xml {allowlist}')
    if not allowlist.is_file():
        raise SystemExit('HFP app privileged permission allowlist is missing from system_ext')
    granted = {node.get('name') for package in ET.parse(allowlist).getroot().findall('privapp-permissions')
               if package.get('package') == 'kr.goodman.carbridge'
               for node in package.findall('permission')}
    if not {'android.permission.BLUETOOTH_PRIVILEGED', 'android.permission.CAPTURE_VIDEO_OUTPUT'} <= granted:
        raise SystemExit('HFP/capture privileged permissions are absent from the installed allowlist')
    property_text = ''
    for relative in ('/build.prop', '/etc/build.prop'):
        prop = scratch / ('properties-' + relative.replace('/', '_'))
        debug(f'dump {prefix}{relative} {prop}')
        if prop.exists():
            property_text += prop.read_text(errors='replace') + '\n'
    hfp_values = []
    for line in property_text.splitlines():
        key, separator, value = line.strip().partition('=')
        if separator and key == 'bluetooth.profile.hfp.hf.enabled':
            hfp_values.append(value.strip())
    if not hfp_values or any(value != 'true' for value in hfp_values):
        raise SystemExit('Required HFP HF property not found in system image')
    packages = set()
    candidates = []
    for folder in ('/priv-app', '/app', '/system_ext/priv-app', '/product/priv-app', '/product/app'):
        base = prefix + folder
        for line in debug('ls -p ' + base).splitlines():
            fields = line.split('/')
            if len(fields) > 5 and any(key in fields[5].lower() for key in ('goodmancar', 'gms', 'phonesky', 'vending', 'playstore', 'androidauto')):
                candidates.append(base + '/' + fields[5])
    for folder in candidates:
        for line in debug('ls -p ' + folder).splitlines():
            fields = line.split('/')
            if len(fields) <= 5 or not fields[5].endswith('.apk'):
                continue
            apk = scratch / 'inspect.apk'
            if apk.exists():
                apk.unlink()
            debug(f'dump {folder}/{fields[5]} {apk}')
            if not apk.exists():
                continue
            result = subprocess.check_output([str(root / 'out/host/linux-x86/bin/aapt2'), 'dump', 'badging', str(apk)], text=True)
            first = result.splitlines()[0]
            if first.startswith("package: name='"):
                packages.add(first.split("'")[1])
    required = {'kr.goodman.carbridge', 'com.google.android.gms', 'com.android.vending',
                'com.google.android.projection.gearhead'}
    if not required <= packages:
        raise SystemExit('Required APKs missing from image: ' + str(required - packages))

digest = hashlib.sha256()
with temporary.open('rb') as stream:
    for chunk in iter(lambda: stream.read(8 * 1024 * 1024), b''):
        digest.update(chunk)
temporary.rename(image)
image.with_suffix('.img.sha256').write_text(digest.hexdigest() + '  ' + image.name + '\n')
image.with_suffix('.img.json').write_text(json.dumps({
    'image': image.name, 'sha256': digest.hexdigest(), 'bytes': image.stat().st_size,
    'format': 'raw ext4', 'packages_verified': sorted(packages), 'hfp_hf_property': True,
    'privileged_permissions_verified': sorted(granted),
    'note10_system_partition_bytes': note10_limit,
    'boot_verified': False, 's21_partition_verified': False,
    'mirror_controls_source_included': 'packages/apps/GoodmanCarBridge/src/kr/goodman/carbridge/MirrorExperience.java' in source_hashes,
    'mirror_controls_device_verified': False,
    'assistant_apps_bundled': False,
    'vehicle_mirroring_complete': False, 'hfp_audio_verified': False,
    'vehicle_controls_audio_microphone_gps_source_included': True,
    'vehicle_transport_implemented': False,
    'vehicle_transport_source_included': 'transport/integration/kr/goodman/carbridge/ProjectionCoordinator.java' in source_hashes,
    'projection_profile': 'legacy GAL 1.1 USB; ccNC and wireless setup pending'
        if 'transport/integration/kr/goodman/carbridge/ProjectionCoordinator.java' in source_hashes else 'not integrated',
    'vehicle_microphone_and_gps_verified': False,
    'android_auto_package_role': 'MindTheGapps AndroidAutoStub; full application update and vehicle tests pending',
    'build_type': 'userdebug development, not production release'
    , 'rom_update_source_included': 'packages/apps/GoodmanCarBridge/src/kr/goodman/carbridge/RomUpdateService.java' in source_hashes
    , 'rom_update_device_verified': False
}, indent=2) + '\n')
shutil.copyfile(Path.home() / 'android/logs/source-lock.xml', artifacts / 'source-lock.xml')
shutil.copyfile(root / 'goodman-patches.json', artifacts / 'goodman-patches.json')
shutil.copyfile(source_record, artifacts / 'goodman-sources.json')
shutil.copyfile(root / 'goodman-platform-sources.json', artifacts / 'goodman-platform-sources.json')
print('EXPORTED ' + str(image))
