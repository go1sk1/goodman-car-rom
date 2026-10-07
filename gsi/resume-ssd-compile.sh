#!/usr/bin/env bash
# Resume the freshly synced SSD tree, preserving completed compiler outputs.
set -eo pipefail
project="$HOME/projects/lineage-car-rom"
root="$HOME/android/lineage"
logs="$HOME/android/logs"
exec 8>"$logs/gsi-run.lock"
flock -n 8 || exit 1
exec 9>"$logs/gsi-build.lock"
flock -n 9 || exit 1
exec >>"$logs/gsi-build.log" 2>&1
stage=resume-ssd-inputs
guard_pid=''
status() { printf '%s\t%s\n' "$(date -Is)" "$stage" > "$logs/gsi-build.status"; echo "STAGE $stage $(date -Is)"; }
cleanup() {
    code=$?
    if [[ -n "$guard_pid" ]]; then kill "$guard_pid" 2>/dev/null || true; fi
    if ((code)); then stage="FAILED:$stage:exit=$code"; status; fi
}
trap cleanup EXIT
trap 'exit 143' TERM
status
grep -qx SUCCESS "$logs/gsi-sync.result"
python3 - <<'PY'
import hashlib, json, shutil
from pathlib import Path
root = Path.home() / 'android/lineage'
logs = Path.home() / 'android/logs'
assert shutil.disk_usage('/mnt/e').free >= 80 * 1024**3, 'Insufficient SSD space'
assert (logs / 'source-lock.xml').is_file(), 'Missing synced source manifest'
assert len(json.loads((root / 'goodman-patches.json').read_text())) == 314, 'Missing applied patches'
for relative, expected in json.loads((root / 'goodman-sources.json').read_text()).items():
    file = (root / relative).resolve()
    assert file.is_relative_to(root) and hashlib.sha256(file.read_bytes()).hexdigest() == expected, relative
PY
test -s "$root/treble_app/TrebleApp.apk"
python3 "$project/gsi/disk-guard.py" "$$" >> "$logs/gsi-space-guard.log" 2>&1 &
guard_pid=$!
source "$HOME/.config/lineage-build/env.sh"
[[ "$BUILD_JOBS" =~ ^[1-6]$ ]]
export ANDROID_HOME="$HOME/android/sdk"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd "$root"
stage=selecting-gsi-product; status
source build/envsetup.sh
lunch goodman_arm64_bgN4-bp4a-userdebug
echo "SSD compile parallel jobs: $BUILD_JOBS"
stage=compiling-hfp-module; status
m -j"$BUILD_JOBS" GoodmanCarBridge
stage=compiling-system-image; status
m -j"$BUILD_JOBS" systemimage simg2img aapt2
product_out="$(get_build_var PRODUCT_OUT)"
stage=verifying-and-exporting-image; status
python3 "$project/gsi/export-image.py" "$root" "$product_out"
stage=IMAGE_BUILT_NOT_DEVICE_TESTED; status
