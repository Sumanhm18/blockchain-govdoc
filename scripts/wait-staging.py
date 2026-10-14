#!/usr/bin/env python3
import json, ssl, time, urllib.request
from pathlib import Path
root=Path(__file__).resolve().parents[1]
context=ssl.create_default_context(cafile=str(root/'.runtime/staging/tls/ca.pem'))
deadline=time.monotonic()+180
while time.monotonic()<deadline:
    try:
        with urllib.request.urlopen('https://localhost:18444/actuator/health/readiness',context=context,timeout=5) as response:
            if response.status==200 and json.load(response)['status']=='UP':
                print('TLS/OIDC staging readiness passed.');break
    except (OSError,ValueError):pass
    time.sleep(2)
else:raise SystemExit('Staging readiness failed; inspect protected local logs.')
