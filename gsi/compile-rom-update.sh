#!/usr/bin/env bash
# A dependent build job, not a recurring monitor. Never modifies a tree while its build holds locks.
set -eo pipefail
logs="$HOME/android/logs"
root="$HOME/android/lineage"
bundle="${1:?A frozen update bundle is required}"
exec 7>"$logs/rom-update-queue.lock"
flock -n 7 || { echo 'A projection update build is already queued'; exit 1; }
exec >>"$logs/rom-update-build.log" 2>&1
stage=waiting-for-current-build
takeover=false
guard_pid=''
status() {
    printf '%s\t%s\n' "$(date -Is)" "$stage" > "$logs/rom-update-build.status"
    if $takeover; then cp "$logs/rom-update-build.status" "$logs/gsi-build.status"; fi
    echo "STAGE $stage $(date -Is)"
}
cleanup() {
    code=$?
    if [[ -n "$guard_pid" ]]; then kill "$guard_pid" 2>/dev/null || true; fi
    if ((code)); then stage="FAILED:$stage:exit=$code"; status; fi
}
trap cleanup EXIT
trap 'exit 143' TERM
status
exec 8>"$logs/gsi-run.lock"
flock 8
exec 9>"$logs/gsi-build.lock"
flock 9
if [[ "$(cut -f2 "$logs/gsi-build.status")" != IMAGE_BUILT_NOT_DEVICE_TESTED ]]; then
    stage=predecessor-did-not-succeed; status
    echo 'The preceding build failed or did not finish exporting; preserve its diagnostic state.'
    exit 3
fi
python3 - "$bundle" <<'PY'
import hashlib, json, shutil, sys
from pathlib import Path
bundle = Path(sys.argv[1]).resolve()
updates = (Path.home() / 'android/updates').resolve()
if not bundle.is_relative_to(updates) or bundle == updates:
    raise SystemExit('Update bundle must be inside the build updates directory')
manifest = json.loads((bundle / 'bundle-sources.json').read_text())
for relative, expected in manifest.items():
    target = (bundle / relative).resolve()
    if not target.is_relative_to(bundle) or hashlib.sha256(target.read_bytes()).hexdigest() != expected:
        raise SystemExit('Frozen update changed: ' + relative)
if shutil.disk_usage('/mnt/e').free < 30 * 1024**3:
    raise SystemExit('Less than 30 GiB free for the incremental update and its separate image')
PY
exec >>"$logs/gsi-build.log" 2>&1
takeover=true
stage=installing-projection-update; status
python3 "$bundle/tools/install-vehicle-update.py" --inherited-locks
python3 "$bundle/gsi/disk-guard.py" "$$" >> "$logs/gsi-space-guard.log" 2>&1 &
guard_pid=$!
source "$HOME/.config/lineage-build/env.sh"
export ANDROID_HOME="$HOME/android/sdk"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd "$root"
stage=selecting-projection-product; status
source build/envsetup.sh
lunch goodman_arm64_bgN4-bp4a-userdebug
stage=compiling-projection-app; status
m -j"$BUILD_JOBS" GoodmanCarBridge
stage=compiling-projection-system-image; status
m -j"$BUILD_JOBS" systemimage simg2img aapt2
product_out="$(get_build_var PRODUCT_OUT)"
stage=exporting-projection-image; status
python3 "$bundle/gsi/export-image.py" "$root" "$product_out" --variant rom-updates-20261007
stage=IMAGE_BUILT_NOT_DEVICE_TESTED; status
