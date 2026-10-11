#!/usr/bin/env python3
"""Logically migrate only the owned local Keycloak database, retaining an encrypted rollback backup."""
import hashlib
import json
import os
import secrets
import subprocess
import time
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

ROOT = Path(__file__).resolve().parents[1]
os.chdir(ROOT)
os.umask(0o077)
env = dict(os.environ, DOCKER_CONFIG=str(ROOT / '.runtime/docker'))
if 'DOCKER_HOST' not in env:
    for socket in [Path.home()/'.docker/run/docker.sock', Path.home()/'.docker/desktop/docker.sock']:
        if socket.exists():
            env['DOCKER_HOST'] = 'unix://' + str(socket)
            break


def docker(*args, data=None, check=True):
    return subprocess.run(['docker', *args], input=data, stdout=subprocess.PIPE,
                          stderr=subprocess.PIPE, env=env, timeout=240, check=check)


def sql(container, query):
    return docker('exec', container, 'psql', '-U', 'keycloak', '-d', 'keycloak', '-At', '-c', query).stdout.strip()


def identity_fingerprint(container):
    # Stable sorted identity rows: hash rather than expose subjects, usernames or password hashes.
    rows = sql(container, "SELECT row_to_json(t)::text FROM (SELECT * FROM user_entity ORDER BY id) t")
    credentials = sql(container, "SELECT row_to_json(t)::text FROM (SELECT * FROM credential ORDER BY id) t")
    clients = sql(container, "SELECT row_to_json(t)::text FROM (SELECT * FROM client ORDER BY id) t")
    return hashlib.sha256(rows + b'\0' + credentials + b'\0' + clients).hexdigest()


def main():
    marker = ROOT / '.runtime/staging/postgres-migration.json'
    if marker.exists():
        raise SystemExit('Migration record already exists; review it before rerunning.')
    name = 'govdoc-staging-database-1'
    original = json.loads(docker('container', 'inspect', name).stdout)[0]
    assert original['Config']['Labels']['com.docker.compose.project'] == 'govdoc-staging'
    assert original['State']['Running']
    volume = next(m['Name'] for m in original['Mounts'] if m['Destination'] == '/var/lib/postgresql/data')
    assert volume == 'govdoc-staging_keycloak-db', 'Unexpected source database volume'
    assert int(sql(name, 'SHOW server_version_num')) // 10000 == 17, 'Only PostgreSQL 17 migration is supported'
    candidate = json.loads((ROOT/'config/staging-images.json').read_text())['postgres']
    assert candidate.startswith('postgres:17-alpine@sha256:'), 'Expected a pinned PostgreSQL 17 candidate'
    new_volume = 'govdoc-staging_keycloak-db-alpine-v1'
    assert docker('volume', 'inspect', new_volume, check=False).returncode != 0, 'Destination volume already exists'
    temporary = 'govdoc-staging-postgres-migration-' + secrets.token_hex(4)
    stopped = []
    switched = False
    log_path = ROOT/'.runtime/logs/postgres-migration.log'
    try:
        for service in ['govdoc-staging-api-1', 'govdoc-staging-keycloak-1']:
            info = json.loads(docker('container', 'inspect', service).stdout)[0]
            assert info['Config']['Labels']['com.docker.compose.project'] == 'govdoc-staging'
            if info['State']['Running']:
                docker('stop', service)
                stopped.append(service)
        fingerprint = identity_fingerprint(name)
        dump = docker('exec', name, 'pg_dump', '-U', 'keycloak', '-d', 'keycloak', '-Fc').stdout
        assert dump.startswith(b'PGDMP'), 'Invalid custom-format database dump'
        keys = ROOT/'.runtime/secrets'
        keys.mkdir(parents=True, exist_ok=True)
        key_file = keys/'identity-backup.key'
        if not key_file.exists():
            key_file.write_bytes(AESGCM.generate_key(bit_length=256))
        nonce = os.urandom(12)
        cipher = AESGCM(key_file.read_bytes())
        encrypted = b'GIDB01' + nonce + cipher.encrypt(nonce, dump, b'govdoc-keycloak-postgres-17')
        backups = ROOT/'.runtime/backups'
        backups.mkdir(parents=True, exist_ok=True)
        backup = backups/('keycloak-' + secrets.token_hex(8) + '.dump.gcm')
        backup.write_bytes(encrypted)
        restored = cipher.decrypt(encrypted[6:18], encrypted[18:], b'govdoc-keycloak-postgres-17')
        assert hashlib.sha256(restored).digest() == hashlib.sha256(dump).digest()
        docker('volume', 'create', '--label', 'com.govdoc.migration=postgres-alpine-v1', new_volume)
        docker('run', '-d', '--name', temporary, '--network', 'none',
               '--label', 'com.govdoc.migration=postgres-alpine-v1',
               '--env-file', str(ROOT/'.runtime/staging/private/containers.env'),
               '-e', 'POSTGRES_DB=keycloak', '-e', 'POSTGRES_USER=keycloak',
               '-e', 'POSTGRES_INITDB_ARGS=--encoding=UTF8 --locale=C.UTF-8',
               '-v', new_volume + ':/var/lib/postgresql/data', candidate)
        for _ in range(60):
            if docker('exec', temporary, 'pg_isready', '-U', 'keycloak', '-d', 'keycloak', check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Candidate PostgreSQL did not become ready')
        docker('exec', '-i', temporary, 'pg_restore', '-U', 'keycloak', '-d', 'keycloak',
               '--exit-on-error', '--no-owner', data=restored)
        assert identity_fingerprint(temporary) == fingerprint, 'Identity/client/credential rows changed during restore'
        assert int(sql(temporary, 'SHOW server_version_num')) // 10000 == 17
        record = {'source_image': original['Config']['Image'], 'source_volume': volume,
                  'target_image': candidate, 'target_volume': new_volume, 'backup': str(backup.relative_to(ROOT)),
                  'identity_fingerprint': fingerprint, 'status': 'restored'}
        docker('rm', '-f', temporary)
        marker.write_text(json.dumps(record, indent=2))
        with log_path.open('w') as log:
            subprocess.run(['bash', 'scripts/staging.sh', 'up'], env=env, stdout=log, stderr=log, check=True, timeout=600)
            switched = True
            subprocess.run(['python3', 'scripts/wait-staging.py'], env=env, stdout=log, stderr=log, check=True, timeout=200)
            subprocess.run(['python3', 'scripts/test-oidc.py'], env=env, stdout=log, stderr=log, check=True, timeout=180)
        assert identity_fingerprint(name) == fingerprint, 'Identity rows changed after login validation'
        record['status'] = 'verified'
        marker.write_text(json.dumps(record, indent=2))
        print('PASS: encrypted identity backup, logical PostgreSQL restore, preserved identity/client/credential rows and HTTPS/OIDC checks. Original volume retained.')
    except Exception:
        if marker.exists():
            rollback_env = dict(env, POSTGRES_IMAGE=original['Config']['Image'], POSTGRES_VOLUME=volume)
            with log_path.open('a') as log:
                subprocess.run(['bash', 'scripts/staging.sh', 'up'], env=rollback_env, stdout=log, stderr=log, timeout=600, check=True)
            switched = True
            record = json.loads(marker.read_text())
            record['status'] = 'rolled_back'
            marker.write_text(json.dumps(record, indent=2))
        raise
    finally:
        docker('rm', '-f', temporary, check=False)
        if not switched:
            for service in stopped:
                docker('start', service)


if __name__ == '__main__':
    try:
        main()
    except (subprocess.SubprocessError, AssertionError, RuntimeError) as error:
        raise SystemExit('Identity migration failed (' + type(error).__name__ + '); inspect protected local state/logs. Original volume was retained.')
