#!/usr/bin/env bash
# Build one agent jar containing all structurally-selected NMS templates.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

PREPARE=false
SKIP_TESTS=false
for arg in "$@"; do
    case "$arg" in
        --prepare) PREPARE=true ;;
        --skip-tests) SKIP_TESTS=true ;;
        -h|--help)
            echo "Usage: bash build.sh [--prepare] [--skip-tests]"
            echo "  --prepare     Download and patch the pinned Paper NMS inputs first"
            echo "  --skip-tests  Package without running Maven tests"
            exit 0
            ;;
        *)
            echo "ERROR: unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

NMS_ROOT="${NMS_ROOT:-$ROOT_DIR/nms-lib}"

if [ "$PREPARE" = true ]; then
    NMS_ROOT="$NMS_ROOT" bash tools/prepare-paper-nms.sh
fi

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
    JAVA_BIN="$JAVA_HOME/bin/java"
    JAVAC_BIN="$JAVA_HOME/bin/javac"
else
    JAVAC_BIN="$(command -v javac || true)"
    JAVA_BIN="$(command -v java || true)"
    if [ -n "$JAVAC_BIN" ]; then
        JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$JAVAC_BIN")")")"
        export JAVA_HOME
    fi
fi

if [ -z "$JAVAC_BIN" ] || [ -z "$JAVA_BIN" ]; then
    echo "ERROR: Java 21+ JDK is required (java and javac must both exist)." >&2
    exit 1
fi

JAVA_SPEC="$($JAVA_BIN -XshowSettings:properties -version 2>&1 \
    | awk -F= '/java.specification.version/ {gsub(/[[:space:]]/, "", $2); print $2; exit}')"
JAVA_MAJOR="${JAVA_SPEC#1.}"
if [ -z "$JAVA_MAJOR" ] || [ "$JAVA_MAJOR" -lt 21 ]; then
    echo "ERROR: Java 21+ JDK is required; detected Java ${JAVA_SPEC:-unknown}." >&2
    exit 1
fi

PROFILES=(value-io registry-nbt legacy-mojang legacy-spigot)
declare -A RELEASES=(
    [value-io]=21
    [registry-nbt]=21
    [legacy-mojang]=17
    [legacy-spigot]=17
)

for profile in "${PROFILES[@]}"; do
    if [ ! -f "$NMS_ROOT/$profile/server.jar" ]; then
        echo "ERROR: missing $NMS_ROOT/$profile/server.jar" >&2
        echo "Run: bash tools/prepare-paper-nms.sh" >&2
        exit 1
    fi
done

echo "== 1. Compile Java 17 bootstrap agent =="
mvn -q -B clean compile -DskipTests

echo "== 2. Compile javac-verified NMS templates =="
for profile in "${PROFILES[@]}"; do
    source_file="templates/$profile/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.java"
    output_dir="target/classes/templates/$profile"
    server_jar="$NMS_ROOT/$profile/server.jar"
    library_dir="$NMS_ROOT/$profile/work/libraries"
    libraries=""
    if [ -d "$library_dir" ]; then
        libraries="$(find "$library_dir" -type f -name '*.jar' -print | sort | paste -sd: -)"
    fi
    classpath="$server_jar:$ROOT_DIR/target/classes"
    if [ -n "$libraries" ]; then
        classpath="$classpath:$libraries"
    fi
    mkdir -p "$output_dir"
    echo "-- $profile (release ${RELEASES[$profile]})"
    "$JAVAC_BIN" --release "${RELEASES[$profile]}" -proc:none -nowarn \
        -cp "$classpath" -d "$output_dir" "$source_file"
done

echo "== 3. Test and package shaded agent =="
MAVEN_TEST_ARGS=()
if [ "$SKIP_TESTS" = true ]; then
    MAVEN_TEST_ARGS=(-DskipTests)
fi
mvn -q -B package -Dlazycontainer.templatesReady=true \
    -Dlazycontainer.nmsRoot="$NMS_ROOT" "${MAVEN_TEST_ARGS[@]}"

JAR="target/LazyContainerAgent.jar"
if [ ! -f "$JAR" ]; then
    echo "ERROR: $JAR was not produced" >&2
    exit 1
fi

echo "== 4. Verify packaged resources =="
jar tf "$JAR" > target/lazycontainer-jar-entries.txt
for profile in "${PROFILES[@]}"; do
    entry="templates/$profile/io/github/kuohsuanlo/lazycontainer/LazyContainerTemplate.class"
    if ! grep -Fxq "$entry" target/lazycontainer-jar-entries.txt; then
        echo "ERROR: packaged jar misses $entry" >&2
        exit 1
    fi
done
unzip -p "$JAR" META-INF/MANIFEST.MF \
    | grep -E 'Premain-Class|Agent-Class|LazyContainer-Supported-Versions'
echo "DONE: $(readlink -f "$JAR")"
