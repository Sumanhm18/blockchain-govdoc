#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT_DIR/scripts/docker-env.sh"
NET="$ROOT_DIR/.runtime/fabric/test-network"
export PATH="$ROOT_DIR/.runtime/fabric/bin:$PATH"
export COMPOSE_PROJECT_NAME=govdoc-dev-fabric
export CONTAINER_CLI=docker
mkdir -p "$ROOT_DIR/.runtime/logs"
umask 077
trap 'echo "Network operation failed; inspect the protected .runtime/logs directory locally." >&2' ERR
[[ -d "$NET" ]] || { echo 'Run ./scripts/bootstrap.sh first.' >&2; exit 1; }
cd "$NET"
case "${1:-}" in
  up)
    for container in govdoc-dev-orderer.example.com govdoc-dev-peer0.org1.example.com govdoc-dev-peer0.org2.example.com govdoc-dev-ca_org1 govdoc-dev-ca_org2 govdoc-dev-ca_orderer; do
      if docker inspect "$container" >/dev/null 2>&1; then
        owner="$(docker inspect -f '{{ index .Config.Labels "com.docker.compose.project" }}' "$container")"
        [[ "$owner" == "$COMPOSE_PROJECT_NAME" ]] || { echo 'A different Fabric network owns required container names; refusing to modify it.' >&2; exit 1; }
      fi
    done
    if [[ -f "$ROOT_DIR/.runtime/network-ready" ]]; then
      ./network.sh up -ca > "$ROOT_DIR/.runtime/logs/network.log" 2>&1
    else
      ./network.sh up createChannel -c govchannel -ca > "$ROOT_DIR/.runtime/logs/network.log" 2>&1
      touch "$ROOT_DIR/.runtime/network-ready"
    fi
    # Upstream skips CA startup once organizations exist; explicitly apply reviewed image patches.
    docker compose -f compose/compose-ca.yaml -f compose/docker/docker-compose-ca.yaml up -d > "$ROOT_DIR/.runtime/logs/ca-update.log" 2>&1
    echo 'Development network and govchannel are ready.'
    ;;
  deployCC)
    runtime_hash="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["image_id"].split(":")[1][:12])' "$ROOT_DIR/.runtime/node-runtime.json")"
    ./network.sh deployCC -c govchannel -ccn docregistry -ccp "$ROOT_DIR/chaincode/docregistry" -ccl javascript -ccv "${CC_VERSION:-2.5-$runtime_hash}" -ccs "${CC_SEQUENCE:-auto}" > "$ROOT_DIR/.runtime/logs/chaincode-deploy.log" 2>&1
    echo 'docregistry deployment completed.'
    ;;
  status) docker compose -f compose/compose-test-net.yaml ps ;;
  *) echo 'Usage: ./scripts/network.sh {up|deployCC|status}. Development shutdown: see docs/OPERATIONS.md.' >&2; exit 1 ;;
esac
