#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
umask 077
python3 scripts/wait-ready.py http://127.0.0.1:18081/actuator/health/readiness
python3 scripts/presentation-demo.py "$@"
