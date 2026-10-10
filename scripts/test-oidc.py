#!/usr/bin/env python3
"""Real HTTPS discovery/JWK/token and citizen ownership checks against the isolated lab."""
import base64, concurrent.futures, json, secrets, ssl, statistics, time, urllib.error, urllib.parse, urllib.request
from pathlib import Path
from e2e import multipart
root=Path(__file__).resolve().parents[1]
context=ssl.create_default_context(cafile=str(root/'.runtime/staging/tls/ca.pem'))
credentials=json.loads((root/'.runtime/staging/private/credentials.json').read_text())
checks=0

def token(user):
    form={'grant_type':'password','client_id':'govdoc-lab-test','client_secret':credentials['clientSecret'],'username':user,'password':credentials['accounts'][user]['password']}
    request=urllib.request.Request('https://localhost:18443/realms/govdoc-lab/protocol/openid-connect/token',data=urllib.parse.urlencode(form).encode())
    try:
        with urllib.request.urlopen(request,context=context,timeout=30) as response: return json.load(response)['access_token']
    except urllib.error.HTTPError as error: raise RuntimeError('OIDC fixture token request failed: HTTP '+str(error.code)) from None

def call(method,path,expected,bearer=None,body=None,content_type='application/json',binary=False):
    global checks
    headers={}
    if bearer: headers['Authorization']='Bearer '+bearer
    if body is not None:
        headers['Content-Type']=content_type
        if not isinstance(body,bytes): body=json.dumps(body).encode()
    request=urllib.request.Request('https://localhost:18444'+path,data=body,headers=headers,method=method)
    try: response=urllib.request.urlopen(request,context=context,timeout=60)
    except urllib.error.HTTPError as error: response=error
    with response: data=response.read(); status=response.status
    if status!=expected: raise AssertionError(f'{method} expected HTTP {expected}; received {status}; response body withheld')
    checks+=1
    return data if binary else json.loads(data) if data else None

def main():
    issuer,reader,citizen,other=[token(u) for u in ['issuer','reader','citizen','other-citizen']]
    claims=json.loads(base64.urlsafe_b64decode(citizen.split('.')[1]+'=='))
    id='CITIZEN-'+secrets.token_hex(6).upper(); path='/api/documents/'+id
    file=b'%PDF-1.4\nSynthetic citizen ownership check\n%%EOF\n'
    call('GET',path,401)
    forged=citizen.rsplit('.',1)[0]+'.'+base64.urlsafe_b64encode(bytes(256)).decode().rstrip('=')
    call('GET',path,401,forged)
    body,kind=multipart({'docId':id,'ownerSubject':claims['sub']},file)
    record=call('POST','/api/documents/upload',200,issuer,body,kind)
    assert claims['sub'] not in json.dumps(record)
    call('GET',path,200,reader);call('GET',path,200,citizen)
    call('GET',path,404,other);call('GET',path+'/file',404,other)
    assert call('GET',path+'/file',200,citizen,binary=True)==file
    call('POST',path+'/verify',404,other,{'hash':record['hash']})
    assert call('POST',path+'/verify',200,citizen,{'hash':record['hash']})['valid']
    call('GET',path+'/history',403,citizen)
    call('POST',path+'/revoke',403,citizen,{'reason':'not authorized'})
    call('POST','/api/documents',403,citizen,{'docId':'DENIED-001','hash':record['hash']})
    changed,kind=multipart({'docId':id,'ownerSubject':'different-owner'},file)
    call('POST','/api/documents/upload',409,issuer,changed,kind)
    def sample(_):
        started=time.monotonic();call('GET',path,200,citizen);return (time.monotonic()-started)*1000
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool: timings=sorted(pool.map(sample,range(20)))
    result={'requests':20,'concurrency':4,'failures':0,'p50_ms':round(statistics.median(timings),2),'p95_ms':round(timings[18],2),'scope':'local authenticated read smoke, not production capacity'}
    (root/'.runtime/staging/load-smoke.json').write_text(json.dumps(result,indent=2))
    call('POST',path+'/revoke',200,issuer,{'reason':'Synthetic OIDC test complete'})
    call('GET',path+'/file',409,citizen)
    assert not call('POST',path+'/verify',200,citizen,{'hash':record['hash']})['valid']
    print(f'PASS: {checks} HTTPS/OIDC and citizen-isolation checks, including 20 concurrent-read samples. No token or credential values displayed.')
    print(json.dumps(result))
if __name__=='__main__': main()
