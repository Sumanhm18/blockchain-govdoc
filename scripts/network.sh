#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLES_DIR="$ROOT_DIR/fabric-samples"
TEST_NETWORK_DIR="$SAMPLES_DIR/test-network"
CC_NAME="docregistry"
CC_LABEL="docregistry_3"
CC_LANG="javascript"
CC_PATH="$ROOT_DIR/chaincode/docregistry"
CHANNEL_NAME="govchannel"
CC_SEQUENCE="1"
CC_VERSION="1.2"

function ensure_samples() {
  if [ ! -d "$TEST_NETWORK_DIR" ]; then
    echo "test-network not found. Run ./scripts/bootstrap.sh first." >&2
    exit 1
  fi
}

function up() {
  ensure_samples
  pushd "$TEST_NETWORK_DIR" >/dev/null
  ./network.sh up createChannel -c "$CHANNEL_NAME" -ca
  popd >/dev/null
  mkdir -p "$ROOT_DIR/application/gateway"
  cp "$TEST_NETWORK_DIR/organizations/peerOrganizations/org1.example.com/connection-org1.json" "$ROOT_DIR/application/gateway/connection-org1.json"
  echo "Network is up. Channel: $CHANNEL_NAME"
}

function create_channel() {
  ensure_samples
  pushd "$TEST_NETWORK_DIR" >/dev/null
  ./network.sh createChannel -c "$CHANNEL_NAME"
  popd >/dev/null
}

function deploy_cc() {
  ensure_samples
  pushd "$TEST_NETWORK_DIR" >/dev/null
  ./network.sh deployCC -c "$CHANNEL_NAME" -ccn "$CC_NAME" -ccp "$CC_PATH" -ccl "$CC_LANG" -ccv "$CC_VERSION" -ccs "$CC_SEQUENCE"
  popd >/dev/null
}

function down() {
  ensure_samples
  pushd "$TEST_NETWORK_DIR" >/dev/null
  ./network.sh down
  popd >/dev/null
}

function clean() {
  ensure_samples
  pushd "$TEST_NETWORK_DIR" >/dev/null
  ./network.sh down
  docker volume rm $(docker volume ls -q | grep "fabric_" || true) >/dev/null 2>&1 || true
  popd >/dev/null
}

case "${1:-}" in
  up) up ;;
  createChannel) create_channel ;;
  deployCC) deploy_cc ;;
  down) down ;;
  clean) clean ;;
  *) echo "Usage: $0 {up|createChannel|deployCC|down|clean}"; exit 1 ;;
 esac
