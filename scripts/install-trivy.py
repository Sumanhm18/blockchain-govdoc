#!/usr/bin/env python3
"""Install the pinned scanner after verifying its recorded release SHA-256."""
import hashlib
import io
import json
import os
import platform
import tarfile
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1]
config = json.loads((root / 'config/trivy.json').read_text())
system = {'Linux': 'Linux', 'Darwin': 'macOS'}.get(platform.system())
arch = {'x86_64': '64bit', 'amd64': '64bit', 'arm64': 'ARM64', 'aarch64': 'ARM64'}.get(platform.machine().lower())
name = f"trivy_{config['version']}_{system}-{arch}.tar.gz"
if name not in config['archives']:
    raise SystemExit('Unsupported scanner platform')
url = f"https://github.com/aquasecurity/trivy/releases/download/v{config['version']}/{name}"
with urllib.request.urlopen(url, timeout=120) as response:
    archive = response.read()
if hashlib.sha256(archive).hexdigest() != config['archives'][name]:
    raise SystemExit('Scanner archive checksum mismatch')
destination = root / '.runtime/tools/trivy/trivy'
destination.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
with tarfile.open(fileobj=io.BytesIO(archive), mode='r:gz') as bundle:
    member = bundle.getmember('trivy')
    if not member.isfile():
        raise SystemExit('Scanner archive does not contain a regular binary')
    binary = bundle.extractfile(member).read()
temporary = destination.with_suffix('.download')
temporary.write_bytes(binary)
temporary.chmod(0o700)
os.replace(temporary, destination)
print('Installed checksum-verified Trivy ' + config['version'])
