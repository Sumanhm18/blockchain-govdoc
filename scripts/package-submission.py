#!/usr/bin/env python3
"""Package committed source and allowlisted demo evidence; never archive private runtime state."""
import datetime
import hashlib
import io
import json
import re
import subprocess
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / '.runtime/submission'


def git(*args):
    return subprocess.check_output(['git', *args], cwd=ROOT)


def main():
    if git('status', '--porcelain').strip():
        raise SystemExit('Commit or resolve source changes before packaging an exact submission revision.')
    revision = git('rev-parse', 'HEAD').decode().strip()
    source = git('archive', '--format=zip', '--prefix=blockchain-govdoc/', 'HEAD')
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        for name in archive.namelist():
            parts = Path(name).parts
            if any(p in {'.runtime', '.git', 'node_modules', 'target', 'wallet', 'uploads'} for p in parts):
                raise SystemExit('Private/generated path found in source archive')
            if re.search(rb'-----BEGIN (?:EC |RSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----', archive.read(name)):
                raise SystemExit('Private-key marker found in source archive')
    raw = json.loads((OUTPUT / 'demo-evidence.json').read_text())
    allowed = {'schema', 'started_utc', 'completed_utc', 'scope', 'checks', 'source_commit',
               'document_id', 'status', 'working_tree_dirty'}
    if (set(raw) != allowed or raw['schema'] != 1 or raw['status'] != 'PASS'
            or raw['source_commit'] != revision or raw['working_tree_dirty'] is not False
            or not re.fullmatch(r'DEMO-[A-F0-9]{12}', raw['document_id'])
            or not isinstance(raw['checks'], list) or len(raw['checks']) != 14):
        raise SystemExit('Run ./demo.sh successfully against the clean submitted revision before packaging.')
    approved_checks = [
        'API and dependencies ready', 'Unauthenticated request rejected (401)',
        'Department reader cannot issue a document (403)',
        'Ledger digest equals SHA-256 of the actual file', 'Encrypted upload confirmed',
        'IPFS has an encrypted envelope, not the PDF bytes',
        'Authorized download decrypts to the exact original',
        'File verification: VALID', 'File verification: TAMPERED_OR_MISMATCH',
        'Reader cannot revoke (403)', 'Issuer revocation recorded',
        'Original bytes now verify as REVOKED, not VALID',
        'Revoked document download blocked (409)',
        'Registration and revocation both remain in history',
    ]
    if raw['checks'] != approved_checks or raw['scope'] != 'local synthetic API/Fabric/IPFS lifecycle; not production certification':
        raise SystemExit('Unexpected content in demo evidence; rerun the supplied walkthrough.')
    for key in ['started_utc', 'completed_utc']:
        if datetime.datetime.fromisoformat(raw[key]).tzinfo is None:
            raise SystemExit('Evidence timestamps must include a timezone')
    # The evidence producer emits only synthetic identifiers, timestamps and fixed check labels.
    encoded = json.dumps(raw, indent=2).encode() + b'\n'
    submodule = git('ls-tree', 'HEAD', 'fabric-samples').decode().strip().split()[2]
    files = {
        'source-review.zip': source,
        'demo-evidence.json': encoded,
        'TESTING_SUMMARY.md': git('show', 'HEAD:TESTING_SUMMARY.md'),
        'README.txt': b'This bundle contains committed source for review and local synthetic test evidence.\nFor execution, clone the harden-document-registry branch with submodules, checkout the manifest commit and follow docs/SUBMISSION.md.\nThe source ZIP does not embed Git metadata or the Fabric samples submodule.\nPrivate runtime state, secrets, databases and detailed logs are excluded.\nHosted CI and production/security release blockers remain documented.\n',
    }
    for path in git('ls-tree', '-r', '--name-only', 'HEAD', 'docs').decode().splitlines():
        if path.endswith('.md'):
            files[path] = git('show', 'HEAD:' + path)
    manifest = {'repository': 'https://github.com/Sumanhm18/blockchain-govdoc',
                'commit': revision, 'fabric_samples_commit': submodule,
                'evidence_completed_utc': raw['completed_utc'],
                'files': {name: hashlib.sha256(data).hexdigest() for name, data in files.items()}}
    files['manifest.json'] = json.dumps(manifest, indent=2).encode() + b'\n'
    target = OUTPUT / ('govdoc-submission-' + revision[:12] + '.zip')
    with zipfile.ZipFile(target, 'w', zipfile.ZIP_DEFLATED) as archive:
        for name, data in files.items():
            archive.writestr(name, data)
    with zipfile.ZipFile(target) as archive:
        if archive.testzip() is not None or set(archive.namelist()) != set(files):
            raise SystemExit('Submission archive validation failed')
        for name, expected in manifest['files'].items():
            if hashlib.sha256(archive.read(name)).hexdigest() != expected:
                raise SystemExit('Submission archive checksum mismatch')
    checksum = hashlib.sha256(target.read_bytes()).hexdigest()
    target.with_suffix('.sha256').write_text(checksum + '  ' + target.name + '\n')
    print('Submission bundle: ' + str(target))
    print('SHA-256: ' + checksum)
    print('Validated committed-source archive and evidence; no private runtime files included.')


if __name__ == '__main__':
    main()
