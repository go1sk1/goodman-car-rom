#!/usr/bin/env bash
set -eo pipefail
logs="$HOME/android/logs"
exec 7>"$logs/rom-package.lock"
flock -n 7 || exit 1
exec >>"$logs/rom-package.log" 2>&1
status() { printf '%s\t%s\n' "$(date -Is)" "$1" > "$logs/rom-package.status"; }
trap 'code=$?; if ((code)); then status "FAILED:packaging:exit=$code"; fi' EXIT
status waiting-for-rom-update-image
while true; do
    stage="$(cut -f2 "$logs/rom-update-build.status" 2>/dev/null || true)"
    if [[ "$stage" == IMAGE_BUILT_NOT_DEVICE_TESTED ]]; then break; fi
    if [[ "$stage" == FAILED:* ]]; then echo 'Dependent ROM build failed; no release packaging.'; exit 3; fi
    sleep 15
done
status packaging-signed-release
python3 "$HOME/android/release-tools/package-rom-update.py" \
    "$HOME/android/artifacts/rom-updates-20261007/GoodmanCar-lineage23.2-arm64-GAPPS-EXT4-development-system.img" \
    --repository go1sk1/goodman-car-rom --build rom-updates-20261007 \
    --key /mnt/c/Users/goodman/.codex/keys/goodman-car-rom/release-signing.pem \
    --output "$HOME/android/releases/rom-updates-20261007" \
    --notes 'Development build with signed image downloads. Phone boot, ccNC mirroring, wireless and HFP audio remain unverified. Install manually in TWRP after backup and device compatibility checks.'
status RELEASE_FILES_READY_NOT_PUBLISHED
