#!/usr/bin/env python3
"""Verify that running managed services and chaincode actually use the patched images."""
import json
import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]
env = dict(os.environ, DOCKER_CONFIG=str(root / '.runtime/docker'))
if 'DOCKER_HOST' not in env:
    for socket in [Path.home() / '.docker/run/docker.sock', Path.home() / '.docker/desktop/docker.sock']:
        if socket.exists():
            env['DOCKER_HOST'] = 'unix://' + str(socket)
            break


def inspect(kind, name):
    return json.loads(subprocess.check_output(['docker', kind, 'inspect', name], env=env))[0]


patched = json.loads((root / '.runtime/fabric-images.json').read_text())
node = json.loads((root / '.runtime/node-runtime.json').read_text())
for component, names in {
    'fabric-peer': ['govdoc-dev-peer0.org1.example.com', 'govdoc-dev-peer0.org2.example.com'],
    'fabric-orderer': ['govdoc-dev-orderer.example.com'],
    'fabric-ca': ['govdoc-dev-ca_org1', 'govdoc-dev-ca_org2', 'govdoc-dev-ca_orderer'],
}.items():
    expected = next(value['image_id'] for key, value in patched.items() if key.startswith('hyperledger/' + component + ':'))
    for name in names:
        actual = inspect('container', name)
        assert actual['State']['Running'], name + ' is not running'
        assert actual['Config']['Labels']['com.docker.compose.project'] == 'govdoc-dev-fabric', 'Unexpected service owner'
        assert actual['Image'] == expected, name + ' has not applied the patched image'
        if component == 'fabric-peer':
            assert 'CORE_CHAINCODE_NODE_RUNTIME=' + node['reference'] in actual['Config']['Env'], 'Peer runtime setting is stale'
base_layers = inspect('image', node['image_id'])['RootFS']['Layers']
ids = subprocess.check_output(['docker', 'ps', '--filter', 'network=govdoc_dev', '-q'], env=env, text=True).split()
count = 0
for container_id in ids:
    actual = inspect('container', container_id)
    if actual['Name'].startswith(('/dev-peer0.org1.example.com-docregistry_', '/dev-peer0.org2.example.com-docregistry_')):
        layers = inspect('image', actual['Image'])['RootFS']['Layers']
        assert layers[:len(base_layers)] == base_layers, 'A running registry chaincode container uses an old runtime'
        count += 1
assert count >= 2, 'Expected active registry chaincode on both peers'
print('PASS: all six managed Fabric services and both chaincode containers use the patched image contents.')
