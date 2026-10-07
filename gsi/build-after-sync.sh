#!/usr/bin/env bash
set -eo pipefail
project="$HOME/projects/lineage-car-rom"
root="$HOME/android/lineage"
logs="$HOME/android/logs"
sync_pid="${1:?Pass the running source-sync PID}"
exec 9>"$logs/gsi-build.lock"
flock -n 9 || { echo 'Another GSI build already holds the lock'; exit 1; }
exec >>"$logs/gsi-build.log" 2>&1
stage=waiting-for-source
status() { printf '%s\t%s\n' "$(date -Is)" "$stage" > "$logs/gsi-build.status"; echo "STAGE $stage $(date -Is)"; }
guard_pid=''
cleanup() {
    code=$?
    if [[ -n "$guard_pid" ]]; then kill "$guard_pid" 2>/dev/null || true; fi
    if ((code)); then stage="FAILED:$stage:exit=$code"; status; fi
}
trap cleanup EXIT
trap 'exit 143' TERM
python3 "$project/gsi/disk-guard.py" "$sync_pid" "$$" >> "$logs/gsi-space-guard.log" 2>&1 &
guard_pid=$!
status
while [[ -e "/proc/$sync_pid/cmdline" ]] && grep -q 'sync-source.sh' "/proc/$sync_pid/cmdline"; do sleep 20; done
test -s "$logs/source-lock.xml"
grep -qx SUCCESS "$logs/gsi-sync.result"
source "$HOME/.config/lineage-build/env.sh"
cd "$root"
stage=checking-build-space; status
python3 - <<'PY'
import shutil
if shutil.disk_usage('/mnt/e').free < 80 * 1024**3:
    raise SystemExit('Less than 80 GiB free on E:; stop before compiling')
PY
stage=checking-required-inputs; status
test -s vendor/gapps/arm64/arm64-vendor.mk
test -s "$HOME/android/sdk/platforms/android-33/android.jar"
test -x "$HOME/android/sdk/build-tools/30.0.3/aapt2"
stage=applying-gsi-patches; status
python3 "$project/gsi/apply-patches.py" "$root"
stage=installing-goodman-sources; status
python3 "$project/tools/install-sources.py" "$root"
stage=installing-vehicle-platform; status
python3 "$project/tools/install-vehicle-update.py" --inherited-locks
source "$HOME/.config/lineage-build/env.sh"
stage=preparing-sdk; status
test -x "$HOME/android/sdk/cmdline-tools/latest/bin/sdkmanager"
export ANDROID_HOME="$HOME/android/sdk"
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export GRADLE_OPTS='-Dorg.gradle.workers.max=2 -Dorg.gradle.daemon=false -Xmx2g'
stage=building-treble-settings; status
(cd treble_app; bash ./build.sh release)
stage=selecting-gsi-product; status
source build/envsetup.sh
lunch goodman_arm64_bgN4-bp4a-userdebug
stage=compiling-hfp-module; status
m -j2 GoodmanCarBridge
stage=compiling-system-image; status
m -j2 systemimage simg2img aapt2
product_out="$(get_build_var PRODUCT_OUT)"
stage=verifying-and-exporting-image; status
python3 "$project/gsi/export-image.py" "$root" "$product_out"
stage=IMAGE_BUILT_NOT_DEVICE_TESTED; status
