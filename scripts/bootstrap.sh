#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
umask 077
ROOT_DIR="$PWD"
source scripts/docker-env.sh
for tool in git python3 docker; do command -v "$tool" >/dev/null || { echo "Missing prerequisite: $tool" >&2; exit 1; }; done
git submodule update --init fabric-samples
python3 scripts/dev-secrets.py
python3 scripts/build-ipfs-image.py
./scripts/build-node-runtime.sh
python3 scripts/build-fabric-images.py
python3 scripts/prepare-network.py
