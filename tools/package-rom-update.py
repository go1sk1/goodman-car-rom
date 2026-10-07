#!/usr/bin/env python3
"""Package an exported image as signed GitHub Release assets, without uploading it."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('image', type=Path)
p.add_argument('--repository', required=True)
p.add_argument('--build', required=True)
p.add_argument('--key', type=Path, required=True, help='Private RSA signing key outside this project')
p.add_argument('--output', type=Path, required=True)
p.add_argument('--notes', default='Development GSI; device boot and vehicle features are not verified.')
a = p.parse_args()
if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', a.repository): p.error('Invalid repository')
if not re.fullmatch(r'[A-Za-z0-9._-]{1,80}', a.build): p.error('Invalid build')
meta = json.loads(a.image.with_suffix('.img.json').read_text())
size = a.image.stat().st_size
if size != meta['bytes'] or meta['format'] != 'raw ext4' or not 0 < size <= 6 * 1024**3:
    p.error('Exported image metadata does not match')
if a.output.exists(): p.error('Output must be a new directory; preserve existing releases')
a.output.mkdir(parents=True)
parts = []
full = hashlib.sha256()
chunk_size = 1536 * 1024**2
with a.image.open('rb') as src:
    for index, start in enumerate(range(0, size, chunk_size)):
        name = f'GoodmanCar-{a.build}-system.img.part{index:02d}'
        digest = hashlib.sha256()
        remaining = min(chunk_size, size-start)
        part_size = remaining
        with (a.output / name).open('xb') as out:
            while remaining:
                block = src.read(min(8*1024**2, remaining))
                if not block: raise SystemExit('Unexpected image EOF; do not publish this directory')
                out.write(block); digest.update(block); full.update(block); remaining -= len(block)
        parts.append({'url': f'https://github.com/{a.repository}/releases/download/{a.build}/{name}',
                      'bytes': part_size, 'sha256': digest.hexdigest()})
if full.hexdigest() != meta['sha256']:
    raise SystemExit('Image changed since export; do not publish this directory')
payload = {'schema': 1, 'product': 'GoodmanCar-arm64-ext4', 'build': a.build,
           'format': 'raw ext4', 'bytes': size, 'sha256': full.hexdigest(),
           'notes': a.notes, 'parts': parts, 'device_verified': False}
manifest = a.output / 'update.json'
manifest.write_bytes((json.dumps(payload, ensure_ascii=False, indent=2)+'\n').encode())
subprocess.run(['openssl','dgst','-sha256','-sign',str(a.key),'-out',str(a.output/'update.sig'),str(manifest)],check=True)
print(f'Release files ready: {a.output}. Upload all parts plus update.json and update.sig together.')
