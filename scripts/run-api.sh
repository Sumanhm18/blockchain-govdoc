#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
umask 077
export IPFS_API_BASE="${IPFS_API_BASE:-http://127.0.0.1:15001}"
export REGISTRY_BIND_ADDRESS=127.0.0.1
export REGISTRY_DATABASE_URL="jdbc:h2:file:$ROOT_DIR/.runtime/data/registry;DB_CLOSE_ON_EXIT=FALSE"
mkdir -p .runtime/data .runtime/logs/api
mvn -B -q -f application/api-spring/pom.xml package
# Run an immutable copy: later Maven builds must not overwrite a running executable JAR.
JAR_PATH="$(python3 - <<'PYJAR'
from pathlib import Path
import hashlib, shutil
source=Path('application/api-spring/target/api-spring-0.0.1-SNAPSHOT.jar')
target=Path('.runtime/api') / (hashlib.sha256(source.read_bytes()).hexdigest()+'.jar')
target.parent.mkdir(parents=True,exist_ok=True)
if not target.exists(): shutil.copyfile(source,target)
print(target)
PYJAR
)"
exec java -jar "$JAR_PATH" --server.port=18081 --logging.file.name=.runtime/logs/api/application.log
