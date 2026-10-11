#!/usr/bin/env python3
"""Encrypted application/IPFS backup and isolated restore drill; requires cryptography."""
import hashlib, json, os, secrets, shutil, subprocess, tarfile, tempfile, time, urllib.request
from pathlib import Path
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.exceptions import InvalidTag
import e2e
os.umask(0o077)
root=Path(__file__).resolve().parents[1]
env=dict(os.environ,DOCKER_CONFIG=str(root/'.runtime/docker'))
if 'DOCKER_HOST' not in env:
    for socket in [Path.home()/'.docker/run/docker.sock',Path.home()/'.docker/desktop/docker.sock']:
        if socket.exists(): env['DOCKER_HOST']='unix://'+str(socket);break

def docker(*args,**kwargs): return subprocess.run(['docker',*args],env=env,check=True,capture_output=True,timeout=90,**kwargs)
def seal(source,target,key):
    nonce=secrets.token_bytes(12);cipher=Cipher(algorithms.AES(key),modes.GCM(nonce)).encryptor();cipher.authenticate_additional_data(b'GOVDOC-BACKUP-1')
    with open(source,'rb') as src,open(target,'xb') as dst:
        dst.write(b'GDBACK01'+nonce)
        while chunk:=src.read(1048576): dst.write(cipher.update(chunk))
        dst.write(cipher.finalize());dst.write(cipher.tag)
def unseal(source,target,key):
    with open(source,'rb') as src:
        assert src.read(8)==b'GDBACK01'; nonce=src.read(12);src.seek(-16,2);tag=src.read(16);remaining=src.tell()-36;src.seek(20)
        cipher=Cipher(algorithms.AES(key),modes.GCM(nonce,tag)).decryptor();cipher.authenticate_additional_data(b'GOVDOC-BACKUP-1')
        try:
            with open(target,'xb') as dst:
                while remaining:
                    chunk=src.read(min(1048576,remaining));remaining-=len(chunk);dst.write(cipher.update(chunk))
                dst.write(cipher.finalize())
        except Exception:
            Path(target).unlink(missing_ok=True);raise

def extract(source,target):
    with tarfile.open(source) as archive:
        for member in archive:
            if not (member.isfile() or member.isdir()): raise RuntimeError('Non-file archive member rejected')
            if not (target/member.name).resolve().is_relative_to(target.resolve()): raise RuntimeError('Archive path escape rejected')
        archive.extractall(target,filter='data')

def ready(port):
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        try:
            with urllib.request.urlopen(f'http://127.0.0.1:{port}/actuator/health/readiness',timeout=5) as r:
                if r.status==200:return
        except OSError: pass
        time.sleep(1)
    raise RuntimeError('Restored API did not become ready')

def main():
    id='BACKUP-'+secrets.token_hex(6).upper(); document=b'%PDF-1.4\nSynthetic backup restore fixture\n%%EOF\n'
    body,kind=e2e.multipart({'docId':id},document)
    e2e.call('POST','/api/documents/upload',200,body,content_type=kind)
    containers=['govdoc-dev-app-api-1','govdoc-dev-app-ipfs-1']
    info=json.loads(docker('inspect',*containers).stdout)
    if any(c['Config']['Labels'].get('com.docker.compose.project')!='govdoc-dev-app' or not c['State']['Running'] for c in info): raise RuntimeError('Only a running isolated development stack can be backed up')
    ipfs=info[1];volume=next(m['Name'] for m in ipfs['Mounts'] if m['Destination']=='/data/ipfs')
    image=ipfs['Image']
    staging=subprocess.run(['docker','inspect','govdoc-staging-api-1'],env=env,capture_output=True)
    if staging.returncode==0:
        current=json.loads(staging.stdout)[0]
        if current['State']['Running'] and current['Config']['Labels'].get('com.docker.compose.project')=='govdoc-staging':containers.append('govdoc-staging-api-1')
    backups=root/'.runtime/backups';backups.mkdir(exist_ok=True)
    key_path=root/'.runtime/secrets/backup-master.key'
    if not key_path.exists(): key_path.write_bytes(secrets.token_bytes(32))
    key=key_path.read_bytes();backup=backups/('registry-'+secrets.token_hex(6)+'.tar.gcm')
    restore_container='govdoc-restore-'+secrets.token_hex(6); process=None;restored_started=False
    with tempfile.TemporaryDirectory(prefix='restore-drill-',dir=root/'.runtime') as temporary:
        temp=Path(temporary);snapshot=temp/'snapshot';snapshot.mkdir()
        try:
            docker('stop',*containers)
            try:
                shutil.copytree(root/'.runtime/data',snapshot/'data')
                # Back up only the API's required secrets, never CA credentials or backup wrapping keys.
                (snapshot/'secrets').mkdir()
                for name in ['users.json', 'encryption.json']:
                    shutil.copyfile(root/'.runtime/secrets'/name, snapshot/'secrets'/name)
                archive=temp/'ipfs.tar'
                with archive.open('wb') as output:
                    subprocess.run(['docker','run','--rm','--network','none','--user','0','--mount',f'type=volume,source={volume},target=/source,readonly','--entrypoint','tar',image,'-C','/source','-cf','-','.'],env=env,stdout=output,stderr=subprocess.DEVNULL,check=True,timeout=90)
                (snapshot/'ipfs').mkdir();extract(archive,snapshot/'ipfs');archive.unlink()
            finally: docker('start',*containers)
            manifest={str(p.relative_to(snapshot)):hashlib.sha256(p.read_bytes()).hexdigest() for p in snapshot.rglob('*') if p.is_file()}
            archive=temp/'snapshot.tar'
            with tarfile.open(archive,'w') as tar:tar.add(snapshot,arcname='snapshot')
            seal(archive,backup,key);archive.unlink();shutil.rmtree(snapshot)
            # Authentication must fail before extraction when ciphertext is altered.
            corrupt=temp/'corrupt.gcm';shutil.copyfile(backup,corrupt)
            with corrupt.open('r+b') as out:out.seek(25);v=out.read(1);out.seek(25);out.write(bytes([v[0]^1]))
            try:unseal(corrupt,temp/'rejected.tar',key);raise AssertionError('Altered backup was accepted')
            except InvalidTag:pass
            unseal(backup,archive,key);extract(archive,temp);archive.unlink()
            assert all(hashlib.sha256((snapshot/p).read_bytes()).hexdigest()==digest for p,digest in manifest.items())
            docker('run','-d','--name',restore_container,'--label','govdoc.test=backup-restore','--user',f'{os.getuid()}:{os.getgid()}','-p','127.0.0.1:18002:5001','-v',str(snapshot/'ipfs')+':/data/ipfs',image,'daemon','--offline')
            restored_started=True
            restored_env=dict(os.environ,IPFS_API_BASE='http://127.0.0.1:18002',REGISTRY_DATABASE_URL='jdbc:h2:file:'+str(snapshot/'data/registry')+';DB_CLOSE_ON_EXIT=FALSE',REGISTRY_USERS_FILE=str(snapshot/'secrets/users.json'),REGISTRY_KEYS_FILE=str(snapshot/'secrets/encryption.json'))
            jar=temp/'app.jar';shutil.copyfile(root/'application/api-spring/target/api-spring-0.0.1-SNAPSHOT.jar',jar)
            with (root/'.runtime/logs/restore-api.log').open('w') as log:
                process=subprocess.Popen(['java','-jar',str(jar),'--server.port=18082','--logging.file.name='+str(temp/'api.log')],cwd=root,env=restored_env,stdout=log,stderr=log)
                ready(18082);e2e.base='http://127.0.0.1:18082'
                assert e2e.call('GET','/api/documents/'+id+'/file',200,user='reader',binary=True)==document
        finally:
            if process:process.terminate();process.wait(timeout=20)
            if restored_started:docker('rm','-f',restore_container)
            e2e.base='http://127.0.0.1:18081'
    ready(18081);e2e.call('POST','/api/documents/'+id+'/revoke',200,{'reason':'Synthetic backup drill complete'})
    print('PASS: encrypted consistent journal/key/IPFS backup, tamper rejection, restored file checksums and exact document download from an isolated restored API/IPFS.')
    print('Backup retained at '+str(backup.relative_to(root))+'. Wrapping key remains separately in .runtime/secrets/backup-master.key. Fabric ledger was not restored by this drill.')
if __name__=='__main__':main()
