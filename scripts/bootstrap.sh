#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
BIN_DIR="$ROOT_DIR/fabric-bin"
SAMPLES_DIR="$ROOT_DIR/fabric-samples"

mkdir -p "$BIN_DIR"
cd "$BIN_DIR"

if [ ! -f "$BIN_DIR/bootstrap.sh" ]; then
  echo "Downloading Fabric bootstrap.sh..."
  curl -sSLo bootstrap.sh https://raw.githubusercontent.com/hyperledger/fabric/main/scripts/bootstrap.sh
  chmod +x bootstrap.sh
fi

# Download binaries, docker images, and samples (Fabric and CA versions)
FABRIC_VERSION="2.5.12"
CA_VERSION="1.5.15"

if [ ! -d "$SAMPLES_DIR" ]; then
  echo "Running bootstrap to fetch binaries, images, and samples..."
  export PATH="$BIN_DIR/bin:$PATH"
  bash bootstrap.sh "$FABRIC_VERSION" "$CA_VERSION"
  # bootstrap clones fabric-samples into current working dir if -s not used
  if [ -d "$BIN_DIR/fabric-samples" ] && [ ! -d "$SAMPLES_DIR" ]; then
    mv "$BIN_DIR/fabric-samples" "$SAMPLES_DIR"
  fi
else
  echo "fabric-samples already present: $SAMPLES_DIR"
fi

echo "Done. Binaries in $BIN_DIR/bin and samples in $SAMPLES_DIR"
