#!/bin/bash
cd "$(dirname "$0")" || exit 1
python3 scripts/start-project.py --menu
result=$?
if [ "$result" -ne 0 ]; then
  read -r -p "Press Enter to close." _reply
fi
exit "$result"
