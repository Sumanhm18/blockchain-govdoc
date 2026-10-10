#!/usr/bin/env python3
"""Generate isolated TLS/OIDC lab credentials; never print their values."""
import base64, json, os, secrets, subprocess, uuid
from pathlib import Path
os.umask(0o077)
root=Path(__file__).resolve().parents[1]; folder=root/'.runtime/staging'
for directory in ['tls','private','data','logs','import']:
    (folder/directory).mkdir(parents=True,exist_ok=True)
def run(args): subprocess.run(args,check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
ca=folder/'tls/ca.pem'; ca_key=folder/'private/ca.key'
if not ca.exists():
    run(['openssl','req','-x509','-newkey','rsa:3072','-nodes','-keyout',str(ca_key),'-out',str(ca),'-days','30','-subj','/CN=Govdoc isolated staging CA','-addext','basicConstraints=critical,CA:TRUE','-addext','keyUsage=critical,keyCertSign,cRLSign'])
for name in ['identity','api']:
    cert=folder/f'tls/{name}.pem'; key=folder/f'tls/{name}.key'
    if not cert.exists():
        csr=folder/f'private/{name}.csr'; ext=folder/f'private/{name}.ext'
        ext.write_text(f'subjectAltName=DNS:localhost,DNS:{name}.govdoc.local,IP:127.0.0.1\nbasicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n')
        run(['openssl','req','-new','-newkey','rsa:2048','-nodes','-keyout',str(key),'-out',str(csr),'-subj',f'/CN={name}.govdoc.local'])
        run(['openssl','x509','-req','-in',str(csr),'-CA',str(ca),'-CAkey',str(ca_key),'-CAcreateserial','-out',str(cert),'-days','30','-extfile',str(ext)])
    key.chmod(0o640);cert.chmod(0o644)
trust=folder/'tls/truststore.p12'
if not trust.exists(): run(['keytool','-importcert','-noprompt','-alias','staging-ca','-file',str(ca),'-keystore',str(trust),'-storetype','PKCS12','-storepass','changeit'])
trust.chmod(0o644);ca.chmod(0o644)
credentials=folder/'private/credentials.json'
if not credentials.exists():
    accounts={name:{'id':str(uuid.uuid4()),'password':secrets.token_urlsafe(32)} for name in ['issuer','reader','citizen','other-citizen']}
    config={'accounts':accounts,'clientSecret':secrets.token_urlsafe(32),'databasePassword':secrets.token_urlsafe(32),'adminPassword':secrets.token_urlsafe(32)}
    credentials.write_text(json.dumps(config,indent=2))
    realm={'realm':'govdoc-lab','enabled':True,'sslRequired':'all','registrationAllowed':False,
      'roles':{'realm':[{'name':role} for role in ['ISSUER','REVOKER','READER','CITIZEN','OPERATOR']]},
      'clients':[{'clientId':'govdoc-lab-test','secret':config['clientSecret'],'publicClient':False,'directAccessGrantsEnabled':True,'standardFlowEnabled':False,
        'protocolMappers':[
          {'name':'roles','protocol':'openid-connect','protocolMapper':'oidc-usermodel-realm-role-mapper','config':{'claim.name':'roles','jsonType.label':'String','multivalued':'true','access.token.claim':'true'}},
          {'name':'department','protocol':'openid-connect','protocolMapper':'oidc-hardcoded-claim-mapper','config':{'claim.name':'department','claim.value':'Org1','jsonType.label':'String','access.token.claim':'true'}},
          {'name':'audience','protocol':'openid-connect','protocolMapper':'oidc-audience-mapper','config':{'included.custom.audience':'govdoc-api','access.token.claim':'true'}}]}],
      'users':[{'id':entry['id'],'username':name,'enabled':True,'emailVerified':True,'firstName':'Synthetic','lastName':'Fixture','email':name+'@example.invalid','credentials':[{'type':'password','value':entry['password'],'temporary':False}],
          'realmRoles': ['ISSUER','REVOKER','READER'] if name=='issuer' else ['READER'] if name=='reader' else ['CITIZEN']} for name,entry in accounts.items()]}
    target=folder/'import/govdoc-lab-realm.json';target.write_text(json.dumps(realm));target.chmod(0o640)
    (folder/'private/containers.env').write_text('POSTGRES_PASSWORD='+config['databasePassword']+'\nKC_DB_PASSWORD='+config['databasePassword']+'\nKC_BOOTSTRAP_ADMIN_USERNAME=staging-admin\nKC_BOOTSTRAP_ADMIN_PASSWORD='+config['adminPassword']+'\n')
if not (folder/'private/encryption.json').exists():
    (folder/'private/encryption.json').write_text(json.dumps({'activeKeyId':'staging-1','keys':{'staging-1':base64.b64encode(secrets.token_bytes(32)).decode()}}))
print('Isolated staging TLS, OIDC fixtures and encryption keys prepared; values are not displayed.')
