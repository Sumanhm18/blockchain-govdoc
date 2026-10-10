#!/usr/bin/env python3
"""Assert the real API/IPFS/Fabric workflow using synthetic data only."""
import base64, hashlib, json, os, secrets, urllib.error, urllib.request
from pathlib import Path
root = Path(__file__).resolve().parents[1]
base = os.environ.get('REGISTRY_API_URL', 'http://127.0.0.1:18081')
credentials = json.loads((root / '.runtime/secrets/credentials.json').read_text())
checks = 0
def call(method, path, expected, body=None, user='issuer', content_type='application/json', binary=False):
    global checks
    headers = {}
    if user:
        headers['Authorization'] = 'Basic ' + base64.b64encode((user + ':' + credentials[user]).encode()).decode()
    if body is not None:
        headers['Content-Type'] = content_type
        if not isinstance(body, bytes): body = json.dumps(body).encode()
    request = urllib.request.Request(base + path, data=body, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=90)
    except urllib.error.HTTPError as error:
        response = error
    data = response.read()
    if response.status != expected:
        # Do not print bodies that might contain document metadata or internal diagnostics.
        raise AssertionError(f'{method} endpoint expected HTTP {expected}, received {response.status}')
    checks += 1
    if binary: return data
    return json.loads(data) if data else None
def multipart(fields, data):
    boundary = 'govdoc-' + secrets.token_hex(16)
    body = bytearray()
    for name, value in fields.items():
        body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
    body.extend(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="synthetic.pdf"\r\nContent-Type: application/pdf\r\n\r\n'.encode())
    body.extend(data)
    body.extend(f'\r\n--{boundary}--\r\n'.encode())
    return bytes(body), 'multipart/form-data; boundary=' + boundary
def main():
    global checks
    id = 'TEST-' + secrets.token_hex(8).upper()
    file = b'%PDF-1.4\nSynthetic integration fixture; contains no personal information.\n%%EOF\n'
    hash_value = 'sha256:' + hashlib.sha256(file).hexdigest()
    call('GET', '/api/documents/' + id, 401, user=None)
    call('POST', '/api/documents', 403, {'docId': id, 'hash': hash_value}, user='reader')
    body, content_type = multipart({'docId': id, 'subject': 'synthetic-reference', 'type': 'Test'}, file)
    record = call('POST', '/api/documents/upload', 200, body, content_type=content_type)
    assert record['hash'] == hash_value and record['metadata']['encryption']['algorithm'] == 'AES-256-GCM'
    assert call('GET', '/api/documents/' + id, 200, user='reader')['docId'] == id
    assert call('GET', '/api/documents/' + id + '/registration', 200)['state'] == 'CONFIRMED'
    assert call('GET', '/api/documents/' + id + '/file', 200, user='reader', binary=True) == file
    assert call('POST', '/api/documents/' + id + '/verify', 200, {'hash': hash_value}, user='reader')['status'] == 'VALID'
    body, content_type = multipart({}, file + b'changed')
    assert call('POST', '/api/documents/' + id + '/verify-file', 200, body, user='reader', content_type=content_type)['status'] == 'TAMPERED_OR_MISMATCH'
    call('POST', '/api/documents', 409, {'docId': id, 'hash': hash_value})
    call('GET', '/api/documents/MISSING-' + secrets.token_hex(4).upper(), 404, user='reader')
    call('POST', '/api/documents/' + id + '/revoke', 403, {'reason': 'test'}, user='reader')
    assert call('POST', '/api/documents/' + id + '/revoke', 200, {'reason': 'Synthetic lifecycle complete'})['revoked']
    verified = call('POST', '/api/documents/' + id + '/verify', 200, {'hash': hash_value}, user='reader')
    assert verified['matches'] and not verified['valid'] and verified['status'] == 'REVOKED'
    call('GET', '/api/documents/' + id + '/file', 409, user='reader')
    assert len(call('GET', '/api/documents/' + id + '/history', 200, user='reader')['events']) == 2
    # Confirm the IPFS bytes are an encrypted envelope, never the uploaded plaintext.
    cid = record['metadata']['ipfsCid']
    ipfs_url = os.environ.get('IPFS_API_BASE', 'http://127.0.0.1:15001')
    with urllib.request.urlopen(urllib.request.Request(ipfs_url + '/api/v0/cat?arg=' + cid, data=b'', method='POST'), timeout=30) as response:
        ciphertext = response.read()
    assert ciphertext.startswith(b'GOVDOC01') and file not in ciphertext
    checks += 1
    print(f'PASS: {checks} live checks covering authenticated registration, encrypted storage/download, verification, revocation, duplicates, missing records and history.')

if __name__ == '__main__':
    main()
