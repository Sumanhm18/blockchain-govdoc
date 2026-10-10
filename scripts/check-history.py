#!/usr/bin/env python3
"""Inspect reachable blobs in a bare cleanup candidate; print identifiers, never secret values."""
import re, subprocess, sys
from pathlib import Path
repo = Path(sys.argv[1]).resolve()
if not (repo / 'HEAD').is_file():
    raise SystemExit('Pass the path to a bare Git mirror.')
base = ['git', '--git-dir=' + str(repo)]
objects = subprocess.check_output(base + ['rev-list', '--objects', '--all'], text=True).splitlines()
pattern = re.compile(rb'-----BEGIN (?:EC |RSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|"privateKey"\s*:\s*"-----')
process = subprocess.Popen(base + ['cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE)
blobs, findings = 0, []
try:
    for entry in objects:
        oid, _, name = entry.partition(' ')
        process.stdin.write((oid + '\n').encode()); process.stdin.flush()
        header = process.stdout.readline().split()
        kind, size = header[1], int(header[2])
        remaining, carry, found = size, b'', False
        while remaining:
            chunk = process.stdout.read(min(65536, remaining))
            if not chunk: raise RuntimeError('Truncated Git object output')
            if kind == b'blob' and pattern.search(carry + chunk): found = True
            carry = chunk[-128:]; remaining -= len(chunk)
        process.stdout.read(1)
        if kind == b'blob': blobs += 1
        if found: findings.append((oid, name))
finally:
    process.stdin.close(); process.stdout.close(); process.wait()
for oid, name in findings[:20]:
    print(f'Private-key marker remains in blob {oid} ({name})')
print(f'Inspected {blobs} reachable blobs; private-key marker findings: {len(findings)}. This is a limited pattern scan, not proof of complete sensitive-data removal.')
raise SystemExit(1 if findings else 0)
