#!/usr/bin/env bash
set -eo pipefail
mkdir -p "$HOME/android/logs"
exec 8>"$HOME/android/logs/gsi-run.lock"
flock -n 8 || { echo 'A durable GSI runner is already active'; exit 1; }
project="$HOME/projects/lineage-car-rom"
printf 'STARTING\n' > "$HOME/android/logs/gsi-sync.result"
bash "$project/gsi/sync-source.sh" &
sync_pid=$!
exec bash "$project/gsi/build-after-sync.sh" "$sync_pid"
