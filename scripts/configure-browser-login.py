#!/usr/bin/env python3
"""Idempotently configure only the isolated lab's public PKCE browser client."""
import json, ssl, urllib.request, urllib.parse, urllib.error
from pathlib import Path
root=Path(__file__).resolve().parents[1]
context=ssl.create_default_context(cafile=str(root/'.runtime/staging/tls/ca.pem'))
credentials=json.loads((root/'.runtime/staging/private/credentials.json').read_text())
base='https://localhost:18443'
form=urllib.parse.urlencode({'grant_type':'password','client_id':'admin-cli','username':'staging-admin','password':credentials['adminPassword']}).encode()
def call(path, data=None, method=None, token=None):
    headers={'Content-Type':'application/x-www-form-urlencoded' if isinstance(data,bytes) else 'application/json'}
    if token:headers['Authorization']='Bearer '+token
    request=urllib.request.Request(base+path,data=data if isinstance(data,bytes) else json.dumps(data).encode() if data is not None else None,headers=headers,method=method)
    try:
        with urllib.request.urlopen(request,context=context,timeout=30) as response:
            raw=response.read();return json.loads(raw) if raw else None
    except urllib.error.HTTPError as error: raise SystemExit('Browser client configuration failed: HTTP '+str(error.code)) from None
bearer=call('/realms/master/protocol/openid-connect/token',form)['access_token']
client=json.loads((root/'config/browser-client.json').read_text())
clients=call('/admin/realms/govdoc-lab/clients?clientId=govdoc-browser',token=bearer)
if clients: call('/admin/realms/govdoc-lab/clients/'+clients[0]['id'],client,'PUT',bearer)
else: call('/admin/realms/govdoc-lab/clients',client,'POST',bearer)
print('Configured public PKCE browser client with exact localhost redirect and origin; no secrets displayed.')
