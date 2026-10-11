#!/usr/bin/env python3
"""Collect fresh dependency/image evidence; return nonzero for findings or incomplete scans."""
import argparse
import collections
import datetime
import json
import os
import subprocess
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def assess(report):
    if not isinstance(report, dict) or report.get('SchemaVersion') != 2 or not report.get('ArtifactName'):
        raise ValueError('Unexpected scanner report schema')
    results = report.get('Results')
    if not isinstance(results, list) or not results:
        raise ValueError('Scanner returned no analyzed targets')
    counts = collections.Counter()
    findings = []
    for result in results:
        for item in result.get('Vulnerabilities') or []:
            severity = item.get('Severity', 'UNKNOWN')
            if severity not in {'LOW', 'MEDIUM', 'HIGH', 'CRITICAL', 'UNKNOWN'}:
                severity = 'UNKNOWN'
            counts[severity] += 1
            if severity in {'HIGH', 'CRITICAL', 'UNKNOWN'}:
                findings.append({key: item.get(key) for key in
                                 ['VulnerabilityID', 'PkgName', 'InstalledVersion', 'FixedVersion', 'Severity']})
    return {'counts': dict(counts), 'blocking_findings': findings}


def gate(scans, os_only):
    if not scans or os_only or any('error' in scan for scan in scans):
        return 2
    return 1 if any(scan['blocking_findings'] for scan in scans) else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--os-only', action='store_true', help='Partial image coverage; always returns incomplete status (2)')
    parser.add_argument('--build', action='store_true', help='Build API and optimized Keycloak without starting services')
    args = parser.parse_args()
    os.umask(0o077)
    os.chdir(ROOT)
    scanner = ROOT / '.runtime/tools/trivy/trivy'
    expected = json.loads((ROOT / 'config/trivy.json').read_text())['version']
    if not scanner.is_file():
        raise SystemExit('Run python3 scripts/install-trivy.py first')
    version = subprocess.check_output([str(scanner), '--version'], text=True, timeout=10).splitlines()[0]
    if version != 'Version: ' + expected:
        raise SystemExit('Scanner version does not match config/trivy.json')
    now = datetime.datetime.now(datetime.timezone.utc)
    output = ROOT / '.runtime/security' / (now.strftime('%Y%m%dT%H%M%SZ') + '-' + uuid.uuid4().hex[:8])
    output.mkdir(parents=True)
    (output / 'empty-ignore').write_text('')
    (output / 'empty-config.yaml').write_text('{}\n')
    env = {k: v for k, v in os.environ.items() if not k.startswith('TRIVY_')}
    staging = json.loads((ROOT / 'config/staging-images.json').read_text())
    pins = json.loads((ROOT / 'config/images.json').read_text())
    if args.build:
        commands = [
            ['bash', 'scripts/build-node-runtime.sh'],
            ['python3', 'scripts/build-fabric-images.py'],
            ['docker', 'build', '-t', 'govdoc-ipfs:local', '--build-arg',
             'IPFS_IMAGE=' + pins['ipfs/kubo:v0.43.1'], '-f', 'deploy/ipfs.Dockerfile', 'deploy'],
            ['docker', 'build', '-t', 'govdoc-postgres:local', '--build-arg',
             'POSTGRES_IMAGE=' + staging['postgres'], '-f', 'deploy/postgres.Dockerfile', 'deploy'],
            ['docker', 'build', '-t', 'govdoc-staging-api', 'application/api-spring'],
            ['docker', 'build', '-t', 'govdoc-staging-keycloak', '--build-arg',
             'KEYCLOAK_IMAGE=' + staging['keycloak'], '--build-arg',
             'KEYCLOAK_RUNTIME_IMAGE=' + staging['keycloak_runtime'], '-f', 'deploy/keycloak.Dockerfile', 'deploy'],
        ]
        for index, command in enumerate(commands):
            with (output / f'build-{index}.log').open('w') as log:
                subprocess.run(command, stdout=log, stderr=log, check=True, timeout=1800, env=env)
    targets = [('source', ['fs', '--skip-dirs', '.runtime', '--skip-dirs', '.git', '--skip-dirs', 'fabric-samples',
                            '--skip-dirs', 'node_modules', '--skip-dirs', 'application/api-spring/target', '.'])]
    images = [('api', 'govdoc-staging-api'), ('keycloak', 'govdoc-staging-keycloak'), ('postgres', 'govdoc-postgres:local')]
    images += [(name.split('/')[1].split(':')[0], pin) for name, pin in pins.items()
               if name.startswith(('hyperledger/fabric-peer:', 'hyperledger/fabric-orderer:',
                                   'hyperledger/fabric-ca:', 'hyperledger/fabric-nodeenv:', 'ipfs/'))]
    for label, image in images:
        if label == 'kubo':
            image = 'govdoc-ipfs:local'
        if label in {'fabric-peer', 'fabric-orderer', 'fabric-ca'}:
            try:
                patched = json.loads((ROOT / '.runtime/fabric-images.json').read_text())
                image = next(value['reference'] for key, value in patched.items() if key.startswith('hyperledger/' + label + ':'))
            except (OSError, ValueError, KeyError, StopIteration):
                image = ''
        if label == 'fabric-nodeenv':
            try:
                image = json.loads((ROOT / '.runtime/node-runtime.json').read_text())['reference']
            except (OSError, ValueError, KeyError):
                image = ''
        if label in {'api', 'keycloak', 'postgres', 'fabric-nodeenv', 'fabric-peer', 'fabric-orderer', 'fabric-ca', 'kubo'}:
            # Resolve mutable local build tags once, then scan their immutable content IDs.
            try:
                image = subprocess.check_output(['docker', 'image', 'inspect', '--format', '{{.Id}}', image],
                                                text=True, stderr=subprocess.DEVNULL, timeout=30).strip()
            except subprocess.SubprocessError:
                image = ''
        targets.append((label, ['image', '--image-src', 'docker,remote', '--pkg-types',
                                'os' if args.os_only else 'os,library', image]))
    scans = []
    for label, target in targets:
        entry = {'target': label, 'reference': target[-1]}
        print('Scanning ' + label + '...', flush=True)
        try:
            if not target[-1]:
                raise ValueError('Local image missing; rerun with --build')
            command = [str(scanner), *target, '--scanners', 'vuln', '--format', 'json', '--list-all-pkgs',
                       '--output', str(output / (label + '.json')), '--cache-dir', str(ROOT / '.runtime/trivy-cache'),
                       '--no-progress', '--timeout', '15m', '--ignorefile', str(output / 'empty-ignore'),
                       '--config', str(output / 'empty-config.yaml')]
            with (output / (label + '.log')).open('w') as log:
                subprocess.run(command, stdout=log, stderr=log, check=True, timeout=960, env=env)
            report = json.loads((output / (label + '.json')).read_text())
            entry.update(assess(report))
            entry['artifact_id'] = report.get('ArtifactID')
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            entry['error'] = (str(error) if isinstance(error, ValueError) else
                              type(error).__name__ + ': scan incomplete; inspect the protected log')
        scans.append(entry)
        print(label + ': ' + json.dumps(entry.get('counts', entry.get('error'))), flush=True)
    code = gate(scans, args.os_only)
    summary = {'created_utc': now.isoformat(), 'scanner': version,
               'image_coverage': 'os-only' if args.os_only else 'os-and-supported-libraries',
               'status': {0: 'PASS', 1: 'BLOCKED_FINDINGS', 2: 'INCOMPLETE'}[code], 'scans': scans}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    print(summary['status'] + ': ' + str(output / 'summary.json'))
    return code


if __name__ == '__main__':
    raise SystemExit(main())
