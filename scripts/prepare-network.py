#!/usr/bin/env python3
"""Materialize the pinned upstream sample in ignored runtime storage."""
import hashlib, json, os, platform, re, secrets, shutil, tarfile, urllib.request
from pathlib import Path
os.umask(0o077)
root = Path(__file__).resolve().parents[1]
runtime = root / '.runtime/fabric'
runtime.mkdir(parents=True, exist_ok=True)
platform_name = {'Darwin': 'darwin', 'Linux': 'linux'}.get(platform.system())
arch = {'arm64': 'arm64', 'aarch64': 'arm64', 'x86_64': 'amd64', 'AMD64': 'amd64'}.get(platform.machine())
if not platform_name or not arch:
    raise SystemExit('Supported development hosts: macOS/Linux on amd64 or arm64')
versions = [('fabric', '2.5.12', 'peer'), ('fabric-ca', '1.5.15', 'fabric-ca-client')]
for project, version, binary in versions:
    stamp = runtime / (project + '.version')
    if (runtime / 'bin' / binary).is_file() and stamp.exists() and stamp.read_text() == version:
        continue
    asset = f'hyperledger-{project}-{platform_name}-{arch}-{version}.tar.gz'
    archive = root / '.runtime' / asset
    url = f'https://github.com/hyperledger/{project}/releases/download/v{version}/{asset}'
    print(f'Downloading pinned {project} {version} for {platform_name}/{arch}', flush=True)
    urllib.request.urlretrieve(url, archive)
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    # Old upstream releases have no published checksum asset. Record the TLS-fetched artifact digest.
    archive.with_suffix(archive.suffix + '.sha256').write_text(digest + '\n')
    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            destination = (runtime / member.name).resolve()
            if not destination.is_relative_to(runtime.resolve()) or not (member.isfile() or member.isdir()):
                raise SystemExit('Unsafe archive member rejected')
        tar.extractall(runtime, filter='data')
    stamp.write_text(version)
network = runtime / 'test-network'
if not network.exists():
    shutil.copytree(root / 'fabric-samples/test-network', network)
    substitutions = {}
    for file in network.rglob('*'):
        if not file.is_file() or file.suffix not in ('.sh', '.yaml', '.yml'):
            continue
        content = file.read_text()
        for secret in re.findall(r'--id.secret\s+([^\s"\']+)', content):
            if '$' not in secret:
                substitutions.setdefault(secret, secrets.token_urlsafe(24))
        for secret in re.findall(r'https?://[^\s:@]+:([^\s@]+)@', content):
            if '$' not in secret:
                substitutions.setdefault(secret, secrets.token_urlsafe(24))
    # The copied sample's CA bootstrap and enrollment scripts must use the same generated secret.
    for file in network.rglob('*'):
        if not file.is_file() or file.suffix not in ('.sh', '.yaml', '.yml'):
            continue
        content = file.read_text()
        for original, replacement in sorted(substitutions.items(), key=lambda p: -len(p[0])):
            content = content.replace(original, replacement)
        content = content.replace('hyperledger/fabric-peer:latest', 'hyperledger/fabric-peer:2.5.12')
        content = content.replace('hyperledger/fabric-orderer:latest', 'hyperledger/fabric-orderer:2.5.12')
        content = content.replace('hyperledger/fabric-ca:latest', 'hyperledger/fabric-ca:1.5.15')
        content = content.replace('fabric_test', 'govdoc_dev')
        pins = json.loads((root / 'config/images.json').read_text())
        for image, pinned in pins.items():
            content = content.replace(image, pinned)
        if file.name == 'compose-test-net.yaml':
            content = content.replace('      - CORE_PEER_TLS_ENABLED=true', '      - CORE_CHAINCODE_NODE_RUNTIME=' + pins['hyperledger/fabric-nodeenv:2.5'] + '\n      - CORE_CHAINCODE_BUILDER=' + pins['hyperledger/fabric-ccenv:2.5.12'] + '\n      - CORE_PEER_TLS_ENABLED=true')

        if file.suffix in ('.yaml', '.yml'):
            content = re.sub(r'(?m)^(\s*-\s*)["\']?(\d+):(\d+)["\']?\s*$', lambda m: f'{m.group(1)}"127.0.0.1:{int(m.group(2))+10000}:{m.group(3)}"', content)
            content = re.sub(r'(container_name:\s*)([^\s]+)', r'\1govdoc-dev-\2', content)
        for port in [7050, 7051, 7053, 7054, 8054, 9051, 9054]:
            content = content.replace(f'localhost:{port}', f'localhost:{port+10000}')
            content = content.replace(f'localhost-{port}', f'localhost-{port+10000}')
        file.write_text(content)
    (root / '.runtime/secrets/ca-bootstrap.json').write_text(json.dumps(substitutions))
    print('Prepared private development network from the pinned Fabric samples revision.')
print('Fabric binaries and network configuration are ready.')
