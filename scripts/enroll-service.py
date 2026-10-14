#!/usr/bin/env python3
"""Enroll an issuer service certificate; capture CA output without printing credentials."""
import json, os, secrets, shutil, subprocess
from pathlib import Path
os.umask(0o077)
root = Path(__file__).resolve().parents[1]
net = root / '.runtime/fabric/test-network'
identity = root / '.runtime/identity'
if (identity / 'signcerts/cert.pem').exists():
    raise SystemExit('Service identity already exists; use the documented rotation procedure rather than overwriting it.')
client = root / '.runtime/fabric/bin/fabric-ca-client'
ca = net / 'organizations/fabric-ca/org1/tls-cert.pem'
admin_home = net / 'organizations/peerOrganizations/org1.example.com'
secret = secrets.token_urlsafe(32)
env = dict(os.environ, FABRIC_CA_CLIENT_HOME=str(admin_home))
log_path = root / '.runtime/logs/enrollment.log'
with log_path.open('w') as log:
    def run(args, environment=env):
        subprocess.run([str(client)] + args, env=environment, stdout=log, stderr=log, check=True)
    run(['register', '--caname', 'ca-org1', '--id.name', 'registry-service', '--id.secret', secret, '--id.type', 'client', '--id.attrs', 'registry.role=issuer:ecert,registry.department=Org1:ecert', '--tls.certfiles', str(ca)])
    run(['enroll', '-u', 'https://registry-service:' + secret + '@localhost:17054', '--caname', 'ca-org1', '-M', str(identity), '--tls.certfiles', str(ca)])
keys = list((identity / 'keystore').iterdir())
if len(keys) != 1:
    raise SystemExit('Expected exactly one enrollment key; inspect protected enrollment log.')
keys[0].rename(identity / 'keystore/key.pem')
shutil.copyfile(net / 'organizations/peerOrganizations/org1.example.com/msp/config.yaml', identity / 'config.yaml')
shutil.copyfile(net / 'organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt', identity / 'tls-ca.pem')
for file in identity.rglob('*'):
    if file.is_file(): file.chmod(0o600)
print('New issuer service identity enrolled with department attributes. No old committed identity was reused.')
