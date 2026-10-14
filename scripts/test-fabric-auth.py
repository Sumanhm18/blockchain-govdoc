#!/usr/bin/env python3
"""Exercise chaincode authorization directly, bypassing HTTP roles; never submit a transaction."""
import json, os, secrets, subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[1]
network = root / '.runtime/fabric/test-network'
org = network / 'organizations/peerOrganizations/org1.example.com'
base = dict(os.environ, FABRIC_CFG_PATH=str(root / '.runtime/fabric/config'),
    CORE_PEER_TLS_ENABLED='true', CORE_PEER_LOCALMSPID='Org1MSP',
    CORE_PEER_ADDRESS='localhost:17051',
    CORE_PEER_TLS_ROOTCERT_FILE=str(org / 'peers/peer0.org1.example.com/tls/ca.crt'))
for identity, issuer in [(org / 'users/User1@org1.example.com/msp', 'Org1'), (root / '.runtime/identity', 'OtherDepartment')]:
    env = dict(base, CORE_PEER_MSPCONFIGPATH=str(identity))
    args = ['registerDocument', 'DENIED-' + secrets.token_hex(6).upper(), 'sha256:' + 'a' * 64, issuer, '', '{}']
    result = subprocess.run([str(root / '.runtime/fabric/bin/peer'), 'chaincode', 'query', '-C', 'govchannel', '-n', 'docregistry', '-c', json.dumps({'Args': args})], env=env, capture_output=True, timeout=30)
    if result.returncode == 0 or b'REGISTRY_FORBIDDEN:' not in result.stderr + result.stdout:
        raise SystemExit('Direct chaincode authorization check failed; no response body was printed.')
print('PASS: 2 direct Fabric checks reject an unattributed identity and department impersonation.')
