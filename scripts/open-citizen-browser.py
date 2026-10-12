#!/usr/bin/env python3
"""Open a separate Chrome profile for local staging, trusting only its public TLS keys."""
import base64, hashlib, os, subprocess, sys
from pathlib import Path
root=Path(__file__).resolve().parents[1]
chrome=Path('/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
if not chrome.exists(): raise SystemExit('Google Chrome is required for this macOS local staging launcher.')
pins=[]
for name in ['api','identity']:
    public=subprocess.check_output(['openssl','x509','-in',str(root/f'.runtime/staging/tls/{name}.pem'),'-pubkey','-noout'])
    der=subprocess.check_output(['openssl','pkey','-pubin','-outform','DER'],input=public,stderr=subprocess.DEVNULL)
    pins.append(base64.b64encode(hashlib.sha256(der).digest()).decode())
profile=root/'.runtime/citizen-browser'; profile.mkdir(mode=0o700,exist_ok=True)
log=open(os.devnull,'w')
subprocess.Popen([str(chrome),'--user-data-dir='+str(profile),'--no-first-run','--host-resolver-rules=MAP identity.govdoc.local 127.0.0.1','--ignore-certificate-errors-spki-list='+','.join(pins),'https://localhost:18444/'],stdout=log,stderr=log,start_new_session=True)
print('Opened isolated citizen browser for local staging. Close it after the demonstration; its profile is private runtime state.')
