#!/usr/bin/env bash
# Two-boot Paper round-trip test using a real world and the server console.
set -euo pipefail

if [ "$#" -ne 4 ]; then
    echo "Usage: $0 <version> <java-bin> <paperclip.jar> <prepared-paper-work-dir>" >&2
    exit 2
fi

VERSION="$1"
JAVA_BIN="$(readlink -f "$2")"
PAPER_JAR="$(readlink -f "$3")"
CACHE_DIR="$(readlink -f "$4")"
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
AGENT_JAR="$ROOT_DIR/target/LazyContainerAgent.jar"
RESULT_PARENT="${RUNTIME_TEST_ROOT:-$ROOT_DIR/target/runtime-tests}"
mkdir -p "$RESULT_PARENT"
TEST_DIR="$(mktemp -d "$RESULT_PARENT/$VERSION.XXXXXX")"

if [ ! -x "$JAVA_BIN" ] || [ ! -f "$PAPER_JAR" ] || [ ! -f "$AGENT_JAR" ]; then
    echo "ERROR: java, Paper jar, or agent jar is missing" >&2
    exit 1
fi

for asset in cache libraries versions; do
    if [ -e "$CACHE_DIR/$asset" ]; then
        ln -s "$CACHE_DIR/$asset" "$TEST_DIR/$asset"
    fi
done

printf 'eula=true\n' > "$TEST_DIR/eula.txt"
cat > "$TEST_DIR/server.properties" <<'PROPERTIES'
allow-nether=false
allow-flight=true
difficulty=peaceful
enable-command-block=false
gamemode=creative
generate-structures=false
level-name=world
level-type=flat
max-players=1
motd=LazyContainerAgent runtime test
online-mode=false
server-port=25569
simulation-distance=2
spawn-protection=0
sync-chunk-writes=true
view-distance=2
white-list=false
PROPERTIES
cat > "$TEST_DIR/bukkit.yml" <<'BUKKIT'
settings:
  allow-end: false
BUKKIT

SERVER_PID=""
CONSOLE_FD=""

stop_on_error() {
    if [ -n "$CONSOLE_FD" ]; then
        printf 'stop\n' >&"$CONSOLE_FD" 2>/dev/null || true
    fi
    if [ -n "$SERVER_PID" ] && kill -0 "$SERVER_PID" 2>/dev/null; then
        kill "$SERVER_PID" 2>/dev/null || true
    fi
}
trap stop_on_error EXIT

start_server() {
    local log_file="$1"
    local with_agent="$2"
    local fifo="$TEST_DIR/console.pipe"
    if [ -p "$fifo" ]; then
        unlink "$fifo"
    fi
    mkfifo "$fifo"
    exec {CONSOLE_FD}<>"$fifo"

    local java_args=(-Xms512M -Xmx1G)
    if [ "$with_agent" = true ]; then
        java_args+=(
            "-javaagent:$AGENT_JAR"
            -Dlazycontainer.shadow=true
            -Dlazycontainer.verbose=true
            -Dlazycontainer.verbose.ms=1000
        )
    fi
    (
        cd "$TEST_DIR"
        "$JAVA_BIN" "${java_args[@]}" -jar "$PAPER_JAR" --nogui < "$fifo"
    ) > "$log_file" 2>&1 &
    SERVER_PID=$!

    local ready=false
    for _ in $(seq 1 180); do
        if grep -q 'Done (' "$log_file" 2>/dev/null; then
            ready=true
            break
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    if [ "$ready" != true ]; then
        echo "ERROR: Paper $VERSION did not become ready" >&2
        tail -80 "$log_file" >&2 || true
        return 1
    fi
}

send_command() {
    printf '%s\n' "$1" >&"$CONSOLE_FD"
}

stop_server() {
    send_command stop
    for _ in $(seq 1 60); do
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            break
        fi
        sleep 1
    done
    local exit_status=0
    wait "$SERVER_PID" || exit_status=$?
    exec {CONSOLE_FD}>&-
    SERVER_PID=""
    CONSOLE_FD=""
    if [ "$exit_status" -ne 0 ]; then
        echo "WARN: Paper $VERSION launcher exited with status $exit_status after shutdown" >&2
    fi
}

SETUP_LOG="$TEST_DIR/boot-setup.log"
TEST_LOG="$TEST_DIR/boot-agent.log"

echo "== $VERSION boot 1: create vanilla fixtures =="
start_server "$SETUP_LOG" false
send_command 'forceload add 0 0'
send_command 'setblock 0 100 0 minecraft:chest'
send_command 'setblock 2 100 0 minecraft:barrel'
send_command 'setblock 4 100 0 minecraft:shulker_box'
send_command 'setblock 6 100 0 minecraft:chest'
send_command 'item replace block 0 100 0 container.0 with minecraft:diamond 42'
send_command 'item replace block 2 100 0 container.0 with minecraft:gold_ingot 17'
send_command 'item replace block 4 100 0 container.0 with minecraft:emerald 9'
send_command 'item replace block 6 100 0 container.0 with minecraft:redstone 5'
send_command 'save-all flush'
sleep 5
stop_server

echo "== $VERSION boot 2: shadow round trip =="
start_server "$TEST_LOG" true
send_command 'forceload add 0 0'
sleep 3
send_command 'save-all flush'
send_command 'item replace block 0 100 0 container.1 with minecraft:netherite_ingot 3'
send_command 'item replace block 6 100 0 container.1 with minecraft:coal 2'
send_command 'data remove block 6 100 0 Items'
send_command 'save-all flush'
send_command 'data get block 0 100 0 Items'
send_command 'data get block 2 100 0 Items'
send_command 'data get block 4 100 0 Items'
send_command 'data get block 6 100 0 Items'
sleep 5
stop_server

STATS="$(grep -E 'shutdown stats:|LazyContainer.*stash=' "$TEST_LOG" | tail -1)"
if [ -z "$STATS" ]; then
    echo "ERROR: runtime stats are missing" >&2
    exit 1
fi

metric() {
    local name="$1"
    printf '%s\n' "$STATS" | sed -n "s/.*$name=\([0-9][0-9]*\).*/\1/p"
}

STASH="$(metric stash)"
ENSURE="$(metric ensure)"
RAW_SAVE="$(metric rawSave)"
EAGER_LOAD="$(metric eagerLoad)"
MISMATCH="$(metric shadowMismatch)"

if [ "${STASH:-0}" -lt 5 ] || [ "${ENSURE:-0}" -lt 2 ] || [ "${RAW_SAVE:-0}" -lt 3 ]; then
    echo "ERROR: optimization counters did not exercise every required path: $STATS" >&2
    exit 1
fi
if [ "${EAGER_LOAD:-0}" -ne 0 ] || [ "${MISMATCH:-0}" -ne 0 ]; then
    echo "ERROR: eager fallback or shadow mismatch detected: $STATS" >&2
    exit 1
fi
if grep -Eq 'VerifyError|NoSuch(Method|Field)Error|IllegalAccessError|transform failed|FATAL' "$TEST_LOG"; then
    echo "ERROR: bytecode/linkage failure found in $TEST_LOG" >&2
    grep -E 'VerifyError|NoSuch(Method|Field)Error|IllegalAccessError|transform failed|FATAL' "$TEST_LOG" >&2
    exit 1
fi
for item in minecraft:diamond minecraft:netherite_ingot minecraft:gold_ingot minecraft:emerald; do
    if ! grep -q "$item" "$TEST_LOG"; then
        echo "ERROR: persisted fixture item not found in console output: $item" >&2
        exit 1
    fi
done
if grep -Eq 'minecraft:(redstone|coal)' "$TEST_LOG"; then
    echo "ERROR: stale items survived a same-instance reload without Items" >&2
    exit 1
fi

trap - EXIT
echo "PASS $VERSION: $STATS"
echo "Logs: $TEST_DIR"
