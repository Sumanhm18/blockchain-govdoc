#!/usr/bin/env python3
"""Restart only this project's already-prepared demo containers; never build or pull."""
import argparse
import ssl
import time
import urllib.request
import json
import os
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
env = dict(os.environ, DOCKER_CONFIG=str(ROOT / '.runtime/docker'))
if 'DOCKER_HOST' not in env:
    for socket in [Path.home() / '.docker/run/docker.sock', Path.home() / '.docker/desktop/docker.sock']:
        if socket.exists():
            env['DOCKER_HOST'] = 'unix://' + str(socket)
            break


def main(citizen=False):
    groups = {
        'govdoc-dev-fabric': ['govdoc-dev-ca_org1', 'govdoc-dev-ca_org2', 'govdoc-dev-ca_orderer',
                              'govdoc-dev-orderer.example.com', 'govdoc-dev-peer0.org1.example.com',
                              'govdoc-dev-peer0.org2.example.com'],
        'govdoc-dev-app': ['govdoc-dev-app-ipfs-1', 'govdoc-dev-app-api-1'],
    }
    if citizen:
        groups["govdoc-staging"] = ["govdoc-staging-database-1", "govdoc-staging-keycloak-1", "govdoc-staging-api-1"]
    stopped = []
    # Validate the entire set before starting anything.
    for project, names in groups.items():
        for name in names:
            item = json.loads(subprocess.check_output(['docker', 'inspect', name], env=env, stderr=subprocess.DEVNULL, timeout=15))[0]
            if item['Config'].get('Labels', {}).get('com.docker.compose.project') != project:
                raise RuntimeError('Unexpected owner for ' + name)
            if not item['State']['Running']:
                stopped.append(name)
    if stopped:
        subprocess.run(['docker', 'start', *stopped], env=env, check=True, stdout=subprocess.DEVNULL, timeout=120)
    subprocess.run(['python3', 'scripts/wait-ready.py', 'http://127.0.0.1:18081/actuator/health/readiness'], cwd=ROOT, check=True)
    if citizen:
        wait_identity()
        subprocess.run(['python3', 'scripts/wait-staging.py'], cwd=ROOT, check=True)
    print('Demo services ready. No images pulled, credentials regenerated or data reset. Run ./demo.sh --pause.')


def wait_identity():
    context = ssl.create_default_context(cafile=str(ROOT / '.runtime/staging/tls/ca.pem'))
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen('https://localhost:18443/realms/govdoc-lab/.well-known/openid-configuration', context=context, timeout=5) as response:
                if response.status == 200 and json.load(response).get('issuer') == 'https://identity.govdoc.local:18443/realms/govdoc-lab':
                    print('Citizen identity provider ready.')
                    return
        except (OSError, ValueError):
            pass
        time.sleep(2)
    raise RuntimeError('Citizen identity provider is not ready')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--citizen", action="store_true", help="Also resume the prepared HTTPS citizen/identity services")
    args = parser.parse_args()
    try:
        main(args.citizen)
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError):
        raise SystemExit('Cannot resume the prepared demo. Start Docker and confirm this project was deployed with ./deploy.sh (and ./scripts/staging.sh up for citizen mode); inspect protected logs if readiness fails.')
