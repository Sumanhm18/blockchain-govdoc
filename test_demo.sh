#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 scripts/e2e.py

python3 scripts/test-fabric-auth.py

python3 scripts/verify-runtime-images.py
