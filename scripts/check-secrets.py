#!/usr/bin/env python3
"""Scan candidate source files without printing secret material."""
import re, subprocess, sys
from pathlib import Path
root = Path(__file__).resolve().parents[1]
paths = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '-z'], cwd=root).decode().split('\0')
failures = []
for name in set(paths):
    if not name:
        continue
    path = root / name
    if not path.is_file():
        continue
    if any(part in {'node_modules', 'wallet', 'uploads', 'target', '.runtime'} for part in path.parts) or name.startswith(('organizations/', 'fabric-bin/')):
        failures.append((name, 'generated or sensitive artifact tracked')); continue
    if path.stat().st_size > 2_000_000:
        continue
    data = path.read_bytes()
    if re.search(rb'-----BEGIN (?:EC |RSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----', data):
        failures.append((name, 'private key detected'))
    if re.search(rb'"privateKey"\s*:\s*"-----', data):
        failures.append((name, 'wallet credential detected'))
for name, reason in sorted(failures)[:20]:
    print(f'{name}: {reason}')
if failures:
    print(f'{len(failures)} findings total; showing at most 20 filenames.')
    sys.exit(1)
print('Source secret/artifact checks passed. This check does not erase Git history.')
