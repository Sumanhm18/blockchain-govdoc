#!/usr/bin/env python3
"""Fault injection against this repository's container deployment, using synthetic data only."""
import json, os, secrets, subprocess, time
from pathlib import Path
from e2e import call, multipart, root

env = dict(os.environ, DOCKER_CONFIG=str(root / '.runtime/docker'))
if 'DOCKER_HOST' not in env:
    for socket in [Path.home() / '.docker/run/docker.sock', Path.home() / '.docker/desktop/docker.sock']:
        if socket.exists():
            env['DOCKER_HOST'] = 'unix://' + str(socket)
            break

def compose(*args):
    return subprocess.run(['docker', 'compose', *args], cwd=root, env=env, capture_output=True, check=True, timeout=90)

# Refuse to stop another project or mistake a host-run API for this project's container.
for service in ['api', 'ipfs']:
    container = compose('ps', '-q', service).stdout.decode().strip()
    if not container:
        raise SystemExit('Recovery check requires the running ./deploy.sh container stack.')
    info = json.loads(subprocess.check_output(['docker', 'inspect', container], env=env))[0]
    if info['Config']['Labels'].get('com.docker.compose.project') != 'govdoc-dev-app':
        raise SystemExit('Refusing fault injection outside the isolated development project.')

id = 'RECOVERY-' + secrets.token_hex(6).upper()
file = b'%PDF-1.4\nSynthetic restart recovery fixture\n%%EOF\n'
body, content_type = multipart({'docId': id}, file)
try:
    compose('stop', 'ipfs')
    pending = call('POST', '/api/documents/upload', 202, body, content_type=content_type)
    assert pending['code'] == 'REGISTRATION_PENDING'
    assert call('GET', '/api/documents/' + id + '/registration', 200)['state'] == 'STAGING'
    compose('restart', 'api')
finally:
    # Always restore IPFS, including on failed assertions.
    compose('start', 'ipfs')

deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    try:
        state = call('GET', '/api/documents/' + id + '/registration', 200)['state']
        if state == 'CONFIRMED':
            break
    except (OSError, AssertionError):
        pass
    time.sleep(2)
else:
    raise SystemExit('Registration did not reconcile after API restart and IPFS recovery.')
assert call('GET', '/api/documents/' + id + '/file', 200, user='reader', binary=True) == file
assert call('POST', '/api/documents/upload', 200, body, content_type=content_type)['docId'] == id
assert len(call('GET', '/api/documents/' + id + '/history', 200, user='reader')['events']) == 1
call('POST', '/api/documents/' + id + '/revoke', 200, {'reason': 'Synthetic recovery check complete'})
print('PASS: IPFS outage stages ciphertext; API restart preserves it; recovery commits once, downloads correctly and accepts an identical retry.')
