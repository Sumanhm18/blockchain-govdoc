#!/usr/bin/env python3
"""Present a real, synthetic document lifecycle without displaying credentials."""
import argparse
import base64
import datetime
import hashlib
import json
import secrets
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = 'http://127.0.0.1:18081'


def synthetic_pdf():
    # A small valid one-page PDF; no third-party tooling or personal data is needed.
    stream = b'BT /F1 16 Tf 50 750 Td (SYNTHETIC DOCUMENT - DEMONSTRATION ONLY) Tj ET\n'
    objects = [b'<< /Type /Catalog /Pages 2 0 R >>',
               b'<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
               b'<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>',
               b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
               b'<< /Length ' + str(len(stream)).encode() + b' >>\nstream\n' + stream + b'endstream']
    data = bytearray(b'%PDF-1.4\n')
    offsets = [0]
    for index, obj in enumerate(objects, 1):
        offsets.append(len(data))
        data.extend(str(index).encode() + b' 0 obj\n' + obj + b'\nendobj\n')
    xref = len(data)
    data.extend(b'xref\n0 6\n0000000000 65535 f \n')
    for offset in offsets[1:]:
        data.extend(f'{offset:010d} 00000 n \n'.encode())
    data.extend(f'trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n'.encode())
    return bytes(data)


def multipart(data, doc_id=None):
    boundary = 'demo-' + secrets.token_hex(12)
    body = bytearray()
    fields = {'docId': doc_id, 'subject': 'SYNTHETIC-REFERENCE', 'type': 'Demonstration'} if doc_id else {}
    for name, value in fields.items():
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.pdf"\r\nContent-Type: application/pdf\r\n\r\n'.encode())
    body.extend(data)
    body.extend(f'\r\n--{boundary}--\r\n'.encode())
    return bytes(body), 'multipart/form-data; boundary=' + boundary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pause', action='store_true', help='Press Enter between presentation steps')
    args = parser.parse_args()
    (ROOT / '.runtime/submission/demo-evidence.json').unlink(missing_ok=True)
    credentials = json.loads((ROOT / '.runtime/secrets/credentials.json').read_text())
    evidence = {'schema': 1, 'started_utc': datetime.datetime.now(datetime.timezone.utc).isoformat(),
                'scope': 'local synthetic API/Fabric/IPFS lifecycle; not production certification', 'checks': []}
    evidence['source_commit'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    evidence['working_tree_dirty'] = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT).strip())

    def check(ok, description):
        if not ok:
            raise RuntimeError(description + ' failed')
        evidence['checks'].append(description)
        print('  PASS: ' + description, flush=True)

    def step(number, title):
        if args.pause and number > 1:
            input('\nPress Enter to continue...')
        print(f'\n{number}. {title}', flush=True)

    def call(method, path, expected=(200,), data=None, user='reader', content_type='application/json', binary=False):
        headers = {}
        if user:
            headers['Authorization'] = 'Basic ' + base64.b64encode((user + ':' + credentials[user]).encode()).decode()
        if data is not None:
            headers['Content-Type'] = content_type
            if not isinstance(data, bytes):
                data = json.dumps(data).encode()
        request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=90)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read()
            if response.status not in expected:
                raise RuntimeError(f'{method} request returned HTTP {response.status}; expected {expected}')
            return payload if binary else (json.loads(payload) if payload else None)

    print('GOVERNMENT DOCUMENT REGISTRY — LIVE SYNTHETIC DEMO', flush=True)
    check(call('GET', '/actuator/health/readiness', user=None)['status'] == 'UP', 'API and dependencies ready')
    doc_id = 'DEMO-' + secrets.token_hex(6).upper()
    document = synthetic_pdf()
    digest = 'sha256:' + hashlib.sha256(document).hexdigest()
    route = '/api/documents/' + doc_id
    evidence['document_id'] = doc_id
    print('Document ID: ' + doc_id)

    step(1, 'Authorization')
    call('GET', route, expected=(401,), user=None)
    check(True, 'Unauthenticated request rejected (401)')
    call('POST', '/api/documents', expected=(403,), data={'docId': doc_id, 'hash': digest})
    check(True, 'Department reader cannot issue a document (403)')

    step(2, 'Issue a document and record its digest on Fabric')
    body, kind = multipart(document, doc_id)
    call('POST', '/api/documents/upload', expected=(200, 202), data=body, user='issuer', content_type=kind)
    deadline = time.monotonic() + 90
    while True:
        state = call('GET', route + '/registration', user='issuer')['state']
        if state == 'CONFIRMED':
            break
        if state not in {'STAGING', 'PINNED', 'SUBMITTING'} or time.monotonic() >= deadline:
            raise RuntimeError('Registration did not reach CONFIRMED; inspect protected local logs')
        time.sleep(2)
    record = call('GET', route)
    check(record['hash'] == digest, 'Ledger digest equals SHA-256 of the actual file')
    check(record['metadata']['encryption']['algorithm'] == 'AES-256-GCM', 'Encrypted upload confirmed')
    print('  Digest: ' + digest)

    step(3, 'Prove that IPFS stores ciphertext')
    cid = record['metadata']['ipfsCid']
    request = urllib.request.Request('http://127.0.0.1:15001/api/v0/cat?arg=' + urllib.parse.quote(cid, safe=''), data=b'', method='POST')
    with urllib.request.urlopen(request, timeout=30) as response:
        encrypted = response.read(11 * 1024 * 1024)
    check(encrypted.startswith(b'GOVDOC01') and document not in encrypted, 'IPFS has an encrypted envelope, not the PDF bytes')
    check(call('GET', route + '/file', binary=True) == document, 'Authorized download decrypts to the exact original')

    step(4, 'Verify original and modified files')
    for data, expected in [(document, 'VALID'), (document + b'\nALTERED', 'TAMPERED_OR_MISMATCH')]:
        body, kind = multipart(data)
        result = call('POST', route + '/verify-file', data=body, content_type=kind)
        check(result['status'] == expected and result['valid'] == (expected == 'VALID'), 'File verification: ' + expected)

    step(5, 'Revoke with an authorized issuer')
    call('POST', route + '/revoke', expected=(403,), data={'reason': 'Synthetic demo'})
    check(True, 'Reader cannot revoke (403)')
    result = call('POST', route + '/revoke', data={'reason': 'Synthetic presentation complete'}, user='issuer')
    check(result['revoked'], 'Issuer revocation recorded')
    body, kind = multipart(document)
    result = call('POST', route + '/verify-file', data=body, content_type=kind)
    check(result['matches'] and not result['valid'] and result['status'] == 'REVOKED', 'Original bytes now verify as REVOKED, not VALID')
    call('GET', route + '/file', expected=(409,))
    check(True, 'Revoked document download blocked (409)')

    step(6, 'Audit history')
    history = call('GET', route + '/history')['events']
    check(len(history) == 2, 'Registration and revocation both remain in history')
    evidence.update(status='PASS', completed_utc=datetime.datetime.now(datetime.timezone.utc).isoformat())
    directory = ROOT / '.runtime/submission'
    directory.mkdir(parents=True, exist_ok=True)
    (directory / 'demo-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
    print(f'\nPASS: {len(evidence["checks"])} checks. Synthetic record remains on the local ledger.')
    print('Safe evidence saved to .runtime/submission/demo-evidence.json; no credentials included.')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        # Never print response bodies, credential files or underlying dependency diagnostics.
        print('Demo did not complete (' + type(error).__name__ + '). Check service readiness and protected logs, then rerun.')
        raise SystemExit(1)
