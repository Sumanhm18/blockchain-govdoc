#!/usr/bin/env python3
"""Copy the exact locked browser libraries; run npm ci in application/web-assets first."""
import hashlib, json, shutil
from pathlib import Path
root=Path(__file__).resolve().parents[1]
source=root/'application/web-assets/node_modules'; target=root/'application/api-spring/src/main/resources/static/vendor'
target.mkdir(exist_ok=True)
files={'keycloak.js':'keycloak-js/lib/keycloak.js','keycloak-LICENSE.txt':'keycloak-js/LICENSE.txt','qrcode.js':'qrcode-generator/dist/qrcode.js'}
for dest,src in files.items(): shutil.copyfile(source/src,target/dest)
(target/'manifest.json').write_text(json.dumps({name:hashlib.sha256((target/name).read_bytes()).hexdigest() for name in [*files, 'qrcode-LICENSE.txt']},indent=2)+'\n')
print('Copied locked browser libraries and license notices')
