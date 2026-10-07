#!/usr/bin/env python3
"""Exercise signed release packaging with a disposable fixture, never a real ROM."""
import hashlib
import json
import subprocess
import sys
import tempfile
from pathlib import Path

project = Path(__file__).resolve().parent.parent
key = Path(sys.argv[1])
with tempfile.TemporaryDirectory(prefix='rom-update-check-') as directory:
    root = Path(directory)
    image = root / 'fixture.img'
    content = bytearray(65536)
    content[1080:1082] = b'\x53\xef'
    image.write_bytes(content)
    digest = hashlib.sha256(content).hexdigest()
    image.with_suffix('.img.json').write_text(json.dumps({'bytes': len(content), 'sha256': digest, 'format': 'raw ext4'}))
    release = root / 'release'
    subprocess.run([sys.executable, str(project/'tools/package-rom-update.py'), str(image), '--repository',
                    'go1sk1/goodman-car-rom', '--build', 'fixture-not-for-publication', '--key', str(key),
                    '--output', str(release)],check=True,stdout=subprocess.DEVNULL)
    data = json.loads((release/'update.json').read_text())
    assert data['sha256'] == digest and data['parts'][0]['bytes'] == len(content)
    assert (release/'GoodmanCar-fixture-not-for-publication-system.img.part00').read_bytes() == content
    pub = root/'public.pem'
    subprocess.run(['openssl','pkey','-in',str(key),'-pubout','-out',str(pub)],check=True)
    verify = ['openssl','dgst','-sha256','-verify',str(pub),'-signature',str(release/'update.sig')]
    subprocess.run(verify+[str(release/'update.json')],check=True,stdout=subprocess.DEVNULL)
    (release/'update.json').write_bytes((release/'update.json').read_bytes()+b' ')
    assert subprocess.run(verify+[str(release/'update.json')],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode != 0
    print('PASS: release image bytes/hash match; valid signature accepted; modified manifest rejected.')
