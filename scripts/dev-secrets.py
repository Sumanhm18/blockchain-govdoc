#!/usr/bin/env python3
"""Create local development credentials once. Never print secret values."""
import base64, hashlib, json, os, secrets
from pathlib import Path
os.umask(0o077)
root = Path(__file__).resolve().parents[1]
folder = root / '.runtime/secrets'
folder.mkdir(parents=True, exist_ok=True)
credentials_path = folder / 'credentials.json'
if credentials_path.exists() != (folder / 'users.json').exists():
    raise SystemExit('Incomplete development account files; restore the matching credentials/users pair before continuing.')
if not credentials_path.exists():
    accounts = [('issuer', ['ISSUER', 'REVOKER', 'READER']), ('reader', ['READER']), ('operator', ['OPERATOR'])]
    credentials, users = {}, []
    for username, roles in accounts:
        password = secrets.token_urlsafe(32)
        salt = secrets.token_bytes(16)
        digest = hashlib.pbkdf2_hmac('sha256', password.encode(), salt, 310000)
        credentials[username] = password
        users.append({'username': username, 'passwordHash': (salt + digest).hex(), 'roles': roles})
    (folder / 'users.json').write_text(json.dumps(users, indent=2) + '\n')
    credentials_path.write_text(json.dumps(credentials, indent=2) + '\n')
if not (folder / 'encryption.json').exists():
    if any((root / '.runtime/data').glob('*.mv.db')):
        raise SystemExit('Encryption keys are missing for an existing journal; restore the original keys. Refusing to silently replace them.')
    (folder / 'encryption.json').write_text(json.dumps({'activeKeyId': 'dev-1', 'keys': {'dev-1': base64.b64encode(secrets.token_bytes(32)).decode()}}, indent=2) + '\n')
for path in folder.iterdir():
    if path.is_file():
        path.chmod(0o600)
print('Local development credentials and encryption key are ready in .runtime/secrets (values not displayed).')
