#!/usr/bin/env python3
"""Install Linux SDK tools from Google's signed-transport repository metadata."""
import hashlib
import os
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

sdk = Path.home() / 'android/sdk'
sdk.mkdir(parents=True, exist_ok=True)
manager = sdk / 'cmdline-tools/latest/bin/sdkmanager'
if not manager.exists():
    metadata = urllib.request.urlopen('https://dl.google.com/android/repository/repository2-1.xml', timeout=120).read()
    tree = ET.fromstring(metadata)
    def local(element):
        return element.tag.rsplit('}', 1)[-1]
    for element in tree.iter():
        element.tag = local(element)
    choices = []
    for package in tree.findall('remotePackage'):
        if not package.get('path', '').startswith('cmdline-tools;'):
            continue
        if package.find('channelRef') is not None and package.find('channelRef').get('ref') != 'channel-0':
            continue
        revision = package.find('revision')
        version = tuple(int(revision.findtext(k, '0')) for k in ('major', 'minor', 'micro'))
        for archive in package.findall('./archives/archive'):
            if archive.findtext('host-os') == 'linux':
                choices.append((version, archive.find('complete')))
    _, archive = max(choices, key=lambda item: item[0])
    url = 'https://dl.google.com/android/repository/' + archive.findtext('url')
    download = sdk / 'commandlinetools.zip'
    print('Downloading ' + url, flush=True)
    urllib.request.urlretrieve(url, download)
    expected = archive.findtext('checksum').strip()
    checksum_type = archive.find('checksum').get('type', 'sha1')
    digest = hashlib.new(checksum_type)
    with download.open('rb') as src:
        for chunk in iter(lambda: src.read(1024 * 1024), b''):
            digest.update(chunk)
    if download.stat().st_size != int(archive.findtext('size')) or digest.hexdigest() != expected:
        raise SystemExit('SDK archive integrity check failed')
    staging = sdk / 'commandlinetools-staging'
    with zipfile.ZipFile(download) as bundle:
        for name in bundle.namelist():
            if not (staging / name).resolve().is_relative_to(staging.resolve()):
                raise SystemExit('Unsafe SDK archive path')
        bundle.extractall(staging)
    (sdk / 'cmdline-tools').mkdir(exist_ok=True)
    shutil.move(str(staging / 'cmdline-tools'), str(sdk / 'cmdline-tools/latest'))
    for binary in (sdk / 'cmdline-tools/latest/bin').iterdir():
        binary.chmod(binary.stat().st_mode | 0o111)
    download.unlink()
env = dict(os.environ, JAVA_HOME='/usr/lib/jvm/java-17-openjdk-amd64')
subprocess.run([str(manager), '--sdk_root=' + str(sdk), '--licenses'], input='y\n' * 100, text=True, env=env, check=True)
subprocess.run([str(manager), '--sdk_root=' + str(sdk), 'platforms;android-33', 'build-tools;30.0.3', 'platform-tools'], input='y\n' * 100, text=True, env=env, check=True)
print('SDK_READY ' + str(sdk))
