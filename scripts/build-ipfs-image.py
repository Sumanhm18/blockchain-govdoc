#!/usr/bin/env python3
"""Build the source-pinned Kubo dependency patch without restarting storage."""
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
os.umask(0o077)
pins = json.loads((ROOT / 'config/images.json').read_text())
runtime = json.loads((ROOT / 'config/ipfs-runtime.json').read_text())
mod = (ROOT / 'deploy/ipfs-runtime/go.mod').read_text()
for module, version in runtime['modules'].items():
    if module + ' ' + version not in mod:
        raise SystemExit('Kubo module lock does not match reviewed version pins')
log = ROOT / '.runtime/logs/ipfs-runtime-build.log'
log.parent.mkdir(parents=True, exist_ok=True)
command = ['docker', 'build', '-t', 'govdoc-ipfs:build', '--build-arg',
           'IPFS_IMAGE=' + pins['ipfs/kubo:v0.43.1'], '--build-arg', 'GO_IMAGE=' + runtime['go_image'],
           '--build-arg', 'KUBO_COMMIT=' + runtime['commit'],
           '--build-arg', 'KUBO_SOURCE_SHA256=' + runtime['source_sha256'],
           '-f', 'deploy/ipfs.Dockerfile', 'deploy']
with log.open('w') as output:
    subprocess.run(command, check=True, stdout=output, stderr=output, timeout=1800)
image_id = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', 'govdoc-ipfs:build'], text=True).strip()
subprocess.run(['docker', 'tag', image_id, 'govdoc-ipfs:local'], check=True)
reference = 'govdoc-ipfs:' + image_id.removeprefix('sha256:')
subprocess.run(['docker', 'tag', image_id, reference], check=True)
path = ROOT / '.runtime/ipfs-image.json'
temp = path.with_suffix('.tmp')
temp.write_text(json.dumps({'image_id': image_id, 'reference': reference, 'source_commit': runtime['commit']}, indent=2))
temp.replace(path)
print('Built source-pinned IPFS runtime: ' + image_id)
