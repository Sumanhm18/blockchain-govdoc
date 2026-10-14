#!/usr/bin/env python3
import json, sys, time, urllib.request
deadline = time.monotonic() + 120
while time.monotonic() < deadline:
    try:
        with urllib.request.urlopen(sys.argv[1], timeout=10) as response:
            if response.status == 200 and json.load(response).get('status') == 'UP':
                print('Readiness check passed.')
                break
    except (OSError, ValueError):
        pass
    time.sleep(2)
else:
    raise SystemExit('Readiness check failed; inspect protected local logs.')
