#!/usr/bin/env python3
"""Check deployed identity services against the reviewed local image references."""
import json
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
env = dict(os.environ, DOCKER_CONFIG=str(root/'.runtime/docker'))
if 'DOCKER_HOST' not in env:
    for socket in [Path.home()/'.docker/run/docker.sock', Path.home()/'.docker/desktop/docker.sock']:
        if socket.exists():
            env['DOCKER_HOST'] = 'unix://' + str(socket)
            break
pins = json.loads((root/'config/staging-images.json').read_text())
for name, reference in [('database', 'govdoc-postgres:local'), ('keycloak', 'govdoc-staging-keycloak')]:
    actual = json.loads(subprocess.check_output(['docker', 'container', 'inspect', 'govdoc-staging-'+name+'-1'], env=env))[0]
    expected = json.loads(subprocess.check_output(['docker', 'image', 'inspect', reference], env=env))[0]
    assert actual['Config']['Labels']['com.docker.compose.project'] == 'govdoc-staging'
    assert actual['State']['Running'] and actual['Image'] == expected['Id'], 'Identity image not applied'
    if name == 'keycloak':
        assert actual['Config']['User'] == '1000', 'Keycloak must run unprivileged'
    else:
        assert actual['Config']['User'] == '70:70', 'PostgreSQL must start unprivileged'
        assert any(m.get('Name') == 'govdoc-staging_keycloak-db-alpine-v1' for m in actual['Mounts']), 'Wrong database volume'
print('PASS: PostgreSQL and non-root Keycloak run the reviewed replacement images and migrated database volume.')
