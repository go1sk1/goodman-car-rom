#!/usr/bin/env bash
set -eo pipefail
project="$HOME/projects/lineage-car-rom"
logs="$HOME/android/logs"
mkdir -p "$logs"
exec >>"$logs/ssd-setup.log" 2>&1
trap 'code=$?; if ((code)); then printf "%s\tFAILED:ssd-setup:exit=%s\n" "$(date -Is)" "$code" > "$logs/gsi-build.status"; fi' EXIT
printf '%s\tpreparing-sdk\n' "$(date -Is)" > "$logs/gsi-build.status"
python3 "$project/gsi/setup-sdk.py"
export GOODMAN_USE_SOURCE_LOCK=1
exec bash "$project/gsi/run-build.sh"
