#!/usr/bin/env bash
set -euo pipefail
source "$HOME/.config/lineage-build/env.sh"
root="$HOME/android/lineage"
project="$HOME/projects/lineage-car-rom"
logs="$HOME/android/logs"
mkdir -p "$logs"
exec >>"$logs/gsi-sync.log" 2>&1
sync_result="$logs/gsi-sync.result"
printf 'RUNNING\n' > "$sync_result"
finish_sync() {
    code=$?
    if ((code)); then printf 'FAILED:%s\n' "$code" > "$sync_result"; fi
}
trap finish_sync EXIT
trap 'exit 143' TERM
echo "START $(date -Is)"
export GIT_TERMINAL_PROMPT=0
# Build-only identity for repo bootstrap; does not change personal git config.
export GIT_AUTHOR_NAME='Goodman GSI Build' GIT_COMMITTER_NAME='Goodman GSI Build'
export GIT_AUTHOR_EMAIL='gsi-build@localhost' GIT_COMMITTER_EMAIL='gsi-build@localhost'
cd "$root"
if [[ ! -d .repo ]]; then
    python3 "$project/tools/preflight.py" "$root"
    repo init -u https://github.com/LineageOS/android.git \
        -b eabe68377217a88c81fa933db0136ea4146ff369 \
        --depth=1 --git-lfs --no-clone-bundle </dev/null
fi
if [[ "${GOODMAN_USE_SOURCE_LOCK:-0}" == 1 ]]; then
    test -s "$project/gsi/source-lock.xml"
    if [[ -d .repo/local_manifests ]] && find .repo/local_manifests -name '*.xml' -print -quit | grep -q .; then
        echo 'Pinned full manifest requires no local manifests; stop for review.' >&2
        exit 1
    fi
    cp "$project/gsi/source-lock.xml" .repo/manifests/goodman-pinned.xml
    repo init -m goodman-pinned.xml --depth=1 --git-lfs --no-clone-bundle </dev/null
else
mkdir -p .repo/local_manifests
if [[ -e .repo/local_manifests/goodman-gsi.xml ]]; then
    cmp "$project/gsi/manifest.xml" .repo/local_manifests/goodman-gsi.xml
else
    cp "$project/gsi/manifest.xml" .repo/local_manifests/goodman-gsi.xml
fi
fi
echo "SYNC $(date -Is)"
repo sync -c -j2 --no-tags --no-clone-bundle --optimized-fetch --fail-fast
repo manifest -r -o "$logs/source-lock.xml"
echo "SOURCE_SYNC_COMPLETE $(date -Is)"
printf 'SUCCESS\n' > "$sync_result"
