#!/usr/bin/env python3
"""Encrypted cold snapshot of the owned local Fabric network before a patch upgrade."""
import io
import json
import os
import secrets
import subprocess
import tarfile
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

root=Path(__file__).resolve().parents[1]
os.chdir(root);os.umask(0o077)
env=dict(os.environ,DOCKER_CONFIG=str(root/'.runtime/docker'))
if 'DOCKER_HOST' not in env:
    for socket in [Path.home()/'.docker/run/docker.sock',Path.home()/'.docker/desktop/docker.sock']:
        if socket.exists():env['DOCKER_HOST']='unix://'+str(socket);break

def docker(*args):
    return subprocess.check_output(['docker',*args],env=env,stderr=subprocess.DEVNULL,timeout=180)

names=['govdoc-staging-api-1','govdoc-dev-app-api-1','govdoc-dev-peer0.org1.example.com',
       'govdoc-dev-peer0.org2.example.com','govdoc-dev-orderer.example.com',
       'govdoc-dev-ca_org1','govdoc-dev-ca_org2','govdoc-dev-ca_orderer']
info=[json.loads(docker('inspect',name))[0] for name in names]
for item in info:
    assert item['Config']['Labels']['com.docker.compose.project'] in {'govdoc-dev-fabric','govdoc-dev-app','govdoc-staging'}
stopped=[]
try:
    for item in info:
        if item['State']['Running']:
            docker('stop',item['Id']);stopped.append(item['Id'])
    memory=io.BytesIO()
    with tarfile.open(fileobj=memory,mode='w:gz') as archive:
        archive.add(root/'.runtime/fabric/test-network/organizations',arcname='organizations')
        archive.add(root/'.runtime/fabric/test-network/compose',arcname='compose')
        metadata=json.dumps([{'name':i['Name'],'image':i['Image'],'mounts':i['Mounts']} for i in info]).encode()
        header=tarfile.TarInfo('inventory.json');header.size=len(metadata);archive.addfile(header,io.BytesIO(metadata))
        volumes={m['Name'] for i in info if i['Config']['Labels']['com.docker.compose.project']=='govdoc-dev-fabric'
                 for m in i['Mounts'] if m['Type']=='volume'}
        for volume in sorted(volumes):
            payload=docker('run','--rm','--network','none','--entrypoint','tar','-v',volume+':/snapshot:ro',
                           'govdoc-fabric-peer:build','-C','/snapshot','-cf','-','.')
            header=tarfile.TarInfo('volumes/'+volume+'.tar');header.size=len(payload)
            archive.addfile(header,io.BytesIO(payload))
    key=root/'.runtime/secrets/fabric-backup.key'
    if not key.exists():key.write_bytes(AESGCM.generate_key(bit_length=256))
    cipher=AESGCM(key.read_bytes());nonce=os.urandom(12)
    encrypted=cipher.encrypt(nonce,memory.getvalue(),b'govdoc-fabric-cold-snapshot-v1')
    assert cipher.decrypt(nonce,encrypted,b'govdoc-fabric-cold-snapshot-v1')==memory.getvalue()
    path=root/'.runtime/backups'/('fabric-'+secrets.token_hex(8)+'.tar.gcm');path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(b'GFAB01'+nonce+encrypted)
    print('Encrypted Fabric cold snapshot retained at '+str(path.relative_to(root))+'. This does not constitute a ledger restore drill.')
finally:
    for container_id in reversed(stopped):docker('start',container_id)
