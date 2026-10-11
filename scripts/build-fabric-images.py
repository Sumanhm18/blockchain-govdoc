#!/usr/bin/env python3
"""Build OS-patched Fabric images and record content-addressed local references."""
import concurrent.futures
import json
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
os.chdir(root)
os.umask(0o077)
pins = json.loads((root / 'config/images.json').read_text())
logs = root / '.runtime/logs'
logs.mkdir(parents=True, exist_ok=True)


def build(item):
    name, base = item
    component = name.split('/')[1].split(':')[0]
    tag = 'govdoc-' + component + ':build'
    with (logs / (component + '-patch-build.log')).open('w') as log:
        subprocess.run(['docker', 'build', '--build-arg', 'BASE_IMAGE=' + base,
                        '-t', tag, '-f', 'deploy/fabric-os-patch.Dockerfile', 'deploy'],
                       check=True, stdout=log, stderr=log, timeout=1800)
    image_id = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', tag], text=True).strip()
    reference = 'govdoc-' + component + ':' + image_id.removeprefix('sha256:')
    subprocess.run(['docker', 'tag', image_id, reference], check=True)
    print('Built patched ' + component, flush=True)
    return name, {'reference': reference, 'image_id': image_id, 'base': base}


selected = [(name, pin) for name, pin in pins.items()
            if name.startswith(('hyperledger/fabric-peer:', 'hyperledger/fabric-orderer:', 'hyperledger/fabric-ca:'))]
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    result = dict(pool.map(build, selected))
path = root / '.runtime/fabric-images.json'
temporary = path.with_suffix('.tmp')
temporary.write_text(json.dumps(result, indent=2))
temporary.replace(path)
