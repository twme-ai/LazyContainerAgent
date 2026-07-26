#!/usr/bin/env bash
# Download checksum-verified, pinned Paper builds and run Paperclip in patch-only mode.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
NMS_ROOT="${NMS_ROOT:-$ROOT_DIR/nms-lib}"
PAPER_API="https://fill.papermc.io/v3/projects/paper"
USER_AGENT="${PAPER_API_USER_AGENT:-twme-ai/LazyContainerAgent multi-version builder}"

for command_name in curl jq sha256sum java; do
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "ERROR: required command not found: $command_name" >&2
        exit 1
    fi
done

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
else
    JAVA_BIN="$(command -v java)"
fi

JAVA_SPEC="$($JAVA_BIN -XshowSettings:properties -version 2>&1 \
    | awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}')"
JAVA_MAJOR="${JAVA_SPEC#1.}"
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "ERROR: Java 21+ is required to patch the pinned Paper inputs." >&2
    exit 1
fi

mkdir -p "$NMS_ROOT"

prepare_profile() {
    local profile="$1"
    local version="$2"
    local build_id="$3"
    local download_key="$4"
    local profile_dir="$NMS_ROOT/$profile"
    local metadata_file="$profile_dir/builds.json"
    local selected_file="$profile_dir/build.json"
    local paperclip_file="$profile_dir/paperclip.jar"
    local patched_file="$profile_dir/work/versions/$version/paper-$version.jar"

    mkdir -p "$profile_dir"
    echo "== $profile: Paper $version build $build_id ($download_key) =="
    curl --fail --silent --show-error --location --retry 3 \
        --user-agent "$USER_AGENT" \
        "$PAPER_API/versions/$version/builds" -o "$metadata_file"
    jq --argjson build "$build_id" '.[] | select(.id == $build)' \
        "$metadata_file" > "$selected_file"

    local url
    local expected_sha
    url="$(jq -er --arg key "$download_key" '.downloads[$key].url' "$selected_file")"
    expected_sha="$(jq -er --arg key "$download_key" \
        '.downloads[$key].checksums.sha256' "$selected_file")"

    local current_sha=""
    if [ -f "$paperclip_file" ]; then
        current_sha="$(sha256sum "$paperclip_file" | awk '{print $1}')"
    fi
    if [ "$current_sha" != "$expected_sha" ]; then
        curl --fail --location --retry 3 --progress-bar \
            --user-agent "$USER_AGENT" "$url" -o "$paperclip_file.part"
        local downloaded_sha
        downloaded_sha="$(sha256sum "$paperclip_file.part" | awk '{print $1}')"
        if [ "$downloaded_sha" != "$expected_sha" ]; then
            echo "ERROR: checksum mismatch for $profile" >&2
            echo "expected $expected_sha, got $downloaded_sha" >&2
            exit 1
        fi
        mv "$paperclip_file.part" "$paperclip_file"
    fi

    if [ ! -f "$patched_file" ]; then
        mkdir -p "$profile_dir/work"
        (
            cd "$profile_dir/work"
            "$JAVA_BIN" -Dpaperclip.patchonly=true -jar "$paperclip_file"
        )
    fi
    if [ ! -f "$patched_file" ]; then
        echo "ERROR: Paperclip did not produce $patched_file" >&2
        exit 1
    fi
    ln -sfn "work/versions/$version/paper-$version.jar" "$profile_dir/server.jar"
}

# Pinned builds used for source-level and runtime signature verification.
prepare_profile value-io 1.21.8 60 server:default
prepare_profile registry-nbt 1.20.6 151 server:default
prepare_profile legacy-mojang 1.19.4 550 server:mojang
prepare_profile legacy-spigot 1.19.4 550 server:default

echo "Prepared NMS inputs under: $NMS_ROOT"
