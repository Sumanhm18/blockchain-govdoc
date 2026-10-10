#!/usr/bin/env python3
"""Demonstrate safe write failure and recovery, not HA, for the two-peer development policy."""
import json, os, secrets, subprocess, time
from pathlib import Path
import e2e
root=Path(__file__).resolve().parents[1]
env=dict(os.environ,DOCKER_CONFIG=str(root/'.runtime/docker'))
if 'DOCKER_HOST' not in env:
    for socket in [Path.home()/'.docker/run/docker.sock',Path.home()/'.docker/desktop/docker.sock']:
        if socket.exists():env['DOCKER_HOST']='unix://'+str(socket);break
name='govdoc-dev-peer0.org2.example.com'
info=json.loads(subprocess.check_output(['docker','inspect',name],env=env))[0]
assert info['Config']['Labels']['com.docker.compose.project']=='govdoc-dev-fabric' and info['State']['Running']
id='QUORUM-'+secrets.token_hex(6).upper();body={'docId':id,'hash':'sha256:'+'a'*64}
try:
    subprocess.run(['docker','stop',name],env=env,check=True,stdout=subprocess.DEVNULL)
    e2e.call('POST','/api/documents',503,body)
    e2e.call('GET','/api/documents/'+id,404,user='reader')
finally:
    subprocess.run(['docker','start',name],env=env,check=True,stdout=subprocess.DEVNULL)
# Gossip/endorser recovery can take several seconds; reconcile by reading before retrying.
deadline=time.monotonic()+90
while time.monotonic()<deadline:
    try:
        e2e.call('POST','/api/documents',200,body);break
    except AssertionError:
        try:
            e2e.call('GET','/api/documents/'+id,200,user='reader');break
        except AssertionError:time.sleep(2)
else:raise RuntimeError('Write did not recover after restoring the required endorser')
assert len(e2e.call('GET','/api/documents/'+id+'/history',200,user='reader')['events'])==1
e2e.call('POST','/api/documents/'+id+'/revoke',200,{'reason':'Synthetic quorum recovery check complete'})
print('PASS: missing required endorser prevents registration; restoring it permits one registration. This topology is not highly available.')
