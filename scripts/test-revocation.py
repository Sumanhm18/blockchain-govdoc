#!/usr/bin/env python3
"""Revoke only a newly created disposable identity and publish its CRL to the isolated channel."""
import base64, copy, json, os, secrets, shutil, subprocess, time
from pathlib import Path
os.umask(0o077)
root=Path(__file__).resolve().parents[1];fabric=root/'.runtime/fabric';net=fabric/'test-network';out=root/'.runtime/revocation-drill';out.mkdir(exist_ok=True)
org=net/'organizations/peerOrganizations/org1.example.com'
ca_tls=net/'organizations/fabric-ca/org1/tls-cert.pem'; client=fabric/'bin/fabric-ca-client'; peer=fabric/'bin/peer'; translator=fabric/'bin/configtxlator'
identity=out/('identity-'+secrets.token_hex(6));name=identity.name;password=secrets.token_urlsafe(32)
admin_env=dict(os.environ,FABRIC_CA_CLIENT_HOME=str(org))
base=dict(os.environ,FABRIC_CFG_PATH=str(fabric/'config'),CORE_PEER_TLS_ENABLED='true',CORE_PEER_LOCALMSPID='Org1MSP',CORE_PEER_ADDRESS='localhost:17051',CORE_PEER_TLS_ROOTCERT_FILE=str(org/'peers/peer0.org1.example.com/tls/ca.crt'))
log=(root/'.runtime/logs/revocation-drill.log').open('w')
def run(args,env=None):
    result=subprocess.run([str(x) for x in args],env=env,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=45)
    log.write(result.stdout.decode(errors='replace'));log.write(result.stderr.decode(errors='replace'));log.flush()
    if result.returncode:raise RuntimeError('Revocation drill command failed; inspect its protected local log.')
    return result.stdout
try:
    run([client,'register','--caname','ca-org1','--id.name',name,'--id.secret',password,'--id.type','client','--tls.certfiles',ca_tls],admin_env)
    run([client,'enroll','-u','https://'+name+':'+password+'@localhost:17054','--caname','ca-org1','-M',identity,'--tls.certfiles',ca_tls],admin_env)
    shutil.copyfile(org/'msp/config.yaml',identity/'config.yaml')
    identity_env=dict(base,CORE_PEER_MSPCONFIGPATH=str(identity))
    query=[peer,'chaincode','query','-C','govchannel','-n','docregistry','-c','{"Args":["ping"]}']
    run(query,identity_env)
    run([client,'revoke','--caname','ca-org1','-e',name,'--gencrl','--tls.certfiles',ca_tls],admin_env)
    crl=org/'msp/crls/crl.pem'
    if not crl.exists():raise RuntimeError('CA did not generate the expected CRL')
    verification=subprocess.run(['openssl','verify','-CAfile',str(next((identity/'cacerts').glob('*.pem'))),'-CRLfile',str(crl),'-crl_check',str(identity/'signcerts/cert.pem')],capture_output=True)
    if verification.returncode==0 or b'certificate revoked' not in (verification.stdout+verification.stderr).lower(): raise RuntimeError('CRL does not revoke the disposable certificate')
    der=run(['openssl','crl','-in',crl,'-outform','DER'])
    admin1=dict(base,CORE_PEER_MSPCONFIGPATH=str(org/'users/Admin@org1.example.com/msp'))
    orderer_ca=net/'organizations/ordererOrganizations/example.com/orderers/orderer.example.com/tls/ca.crt'
    orderer=['-o','localhost:17050','--ordererTLSHostnameOverride','orderer.example.com','--tls','--cafile',orderer_ca]
    block=out/'config.block';decoded=out/'block.json'
    run([peer,'channel','fetch','config',block,'-c','govchannel',*orderer],admin1)
    run([translator,'proto_decode','--input',block,'--type','common.Block','--output',decoded])
    config=json.loads(decoded.read_text())['data']['data'][0]['payload']['data']['config'];updated=copy.deepcopy(config)
    msp=updated['channel_group']['groups']['Application']['groups']['Org1MSP']['values']['MSP']['value']['config']
    msp['revocation_list']=[base64.b64encode(der).decode()]
    for filename,value in [('original',config),('updated',updated)]:
        source=out/(filename+'.json');source.write_text(json.dumps(value))
        run([translator,'proto_encode','--input',source,'--type','common.Config','--output',out/(filename+'.pb')])
    run([translator,'compute_update','--channel_id','govchannel','--original',out/'original.pb','--updated',out/'updated.pb','--output',out/'update.pb'])
    run([translator,'proto_decode','--input',out/'update.pb','--type','common.ConfigUpdate','--output',out/'update.json'])
    envelope={'payload':{'header':{'channel_header':{'channel_id':'govchannel','type':2}},'data':{'config_update':json.loads((out/'update.json').read_text())}}}
    (out/'envelope.json').write_text(json.dumps(envelope))
    run([translator,'proto_encode','--input',out/'envelope.json','--type','common.Envelope','--output',out/'envelope.pb'])
    org2=net/'organizations/peerOrganizations/org2.example.com'
    admin2=dict(base,CORE_PEER_LOCALMSPID='Org2MSP',CORE_PEER_ADDRESS='localhost:19051',CORE_PEER_TLS_ROOTCERT_FILE=str(org2/'peers/peer0.org2.example.com/tls/ca.crt'),CORE_PEER_MSPCONFIGPATH=str(org2/'users/Admin@org2.example.com/msp'))
    run([peer,'channel','signconfigtx','-f',out/'envelope.pb'],admin2)
    run([peer,'channel','update','-f',out/'envelope.pb','-c','govchannel',*orderer],admin1)
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        result=subprocess.run([str(x) for x in query],env=identity_env,capture_output=True,timeout=15)
        if result.returncode and b'error validating proposal: access denied' in (result.stdout+result.stderr).lower():break
        time.sleep(1)
    else:raise RuntimeError('Revoked identity was not demonstrably rejected by the peer')
    # Existing application identity must remain usable.
    run(query,dict(base,CORE_PEER_MSPCONFIGPATH=str(root/'.runtime/identity')))
    print('PASS: disposable identity accepted before revocation, rejected after CA revocation plus channel MSP CRL update; current service identity remains valid.')
finally:log.close()
