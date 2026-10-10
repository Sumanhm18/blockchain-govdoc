#!/usr/bin/env bash
# Prepare a local-only candidate; never rewrite this checkout or push to any remote.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"
umask 077
command -v git-filter-repo >/dev/null || { echo 'Install git-filter-repo in an isolated tool environment first.' >&2; exit 1; }
[[ -z "$(git status --porcelain --untracked-files=normal)" ]] || { echo 'Commit and review source changes before preparing a history rewrite.' >&2; exit 1; }
DEST="$ROOT_DIR/.runtime/history-cleanup.git"
[[ ! -e "$DEST" ]] || { echo 'A candidate already exists; review it before generating another.' >&2; exit 1; }
mkdir -p "$ROOT_DIR/.runtime"
git clone --mirror --no-local "$ROOT_DIR" "$DEST"
git -C "$DEST" filter-repo --force --invert-paths \
  --path node_modules/ --path application/client/node_modules/ \
  --path chaincode/docregistry/node_modules/ --path fabric-bin/ \
  --path application/api-spring/target/ --path application/api-spring/logs/ --path logs/ \
  --path wallet/ --path application/wallet/ --path uploads/ --path organizations/ \
  --path application/gateway/ --path connection-org1.json --path network/test-network/ \
  --path-glob '*.pem' --path-glob '*.key' --path-glob '*_sk' --path-glob '*.id'
python3 "$ROOT_DIR/scripts/check-history.py" "$DEST"
printf 'Local candidate created at .runtime/history-cleanup.git. No remote was changed.\n'
printf 'Review all history and filter coverage, rotate exposed credentials, coordinate force-push approval and fresh clones.\n'
